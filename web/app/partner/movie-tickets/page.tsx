"use client";

/**
 * Starlight Cinema — Link WebView page (lane PC, flow "webview"). Opened by MB App in a WebView (Android) or an
 * iframe (simulator). Sign-in is automatic through the JS bridge (MB App already ran consent natively); the
 * partner session is an HttpOnly cookie set by the partner server, so this page stores nothing itself.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText, isApiError, money, PARTNER } from "@/lib/api";
import { bridge, detectTransport, type Order, type PayResult, type Transport } from "@/lib/bridge";
import {
  Busy,
  hostErrorText,
  is401,
  MovieList,
  NoticeBar,
  OrderList,
  pollOrder,
  resultOf,
  Tabs,
  TicketCard,
  useShop,
  type Item,
  type Me,
  type Notice,
  type PartnerCall,
  type StarOrder,
} from "@/lib/starlight/shop";
import { trace, type Flow } from "@/lib/trace";
import s from "./movie.module.css";

const FLOW: Flow = "webview";

export default function MovieTicketsPage() {
  const [transport, setTransport] = useState<Transport | null>(null);
  const [vip, setVip] = useState(false);
  const [me, setMe] = useState<Me | null>(null);
  const [tab, setTab] = useState<"movies" | "orders">("movies");
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [ticket, setTicket] = useState<StarOrder | null>(null);
  const [signInError, setSignInError] = useState<string | null>(null);
  const started = useRef(false);

  /** Partner client API with the cookie session (same origin, sent automatically). A 401 drops the session. */
  const call = useCallback<PartnerCall>(async (path, opts) => {
    try {
      return await api(`${PARTNER}${path}`, opts);
    } catch (e) {
      if (is401(e) && path !== "/api/catalog") {
        setMe(null);
        setSignInError("Your cinema session expired.");
      }
      throw e;
    }
  }, []);
  const { catalog, catalogError, orders, loadCatalog, loadOrders, refund } = useShop(call, setNotice);
  const refreshOrders = useCallback(() => loadOrders().catch(() => {}), [loadOrders]);

  const signIn = useCallback(
    async (retry = true): Promise<void> => {
      setNotice(null);
      setSignInError(null);
      setBusy("Connecting to MB App…");
      let authCode: string;
      try {
        ({ authCode } = await bridge.login());
      } catch (e) {
        setSignInError(hostErrorText(e));
        setBusy(null);
        return;
      }
      trace("PC", "2:35", FLOW, "authCode received (one-time)");
      setBusy("Signing in…");
      try {
        const m = await api<Me>(`${PARTNER}/api/login`, {
          body: { authCode },
          headers: { "X-Client-Kind": "webview" },
        });
        trace("PC", "2:43", FLOW, `cookie session · partnerUserId ${m.partnerUserId}`, m.appId);
        setMe(m);
        trace("U", "2:44", FLOW, `signed in as ${m.partnerUserId}`, m.appId);
        setBusy(null);
        void refreshOrders();
      } catch (e) {
        trace("PC", "2:39c", FLOW, errorText(e));
        if (retry && isApiError(e) && e.status !== 0) {
          trace("PC", "2:39d", FLOW, "retry once with a new authCode");
          return signIn(false);
        }
        setSignInError(`Couldn't sign in to Starlight Cinema — ${errorText(e)}`);
        setBusy(null);
      }
    },
    [refreshOrders],
  );

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const t = detectTransport();
    const isVip = new URLSearchParams(window.location.search).get("room") === "vip";
    setTransport(t);
    setVip(isVip);
    trace("PC", "2:25", FLOW, `transport: ${t}${isVip ? " · room=vip" : ""}`);
    void loadCatalog();
    // Sign in automatically: consent already happened natively in MB App before this page was opened.
    if (t !== "none") void signIn();
  }, [loadCatalog, signIn]);

  async function buy(item: Item) {
    if (!me) return;
    setNotice(null);
    setTicket(null);
    trace("PC", "3:1", FLOW, `${item.title} (${item.itemId})`);
    setBusy("Creating your order…");
    try {
      const created = await call<{ order: Order; checksum: string; title: string }>("/api/orders", {
        body: { itemId: item.itemId },
      });
      const orderId = created.order.orderId;
      trace("PC", "3:4", FLOW, `${created.title} · ${money(created.order.amount, created.order.currency)}`, orderId);

      setBusy("Confirm the payment in MB App…");
      let res: PayResult;
      try {
        res = await bridge.openPayment(created.order, created.checksum);
      } catch (e) {
        setNotice({ kind: "err", text: hostErrorText(e) });
        return;
      }

      if (res.status === "FAILED" && !res.paymentRef) {
        // MB App rejected the order before a payment existed (verify failed).
        trace("PC", "3:58", FLOW, `FAILED · ${res.errorCode ?? "no errorCode"}`, orderId);
        const text = `MB App couldn't start the payment${res.errorCode ? ` (${res.errorCode})` : ""}. Nothing was charged.`;
        trace("U", "3:59", FLOW, text, orderId);
        setNotice({ kind: "err", text });
        return;
      }
      if (res.status === "CANCELLED") {
        setNotice({ kind: "warn", text: "Payment cancelled, nothing was charged." });
        return;
      }

      trace("PC", "3:46", FLOW, `${res.status} · paymentRef ${res.paymentRef ?? "–"}`, res.paymentRef ?? orderId);
      setBusy("Confirming with the cinema…");
      const final = await pollOrder(call, orderId);
      trace("PC", "3:48", FLOW, final?.status ?? "unknown", orderId);
      const r = resultOf(final, res);
      setTicket(r.ticket);
      setNotice(r.notice);
    } catch (e) {
      if (!is401(e)) setNotice({ kind: "err", text: `Purchase failed — ${errorText(e)}` });
    } finally {
      setBusy(null);
      void refreshOrders();
    }
  }

  async function doRefund(o: StarOrder, amount: number | null) {
    setNotice(null);
    setBusy(amount ? `Refunding ${money(amount, o.currency)}…` : "Refunding…");
    try {
      const r = await refund(o, amount);
      setNotice({ kind: "ok", text: `Refund requested — order is now ${r.status}.` });
    } catch (e) {
      if (!is401(e)) setNotice({ kind: "err", text: `Refund failed — ${errorText(e)}` });
    } finally {
      setBusy(null);
      void refreshOrders();
    }
  }

  async function close() {
    try {
      await bridge.close();
    } catch (e) {
      setNotice({ kind: "err", text: hostErrorText(e) });
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
            <small>{vip ? "VIP screening" : "Movie tickets"}</small>
          </div>
        </div>
        <button className={s.close} onClick={close} aria-label="Close mini app" disabled={noBridge}>
          ✕
        </button>
      </header>

      {noBridge && (
        <div className={s.banner}>
          Open this page from MB App or the Simulator. <a href="/simulator">Go to the Simulator →</a>
        </div>
      )}

      {vip && (
        <div className={s.vip}>
          <span>⭐ VIP screening</span>
          <small>Premium lounge seats · members only</small>
        </div>
      )}

      {me ? (
        <section className={s.account}>
          <div>
            <div className={s.hello}>Hi, {me.profile?.name ?? "guest"}</div>
            <div className={s.sub}>
              {me.profile?.phone ?? "no phone shared"} · <code>{me.partnerUserId}</code>
            </div>
          </div>
        </section>
      ) : signInError ? (
        <section className={s.account}>
          <div className={s.sub}>{signInError}</div>
          <button className={s.kb} onClick={() => signIn()} disabled={!!busy || noBridge}>
            Try again
          </button>
        </section>
      ) : null}

      <NoticeBar s={s} notice={notice} onClose={() => setNotice(null)} />

      <Tabs
        s={s}
        tab={tab}
        setTab={(t) => {
          setTab(t);
          if (t === "orders") void refreshOrders();
        }}
        ordersCount={me ? orders.length : 0}
        signedIn={!!me}
      />

      {tab === "movies" && (
        <MovieList
          s={s}
          catalog={catalog}
          error={catalogError}
          canBuy={!!me && !busy}
          onBuy={buy}
          onRetry={() => void loadCatalog()}
        />
      )}
      {tab === "orders" && me && (
        <OrderList
          s={s}
          orders={orders}
          disabled={!!busy}
          onRefund={doRefund}
          onRefresh={() => void refreshOrders()}
          onShow={setTicket}
        />
      )}
      {tab === "orders" && !me && <div className={s.empty}>Sign in to see your orders.</div>}

      <Busy s={s} text={busy} />
      <TicketCard s={s} order={ticket} onDone={() => setTicket(null)} />
    </div>
  );
}
