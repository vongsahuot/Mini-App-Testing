/**
 * Partner-page side of the KOSIGN JS bridge (docs/CONTRACT.md, "JS bridge").
 * Transport A: Android WebView -> window.KosignNative.postMessage / window.__kosignBridgeCallback
 * Transport B: web simulator   -> window.parent.postMessage({source:"kosign-bridge"}) / reply "kosign-bridge-reply"
 */

export type Transport = "native" | "iframe" | "none";
type Method = "login" | "pay" | "close";

export interface LoginResult {
  authCode: string;
}
export interface PayResult {
  status: "PAID" | "FAILED" | "CANCELLED";
  bankTxnId?: string | null;
  reason?: string | null;
}

export class BridgeError extends Error {
  constructor(
    public code: string,
    message: string,
  ) {
    super(message);
    this.name = "BridgeError";
  }
}

declare global {
  interface Window {
    KosignNative?: { postMessage(message: string): void };
    __kosignBridgeCallback?: (id: string, ok: boolean, payload: unknown) => void;
  }
}

const TIMEOUT_MS: Record<Method, number> = { login: 60_000, close: 60_000, pay: 5 * 60_000 };

interface Pending {
  resolve: (v: unknown) => void;
  reject: (e: BridgeError) => void;
  timer: ReturnType<typeof setTimeout>;
}
const pending = new Map<string, Pending>();
let installed = false;

export function detectTransport(): Transport {
  if (typeof window === "undefined") return "none";
  if (window.KosignNative && typeof window.KosignNative.postMessage === "function") return "native";
  if (window.parent && window.parent !== window) return "iframe";
  return "none";
}

function settle(id: string, ok: boolean, payload: unknown) {
  const p = pending.get(id);
  if (!p) return;
  pending.delete(id);
  clearTimeout(p.timer);
  if (typeof payload === "string") {
    try {
      payload = JSON.parse(payload);
    } catch {
      /* keep the raw string */
    }
  }
  if (ok) {
    p.resolve(payload ?? {});
  } else {
    const e = (payload ?? {}) as { code?: string; message?: string };
    p.reject(new BridgeError(e.code || "ERROR", e.message || "Bridge call failed"));
  }
}

function install() {
  if (installed) return;
  installed = true;
  // Transport A: the app runs evaluateJavascript("window.__kosignBridgeCallback('<id>', ok, <json>)").
  window.__kosignBridgeCallback = (id, ok, payload) => settle(String(id), Boolean(ok), payload);
  // Transport B: only trust replies that come from our direct parent window.
  window.addEventListener("message", (e: MessageEvent) => {
    if (window.parent === window || e.source !== window.parent) return;
    const d = e.data as { source?: string; id?: unknown; ok?: unknown; payload?: unknown } | null;
    if (!d || typeof d !== "object" || d.source !== "kosign-bridge-reply") return;
    settle(String(d.id), Boolean(d.ok), d.payload);
  });
}

function newId(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  }
}

function call<T>(method: Method, params: Record<string, unknown>): Promise<T> {
  const transport = detectTransport();
  if (transport === "none") {
    return Promise.reject(new BridgeError("NO_BRIDGE", "Open this page from the bank app or the Simulator"));
  }
  install();
  const id = newId();
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new BridgeError("TIMEOUT", `${method}() got no answer from the host`));
    }, TIMEOUT_MS[method]);
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject, timer });
    try {
      if (transport === "native") {
        window.KosignNative!.postMessage(JSON.stringify({ id, method, params }));
      } else {
        // "*" per contract: the page cannot know the host origin. The host enforces the origin allowlist.
        window.parent.postMessage({ source: "kosign-bridge", id, method, params }, "*");
      }
    } catch (err) {
      clearTimeout(timer);
      pending.delete(id);
      reject(new BridgeError("ERROR", err instanceof Error ? err.message : String(err)));
    }
  });
}

export const bridge = {
  transport: detectTransport,
  login: (scopes: string[]) => call<LoginResult>("login", { scopes }),
  pay: (paymentId: string) => call<PayResult>("pay", { paymentId }),
  close: () => call<Record<string, never>>("close", {}),
};
