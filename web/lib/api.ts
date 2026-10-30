export const PORTAL = "/portal-api";
export const BANK = "/bank-api";
export const PARTNER = "/partner-api";

/** Thrown by `api()` on any non-2xx response or network failure. */
export interface ApiError {
  status: number;
  code: string;
  message: string;
}

export function isApiError(e: unknown): e is ApiError {
  return typeof e === "object" && e !== null && "status" in e && "code" in e;
}

/** Human-readable text for any thrown value (ApiError, BridgeError, Error, ...). */
export function errorText(e: unknown): string {
  if (typeof e === "object" && e !== null && "code" in e && "message" in e) {
    const { code, message } = e as { code: unknown; message: unknown };
    return message ? `${code}: ${message}` : String(code);
  }
  return e instanceof Error ? e.message : String(e);
}

export interface ApiOptions {
  method?: string;
  body?: unknown;
  token?: string;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

/** JSON fetch helper. Resolves with the parsed body (or null), throws ApiError otherwise. */
export async function api<T = unknown>(url: string, opts: ApiOptions = {}): Promise<T> {
  const hasBody = opts.body !== undefined;
  const headers: Record<string, string> = { Accept: "application/json", ...opts.headers };
  if (hasBody) headers["Content-Type"] = "application/json";
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;

  let res: Response;
  try {
    res = await fetch(url, {
      method: opts.method ?? (hasBody ? "POST" : "GET"),
      headers,
      body: hasBody ? JSON.stringify(opts.body) : undefined,
      cache: "no-store",
      signal: opts.signal,
    });
  } catch {
    throw { status: 0, code: "OFFLINE", message: "Service unreachable" } satisfies ApiError;
  }

  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (res.ok) return data as T;

  const d = (data ?? {}) as { code?: unknown; message?: unknown; error?: unknown };
  if (typeof d.code === "string") {
    throw { status: res.status, code: d.code, message: String(d.message ?? res.statusText) } satisfies ApiError;
  }
  // The Next.js proxy answers a plain-text 5xx when the backend is not running.
  const offline = data === null && res.status >= 500;
  throw {
    status: res.status,
    code: offline ? "OFFLINE" : `HTTP_${res.status}`,
    message: offline ? "Service unreachable" : String(d.message ?? d.error ?? (text.slice(0, 200) || res.statusText)),
  } satisfies ApiError;
}

export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export const money = (n: unknown, currency?: unknown) => {
  const c = typeof currency === "string" ? currency : "USD";
  if (typeof n === "number") return `${n.toFixed(2)} ${c}`;
  return n == null ? "–" : `${String(n)} ${c}`;
};

/** sessionStorage/localStorage access that never throws (private mode, blocked storage...). */
export function store(kind: "session" | "local") {
  const s = () => (kind === "session" ? window.sessionStorage : window.localStorage);
  return {
    get<T>(key: string): T | null {
      try {
        const v = s().getItem(key);
        return v ? (JSON.parse(v) as T) : null;
      } catch {
        return null;
      }
    },
    set(key: string, value: unknown) {
      try {
        if (value === null || value === undefined) s().removeItem(key);
        else s().setItem(key, JSON.stringify(value));
      } catch {
        /* ignore */
      }
    },
  };
}
