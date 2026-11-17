/**
 * Partner-page side of the MB App JS bridge (docs/CONTRACT.md, "JS bridge").
 * Android WebView: window.MBAppNative.postMessage / window.__mbappBridgeCallback
 * Web simulator (page in an iframe): parent.postMessage({source: "mbapp-bridge"}) / reply "mbapp-bridge-reply"
 */

export type Transport = "native" | "iframe" | "none";
type Method = "login" | "openPayment" | "close";

/** The sealed order, exactly as the partner server returned it (docs/CONTRACT.md, "Order checksum"). */
export interface Order {
  appId: string;
  orderId: string;
  amount: number;
  currency: string;
  partnerUserId: string;
  expiry: string;
  description?: string;
}
export interface LoginResult {
  authCode: string;
}
/** A UI hint only: the partner confirms the real status with its own server. */
export interface PayResult {
  status: "SUCCESS" | "FAILED" | "PROCESSING" | "CANCELLED";
  paymentRef?: string | null;
  errorCode?: string | null;
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
    MBAppNative?: { postMessage(message: string): void };
    __mbappBridgeCallback?: (id: string, ok: boolean, payload: unknown) => void;
  }
}

const TIMEOUT_MS: Record<Method, number> = { login: 2 * 60_000, close: 60_000, openPayment: 10 * 60_000 };

interface Pending {
  resolve: (v: unknown) => void;
  reject: (e: BridgeError) => void;
  timer: ReturnType<typeof setTimeout>;
}
const pending = new Map<string, Pending>();
let installed = false;

export function detectTransport(): Transport {
  if (typeof window === "undefined") return "none";
  if (window.MBAppNative && typeof window.MBAppNative.postMessage === "function") return "native";
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
  window.__mbappBridgeCallback = (id, ok, payload) => settle(String(id), Boolean(ok), payload);
  // Only trust replies that come from our direct parent window (the simulator).
  window.addEventListener("message", (e: MessageEvent) => {
    if (window.parent === window || e.source !== window.parent) return;
    const d = e.data as { source?: string; id?: unknown; ok?: unknown; payload?: unknown } | null;
    if (!d || typeof d !== "object" || d.source !== "mbapp-bridge-reply") return;
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
    return Promise.reject(new BridgeError("NO_BRIDGE", "Open this page from MB App or the simulator"));
  }
  install();
  const id = newId();
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new BridgeError("TIMEOUT", `${method}() got no answer from MB App`));
    }, TIMEOUT_MS[method]);
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject, timer });
    try {
      if (transport === "native") {
        window.MBAppNative!.postMessage(JSON.stringify({ id, method, params }));
      } else {
        // "*": the page cannot know the host origin. MB App enforces the allowedOrigins + main-frame check.
        window.parent.postMessage({ source: "mbapp-bridge", id, method, params }, "*");
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
  /** No parameters: MB App uses the appId of the Mini App it opened. */
  login: () => call<LoginResult>("login", {}),
  openPayment: (order: Order, checksum: string) => call<PayResult>("openPayment", { order, checksum }),
  close: () => call<Record<string, never>>("close", {}),
};
