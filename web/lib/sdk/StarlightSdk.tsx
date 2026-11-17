"use client";

/**
 * Starlight Cinema partner SDK (lane SDK, flow "sdk") — the partner's own library bundled into MB App.
 * It gets only `host` (login / openPayment / close) and the appId MB App opened. It never sees the MB App
 * session or storage, talks only to its registered host (`/partner-api`, a "TLS pinning" mock) and keeps the
 * partner session token in memory (a React ref), never in web storage.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText, isApiError, money, PARTNER, type ApiError, type ApiOptions } from "../api";
import type { Order, PayResult } from "../bridge";
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
} from "../starlight/shop";
import { trace, type Flow } from "../trace";
import type { HostApi } from "./host";
import s from "./StarlightSdk.module.css";

const FLOW: Flow = "sdk";
const SDK_VERSION = "2.1.0";
/** The only host this SDK talks to (mock of certificate pinning to the partner's own server). */
const PINNED_PREFIX = `${PARTNER}/`;

/** fetch that refuses any URL that is not the SDK's registered host. */
async function sdkFetch<T>(path: string, opts: ApiOptions = {}): Promise<T> {
  if (!path.startsWith(PINNED_PREFIX)) {
    throw { status: 0, code: "HOST_NOT_PINNED", message: `SDK refused to call ${path}` } satisfies ApiError;
  }
  return api<T>(path, opts);
}

const TEXT: Record<string, Record<string, string>> = {
  en: { connecting: "Connecting to MB App…", signingIn: "Signing in…", close: "Close Starlight" },
};

export function StarlightSdk({ host, appId, locale }: { host: HostApi; appId: string; locale?: string }) {
  const t = TEXT[locale ?? "en"] ?? TEXT.en;
  const token = useRef<string | null>(null);
  const [me, setMe] = useState<Me | null>(null);
  const [tab, setTab] = useState<"movies" | "orders">("movies");
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [ticket, setTicket] = useState<StarOrder | null>(null);
  const [signInError, setSignInError] = useState<string | null>(null);
  const started = useRef(false);

  const call = useCallback<PartnerCall>(async (path, opts) => {
    try {
      return await sdkFetch(`${PARTNER}${path}`, { ...opts, token: token.current ?? undefined });
    } catch (e) {
      if (is401(e) && path !== "/api/catalog") {
        token.current = null;
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
      setBusy(t.connecting);
      let authCode: string;
      try {
        ({ authCode } = await host.login());
      } catch (e) {
        setSignInError(hostErrorText(e));
        setBusy(null);
        return;
      }
      trace("SDK", "2:35", FLOW, "authCode received (one-time)", appId);
      setBusy(t.signingIn);
      try {
        const m = await sdkFetch<Me>(`${PARTNER}/api/login`, {
          body: { authCode },
          headers: { "X-Client-Kind": "sdk" },
        });
        if (!m.sessionToken) throw { status: 502, code: "NO_SESSION", message: "Partner server returned no session" };
        token.current = m.sessionToken;
        trace("SDK", "2:43", FLOW, `token kept in SDK memory · partnerUserId ${m.partnerUserId}`, m.appId);
        setMe({ ...m, sessionToken: undefined });
        trace("U", "2:44", FLOW, `signed in as ${m.partnerUserId}`, m.appId);
        setBusy(null);
        void refreshOrders();
      } catch (e) {
        trace("SDK", "2:39c", FLOW, errorText(e), appId);
        if (retry && isApiError(e) && e.status !== 0) {
          trace("SDK", "2:39d", FLOW, "retry once with a new authCode", appId);
          return signIn(false);
        }
        setSignInError(`Couldn't sign in to Starlight Cinema — ${errorText(e)}`);
        setBusy(null);
      }
    },
    [host, appId, refreshOrders, t],
  );

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    trace("SDK", "2:25", FLOW, `SDK ${SDK_VERSION} · appId ${appId} · locale ${locale ?? "en"}`, appId);
    void loadCatalog();
    void signIn();
  }, [appId, locale, loadCatalog, signIn]);

  async function buy(item: Item) {
    if (!me) return;
    setNotice(null);
    setTicket(null);
    trace("SDK", "3:1", FLOW, `${item.title} (${item.itemId})`, appId);
    setBusy("Creating your order…");
    try {
      const created = await call<{ order: Order; checksum: string; title: string }>("/api/orders", {
        body: { itemId: item.itemId },
      });
      const orderId = created.order.orderId;
      trace("SDK", "3:4", FLOW, `${created.title} · ${money(created.order.amount, created.order.currency)}`, orderId);

      setBusy("Confirm the payment…");
      let res: PayResult;
      try {
        res = await host.openPayment(created.order, created.checksum);
      } catch (e) {
        setNotice({ kind: "err", text: hostErrorText(e) });
        return;
      }

      if (res.status === "FAILED" && !res.paymentRef) {
        trace("SDK", "3:58", FLOW, `FAILED · ${res.errorCode ?? "no errorCode"}`, orderId);
        const text = `The payment couldn't start${res.errorCode ? ` (${res.errorCode})` : ""}. Nothing was charged.`;
        trace("U", "3:59", FLOW, text, orderId);
        setNotice({ kind: "err", text });
        return;
      }
      if (res.status === "CANCELLED") {
        setNotice({ kind: "warn", text: "Payment cancelled, nothing was charged." });
        return;
      }

      trace("SDK", "3:46", FLOW, `${res.status} · paymentRef ${res.paymentRef ?? "–"}`, res.paymentRef ?? orderId);
      setBusy("Confirming with the cinema…");
      const final = await pollOrder(call, orderId);
      trace("SDK", "3:48", FLOW, final?.status ?? "unknown", orderId);
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

  return (
    <div className={s.root}>
      <div className={s.scroll}>
        <div className={s.hero}>
          <span className={s.logo}>★</span>
          <div className={s.heroText}>
            <strong>Starlight Cinema</strong>
            {me ? (
              <small>
                Hi, {me.profile?.name ?? "guest"} · <code>{me.partnerUserId}</code>
              </small>
            ) : (
              <small>{signInError ? "Not signed in" : "Signing in…"}</small>
            )}
          </div>
        </div>

        {!me && signInError && (
          <section className={s.account}>
            <div className={s.sub}>{signInError}</div>
            <button className={s.kb} onClick={() => signIn()} disabled={!!busy}>
              Try again
            </button>
          </section>
        )}

        <NoticeBar s={s} notice={notice} onClose={() => setNotice(null)} />

        <Tabs
          s={s}
          tab={tab}
          setTab={(x) => {
            setTab(x);
            if (x === "orders") void refreshOrders();
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

        <button className={s.closeLink} onClick={() => host.close()}>
          {t.close}
        </button>
        <div className={s.powered}>Starlight SDK {SDK_VERSION}</div>
      </div>

      <Busy s={s} text={busy} />
      <TicketCard s={s} order={ticket} onDone={() => setTicket(null)} />
    </div>
  );
}

export default StarlightSdk;
