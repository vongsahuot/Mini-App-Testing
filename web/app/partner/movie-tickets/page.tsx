"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText, isApiError, money, PARTNER, sleep, store } from "@/lib/api";
import { bridge, detectTransport, type Transport } from "@/lib/bridge";
import { traceStep } from "@/lib/trace";
import s from "./movie.module.css";

interface Profile {
  name?: string;
  phone?: string;
}
interface Session {
  sessionToken: string;
  partnerCustomerId: string;
  profile: Profile;
}
interface Item {
  itemId: string;
  title: string;
  showtime: string;
  price: number;
  currency: string;
  poster: string;
}
interface Order {
  orderId: string;
  itemId: string;
  title: string;
  amount: number;
  currency: string;
  paymentId: string;
  status: string;
  refundedAmount: number | null;
  ticket: { code: string; seat: string } | null;
  createdAt: string;
}
type Notice = { kind: "ok" | "warn" | "err"; text: string };

const APP_ID = "movie-tickets";
const SESSION_KEY = "movie-tickets.session";
const PENDING_STATES = ["PENDING_PAYMENT", "PAID"];
const REFUNDABLE = ["DELIVERED", "PAID", "PARTIALLY_REFUNDED"];
const ss = () => store("session");

export default function MovieTicketsPage() {
  const [transport, setTransport] = useState<Transport | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [catalog, setCatalog] = useState<Item[] | null>(null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [orders, setOrders] = useState<Order[]>([]);
  const [tab, setTab] = useState<"movies" | "orders">("movies");
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [ticket, setTicket] = useState<Order | null>(null);
  const [signInError, setSignInError] = useState<string | null>(null);
  const started = useRef(false);

  const signOut = useCallback((msg?: string) => {
    ss().set(SESSION_KEY, null);
    setSession(null);
    setOrders([]);
    if (msg) setSignInError(msg);
  }, []);

  /** Partner API call with the partner session; a 401 drops the session. */
  const partner = useCallback(
    async <T,>(path: string, sess: Session, body?: unknown): Promise<T> => {
      try {
        return await api<T>(`${PARTNER}${path}`, { token: sess.sessionToken, body });
      } catch (e) {
        if (isApiError(e) && e.status === 401) signOut("Your cinema session expired.");
        throw e;
      }
    },
    [signOut],
  );

  const loadOrders = useCallback(
    async (sess: Session | null = session) => {
      if (!sess) return;
      try {
        const list = await partner<Order[]>("/api/orders", sess);
        setOrders([...(list ?? [])].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? "")));
      } catch (e) {
        setNotice({ kind: "err", text: `Could not load orders — ${errorText(e)}` });
      }
    },
    [partner, session],
  );

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const t = detectTransport();
    setTransport(t);
    traceStep("PP", 9, `Partner page loaded (transport: ${t})`, APP_ID);
    api<Item[]>(`${PARTNER}/api/catalog`)
      .then((c) => setCatalog(c ?? []))
      .catch((e) => setCatalogError(errorText(e)));
    // Sign in automatically on open: the bank app shows its consent sheet the first time only,
    // afterwards login() resolves silently. The page never shows its own sign-in button.
    if (t !== "none") void signIn();
  }, [loadOrders]);

  async function signIn() {
    setNotice(null);
    setSignInError(null);
    setBusy("Connecting to KB Bank…");
    try {
      const { authCode } = await bridge.login(["profile", "phone"]);
      traceStep("PP", 16, "login() resolved with authCode", APP_ID);
      setBusy("Signing in…");
      const sess = await api<Session>(`${PARTNER}/api/login`, { body: { authCode } });
      ss().set(SESSION_KEY, sess);
      setSession(sess);
      void loadOrders(sess);
    } catch (e) {
      const denied = (e as { code?: string })?.code === "USER_DENIED";
      setSignInError(
        denied
          ? "You didn't allow Starlight Cinema to see your name and phone, so tickets can't be booked."
          : `Couldn't connect to KB Bank — ${errorText(e)}`,
      );
    } finally {
      setBusy(null);
    }
  }

  async function pollOrder(sess: Session, orderId: string): Promise<Order | null> {
    let last: Order | null = null;
    for (let i = 0; i < 20; i++) {
      try {
        last = await partner<Order>(`/api/orders/${encodeURIComponent(orderId)}`, sess);
        if (!PENDING_STATES.includes(last.status)) return last;
      } catch (e) {
        if (isApiError(e) && e.status === 401) return null;
      }
      await sleep(1500);
    }
    return last;
  }

  async function buy(item: Item) {
    if (!session) return;
    const sess = session;
    setNotice(null);
    setTicket(null);
    traceStep("PP", 20, `${item.title} (${item.itemId})`, item.itemId);
    setBusy("Creating your order…");
    try {
      const order = await partner<Order>("/api/orders", sess, { itemId: item.itemId });
      traceStep("PP", 25, `order ${order.orderId}, ${money(order.amount, order.currency)}`, order.paymentId);
      setBusy("Confirm the payment in the bank app…");
      const res = await bridge.pay(order.paymentId);
      if (res.status === "CANCELLED") {
        traceStep("PP", 32, `order ${order.orderId}`, order.paymentId);
        setNotice({ kind: "warn", text: "Payment cancelled. No money was taken." });
        return;
      }
      traceStep(
        "PP",
        50,
        `pay() → ${res.status}${res.bankTxnId ? ` ${res.bankTxnId}` : ""}${res.reason ? ` (${res.reason})` : ""}`,
        order.paymentId,
      );
      setBusy("Confirming with the cinema…");
      const final = await pollOrder(sess, order.orderId);
      if (final?.status === "DELIVERED") {
        traceStep("PP", 54, `ticket ${final.ticket?.code ?? "?"} seat ${final.ticket?.seat ?? "?"}`, order.paymentId);
        setTicket(final);
      } else if (final?.status === "FAILED") {
        setNotice({ kind: "err", text: `Payment failed${res.reason ? ` — ${res.reason}` : ""}.` });
      } else if (final) {
        setNotice({
          kind: "warn",
          text: `Order is ${final.status}${res.status === "FAILED" && res.reason ? ` (bank: ${res.reason})` : ""}. Check “My orders” later.`,
        });
      }
    } catch (e) {
      setNotice({ kind: "err", text: `Purchase failed — ${errorText(e)}` });
    } finally {
      setBusy(null);
      void loadOrders(sess);
    }
  }

  async function refund(order: Order, amount: number | null) {
    if (!session) return;
    setNotice(null);
    setBusy(amount ? `Refunding ${money(amount, order.currency)}…` : "Refunding…");
    try {
      const o = await partner<Order>(`/api/orders/${encodeURIComponent(order.orderId)}/refund`, session, {
        amount,
        reason: amount ? "Partial refund (test)" : "Customer request",
      });
      setNotice({ kind: "ok", text: `Refund requested — order is now ${o?.status ?? "updated"}.` });
      await loadOrders();
      await sleep(1500);
      await loadOrders();
    } catch (e) {
      setNotice({ kind: "err", text: `Refund failed — ${errorText(e)}` });
    } finally {
      setBusy(null);
    }
  }

  async function close() {
    try {
      await bridge.close();
    } catch (e) {
      setNotice({ kind: "err", text: `close() failed — ${errorText(e)}` });
    }
  }

  const noBridge = transport === "none";

  return (
    <div className={s.page}>
      <header className={s.header}>
        <div className={s.brand}>
          <span className={s.logo}>★</span>
          <div>
            <strong>Starlight Cinema</strong>
            <small>Movie tickets</small>
          </div>
        </div>
        <button className={s.close} onClick={close} aria-label="Close mini app" disabled={noBridge}>
          ✕
        </button>
      </header>

      {noBridge && (
        <div className={s.banner}>
          Open this page from the bank app or the Simulator. <a href="/simulator">Go to the Simulator →</a>
        </div>
      )}

      {session ? (
        <section className={s.account}>
          <div>
            <div className={s.hello}>Hi, {session.profile?.name ?? "guest"}</div>
            <div className={s.sub}>
              {session.profile?.phone ?? "no phone shared"} · <code>{session.partnerCustomerId}</code>
            </div>
          </div>
        </section>
      ) : signInError ? (
        <section className={s.account}>
          <div className={s.sub}>{signInError}</div>
          <button className={s.ghost} onClick={signIn} disabled={!!busy}>
            Try again
          </button>
        </section>
      ) : null}

      {notice && (
        <div className={`${s.notice} ${s[notice.kind]}`} role="status">
          <span>{notice.text}</span>
          <button onClick={() => setNotice(null)} aria-label="Dismiss">
            ✕
          </button>
        </div>
      )}

      <nav className={s.tabs}>
        <button aria-pressed={tab === "movies"} onClick={() => setTab("movies")}>
          Now showing
        </button>
        <button
          aria-pressed={tab === "orders"}
          onClick={() => {
            setTab("orders");
            void loadOrders();
          }}
          disabled={!session}
        >
          My orders {orders.length ? `(${orders.length})` : ""}
        </button>
      </nav>

      {tab === "movies" && (
        <ul className={s.list}>
          {catalogError && <li className={s.empty}>Catalog unavailable — {catalogError}</li>}
          {!catalog && !catalogError && <li className={s.empty}>Loading…</li>}
          {catalog?.map((m) => (
            <li key={m.itemId} className={s.movie}>
              <div className={s.poster}>{m.poster || "🎬"}</div>
              <div className={s.info}>
                <strong>{m.title}</strong>
                <span>{m.showtime}</span>
                <span className={s.price}>{money(m.price, m.currency)}</span>
              </div>
              <button className={s.buy} onClick={() => buy(m)} disabled={!session || !!busy}>
                Buy
              </button>
            </li>
          ))}
        </ul>
      )}

      {tab === "orders" && (
        <ul className={s.list}>
          {orders.length === 0 && <li className={s.empty}>No orders yet.</li>}
          {orders.map((o) => {
            const left = o.amount - (o.refundedAmount ?? 0);
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
                  {o.refundedAmount ? ` · refunded ${money(o.refundedAmount, o.currency)}` : ""} ·{" "}
                  <code>{o.orderId}</code>
                </div>
                {o.ticket && (
                  <div className={s.sub}>
                    🎟 <code>{o.ticket.code}</code> · seat {o.ticket.seat}
                  </div>
                )}
                {REFUNDABLE.includes(o.status) && left > 0 && (
                  <div className={s.actions}>
                    <button className={s.ghost} onClick={() => refund(o, null)} disabled={!!busy}>
                      Refund all
                    </button>
                    {left > 1 && (
                      <button className={s.ghost} onClick={() => refund(o, 1)} disabled={!!busy}>
                        Refund 1.00
                      </button>
                    )}
                  </div>
                )}
              </li>
            );
          })}
          {session && (
            <li>
              <button className={s.ghost} onClick={() => loadOrders()} disabled={!!busy}>
                Refresh
              </button>
            </li>
          )}
        </ul>
      )}

      {busy && (
        <div className={s.overlay} aria-live="polite">
          <div className={s.spinner} />
          <span>{busy}</span>
        </div>
      )}

      {ticket && (
        <div className={s.overlay} onClick={() => setTicket(null)}>
          <div className={s.ticket} onClick={(e) => e.stopPropagation()}>
            <small>Enjoy the movie</small>
            <strong>{ticket.title}</strong>
            <div className={s.ticketRow}>
              <div>
                <small>Seat</small>
                <b>{ticket.ticket?.seat}</b>
              </div>
              <div>
                <small>Code</small>
                <b className="mono">{ticket.ticket?.code}</b>
              </div>
            </div>
            <div className={s.sub}>
              Paid {money(ticket.amount, ticket.currency)} · order <code>{ticket.orderId}</code>
            </div>
            <button className={s.kb} onClick={() => setTicket(null)}>
              Done
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
