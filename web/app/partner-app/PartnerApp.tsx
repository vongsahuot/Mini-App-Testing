"use client";

/**
 * Starlight Cinema partner app (lane PA, flow "a2a"). A separate app on the phone: in the web simulator it runs
 * in an iframe and the simulator acts as the OS (lib/os.ts). It is opened by verified links:
 *   /links/starlight/open                                 → A2 (launch only)
 *   /links/starlight/return?authCode&state | ?error&state → A14 (account linking result)
 *   /links/starlight/return?paymentRef&status(&errorCode) → D40 (payment result hint)
 * or directly at /partner-app (app icon). "App secure storage" = localStorage keys prefixed `starlight-app.`.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText, isApiError, money, PARTNER, store } from "@/lib/api";
import { openUrl } from "@/lib/os";
import {
  Busy,
  is401,
  isPending,
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
import s from "./partner-app.module.css";

const FLOW: Flow = "a2a";
const APP_ID = "starlight-app";
const K = {
  session: "starlight-app.session",
  link: "starlight-app.pendingLink",
  order: "starlight-app.pendingOrder",
} as const;

interface PendingLink {
  state: string;
  codeVerifier: string;
}
interface PendingOrder {
  orderId: string;
  paymentRef: string | null;
  title: string;
  amount: number;
  currency: string;
}
/** `POST /api/orders` answer for App-to-App. */
interface A2aOrder {
  orderId: string;
  title: string;
  amount: number;
  currency: string;
  paymentRef: string;
  payLink: string;
  status: string;
}

type Phase = "starting" | "signedOut" | "signedIn";

const ls = () => store("local");

function b64url(bytes: ArrayBuffer | Uint8Array): string {
  const a = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let bin = "";
  for (const b of a) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function randomB64url(n: number): string {
  const a = new Uint8Array(n);
  crypto.getRandomValues(a);
  return b64url(a);
}

export default function PartnerApp() {
  const token = useRef<string | null>(null);
  const [phase, setPhase] = useState<Phase>("starting");
  const [me, setMe] = useState<Me | null>(null);
  const [inFrame, setInFrame] = useState(true);
  const [tab, setTab] = useState<"movies" | "orders">("movies");
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [ticket, setTicket] = useState<StarOrder | null>(null);
  const [relink, setRelink] = useState(false);
  const started = useRef(false);
  const checking = useRef(false);

  const dropSession = useCallback((msg?: string) => {
    token.current = null;
    ls().set(K.session, null);
    setMe(null);
    setPhase("signedOut");
    if (msg) setNotice({ kind: "warn", text: msg });
  }, []);

  /** Partner client API with the bearer token from app storage. A 401 drops the session. */
  const call = useCallback<PartnerCall>(
    async (path, opts) => {
      try {
        return await api(`${PARTNER}${path}`, { ...opts, token: token.current ?? undefined });
      } catch (e) {
        if (is401(e) && path !== "/api/catalog") dropSession("Your Starlight session expired — link with MB Bank again.");
        throw e;
      }
    },
    [dropSession],
  );
  const { catalog, catalogError, orders, loadCatalog, loadOrders, refund, clear } = useShop(call, setNotice);
  const refreshOrders = useCallback(() => loadOrders().catch(() => {}), [loadOrders]);

  /** Session from app storage, checked with GET /api/me. */
  const restore = useCallback(async (): Promise<Me | null> => {
    const t = ls().get<string>(K.session);
    if (!t) return null;
    token.current = t;
    try {
      const m = await api<Me>(`${PARTNER}/api/me`, { token: t });
      setMe(m);
      setPhase("signedIn");
      return m;
    } catch (e) {
      if (is401(e)) {
        token.current = null;
        ls().set(K.session, null);
      } else {
        setNotice({ kind: "err", text: `Couldn't reach Starlight — ${errorText(e)}` });
      }
      return null;
    }
  }, []);

  /** D44: on the next foreground, ask the partner server about a stored pending order. */
  const checkPending = useCallback(async () => {
    const p = ls().get<PendingOrder>(K.order);
    if (!p || !token.current || checking.current) return;
    checking.current = true;
    try {
      trace("PA", "3:D44", FLOW, `foreground: refresh pending order (${p.title})`, p.orderId);
      const o = await call<StarOrder>(`/api/orders/${encodeURIComponent(p.orderId)}`);
      trace("PA", "3:D42", FLOW, o.status, p.orderId);
      if (!isPending(o)) {
        ls().set(K.order, null);
        const r = resultOf(o);
        trace("U", "3:D43", FLOW, r.notice?.text ?? o.status, p.orderId);
        setTicket(r.ticket);
        setNotice(r.notice);
      }
      void refreshOrders();
    } catch (e) {
      if (isApiError(e) && e.status === 404) ls().set(K.order, null);
    } finally {
      checking.current = false;
    }
  }, [call, refreshOrders]);

  /** A14–A24: return from MB App after linking. */
  const handleLinkReturn = useCallback(
    async (q: URLSearchParams) => {
      const authCode = q.get("authCode");
      const state = q.get("state");
      const error = q.get("error");
      trace("PA", "2:A14", FLOW, error ? `error=${error}` : "authCode + state", APP_ID);
      const pending = ls().get<PendingLink>(K.link);
      ls().set(K.link, null); // one use: state + verifier are cleared whatever happens

      if (error || !authCode) {
        setPhase("signedOut");
        setNotice({
          kind: "warn",
          text:
            error === "access_denied"
              ? "You didn't link MB Bank. You can link it any time."
              : `MB Bank couldn't link your account${error ? ` (${error})` : ""}. Please try again.`,
        });
        return;
      }

      const match = !!pending && !!state && pending.state === state;
      trace("PA", "2:A15", FLOW, match ? "match" : "mismatch → code discarded", APP_ID);
      if (!match) {
        setPhase("signedOut");
        setNotice({ kind: "err", text: "This sign-in wasn't started from this app, so it was ignored." });
        return;
      }

      setBusy("Linking with MB Bank…");
      try {
        const m = await api<Me>(`${PARTNER}/api/login`, {
          body: { authCode, codeVerifier: pending.codeVerifier },
          headers: { "X-Client-Kind": "a2a" },
        });
        if (!m.sessionToken) throw { status: 502, code: "NO_SESSION", message: "Partner server returned no session" };
        token.current = m.sessionToken;
        ls().set(K.session, m.sessionToken);
        trace("PA", "2:A23", FLOW, `token in app storage · partnerUserId ${m.partnerUserId}`, m.appId);
        setMe({ ...m, sessionToken: undefined });
        setPhase("signedIn");
        setRelink(false);
        trace("U", "2:A24", FLOW, `signed in as ${m.partnerUserId}`, m.appId);
        setNotice({ kind: "ok", text: "MB Bank linked. You can pay for tickets with MB App now." });
        void refreshOrders();
      } catch (e) {
        trace("PA", "2:A19c", FLOW, errorText(e), APP_ID);
        trace("PA", "2:A19d", FLOW, "Start linking again from A4", APP_ID);
        setPhase("signedOut");
        setRelink(true);
        setNotice({ kind: "err", text: `Linking failed — ${errorText(e)}. Please link again.` });
      } finally {
        setBusy(null);
      }
    },
    [refreshOrders],
  );

  /** D40–D43: return from MB App after a payment (the status is only a hint). */
  const handlePayReturn = useCallback(
    async (q: URLSearchParams) => {
      const paymentRef = q.get("paymentRef") ?? "";
      const status = q.get("status");
      const errorCode = q.get("errorCode");
      trace("PA", "3:D40", FLOW, `status hint ${status ?? "–"}${errorCode ? ` (${errorCode})` : ""}`, paymentRef);
      setBusy("Checking your payment…");
      try {
        if (!(await restore())) {
          setPhase("signedOut");
          setNotice({ kind: "warn", text: "Link with MB Bank to see this payment." });
          return;
        }
        const pending = ls().get<PendingOrder>(K.order);
        let orderId = pending && pending.paymentRef === paymentRef ? pending.orderId : null;
        if (!orderId) {
          const list = (await call<StarOrder[]>("/api/orders")) ?? [];
          orderId = list.find((o) => o.paymentRef === paymentRef)?.orderId ?? null;
        }
        if (!orderId) {
          setNotice({ kind: "warn", text: "We couldn't find the order for this payment. Check My orders." });
          return;
        }
        const final = await pollOrder(call, orderId, status === "CANCELLED" ? 1 : 6);
        trace("PA", "3:D42", FLOW, final?.status ?? "unknown", orderId);
        const r = resultOf(final, { status, errorCode });
        trace("U", "3:D43", FLOW, r.notice?.text ?? "no result", orderId);
        if (final && !isPending(final) && pending?.orderId === orderId) ls().set(K.order, null);
        setTicket(r.ticket);
        setNotice(r.notice);
      } catch (e) {
        if (!is401(e)) setNotice({ kind: "err", text: `Couldn't check the payment — ${errorText(e)}` });
      } finally {
        setBusy(null);
        void refreshOrders();
      }
    },
    [call, restore, refreshOrders],
  );

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    setInFrame(window.parent !== window);
    void loadCatalog();

    const url = new URL(window.location.href);
    const path = url.pathname;
    const q = url.searchParams;
    if (path.startsWith("/links/starlight/return")) {
      // Codes in the URL are single-use: drop them from the address/history right away.
      window.history.replaceState(null, "", "/partner-app");
      if (q.has("paymentRef")) void handlePayReturn(q);
      else void handleLinkReturn(q);
      return;
    }
    const viaLink = path.startsWith("/links/starlight/");
    if (viaLink) trace("PA", "2:A2", FLOW, "launch only (no code, no customer data)", APP_ID);
    void (async () => {
      const m = await restore();
      if (!m) {
        setPhase("signedOut");
        return;
      }
      if (viaLink) trace("U", "2:A3", FLOW, `signed in as ${m.partnerUserId}`, m.appId);
      void refreshOrders();
      void checkPending();
    })();
  }, [loadCatalog, restore, refreshOrders, checkPending, handleLinkReturn, handlePayReturn]);

  useEffect(() => {
    const onShow = () => {
      if (document.visibilityState === "visible") void checkPending();
    };
    document.addEventListener("visibilitychange", onShow);
    window.addEventListener("pageshow", onShow);
    return () => {
      document.removeEventListener("visibilitychange", onShow);
      window.removeEventListener("pageshow", onShow);
    };
  }, [checkPending]);

  /** A4–A5: create state + PKCE verifier, keep them in app storage, open MB App via its verified link. */
  async function link() {
    setNotice(null);
    setRelink(false);
    if (!globalThis.crypto?.subtle) {
      setNotice({ kind: "err", text: "This browser can't create a secure link (WebCrypto needs https or localhost)." });
      return;
    }
    const state = randomB64url(16);
    const codeVerifier = randomB64url(32);
    const codeChallenge = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(codeVerifier)));
    ls().set(K.link, { state, codeVerifier } satisfies PendingLink);
    trace("PA", "2:A4", FLOW, "state + codeVerifier in app storage; codeChallenge = S256(codeVerifier)", APP_ID);
    const href = `/links/mbapp/link?appId=${APP_ID}&state=${encodeURIComponent(state)}&codeChallenge=${encodeURIComponent(codeChallenge)}`;
    const routed = await openUrl(href);
    if (routed === "store") {
      setNotice({ kind: "warn", text: "MB App is not installed — install it from the store, then link again." });
    } else if (routed === "refused") {
      setNotice({ kind: "err", text: "The phone couldn't open MB App." });
    } else {
      setNotice({ kind: "ok", text: "Continue in MB App to link your account…" });
    }
  }

  /** D20–D30: create the order + payment server-to-server, then open MB App with the payLink. */
  async function buy(item: Item) {
    if (!me) return;
    setNotice(null);
    setTicket(null);
    trace("PA", "3:D20", FLOW, `${item.title} (${item.itemId})`);
    setBusy("Creating your order…");
    let o: A2aOrder;
    try {
      o = await call<A2aOrder>("/api/orders", { body: { itemId: item.itemId } });
    } catch (e) {
      setBusy(null);
      if (is401(e)) return;
      trace("PA", "3:D25c", FLOW, errorText(e));
      const text = `Couldn't create the payment — ${errorText(e)}`;
      trace("U", "3:D25d", FLOW, text);
      setNotice({ kind: "err", text });
      void refreshOrders();
      return;
    }
    trace("PA", "3:D29", FLOW, `${o.title} · ${money(o.amount, o.currency)} · paymentRef ${o.paymentRef}`, o.orderId);
    ls().set(K.order, {
      orderId: o.orderId,
      paymentRef: o.paymentRef,
      title: o.title,
      amount: o.amount,
      currency: o.currency,
    } satisfies PendingOrder);
    setBusy("Opening MB App…");
    try {
      const routed = await openUrl(o.payLink);
      if (routed === "store") {
        trace("PA", "3:D30a", FLOW, "MB App not installed → store page", o.paymentRef);
        setNotice({
          kind: "warn",
          text: "MB App is not installed, so the store opened instead. Your order stays unpaid until it expires.",
        });
      } else if (routed === "refused") {
        setNotice({ kind: "err", text: "The phone couldn't open MB App." });
      } else {
        setNotice({ kind: "ok", text: "Continue in MB App to pay…" });
      }
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

  async function signOut() {
    setBusy("Signing out…");
    try {
      await call("/api/logout", { method: "POST" });
    } catch {
      /* the local session goes anyway */
    } finally {
      ls().set(K.link, null);
      ls().set(K.order, null);
      dropSession();
      clear();
      setTab("movies");
      setNotice({ kind: "ok", text: "Signed out." });
      setBusy(null);
    }
  }

  return (
    <div className={s.app}>
      <header className={s.bar}>
        <span className={s.logo}>★</span>
        <div className={s.brand}>
          <strong>Starlight Cinema</strong>
          <small>App</small>
        </div>
        {me && (
          <button className={s.barBtn} onClick={signOut} disabled={!!busy}>
            Sign out
          </button>
        )}
      </header>

      <main className={s.main}>
        {!inFrame && (
          <div className={s.banner}>
            Open the Simulator to run the partner app. <a href="/simulator">Go to the Simulator →</a>
          </div>
        )}

        {phase === "signedIn" && me && (
          <section className={s.account}>
            <div>
              <div className={s.hello}>Hi, {me.profile?.name ?? "guest"}</div>
              <div className={s.sub}>
                Linked with MB Bank · <code>{me.partnerUserId}</code>
              </div>
            </div>
          </section>
        )}
        {phase === "signedOut" && (
          <section className={s.linkCard}>
            <div>
              <div className={s.hello}>{relink ? "Link again" : "Pay with MB Bank"}</div>
              <div className={s.sub}>
                Link your MB Bank account to buy tickets. MB App asks you to confirm; Starlight never sees your PIN.
              </div>
            </div>
            <button className={s.kb} onClick={link} disabled={!!busy}>
              Link with MB Bank
            </button>
          </section>
        )}
        {phase === "starting" && <div className={s.empty}>Starting…</div>}

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
      </main>

      <Busy s={s} text={busy} />
      <TicketCard s={s} order={ticket} onDone={() => setTicket(null)} />
    </div>
  );
}
