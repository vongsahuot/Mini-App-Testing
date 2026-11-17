"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, errorText, GW, isApiError, money, PARTNER, store, type ApiOptions } from "@/lib/api";
import type { Order, PayResult } from "@/lib/bridge";
import { claimedBy, STORE_URL, type RoutedTo } from "@/lib/os";
import { BUNDLED_SDK_VERSION, versionAtLeast, type HostApi } from "@/lib/sdk/host";
import { StarlightSdk } from "@/lib/sdk/StarlightSdk";
import { trace, type Flow } from "@/lib/trace";
import { NativeApp } from "./NativeApp";
import { PaymentSheet } from "./PaymentSheet";
import type { Account, BankSession, Gw, MiniAppDetail, MiniAppList, MiniAppTile, Notice, PaymentInfo } from "./types";
import s from "./sim.module.css";

const APP_VERSION = "5.2.0";
const SESSION_KEY = "sim.session";
const LIST_KEY = "sim.miniApps";
const LAST_CUSTOMER_KEY = "sim.lastCustomer";
const LOAD_TIMEOUT_MS = 15_000;

type Screen = "login" | "home" | "list";
type Sheet =
  | { kind: "consent"; detail: MiniAppDetail; done: (allow: boolean) => void }
  | { kind: "pay"; info: PaymentInfo; done: (r: PayResult) => void }
  | { kind: "unlock"; text: string; done: (ok: boolean) => void }
  | { kind: "link"; detail: MiniAppDetail; done: (ok: boolean) => void }
  | { kind: "wait"; text: string };
interface Opened {
  kind: "webview" | "sdk" | "api";
  detail: MiniAppDetail;
  /** One per launch: the bridge / host API answer only the instance MB App started. */
  instance: string;
  loaded: boolean;
  failed: string | null;
  frameKey: number;
}
type Foreground = "mbapp" | "starlight" | "browser";
interface LogLine {
  ts: string;
  dir: "→" | "←" | "·";
  text: string;
}

const deviceId = () => {
  const ls = store("local");
  let id = ls.get<string>("sim.deviceId");
  if (!id) ls.set("sim.deviceId", (id = `web-${crypto.randomUUID().slice(0, 8)}`));
  return id;
};

export default function SimulatorPage() {
  // ---------- MB App state ----------
  const [screen, setScreen] = useState<Screen>("login");
  const [session, setSession] = useState<BankSession | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [list, setList] = useState<MiniAppList | null>(null);
  const [listState, setListState] = useState<"idle" | "loading" | "error">("idle");
  const [opened, setOpened] = useState<Opened | null>(null);
  const [sheet, setSheet] = useState<Sheet | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [push, setPush] = useState<Notice | null>(null);
  const [otps, setOtps] = useState<Record<string, string>>({});
  const [username, setUsername] = useState("alice");
  const [pin, setPin] = useState("1234");
  const [loginError, setLoginError] = useState<string | null>(null);
  const [log, setLog] = useState<LogLine[]>([]);
  const [devId, setDevId] = useState("");
  // ---------- phone OS state ----------
  const [fg, setFg] = useState<Foreground>("mbapp");
  const [installed, setInstalled] = useState({ mbapp: true, starlight: true });
  const [partnerSrc, setPartnerSrc] = useState<string | null>(null);
  const [partnerKey, setPartnerKey] = useState(0);
  const [browserUrl, setBrowserUrl] = useState<string | null>(null);

  const sessionRef = useRef<BankSession | null>(null);
  const openedRef = useRef<Opened | null>(null);
  const sheetBusy = useRef(false);
  const lastNotice = useRef(0);
  const details = useRef<Record<string, MiniAppDetail>>({});
  const pendingLink = useRef<string | null>(null);
  const listSource = useRef<string | null>(null);
  const webFrame = useRef<HTMLIFrameElement | null>(null);
  const partnerFrame = useRef<HTMLIFrameElement | null>(null);
  const installedRef = useRef(installed);
  openedRef.current = opened;
  installedRef.current = installed;

  const addLog = useCallback((dir: LogLine["dir"], text: string) => {
    setLog((l) => [...l.slice(-199), { ts: new Date().toLocaleTimeString(), dir, text }]);
  }, []);
  const showToast = useCallback((text: string) => {
    setToast(text);
    window.setTimeout(() => setToast((t) => (t === text ? null : t)), 5000);
  }, []);

  // ---------- session + app gateway ----------
  const logout = useCallback((msg?: string) => {
    sessionRef.current = null;
    store("session").set(SESSION_KEY, null);
    setSession(null);
    setOpened(null);
    setSheet(null);
    setScreen("login");
    if (msg) setLoginError(msg);
  }, []);

  /** MB App → app gateway (C1): customer session token, app version and platform on every call. */
  const gw = useCallback(
    async <T,>(path: string, opts: ApiOptions = {}): Promise<T> => {
      try {
        return await api<T>(`${GW}${path}`, {
          ...opts,
          token: sessionRef.current?.sessionToken,
          headers: { "X-App-Version": APP_VERSION, "X-Platform": "web", ...opts.headers },
        });
      } catch (e) {
        if (isApiError(e) && e.status === 401 && (e.code === "INVALID_SESSION" || e.code === "MISSING_TOKEN")) {
          logout("Session expired, log in again.");
        }
        throw e;
      }
    },
    [logout],
  );

  const refreshAccounts = useCallback(async () => {
    if (!sessionRef.current) return [];
    try {
      const a = await gw<Account[]>("/accounts");
      setAccounts(a);
      return a;
    } catch {
      return sessionRef.current?.accounts ?? [];
    }
  }, [gw]);

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
      const sess = await api<BankSession>(`${GW}/login`, { body: { username: username.trim(), pin, deviceId: deviceId() } });
      // Per-customer WebView data store: a different customer never inherits the previous one's partner sessions.
      const last = store("local").get<string>(LAST_CUSTOMER_KEY);
      if (last && last !== sess.customerId) {
        await api(`${PARTNER}/api/logout`, { method: "POST" }).catch(() => {});
        store("local").set(LIST_KEY, null);
      }
      store("local").set(LAST_CUSTOMER_KEY, sess.customerId);
      sessionRef.current = sess;
      lastNotice.current = 0;
      store("session").set(SESSION_KEY, sess);
      setSession(sess);
      setAccounts(sess.accounts ?? []);
      setScreen("home");
      const link = pendingLink.current;
      pendingLink.current = null;
      if (link) void handleMbLink(link);
    } catch (err) {
      setLoginError(errorText(err));
    }
  }

  // ---------- push notifications (C10, polled) ----------
  useEffect(() => {
    if (!session) return;
    let stop = false;
    const poll = async (initial: boolean) => {
      try {
        const list = await gw<Notice[]>(`/notifications?after=${lastNotice.current}`);
        if (stop || !list.length) return;
        lastNotice.current = list[list.length - 1].id;
        const otp: Record<string, string> = {};
        for (const n of list) {
          const m = n.kind === "OTP" && n.ref ? n.body.match(/OTP (\d{6})/) : null;
          if (m) otp[n.ref!] = m[1];
        }
        if (Object.keys(otp).length) setOtps((o) => ({ ...o, ...otp }));
        if (!initial) {
          const n = list[list.length - 1];
          setPush(n);
          addLog("·", `push: ${n.title} — ${n.body}`);
          window.setTimeout(() => setPush((p) => (p?.id === n.id ? null : p)), 6000);
          if (n.kind !== "OTP") void refreshAccounts();
        }
      } catch {
        /* offline: try again next tick */
      }
    };
    void poll(lastNotice.current === 0);
    const t = window.setInterval(() => void poll(false), 3000);
    return () => {
      stop = true;
      window.clearInterval(t);
    };
  }, [session, gw, addLog, refreshAccounts]);

  // ---------- sheets as promises ----------
  function ask<T>(make: (done: (v: T) => void) => Sheet): Promise<T> {
    return new Promise<T>((resolve) => setSheet(make((v) => resolve(v))));
  }

  /** The shared payment bottom sheet (section 3, 14–45g). One sheet at a time. */
  const runPayment = useCallback(async (info: PaymentInfo): Promise<PayResult> => {
    sheetBusy.current = true;
    try {
      await refreshAccounts();
      const r = await ask<PayResult>((done) => ({ kind: "pay", info, done }));
      setSheet(null);
      void refreshAccounts();
      return r;
    } finally {
      sheetBusy.current = false;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshAccounts]);

  // ---------- Section 1: Mini App list ----------
  async function openList() {
    trace("APP", "1:1", null, null, null);
    setScreen("list");
    const cached = store("local").get<MiniAppList>(LIST_KEY);
    if (cached) {
      setList(cached);
      listSource.current = "cache";
    }
    setListState("loading");
    try {
      const data = await gw<MiniAppList>("/mini-apps", { headers: cached?.etag ? { "If-None-Match": cached.etag } : {} });
      trace("APP", "1:8", null, `200, etag ${data.etag}, ${data.sections.flatMap((x) => x.apps).length} apps`);
      store("local").set(LIST_KEY, data);
      listSource.current = "network";
      setList(data);
      setListState("idle");
    } catch (e) {
      if (isApiError(e) && e.status === 304 && cached) {
        trace("APP", "1:8", null, "304 Not Modified → cached list");
        listSource.current = "cache (304)";
        setList({ ...cached });
        setListState("idle");
        return;
      }
      setListState("error");
      showToast(`Mini Apps: ${errorText(e)}`);
    }
  }

  useEffect(() => {
    if (list && screen === "list" && listSource.current) {
      trace("U", "1:9", null, `from ${listSource.current}`);
      listSource.current = null;
    }
  }, [list, screen]);

  // ---------- Section 2: detail, consent, launch ----------
  async function loadDetail(appId: string, flow: Flow | null): Promise<MiniAppDetail> {
    const d = await gw<MiniAppDetail>(`/mini-apps/${encodeURIComponent(appId)}`);
    details.current[appId] = d;
    trace("APP", "2:8", flow ?? d.type, `${d.type}, consent ${d.consent.status}, registered ${d.registered}`, appId);
    return d;
  }

  /** 2:9–2:23 when consent is missing or outdated. Returns false if the customer declined or it failed. */
  async function ensureConsent(d: MiniAppDetail): Promise<boolean> {
    if (d.consent.status === "ACCEPTED") return true;
    trace("U", "2:9", d.type, d.consent.scopes.map((x) => x.label).join(", "), d.appId);
    const allow = await ask<boolean>((done) => ({ kind: "consent", detail: d, done }));
    setSheet(null);
    trace("APP", "2:10", d.type, allow ? `accepted v${d.consent.currentVersion}` : "declined → close", d.appId);
    if (!allow) return false;
    setSheet({ kind: "wait", text: "Saving your consent…" });
    try {
      await gw(`/mini-apps/${encodeURIComponent(d.appId)}/consent`, { body: { consentVersion: d.consent.currentVersion } });
      trace("APP", "2:23", d.type, "consent ACCEPTED", d.appId);
      setSheet(null);
      return true;
    } catch (e) {
      trace("APP", "2:19d", d.type, errorText(e), d.appId);
      trace("APP", "2:19e", d.type, "Can't open right now, try again", d.appId);
      setSheet(null);
      showToast(`Can't open ${d.name} right now, try again. (${isApiError(e) ? e.code : "error"})`);
      return false;
    }
  }

  async function openApp(tile: MiniAppTile) {
    trace("APP", "2:1", tile.type, tile.name, tile.appId);
    let d: MiniAppDetail;
    try {
      setSheet({ kind: "wait", text: `Opening ${tile.name}…` });
      d = await loadDetail(tile.appId, tile.type);
      setSheet(null);
    } catch (e) {
      setSheet(null);
      showToast(`Cannot open ${tile.name}: ${errorText(e)}`);
      return;
    }
    if (!(await ensureConsent(d))) return;
    launch(d);
  }

  function launch(d: MiniAppDetail) {
    const instance = crypto.randomUUID();
    if (d.type === "webview") {
      trace("APP", "2:24", "webview", `bridges only for ${d.launch.allowedOrigins?.join(", ")}, main frame`, d.appId);
      setOpened({ kind: "webview", detail: d, instance, loaded: false, failed: null, frameKey: Date.now() });
    } else if (d.type === "sdk") {
      const ok = d.launch.sdkEnabled !== false && versionAtLeast(BUNDLED_SDK_VERSION, d.launch.minSdkVersion);
      trace("APP", "2:24", "sdk", `kill switch ${d.launch.sdkEnabled === false ? "OFF" : "on"}, bundled ${BUNDLED_SDK_VERSION} ≥ ${d.launch.minSdkVersion}: ${ok}`, d.appId);
      if (!ok) {
        trace("APP", "2:25a", "sdk", d.launch.sdkEnabled === false ? "SDK switched off by ops" : "bundled SDK too old", d.appId);
        setOpened({ kind: "sdk", detail: d, instance, loaded: false, failed: d.launch.sdkEnabled === false ? `${d.name} is switched off right now.` : "Update MB App to use this Mini App.", frameKey: 0 });
        return;
      }
      addLog("·", `start SDK ${d.launch.sdkModule} (instance ${instance.slice(0, 8)}) with host API only`);
      setOpened({ kind: "sdk", detail: d, instance, loaded: true, failed: null, frameKey: 0 });
    } else if (d.type === "api") {
      setOpened({ kind: "api", detail: d, instance, loaded: true, failed: null, frameKey: 0 });
    } else {
      trace("APP", "2:A1", "a2a", d.launch.appLink, d.appId);
      const routed = osOpen(d.launch.appLink ?? "", "mbapp");
      if (routed === "store") trace("APP", "2:A2a", "a2a", d.launch.storeUrl, d.appId);
    }
  }

  /** 4:20–4:22: close the WebView / SDK screens (bridge close(), host close() or the native X). */
  function closeApp(reason: string) {
    const o = openedRef.current;
    if (!o) return;
    const flow = o.kind === "api" ? null : o.kind;
    if (flow) trace("APP", "4:20", flow, reason, o.detail.appId);
    if (flow) trace("APP", "4:21", flow, flow === "webview" ? "WebView closed, bridges removed" : "SDK screens closed, host API released", o.detail.appId);
    setOpened(null);
    setSheet(null);
    if (flow) trace("U", "4:22", flow, null, o.detail.appId);
    addLog("·", `${o.kind} closed (${reason})`);
  }

  // WebView load timeout (2:25a)
  useEffect(() => {
    if (opened?.kind !== "webview" || opened.loaded || opened.failed) return;
    const t = window.setTimeout(() => {
      trace("APP", "2:25a", "webview", `entryUrl not loaded in ${LOAD_TIMEOUT_MS / 1000} s`, opened.detail.appId);
      setOpened((o) => (o && o.instance === opened.instance ? { ...o, failed: "The page didn't load." } : o));
    }, LOAD_TIMEOUT_MS);
    return () => window.clearTimeout(t);
  }, [opened]);

  // ---------- login() / openPayment() shared by the JS bridge and the SDK host API ----------
  async function authCode(o: Opened): Promise<string> {
    const r = await gw<{ authCode: string }>(`/mini-apps/${encodeURIComponent(o.detail.appId)}/auth-code`, { body: {} });
    trace("APP", "2:34", o.kind as Flow, "authCode (1 use, 60 s)", o.detail.appId);
    return r.authCode;
  }

  async function openPayment(o: Opened, order: Order, checksum: string): Promise<PayResult> {
    const flow = o.kind as Flow;
    trace("APP", "3:5", flow, `orderId ${order?.orderId}`, o.detail.appId);
    if (sheetBusy.current) {
      trace("APP", "3:5a", flow, "refused: a payment sheet is already open", o.detail.appId);
      throw { code: "SHEET_OPEN", message: "A payment sheet is already open" };
    }
    trace("APP", "3:5a", flow, `appId ${o.detail.appId} taken from the open Mini App`, o.detail.appId);
    sheetBusy.current = true;
    let info: PaymentInfo;
    try {
      setSheet({ kind: "wait", text: "Checking the order with the bank…" });
      info = await gw<PaymentInfo>("/payments/verify", { body: { appId: o.detail.appId, order, checksum } });
      trace("APP", "3:13", flow, `${info.paymentRef} ${money(info.amount, info.currency)} · ${info.merchantName}`, info.paymentRef);
    } catch (e) {
      setSheet(null);
      sheetBusy.current = false;
      trace("APP", "3:57", flow, isApiError(e) ? e.code : errorText(e), order?.orderId);
      return { status: "FAILED", errorCode: isApiError(e) ? e.code : "ERROR" };
    }
    sheetBusy.current = false;
    return runPayment(info);
  }

  // ---------- JS bridge host (WebView) ----------
  async function onBridgeMessage(e: MessageEvent) {
    const d = e.data as { source?: string; id?: unknown; method?: unknown; params?: unknown } | null;
    if (!d || typeof d !== "object" || d.source !== "mbapp-bridge") return;
    const id = String(d.id);
    const method = String(d.method);
    const params = (d.params ?? {}) as Record<string, unknown>;
    const target = e.source as Window | null;
    addLog("←", `bridge ${method} #${id.slice(0, 8)} from ${e.origin}`);
    const reply = (ok: boolean, payload: unknown) => {
      addLog("→", `bridge ${ok ? "ok" : "error"} #${id.slice(0, 8)} ${JSON.stringify(payload)}`);
      try {
        target?.postMessage({ source: "mbapp-bridge-reply", id, ok, payload }, e.origin && e.origin !== "null" ? e.origin : "*");
      } catch {
        /* window gone */
      }
    };
    const o = openedRef.current;
    if (!o || o.kind !== "webview") return reply(false, { code: "ERROR", message: "No Mini App is open" });
    const mainFrame = e.source === webFrame.current?.contentWindow;
    const allowed = o.detail.launch.allowedOrigins?.includes(e.origin) ?? false;
    if (!allowed || !mainFrame) {
      const why = !allowed ? `origin ${e.origin} is not allowed` : "call is not from the main frame";
      if (method === "login") trace("APP", "2:27", "webview", `refused: ${why}`, o.detail.appId);
      return reply(false, { code: allowed ? "NOT_MAIN_FRAME" : "ORIGIN_NOT_ALLOWED", message: why });
    }
    try {
      if (method === "login") {
        trace("APP", "2:26", "webview", null, o.detail.appId);
        trace("APP", "2:27", "webview", `${e.origin}, main frame`, o.detail.appId);
        const code = await authCode(o);
        if (openedRef.current?.instance === o.instance) reply(true, { authCode: code });
      } else if (method === "openPayment") {
        const r = await openPayment(o, params.order as Order, String(params.checksum ?? ""));
        if (openedRef.current?.instance === o.instance) reply(true, r);
      } else if (method === "close") {
        reply(true, {});
        closeApp("bridge close()");
      } else {
        reply(false, { code: "ERROR", message: `Unknown method ${method}` });
      }
    } catch (err) {
      setSheet(null);
      const code = (err as { code?: string })?.code ?? "ERROR";
      if (openedRef.current?.instance === o.instance) reply(false, { code, message: errorText(err) });
    }
  }

  // ---------- SDK host API: only for the SDK instance MB App started ----------
  const host = useMemo<HostApi | null>(() => {
    if (opened?.kind !== "sdk" || opened.failed) return null;
    const instance = opened.instance;
    const alive = () => {
      const o = openedRef.current;
      if (!o || o.instance !== instance) throw { code: "SDK_CLOSED", message: "This SDK instance was closed" };
      return o;
    };
    return {
      async login() {
        const o = alive();
        trace("APP", "2:26", "sdk", null, o.detail.appId);
        trace("APP", "2:27", "sdk", `SDK instance ${instance.slice(0, 8)} started for ${o.detail.appId}`, o.detail.appId);
        addLog("←", "host login()");
        return { authCode: await authCode(o) };
      },
      async openPayment(order, checksum) {
        const o = alive();
        addLog("←", `host openPayment(${order?.orderId})`);
        const r = await openPayment(o, order, checksum);
        addLog("→", `host openPayment → ${JSON.stringify(r)}`);
        return r;
      },
      close() {
        if (openedRef.current?.instance === instance) closeApp("host close()");
      },
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened?.instance, opened?.kind, opened?.failed]);

  /** Mock biometrics / device PIN: re-checks the signed-in customer's PIN with the bank. */
  async function verifyPin(value: string) {
    await api(`${GW}/login`, { body: { username: username.trim(), pin: value, deviceId: deviceId() } });
  }

  // ---------- App-to-App: links into MB App ----------
  /** Asks for the PIN before acting on a link (the link itself proves nothing). */
  async function unlock(text: string): Promise<boolean> {
    const ok = await ask<boolean>((done) => ({ kind: "unlock", text, done }));
    setSheet(null);
    return ok;
  }

  async function a2aApp(appId?: string | null): Promise<MiniAppDetail | null> {
    const id =
      appId ??
      Object.values(details.current).find((x) => x.type === "a2a")?.appId ??
      list?.sections.flatMap((x) => x.apps).find((x) => x.type === "a2a")?.appId ??
      "starlight-app";
    if (details.current[id]) return details.current[id];
    try {
      return await loadDetail(id, "a2a");
    } catch {
      return null;
    }
  }

  async function handleMbLink(url: string) {
    const u = new URL(url, window.location.origin);
    setFg("mbapp");
    if (!sessionRef.current) {
      pendingLink.current = url;
      setScreen("login");
      showToast("Log in to MB App to continue");
      return;
    }
    if (u.pathname === "/links/mbapp/link") await handleLinkRequest(u.searchParams);
    else if (u.pathname === "/links/mbapp/pay") await handlePayLink(u.searchParams.get("paymentRef") ?? "");
    else showToast(`MB App can't open ${u.pathname}`);
  }

  /** 2:A5–A14: the partner app asks MB App to identify the customer (state + S256 code challenge). */
  async function handleLinkRequest(q: URLSearchParams) {
    const appId = q.get("appId") ?? "";
    const state = q.get("state") ?? "";
    const challenge = q.get("codeChallenge") ?? "";
    trace("APP", "2:A5", "a2a", `appId ${appId}, state ${state.slice(0, 8)}…, codeChallenge`, appId);
    let d: MiniAppDetail | null = null;
    const back = (params: Record<string, string>) => {
      const base = d?.launch.returnLink ?? "/links/starlight/return";
      osOpen(`${base}?${new URLSearchParams({ ...params, state })}`, "mbapp");
    };
    if (!(await unlock("Confirm it's you to link this app to MB Bank."))) return back({ error: "access_denied" });
    try {
      d = await loadDetail(appId, "a2a");
    } catch (e) {
      showToast(`Cannot link: ${errorText(e)}`);
      return back({ error: "server_error" });
    }
    if (d.type !== "a2a") return back({ error: "server_error" });
    if (!(await ensureConsent(d))) return back({ error: "access_denied" });
    trace("APP", "2:A6", "a2a", "signed in with PIN; consent & registration ok; asked “Link to this partner?”", appId);
    const yes = await ask<boolean>((done) => ({ kind: "link", detail: d!, done }));
    setSheet(null);
    if (!yes) return back({ error: "access_denied" });
    try {
      const r = await gw<{ authCode: string }>(`/mini-apps/${encodeURIComponent(appId)}/auth-code`, {
        body: { codeChallenge: challenge, codeChallengeMethod: "S256" },
      });
      trace("APP", "2:A13", "a2a", "authCode bound to the code challenge", appId);
      back({ authCode: r.authCode });
    } catch (e) {
      showToast(`Linking failed: ${errorText(e)}`);
      back({ error: "server_error" });
    }
  }

  /** 3:D30–D40: pay a payment the partner server registered; only the paymentRef comes from the link. */
  async function handlePayLink(paymentRef: string) {
    trace("APP", "3:D30", "a2a", null, paymentRef);
    const toPartner = async (appId: string | null, params: Record<string, string>) => {
      const d = await a2aApp(appId);
      trace("APP", "3:D39", "a2a", `returnLink + ${params.status}`, paymentRef);
      osOpen(`${d?.launch.returnLink ?? "/links/starlight/return"}?${new URLSearchParams({ paymentRef, ...params })}`, "mbapp");
    };
    if (!(await unlock("Confirm it's you to see this payment."))) return toPartner(null, { status: "CANCELLED" });
    trace("APP", "3:D31", "a2a", "signed in (PIN); nothing from the link is shown", paymentRef);
    let info: PaymentInfo;
    try {
      setSheet({ kind: "wait", text: "Loading the payment…" });
      info = await gw<PaymentInfo>(`/payments/${encodeURIComponent(paymentRef)}`);
      setSheet(null);
      trace("APP", "3:D38", "a2a", `${money(info.amount, info.currency)} · ${info.merchantName}`, paymentRef);
    } catch (e) {
      setSheet(null);
      const code = isApiError(e) ? e.code : "ERROR";
      trace("APP", "3:D35c", "a2a", code, paymentRef);
      trace("APP", "3:D35d", "a2a", errorText(e), paymentRef);
      showToast(`Can't pay this: ${errorText(e)}`);
      return toPartner(null, { status: "FAILED", errorCode: code });
    }
    const r = await runPayment(info);
    await toPartner(info.appId, { status: r.status, ...(r.errorCode ? { errorCode: r.errorCode } : {}) });
  }

  // ---------- phone OS: verified links only ----------
  function osOpen(url: string, from: "mbapp" | "starlight"): RoutedTo {
    const target = claimedBy(url, window.location.origin);
    addLog("·", `OS: ${from} opens ${url} → ${target ?? "refused"}`);
    if (!target) {
      showToast(`OS refused ${url} (not a verified app link)`);
      return "refused";
    }
    if (!installedRef.current[target]) {
      setBrowserUrl(STORE_URL[target]);
      setFg("browser");
      return "store";
    }
    if (target === "mbapp") {
      void handleMbLink(url);
    } else {
      setPartnerSrc(url);
      setPartnerKey((k) => k + 1);
      setFg("starlight");
    }
    return "app";
  }

  function launchPartnerApp() {
    if (!installed.starlight) {
      setBrowserUrl(STORE_URL.starlight);
      setFg("browser");
      return;
    }
    if (!partnerSrc) setPartnerSrc("/partner-app");
    setFg("starlight");
  }

  function onOsMessage(e: MessageEvent) {
    const d = e.data as { source?: string; type?: string; url?: string } | null;
    if (!d || d.source !== "os" || d.type !== "openUrl" || typeof d.url !== "string") return;
    if (e.source !== partnerFrame.current?.contentWindow) return;
    const routedTo = osOpen(d.url, "starlight");
    (e.source as Window).postMessage({ source: "os-reply", type: "openUrlResult", url: d.url, routedTo }, e.origin);
  }

  const handlers = useRef({ onBridgeMessage, onOsMessage });
  handlers.current = { onBridgeMessage, onOsMessage };
  useEffect(() => {
    const fn = (e: MessageEvent) => {
      void handlers.current.onBridgeMessage(e);
      handlers.current.onOsMessage(e);
    };
    window.addEventListener("message", fn);
    return () => window.removeEventListener("message", fn);
  }, []);

  // ---------- render ----------
  const apps = list?.sections ?? [];
  return (
    <main className={s.stage}>
      <div className={s.intro}>
        <div className="eyebrow">Lane APP · MB App in the browser</div>
        <h1>MB App simulator</h1>
        <p>
          Runs all four integration types. Device <code>{devId || "…"}</code> ·{" "}
          <a href="/admin" target="_blank" rel="noreferrer">
            Open test console ↗
          </a>
        </p>
        <section className={s.os}>
          <h2>Phone OS</h2>
          <div className={s.osApps}>
            <button aria-pressed={fg === "mbapp"} onClick={() => setFg("mbapp")} disabled={!installed.mbapp}>
              🏦 MB App
            </button>
            <button aria-pressed={fg === "starlight"} onClick={launchPartnerApp}>
              📱 Starlight app
            </button>
            {fg === "browser" && <button aria-pressed>🌐 Browser</button>}
          </div>
          <label className={s.osToggle}>
            <input type="checkbox" checked={installed.mbapp} onChange={(e) => setInstalled((i) => ({ ...i, mbapp: e.target.checked }))} /> MB App
            installed
          </label>
          <label className={s.osToggle}>
            <input
              type="checkbox"
              checked={installed.starlight}
              onChange={(e) => setInstalled((i) => ({ ...i, starlight: e.target.checked }))}
            />{" "}
            Starlight app installed
          </label>
          <p className={s.hint}>Apps hand over only through verified links: /links/mbapp/* → MB App, /links/starlight/* → Starlight app.</p>
        </section>
      </div>

      <div className={s.phone}>
        <div className={s.notch} />
        <div className={s.screen}>
          {/* ---------------- MB App ---------------- */}
          <div className={s.layer} style={{ display: fg === "mbapp" ? undefined : "none" }}>
            {screen === "login" && (
              <form className={s.login} onSubmit={login}>
                <div className={s.bankLogo}>MB</div>
                <h2>MB Bank</h2>
                <label>
                  Username
                  <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
                </label>
                <label>
                  PIN
                  <input value={pin} onChange={(e) => setPin(e.target.value)} type="password" inputMode="numeric" autoComplete="current-password" />
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
                  (PREMIUM, USD 500) or{" "}
                  <button type="button" className={s.link} onClick={() => setUsername("bob")}>
                    bob
                  </button>{" "}
                  (USD 3) · PIN 1234
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
                    <strong>{money(a.available ?? a.balance, a.currency)}</strong>
                    {Number(a.held) > 0 && <span>{money(a.held, a.currency)} on hold</span>}
                  </div>
                ))}
                <div className={s.tiles}>
                  <button className={s.tile} onClick={openList}>
                    <span>🧩</span>Mini Apps
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
                  <strong>Mini Apps</strong>
                  {listState === "loading" && <span className={s.dot} title="Refreshing" />}
                </div>
                {!list && listState === "loading" && <div className={s.hint}>Loading…</div>}
                {!list && listState === "error" && (
                  <div className={s.hint}>
                    Could not load Mini Apps.{" "}
                    <button className={s.link} onClick={openList}>
                      Retry
                    </button>
                  </div>
                )}
                {apps.map((sec) => (
                  <section key={sec.id}>
                    <h3 className={s.sectionTitle}>{sec.title}</h3>
                    <div className={s.apps}>
                      {sec.apps.map((a) => (
                        <button key={a.appId} className={s.app} onClick={() => openApp(a)}>
                          <span className={s.appIcon}>{a.icon}</span>
                          {a.name}
                          <em className={s.typeTag}>{a.type}</em>
                        </button>
                      ))}
                    </div>
                  </section>
                ))}
              </div>
            )}

            {opened?.kind === "webview" && (
              <div className={s.webview}>
                <div className={s.webviewBar}>
                  <strong>{opened.detail.name}</strong>
                  <button onClick={() => closeApp("native X")} aria-label="Close Mini App">
                    ✕
                  </button>
                </div>
                {opened.failed ? (
                  <ErrorScreen
                    text={opened.failed}
                    onRetry={() => setOpened({ ...opened, failed: null, loaded: false, frameKey: Date.now() })}
                  />
                ) : (
                  <iframe
                    key={opened.frameKey}
                    ref={webFrame}
                    src={opened.detail.launch.entryUrl}
                    title={opened.detail.name}
                    className={s.frame}
                    onLoad={() => setOpened((o) => (o && o.instance === opened.instance ? { ...o, loaded: true } : o))}
                  />
                )}
              </div>
            )}

            {opened?.kind === "sdk" && (
              <div className={s.webview}>
                <div className={s.webviewBar}>
                  <strong>
                    {opened.detail.icon} {opened.detail.name}
                  </strong>
                  <button onClick={() => closeApp("native X")} aria-label="Close Mini App">
                    ✕
                  </button>
                </div>
                {opened.failed || !host ? (
                  <ErrorScreen text={opened.failed ?? "The SDK could not start."} />
                ) : (
                  <div className={s.sdkHost}>
                    <StarlightSdk key={opened.instance} host={host} appId={opened.detail.appId} locale="en" />
                  </div>
                )}
              </div>
            )}

            {opened?.kind === "api" && (
              <NativeApp detail={opened.detail} gw={gw} pay={runPayment} onClose={() => setOpened(null)} />
            )}

            {sheet && (
              <div className={s.scrim}>
                <div className={s.sheet} role="dialog" aria-modal="true">
                  <SheetBody sheet={sheet} gw={gw} accounts={accounts} otps={otps} name={session?.name ?? ""} verifyPin={verifyPin} />
                </div>
              </div>
            )}
          </div>

          {/* ---------------- Starlight partner app (separate app) ---------------- */}
          {partnerSrc && installed.starlight && (
            <div className={s.layer} style={{ display: fg === "starlight" ? undefined : "none" }}>
              <iframe key={partnerKey} ref={partnerFrame} src={partnerSrc} title="Starlight app" className={s.fullFrame} />
            </div>
          )}

          {/* ---------------- Browser (store pages) ---------------- */}
          {fg === "browser" && browserUrl && (
            <div className={s.layer}>
              <div className={s.webviewBar}>
                <strong>🌐 {browserUrl}</strong>
                <button onClick={() => setFg("mbapp")} aria-label="Close browser">
                  ✕
                </button>
              </div>
              <iframe src={browserUrl} title="Browser" className={s.frame} />
            </div>
          )}

          {push && (
            <button className={s.push} onClick={() => setPush(null)}>
              <b>MB Bank · {push.title}</b>
              <span>{push.body}</span>
            </button>
          )}
          {toast && (
            <div className={s.toast} role="alert" onClick={() => setToast(null)}>
              {toast}
            </div>
          )}
        </div>
      </div>

      <details className={s.log} open>
        <summary>Bridge · host API · OS log ({log.length})</summary>
        {log.length === 0 ? (
          <div className={s.hint}>No messages yet.</div>
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

function ErrorScreen({ text, onRetry }: { text: string; onRetry?: () => void }) {
  return (
    <div className={s.errorScreen}>
      <div className={s.receiptBad}>!</div>
      <h3>Can&apos;t open this right now</h3>
      <p className={s.hint}>{text}</p>
      {onRetry && (
        <button className={s.primary} onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}

function SheetBody({
  sheet,
  gw,
  accounts,
  otps,
  name,
  verifyPin,
}: {
  sheet: Sheet;
  gw: Gw;
  accounts: Account[];
  otps: Record<string, string>;
  name: string;
  verifyPin: (pin: string) => Promise<void>;
}) {
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);

  switch (sheet.kind) {
    case "wait":
      return (
        <div className={s.wait}>
          <div className={s.spinner} />
          {sheet.text}
        </div>
      );
    case "pay":
      return <PaymentSheet info={sheet.info} accounts={accounts} gw={gw} otp={otps[sheet.info.paymentRef] ?? null} onDone={sheet.done} />;
    case "consent":
      return (
        <>
          <h3>Share your info with {sheet.detail.merchantName}?</h3>
          <p>
            <strong>{sheet.detail.name}</strong> will receive:
          </p>
          <ul className={s.scopes}>
            {sheet.detail.consent.scopes.map((x) => (
              <li key={x.scope}>
                ✓ {x.label} <code>{x.scope}</code>
              </li>
            ))}
          </ul>
          <p className={s.hint}>
            Consent version {sheet.detail.consent.currentVersion}
            {sheet.detail.consent.acceptedVersion ? ` (you accepted v${sheet.detail.consent.acceptedVersion}, it changed)` : ""}. Nothing else is
            shared.
          </p>
          <div className={s.sheetActions}>
            <button className={s.ghost} onClick={() => sheet.done(false)}>
              Decline
            </button>
            <button className={s.primary} onClick={() => sheet.done(true)}>
              Accept
            </button>
          </div>
        </>
      );
    case "link":
      return (
        <>
          <h3>Link {sheet.detail.name}?</h3>
          <p>
            The <strong>{sheet.detail.merchantName}</strong> app will sign you in with your MB Bank account. It gets a one-time code, never your
            PIN.
          </p>
          <div className={s.sheetActions}>
            <button className={s.ghost} onClick={() => sheet.done(false)}>
              Not now
            </button>
            <button className={s.primary} onClick={() => sheet.done(true)}>
              Link
            </button>
          </div>
        </>
      );
    case "unlock":
      return (
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            setChecking(true);
            setError(null);
            try {
              await verifyPin(pin);
              sheet.done(true);
            } catch (err) {
              setError(errorText(err));
              setPin("");
            } finally {
              setChecking(false);
            }
          }}
        >
          <h3>Hi {name}</h3>
          <p className={s.hint}>{sheet.text} (mock PIN 1234)</p>
          <input
            className={s.pin}
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 6))}
            type="password"
            inputMode="numeric"
            autoFocus
            aria-label="PIN"
          />
          {error && <div className={s.error}>{error}</div>}
          <div className={s.sheetActions}>
            <button type="button" className={s.ghost} onClick={() => sheet.done(false)}>
              Cancel
            </button>
            <button type="submit" className={s.primary} disabled={pin.length < 4 || checking}>
              Continue
            </button>
          </div>
        </form>
      );
  }
}
