"use client";

/**
 * Starlight Cinema's own client code, shared by its three clients: the WebView page (/partner/movie-tickets),
 * the SDK bundled into MB App (lib/sdk/StarlightSdk.tsx) and the partner app (/partner-app).
 * Pure partner-side helpers and presentational components: no bank code, no MB App storage.
 * Each client passes its own CSS module (`s`) so the same markup can look like a web page, a native SDK
 * screen or a separate app. Class names used: list empty movie poster info price buy order orderTop status
 * sub actions ghost tabs notice ok warn err overlay spinner ticket ticketRow kb.
 */

import { useCallback, useState } from "react";
import { errorText, isApiError, money, sleep } from "@/lib/api";
import { BridgeError } from "@/lib/bridge";

export type Styles = Readonly<Record<string, string>>;

export interface Profile {
  name?: string | null;
  phone?: string | null;
}
/** `POST /api/login` and `GET /api/me` (sessionToken only for sdk / a2a). */
export interface Me {
  appId: string;
  type: string;
  partnerUserId: string;
  profile: Profile | null;
  sessionToken?: string;
}
export interface Item {
  itemId: string;
  title: string;
  showtime: string;
  price: number;
  currency: string;
  poster: string;
}
export type OrderStatus =
  | "PENDING_PAYMENT"
  | "DELIVERED"
  | "FAILED"
  | "EXPIRED"
  | "REFUND_PENDING"
  | "PARTIALLY_REFUNDED"
  | "REFUNDED";
/** The partner server's order view (docs/CONTRACT.md, "Order"). */
export interface StarOrder {
  orderId: string;
  appId: string;
  itemId: string;
  title: string;
  amount: number;
  currency: string;
  paymentRef: string | null;
  status: OrderStatus | string;
  refundedAmount: number | null;
  ticket: { code: string; seat: string } | null;
  createdAt: string;
}
export type Notice = { kind: "ok" | "warn" | "err"; text: string };

/** Calls the partner client API; `path` starts with `/api/`. Throws ApiError. */
export type PartnerCall = <T>(path: string, opts?: { method?: string; body?: unknown }) => Promise<T>;

export const isPending = (o: StarOrder | null | undefined) => o?.status === "PENDING_PAYMENT";
const REFUNDABLE = ["DELIVERED", "PARTIALLY_REFUNDED"];
export const refundLeft = (o: StarOrder) => +(o.amount - (o.refundedAmount ?? 0)).toFixed(2);
export const is401 = (e: unknown) => isApiError(e) && e.status === 401;

/**
 * Asks the partner server for the real status until it is final (the server asks the bank while unpaid).
 * Returns the last order seen (null if none could be read). A 401 is rethrown.
 */
export async function pollOrder(
  call: PartnerCall,
  orderId: string,
  tries = 20,
  everyMs = 1500,
): Promise<StarOrder | null> {
  let last: StarOrder | null = null;
  for (let i = 0; i < tries; i++) {
    try {
      last = await call<StarOrder>(`/api/orders/${encodeURIComponent(orderId)}`);
      if (!isPending(last)) return last;
    } catch (e) {
      if (is401(e)) throw e;
    }
    if (i < tries - 1) await sleep(everyMs);
  }
  return last;
}

/** What to show for the confirmed order (the MB App result is only a hint). */
export function resultOf(
  o: StarOrder | null,
  hint?: { status?: string | null; errorCode?: string | null },
): { ticket: StarOrder | null; notice: Notice | null } {
  const code = hint?.errorCode ? ` (${hint.errorCode})` : "";
  if (!o) return { ticket: null, notice: { kind: "warn", text: "Couldn't confirm with the cinema — check My orders later." } };
  switch (o.status) {
    case "DELIVERED":
      return { ticket: o, notice: { kind: "ok", text: `Paid — your ticket for ${o.title} is ready.` } };
    case "FAILED":
      return { ticket: null, notice: { kind: "err", text: `Payment failed${code}. Your ticket was not issued.` } };
    case "EXPIRED":
      return { ticket: null, notice: { kind: "warn", text: "The payment expired before it was completed. Nothing was charged." } };
    case "PENDING_PAYMENT":
      if (hint?.status === "CANCELLED") {
        return { ticket: null, notice: { kind: "warn", text: "Payment cancelled, nothing was charged." } };
      }
      if (hint?.status === "FAILED") {
        return { ticket: null, notice: { kind: "err", text: `The payment didn't go through${code}. Nothing was charged.` } };
      }
      return { ticket: null, notice: { kind: "warn", text: "Processing — check My orders later." } };
    default:
      return { ticket: null, notice: { kind: "ok", text: `Order ${o.orderId} is ${o.status}.` } };
  }
}

/** Friendly text for MB App bridge / host errors. */
export function hostErrorText(e: unknown, appName = "MB App"): string {
  const code = e instanceof BridgeError ? e.code : (e as { code?: string } | null)?.code;
  switch (code) {
    case "NO_BRIDGE":
      return `Open Starlight Cinema from ${appName} to sign in.`;
    case "CONSENT_REQUIRED":
      return `You haven't allowed Starlight Cinema to use your ${appName} profile yet.`;
    case "ORIGIN_NOT_ALLOWED":
    case "NOT_MAIN_FRAME":
      return `${appName} refused the request from this page.`;
    case "SHEET_OPEN":
      return "A payment is already open — finish it first.";
    case "TIMEOUT":
      return `${appName} did not answer. Please try again.`;
    default:
      return `Couldn't reach ${appName} — ${errorText(e)}`;
  }
}

/** Catalog + "My orders" state for one client. */
export function useShop(call: PartnerCall, onError: (n: Notice) => void) {
  const [catalog, setCatalog] = useState<Item[] | null>(null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [orders, setOrders] = useState<StarOrder[]>([]);

  const loadCatalog = useCallback(async () => {
    setCatalogError(null);
    try {
      setCatalog((await call<Item[]>("/api/catalog")) ?? []);
    } catch (e) {
      setCatalogError(errorText(e));
    }
  }, [call]);

  const loadOrders = useCallback(async () => {
    try {
      const list = (await call<StarOrder[]>("/api/orders")) ?? [];
      setOrders([...list].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? "")));
    } catch (e) {
      if (!is401(e)) onError({ kind: "err", text: `Could not load orders — ${errorText(e)}` });
      throw e;
    }
  }, [call, onError]);

  /** Refund all that is left (amount null) or a part. The partner server records the refund steps. */
  const refund = useCallback(
    async (o: StarOrder, amount: number | null): Promise<StarOrder> => {
      const r = await call<StarOrder>(`/api/orders/${encodeURIComponent(o.orderId)}/refund`, { body: { amount } });
      setOrders((list) => list.map((x) => (x.orderId === r.orderId ? r : x)));
      return r;
    },
    [call],
  );

  const clear = useCallback(() => setOrders([]), []);

  return { catalog, catalogError, orders, loadCatalog, loadOrders, refund, clear };
}

export function NoticeBar({ s, notice, onClose }: { s: Styles; notice: Notice | null; onClose: () => void }) {
  if (!notice) return null;
  return (
    <div className={`${s.notice} ${s[notice.kind]}`} role="status">
      <span>{notice.text}</span>
      <button onClick={onClose} aria-label="Dismiss">
        ✕
      </button>
    </div>
  );
}

export function Tabs({
  s,
  tab,
  setTab,
  ordersCount,
  signedIn,
}: {
  s: Styles;
  tab: "movies" | "orders";
  setTab: (t: "movies" | "orders") => void;
  ordersCount: number;
  signedIn: boolean;
}) {
  return (
    <nav className={s.tabs}>
      <button aria-pressed={tab === "movies"} onClick={() => setTab("movies")}>
        Now showing
      </button>
      <button aria-pressed={tab === "orders"} onClick={() => setTab("orders")} disabled={!signedIn}>
        My orders {ordersCount ? `(${ordersCount})` : ""}
      </button>
    </nav>
  );
}

export function MovieList({
  s,
  catalog,
  error,
  canBuy,
  onBuy,
  onRetry,
}: {
  s: Styles;
  catalog: Item[] | null;
  error: string | null;
  canBuy: boolean;
  onBuy: (m: Item) => void;
  onRetry?: () => void;
}) {
  return (
    <ul className={s.list}>
      {error && (
        <li className={s.empty}>
          Catalog unavailable — {error}{" "}
          {onRetry && (
            <button className={s.ghost} onClick={onRetry}>
              Retry
            </button>
          )}
        </li>
      )}
      {!catalog && !error && <li className={s.empty}>Loading…</li>}
      {catalog?.map((m) => (
        <li key={m.itemId} className={s.movie}>
          <div className={s.poster}>{m.poster || "🎬"}</div>
          <div className={s.info}>
            <strong>{m.title}</strong>
            <span>{m.showtime}</span>
            <span className={s.price}>{money(m.price, m.currency)}</span>
          </div>
          <button className={s.buy} onClick={() => onBuy(m)} disabled={!canBuy}>
            Buy
          </button>
        </li>
      ))}
    </ul>
  );
}

export function OrderList({
  s,
  orders,
  disabled,
  onRefund,
  onRefresh,
  onShow,
}: {
  s: Styles;
  orders: StarOrder[];
  disabled: boolean;
  onRefund: (o: StarOrder, amount: number | null) => void;
  onRefresh: () => void;
  onShow: (o: StarOrder) => void;
}) {
  return (
    <ul className={s.list}>
      {orders.length === 0 && <li className={s.empty}>No orders yet.</li>}
      {orders.map((o) => {
        const left = refundLeft(o);
        return (
          <li key={o.orderId} className={s.order}>
            <div className={s.orderTop}>
              <strong>{o.title}</strong>
              <span className={s.status} data-status={o.status}>
                {o.status}
              </span>
            </div>
            <div className={s.sub}>
              {money(o.amount, o.currency)}
              {o.refundedAmount ? ` · refunded ${money(o.refundedAmount, o.currency)}` : ""} · <code>{o.orderId}</code>
            </div>
            {o.ticket && (
              <div className={s.sub}>
                🎟 <code>{o.ticket.code}</code> · seat {o.ticket.seat}
              </div>
            )}
            {(o.ticket || (REFUNDABLE.includes(o.status) && left > 0)) && (
              <div className={s.actions}>
                {o.ticket && o.status === "DELIVERED" && (
                  <button className={s.ghost} onClick={() => onShow(o)}>
                    Ticket
                  </button>
                )}
                {REFUNDABLE.includes(o.status) && left > 0 && (
                  <>
                    <button className={s.ghost} onClick={() => onRefund(o, null)} disabled={disabled}>
                      Refund all
                    </button>
                    {left > 1 && (
                      <button className={s.ghost} onClick={() => onRefund(o, 1)} disabled={disabled}>
                        Refund 1.00
                      </button>
                    )}
                  </>
                )}
              </div>
            )}
          </li>
        );
      })}
      <li>
        <button className={s.ghost} onClick={onRefresh} disabled={disabled}>
          Refresh
        </button>
      </li>
    </ul>
  );
}

export function Busy({ s, text }: { s: Styles; text: string | null }) {
  if (!text) return null;
  return (
    <div className={s.overlay} aria-live="polite">
      <div className={s.spinner} />
      <span>{text}</span>
    </div>
  );
}

export function TicketCard({ s, order, onDone }: { s: Styles; order: StarOrder | null; onDone: () => void }) {
  if (!order) return null;
  return (
    <div className={s.overlay} onClick={onDone}>
      <div className={s.ticket} onClick={(e) => e.stopPropagation()}>
        <small>Enjoy the movie</small>
        <strong>{order.title}</strong>
        <div className={s.ticketRow}>
          <div>
            <small>Seat</small>
            <b>{order.ticket?.seat ?? "–"}</b>
          </div>
          <div>
            <small>Code</small>
            <b className="mono">{order.ticket?.code ?? "–"}</b>
          </div>
        </div>
        <div className={s.sub}>
          Paid {money(order.amount, order.currency)} · order <code>{order.orderId}</code>
        </div>
        <button className={s.kb} onClick={onDone}>
          Done
        </button>
      </div>
    </div>
  );
}
