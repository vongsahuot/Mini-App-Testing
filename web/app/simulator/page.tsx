"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, BANK, errorText, isApiError, money, PORTAL, store, type ApiOptions } from "@/lib/api";
import { traceStep } from "@/lib/trace";
import s from "./sim.module.css";

/* ---------- contract types ---------- */
interface Account {
  accountNo: string;
  name: string;
  balance: number;
  currency: string;
}
interface BankSession {
  sessionToken: string;
  customerId: string;
  name: string;
  phone: string;
  deviceSecret: string;
  accounts: Account[];
}
interface MiniApp {
  appId: string;
  name: string;
  icon: string;
  badge: string | null;
  enabled: boolean;
}
interface MiniAppList {
  sections: { id: string; title: string; apps: MiniApp[] }[];
  banners: { id: string; title: string; subtitle: string; appId: string; color: string }[];
  cacheTtlSeconds: number;
}
interface AppSession {
  sessionId: string;
  appId: string;
  appName: string;
  url: string;
  allowedOrigins: string[];
}
interface Consent {
  granted: boolean;
  partnerName: string;
  scopes: { scope: string; label: string }[];
}
interface PaymentInfo {
  paymentId: string;
  orderId: string;
  amount: number;
  currency: string;
  merchantName: string;
  description: string;
  status: string;
  expiresAt: string;
}
interface ApproveResult {
  paymentId: string;
  status: "PAID" | "FAILED";
  bankTxnId: string | null;
  amount: number;
  currency: string;
  reason: string | null;
}
type PayDecision = { action: "cancel" } | { action: "approve"; account: string };
type Sheet =
  | { kind: "consent"; consent: Consent; done: (allow: boolean) => void }
  | { kind: "pay"; payment: PaymentInfo; accounts: Account[]; done: (d: PayDecision) => void }
  | { kind: "pin"; done: (ok: boolean) => void }
  | { kind: "wait"; text: string }
  | { kind: "receipt"; result: ApproveResult; merchant: string; done: () => void };
interface LogLine {
  ts: string;
  dir: "→" | "←" | "·";
  text: string;
}
type Screen = "login" | "home" | "list";

const DEVICE_KEY = "sim.deviceId";
const SESSION_KEY = "sim.session";
const LIST_KEY = "sim.miniApps";
const PARTNER_SESSION_KEY = "movie-tickets.session"; // partner page shares this origin's sessionStorage

async function hmacHex(secret: string, message: string): Promise<string> {
  if (!crypto?.subtle) throw new Error("WebCrypto unavailable (page must be served from localhost or https)");
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, [
    "sign",
  ]);
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(message));
  return Array.from(new Uint8Array(sig), (b) => b.toString(16).padStart(2, "0")).join("");
}

function deviceId(): string {
  const ls = store("local");
  let id = ls.get<string>(DEVICE_KEY);
  if (!id) {
    id = `web-${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
    ls.set(DEVICE_KEY, id);
  }
  return id;
}

export default function SimulatorPage() {
  const [screen, setScreen] = useState<Screen>("login");
  const [session, setSession] = useState<BankSession | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [list, setList] = useState<MiniAppList | null>(null);
  const [listState, setListState] = useState<"idle" | "loading" | "error">("idle");
  const [app, setApp] = useState<AppSession | null>(null);
  const [sheet, setSheet] = useState<Sheet | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [log, setLog] = useState<LogLine[]>([]);
  const [username, setUsername] = useState("alice");
  const [pin, setPin] = useState("1234");
  const [loginError, setLoginError] = useState<string | null>(null);
  const [devId, setDevId] = useState("");

  const sessionRef = useRef<BankSession | null>(null);
  const jwtRef = useRef<{ jwt: string; exp: number } | null>(null);
  const appRef = useRef<AppSession | null>(null);
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  const busyRef = useRef(false);
  const renderedFrom = useRef<string | null>(null);

  appRef.current = app;

  const addLog = useCallback((dir: LogLine["dir"], text: string) => {
    setLog((l) => [...l.slice(-199), { ts: new Date().toLocaleTimeString(), dir, text }]);
  }, []);

  const showToast = useCallback((text: string) => {
    setToast(text);
    window.setTimeout(() => setToast((t) => (t === text ? null : t)), 4500);
  }, []);

  /* ---------- session ---------- */
  const logout = useCallback((msg?: string) => {
    sessionRef.current = null;
    jwtRef.current = null;
    store("session").set(SESSION_KEY, null);
    store("session").set(PARTNER_SESSION_KEY, null);
    setSession(null);
    setApp(null);
    setSheet(null);
    setScreen("login");
    if (msg) setLoginError(msg);
  }, []);

  useEffect(() => {
    setDevId(deviceId());
    const saved = store("session").get<BankSession>(SESSION_KEY);
    if (saved?.sessionToken) {
      sessionRef.current = saved;
      setSession(saved);
      setAccounts(saved.accounts ?? []);
      setScreen("home");
    }
  }, []);

  async function login(e: React.FormEvent) {
    e.preventDefault();
    setLoginError(null);
    try {
      const sess = await api<BankSession>(`${BANK}/api/v1/bank-app/login`, {
        body: { username: username.trim(), pin, deviceId: devId || deviceId() },
      });
      sessionRef.current = sess;
      jwtRef.current = null;
      store("session").set(SESSION_KEY, sess);
      store("session").set(PARTNER_SESSION_KEY, null);
      setSession(sess);
      setAccounts(sess.accounts ?? []);
      setScreen("home");
    } catch (err) {
      setLoginError(errorText(err));
    }
  }

  /** Bank JWT for portal calls. Cached until 30 s before expiry unless `fresh`. */
  const getJwt = useCallback(
    async (fresh = false): Promise<string> => {
      const sess = sessionRef.current;
      if (!sess) throw { status: 401, code: "NO_SESSION", message: "Not logged in to the bank app" };
      const c = jwtRef.current;
      if (!fresh && c && c.exp - 30_000 > Date.now()) return c.jwt;
      try {
        const r = await api<{ jwt: string; expiresIn: number }>(`${BANK}/api/v1/bank-app/jwt`, {
          method: "POST",
          token: sess.sessionToken,
        });
        jwtRef.current = { jwt: r.jwt, exp: Date.now() + (r.expiresIn ?? 300) * 1000 };
        return r.jwt;
      } catch (e) {
        if (isApiError(e) && e.status === 401) logout("Bank session expired, log in again.");
        throw e;
      }
    },
    [logout],
  );

  const portal = useCallback(
    async <T,>(path: string, opts: ApiOptions = {}, fresh = false): Promise<T> =>
      api<T>(`${PORTAL}${path}`, { ...opts, token: await getJwt(fresh) }),
    [getJwt],
  );

  const refreshAccounts = useCallback(async () => {
    const sess = sessionRef.current;
    if (!sess) return accounts;
    try {
      const a = await api<Account[]>(`${BANK}/api/v1/bank-app/accounts`, { token: sess.sessionToken });
      setAccounts(a ?? []);
      return a ?? [];
    } catch (e) {
      if (isApiError(e) && e.status === 401) logout("Bank session expired, log in again.");
      return sessionRef.current?.accounts ?? [];
    }
  }, [accounts, logout]);

  useEffect(() => {
    if (screen === "home") void refreshAccounts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [screen]);

  /* ---------- native mini apps list (steps 1–5) ---------- */
  async function openList() {
    traceStep("APP", 1, null, null);
    setScreen("list");
    const cached = store("local").get<{ at: number; data: MiniAppList }>(LIST_KEY);
    if (cached && Date.now() - cached.at < (cached.data.cacheTtlSeconds ?? 60) * 1000) {
      renderedFrom.current = "cache";
      setList(cached.data);
    } else {
      setList(null);
    }
    setListState("loading");
    try {
      const data = await portal<MiniAppList>("/api/v1/client/mini-apps");
      traceStep(
        "APP",
        4,
        `${data.sections?.length ?? 0} sections, ${data.banners?.length ?? 0} banners, ttl ${data.cacheTtlSeconds}s`,
      );
      store("local").set(LIST_KEY, { at: Date.now(), data });
      renderedFrom.current = "network";
      setList(data);
      setListState("idle");
    } catch (e) {
      setListState("error");
      showToast(`Mini apps: ${errorText(e)}`);
    }
  }

  // Step 5 once the list has actually been rendered.
  useEffect(() => {
    if (list && screen === "list" && renderedFrom.current) {
      traceStep("APP", 5, `rendered from ${renderedFrom.current}`);
      renderedFrom.current = null;
    }
  }, [list, screen]);

  /* ---------- open mini app (steps 6–8) ---------- */
  async function openApp(a: MiniApp) {
    if (!a.enabled) return;
    traceStep("APP", 6, a.name, a.appId);
    try {
      const sess = await portal<AppSession>(`/api/v1/client/mini-apps/${encodeURIComponent(a.appId)}/sessions`, {
        method: "POST",
      });
      traceStep("APP", 8, `${sess.url} · allowed ${sess.allowedOrigins?.join(", ")}`, a.appId);
      addLog("·", `open ${sess.appName} ${sess.url} (allowed: ${sess.allowedOrigins?.join(", ")})`);
      setApp(sess);
    } catch (e) {
      showToast(`Cannot open ${a.name}: ${errorText(e)}`);
    }
  }

  function closeApp(reason: string) {
    const a = appRef.current;
    if (!a) return;
    setApp(null);
    setSheet(null);
    busyRef.current = false;
    traceStep("APP", 62, reason, a.appId);
    addLog("·", `WebView closed (${reason})`);
  }

  /* ---------- sheets as promises ---------- */
  function ask<T>(make: (done: (v: T) => void) => Sheet): Promise<T> {
    return new Promise<T>((resolve) => setSheet(make((v) => resolve(v))));
  }

  /* ---------- bridge host ---------- */
  async function handleLogin(a: AppSession, scopes: string[]): Promise<{ ok: boolean; payload: unknown }> {
    traceStep("APP", 10, `scopes ${scopes.join(",")}`, a.appId);
    const consent = await portal<Consent>(
      `/api/v1/client/mini-apps/${encodeURIComponent(a.appId)}/consents?scopes=${encodeURIComponent(scopes.join(","))}`,
    );
    let consentGiven = consent.granted;
    if (!consent.granted) {
      traceStep("APP", 11, `${consent.partnerName}: ${consent.scopes?.map((x) => x.label).join(", ")}`, a.appId);
      const allow = await ask<boolean>((done) => ({ kind: "consent", consent, done }));
      setSheet(null);
      if (!allow) return { ok: false, payload: { code: "USER_DENIED", message: "The customer declined to share data" } };
      traceStep("APP", 12, null, a.appId);
      consentGiven = true;
    }
    const r = await portal<{ authCode: string; expiresIn: number }>(
      `/api/v1/client/mini-apps/${encodeURIComponent(a.appId)}/auth-codes`,
      { body: { sessionId: a.sessionId, scopes, consentGiven } },
    );
    traceStep("APP", 15, `expires in ${r.expiresIn}s`, a.appId);
    return { ok: true, payload: { authCode: r.authCode } };
  }

  async function handlePay(paymentId: string, pageOrigin: string): Promise<{ ok: boolean; payload: unknown }> {
    traceStep("APP", 26, null, paymentId);
    setSheet({ kind: "wait", text: "Loading payment…" });
    const payment = await portal<PaymentInfo>(
      `/api/v1/client/payments/${encodeURIComponent(paymentId)}`,
      { headers: { "X-Page-Origin": pageOrigin } },
      true, // step 27 always uses a fresh JWT
    );
    traceStep("APP", 28, `${payment.merchantName} ${money(payment.amount, payment.currency)}`, paymentId);
    const accts = await refreshAccounts();

    let debitAccount = "";
    for (;;) {
      traceStep("APP", 29, payment.description, paymentId);
      const d = await ask<PayDecision>((done) => ({ kind: "pay", payment, accounts: accts, done }));
      if (d.action === "cancel") {
        traceStep("APP", 30, null, paymentId);
        setSheet({ kind: "wait", text: "Cancelling…" });
        await portal(`/api/v1/client/payments/${encodeURIComponent(paymentId)}/cancel`, { method: "POST" });
        setSheet(null);
        return { ok: true, payload: { status: "CANCELLED" } };
      }
      const pinOk = await ask<boolean>((done) => ({ kind: "pin", done }));
      if (pinOk) {
        debitAccount = d.account;
        break;
      }
    }

    const sess = sessionRef.current;
    if (!sess) throw { code: "NO_SESSION", message: "Bank session lost" };
    const signature = await hmacHex(sess.deviceSecret, paymentId);
    traceStep("APP", 33, `signed with device key, debit ${debitAccount}`, paymentId);
    setSheet({ kind: "wait", text: "Processing payment…" });
    const result = await api<ApproveResult>(`${BANK}/api/v1/bank-app/payments/${encodeURIComponent(paymentId)}/approve`, {
      token: sess.sessionToken,
      body: { debitAccount, signature },
    });
    traceStep("APP", 40, `${result.status} ${result.bankTxnId ?? result.reason ?? ""}`.trim(), paymentId);
    void refreshAccounts();
    await ask<void>((done) => ({ kind: "receipt", result, merchant: payment.merchantName, done: () => done() }));
    setSheet(null);
    return {
      ok: true,
      payload: { status: result.status, bankTxnId: result.bankTxnId ?? null, reason: result.reason ?? null },
    };
  }

  async function onBridgeMessage(e: MessageEvent) {
    const d = e.data as { source?: string; id?: unknown; method?: unknown; params?: unknown } | null;
    if (!d || typeof d !== "object" || d.source !== "kosign-bridge") return;
    const id = String(d.id);
    const method = String(d.method);
    const params = (d.params ?? {}) as Record<string, unknown>;
    const target = e.source as Window | null;
    addLog("←", `${method} #${id.slice(0, 8)} ${JSON.stringify(params)} from ${e.origin}`);

    const reply = (ok: boolean, payload: unknown) => {
      addLog("→", `${ok ? "ok" : "error"} #${id.slice(0, 8)} ${JSON.stringify(payload)}`);
      try {
        target?.postMessage(
          { source: "kosign-bridge-reply", id, ok, payload },
          e.origin && e.origin !== "null" ? e.origin : "*",
        );
      } catch {
        /* window gone */
      }
    };

    const a = appRef.current;
    const frame = iframeRef.current;
    if (!a || !frame || e.source !== frame.contentWindow || !a.allowedOrigins?.includes(e.origin)) {
      reply(false, {
        code: "ORIGIN_NOT_ALLOWED",
        message: `Origin ${e.origin} is not allowed${a ? ` for ${a.appId} (${a.allowedOrigins?.join(", ")})` : ""}`,
      });
      return;
    }

    if (method === "close") {
      traceStep("APP", 61, null, a.appId);
      reply(true, {});
      closeApp("bridge close()");
      return;
    }
    if (method !== "login" && method !== "pay") {
      reply(false, { code: "ERROR", message: `Unknown method ${method}` });
      return;
    }
    if (busyRef.current) {
      reply(false, { code: "ERROR", message: "Another bridge call is in progress" });
      return;
    }
    busyRef.current = true;
    try {
      let r: { ok: boolean; payload: unknown };
      if (method === "login") {
        const scopes = Array.isArray(params.scopes) ? params.scopes.map(String) : [];
        r = await handleLogin(a, scopes);
      } else {
        const paymentId = typeof params.paymentId === "string" ? params.paymentId : "";
        if (!paymentId) throw { code: "ERROR", message: "paymentId is required" };
        r = await handlePay(paymentId, e.origin);
      }
      if (appRef.current === a) reply(r.ok, r.payload);
    } catch (err) {
      setSheet(null);
      showToast(errorText(err));
      if (appRef.current === a) reply(false, { code: "ERROR", message: errorText(err) });
    } finally {
      busyRef.current = false;
    }
  }

  const handlerRef = useRef(onBridgeMessage);
  handlerRef.current = onBridgeMessage;
  useEffect(() => {
    const fn = (e: MessageEvent) => void handlerRef.current(e);
    window.addEventListener("message", fn);
    return () => window.removeEventListener("message", fn);
  }, []);

  /* ---------- render ---------- */
  return (
    <main className={s.stage}>
      <div className={s.intro}>
        <div className="eyebrow">Lane APP · browser bank app</div>
        <h1>Bank app simulator</h1>
        <p>
          Does what the Android app does. Device <code>{devId || "…"}</code>.{" "}
          <a href="/admin" target="_blank" rel="noreferrer">
            Open test console ↗
          </a>
        </p>
      </div>

      <div className={s.phone}>
        <div className={s.notch} />
        <div className={s.screen}>
          {screen === "login" && (
            <form className={s.login} onSubmit={login}>
              <div className={s.bankLogo}>KB</div>
              <h2>KB Mobile</h2>
              <label>
                Username
                <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
              </label>
              <label>
                PIN
                <input
                  value={pin}
                  onChange={(e) => setPin(e.target.value)}
                  type="password"
                  inputMode="numeric"
                  autoComplete="current-password"
                />
              </label>
              {loginError && <div className={s.error}>{loginError}</div>}
              <button className={s.primary} type="submit">
                Log in
              </button>
              <div className={s.hint}>
                Try{" "}
                <button type="button" className={s.link} onClick={() => setUsername("alice")}>
                  alice
                </button>{" "}
                or{" "}
                <button type="button" className={s.link} onClick={() => setUsername("bob")}>
                  bob
                </button>{" "}
                · PIN 1234
              </div>
            </form>
          )}

          {screen === "home" && session && (
            <div className={s.home}>
              <div className={s.topRow}>
                <div>
                  <small>Good day</small>
                  <strong>{session.name}</strong>
                </div>
                <button className={s.ghost} onClick={() => logout()}>
                  Log out
                </button>
              </div>
              {accounts.map((a) => (
                <div key={a.accountNo} className={s.accountCard}>
                  <small>{a.name}</small>
                  <span className="mono">{a.accountNo}</span>
                  <strong>{money(a.balance, a.currency)}</strong>
                </div>
              ))}
              <div className={s.tiles}>
                <button className={s.tile} onClick={openList}>
                  <span>🧩</span>Mini apps
                </button>
                {["💸 Transfer", "🧾 Pay bills", "📈 Invest"].map((t) => (
                  <button key={t} className={s.tile} disabled>
                    <span>{t.split(" ")[0]}</span>
                    {t.split(" ").slice(1).join(" ")}
                  </button>
                ))}
              </div>
            </div>
          )}

          {screen === "list" && (
            <div className={s.list}>
              <div className={s.navBar}>
                <button className={s.back} onClick={() => setScreen("home")} aria-label="Back">
                  ‹
                </button>
                <strong>Mini apps</strong>
                {listState === "loading" && <span className={s.dot} title="Refreshing" />}
              </div>
              {!list && listState === "loading" && <div className={s.hint}>Loading…</div>}
              {!list && listState === "error" && (
                <div className={s.hint}>
                  Could not load mini apps.{" "}
                  <button className={s.link} onClick={openList}>
                    Retry
                  </button>
                </div>
              )}
              {list?.banners?.map((b) => {
                const target = (list.sections ?? []).flatMap((x) => x.apps).find((x) => x.appId === b.appId);
                return (
                  <button
                    key={b.id}
                    className={s.banner}
                    style={{ background: b.color }}
                    disabled={!target?.enabled}
                    onClick={() => target && openApp(target)}
                  >
                    <strong>{b.title}</strong>
                    <span>{b.subtitle}</span>
                  </button>
                );
              })}
              {list?.sections?.map((sec) => (
                <section key={sec.id}>
                  <h3 className={s.sectionTitle}>{sec.title}</h3>
                  <div className={s.apps}>
                    {sec.apps.map((a) => (
                      <button
                        key={a.appId}
                        className={s.app}
                        disabled={!a.enabled}
                        onClick={() => openApp(a)}
                        aria-disabled={!a.enabled}
                      >
                        <span className={s.appIcon}>{a.icon}</span>
                        {a.name}
                        {a.badge && <em className={s.badge}>{a.badge}</em>}
                      </button>
                    ))}
                  </div>
                </section>
              ))}
            </div>
          )}

          {app && (
            <div className={s.webview}>
              <div className={s.webviewBar}>
                <strong>{app.appName}</strong>
                <button onClick={() => closeApp("X tapped")} aria-label="Close mini app">
                  ✕
                </button>
              </div>
              <iframe ref={iframeRef} src={app.url} title={app.appName} className={s.frame} />
            </div>
          )}

          {sheet && (
            <div className={s.scrim}>
              <div className={s.sheet} role="dialog" aria-modal="true">
                <SheetBody key={sheet.kind} sheet={sheet} />
              </div>
            </div>
          )}

          {toast && (
            <div className={s.toast} role="alert" onClick={() => setToast(null)}>
              {toast}
            </div>
          )}
        </div>
      </div>

      <details className={s.log} open>
        <summary>Bridge log ({log.length})</summary>
        {log.length === 0 ? (
          <div className={s.hint}>No bridge messages yet.</div>
        ) : (
          <ol>
            {log
              .slice()
              .reverse()
              .map((l, i) => (
                <li key={log.length - i}>
                  <span>{l.ts}</span> <b>{l.dir}</b> {l.text}
                </li>
              ))}
          </ol>
        )}
        {log.length > 0 && (
          <button className="btn" onClick={() => setLog([])}>
            Clear log
          </button>
        )}
      </details>
    </main>
  );
}

function SheetBody({ sheet }: { sheet: Sheet }) {
  const [account, setAccount] = useState(sheet.kind === "pay" ? (sheet.accounts[0]?.accountNo ?? "") : "");
  const [pin, setPin] = useState("");
  const [pinError, setPinError] = useState(false);

  switch (sheet.kind) {
    case "wait":
      return (
        <div className={s.wait}>
          <div className={s.spinner} />
          {sheet.text}
        </div>
      );
    case "consent":
      return (
        <>
          <h3>Share your info?</h3>
          <p>
            <strong>{sheet.consent.partnerName}</strong> wants to access:
          </p>
          <ul className={s.scopes}>
            {sheet.consent.scopes?.map((x) => (
              <li key={x.scope}>
                ✓ {x.label} <code>{x.scope}</code>
              </li>
            ))}
          </ul>
          <div className={s.sheetActions}>
            <button className={s.ghost} onClick={() => sheet.done(false)}>
              Deny
            </button>
            <button className={s.primary} onClick={() => sheet.done(true)}>
              Allow
            </button>
          </div>
        </>
      );
    case "pay": {
      const p = sheet.payment;
      return (
        <>
          <h3>Confirm payment</h3>
          <div className={s.amount}>{money(p.amount, p.currency)}</div>
          <dl className={s.kv}>
            <dt>Merchant</dt>
            <dd>{p.merchantName}</dd>
            <dt>For</dt>
            <dd>{p.description}</dd>
            <dt>Payment</dt>
            <dd className="mono">{p.paymentId}</dd>
          </dl>
          <label className={s.field}>
            Debit account
            <select value={account} onChange={(e) => setAccount(e.target.value)}>
              {sheet.accounts.map((a) => (
                <option key={a.accountNo} value={a.accountNo}>
                  {a.accountNo} · {money(a.balance, a.currency)}
                </option>
              ))}
            </select>
          </label>
          <div className={s.sheetActions}>
            <button className={s.ghost} onClick={() => sheet.done({ action: "cancel" })}>
              Cancel
            </button>
            <button
              className={s.primary}
              disabled={!account}
              onClick={() => sheet.done({ action: "approve", account })}
            >
              Approve
            </button>
          </div>
        </>
      );
    }
    case "pin":
      return (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (pin === "1234") sheet.done(true);
            else {
              setPinError(true);
              setPin("");
            }
          }}
        >
          <h3>Enter your PIN</h3>
          <p className={s.hint}>Unlocks the device key that signs this payment (mock PIN 1234).</p>
          <input
            className={s.pin}
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 6))}
            type="password"
            inputMode="numeric"
            autoFocus
            aria-label="PIN"
          />
          {pinError && <div className={s.error}>Wrong PIN, try again.</div>}
          <div className={s.sheetActions}>
            <button type="button" className={s.ghost} onClick={() => sheet.done(false)}>
              Back
            </button>
            <button type="submit" className={s.primary} disabled={pin.length < 4}>
              Confirm
            </button>
          </div>
        </form>
      );
    case "receipt": {
      const r = sheet.result;
      const ok = r.status === "PAID";
      return (
        <>
          <div className={ok ? s.receiptOk : s.receiptBad}>{ok ? "✓" : "!"}</div>
          <h3 style={{ textAlign: "center" }}>{ok ? "Payment successful" : "Payment failed"}</h3>
          <div className={s.amount}>{money(r.amount, r.currency)}</div>
          <dl className={s.kv}>
            <dt>Merchant</dt>
            <dd>{sheet.merchant}</dd>
            {ok ? (
              <>
                <dt>Bank txn</dt>
                <dd className="mono">{r.bankTxnId}</dd>
              </>
            ) : (
              <>
                <dt>Reason</dt>
                <dd className="mono">{r.reason ?? "unknown"}</dd>
              </>
            )}
          </dl>
          <div className={s.sheetActions}>
            <button className={s.primary} onClick={() => sheet.done()}>
              Done
            </button>
          </div>
        </>
      );
    }
  }
}
