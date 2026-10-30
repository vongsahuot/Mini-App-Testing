import { PORTAL } from "./api";

export type Actor = "APP" | "PP" | "KP" | "BS" | "PS";

export const ACTOR_LABEL: Record<Actor, string> = {
  APP: "Bank App",
  PP: "Partner Page",
  KP: "KOSIGN Portal",
  BS: "Bank Server",
  PS: "Partner Server",
};

/** Fire-and-forget trace report (clients APP / PP only; backends record their own steps). */
export function trace(actor: Actor, step: number, title: string, detail?: string | null, ref?: string | null): void {
  try {
    fetch(`${PORTAL}/api/v1/admin/trace`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor, step, title, detail: detail ?? null, ref: ref ?? null }),
      keepalive: true,
    }).catch(() => {});
  } catch {
    /* never break the flow because of tracing */
  }
}

/** Same as trace() but uses the catalog title for the step. */
export function traceStep(actor: Actor, step: number, detail?: string | null, ref?: string | null): void {
  trace(actor, step, STEP_TITLES[step - 1] ?? `Step ${step}`, detail, ref);
}

export const STEP_TITLES: readonly string[] = [
  'Tap "Mini apps"',
  "GET /client/mini-apps (JWT)",
  "Verify JWT via JWKS, filter apps",
  "Return sections, apps, banners",
  "Render native list",
  "Tap mini app",
  "POST /client/mini-apps/{appId}/sessions",
  "Return partner URL + allowed origins",
  "Open WebView (bridges on allowed origins)",
  "Bridge login(scopes)",
  "Show consent sheet",
  "Tap Allow",
  "POST /client/mini-apps/{appId}/auth-codes",
  "Issue authCode (1 use, 60 s)",
  "Return authCode",
  "Resolve login() with authCode",
  "Send authCode to partner backend",
  "POST /partner/oauth/token",
  "Burn code, return partnerCustomerId",
  'Tap "Buy"',
  "Send itemId (price on server)",
  "POST /partner/payments (signed)",
  "Idempotency check, save PENDING",
  "Return paymentId",
  "Pass paymentId to page",
  "Bridge pay(paymentId)",
  "GET /client/payments/{id}",
  "Return amount, merchant",
  "Show native payment sheet",
  "Tap Cancel",
  "POST /client/payments/{id}/cancel",
  "Resolve pay() CANCELLED",
  "Biometrics/PIN signs paymentId",
  "Send approval to bank",
  "POST /bank/payments/{id}/lock",
  "Atomic PENDING→PROCESSING",
  "Return amount + settlement account",
  "Verify signature + balance, debit/credit",
  "POST /bank/payments/{id}/result",
  "Return Done + bankTxnId",
  "GET /bank/payments/{id}/status (recovery)",
  "Return bank status",
  "Apply bank's answer",
  "Settlement file received",
  "Reconcile, alert on mismatch",
  "Partner statement",
  "Webhook payment.*",
  "Verify webhook, mark paid, deliver",
  "200 OK (stop retries)",
  "Resolve pay() (UI hint)",
  "Check order status",
  "GET /partner/payments/{id} (fallback)",
  "Return payment status",
  '"Delivered" → show ticket',
  "POST /partner/payments/{id}/refunds",
  "Refund idempotency, REFUND_PENDING",
  "POST /bank/refunds",
  "Debit settlement, credit customer",
  "REFUNDED + refundTxnId",
  "Webhook refund.*",
  "Bridge close()",
  "Close WebView → list",
];

/** Which actor records each step (the receiver of the message), per CONTRACT.md. */
const ACTOR_STEPS: Record<Actor, number[]> = {
  APP: [1, 4, 5, 6, 8, 10, 11, 12, 15, 26, 28, 29, 30, 33, 40, 61, 62],
  PP: [9, 16, 20, 25, 32, 50, 54],
  KP: [2, 3, 7, 13, 14, 18, 22, 23, 27, 31, 35, 36, 39, 42, 43, 44, 45, 49, 52, 55, 56, 59],
  BS: [34, 37, 38, 41, 57, 58],
  PS: [17, 19, 21, 24, 46, 47, 48, 51, 53, 60],
};

export interface Phase {
  n: number;
  name: string;
  from: number;
  to: number;
}

export const PHASES: readonly Phase[] = [
  { n: 1, name: "List mini apps (native)", from: 1, to: 5 },
  { n: 2, name: "Open mini app", from: 6, to: 9 },
  { n: 3, name: "Login & consent", from: 10, to: 19 },
  { n: 4, name: "Pay 1 · Create (idempotent)", from: 20, to: 25 },
  { n: 5, name: "Pay 2 · Confirm (native sheet)", from: 26, to: 33 },
  { n: 6, name: "Pay 3 · Debit (mTLS + lock)", from: 34, to: 40 },
  { n: 7, name: "Recovery & reconciliation", from: 41, to: 46 },
  { n: 8, name: "Pay 4 · Notify (async + fallback check)", from: 47, to: 54 },
  { n: 9, name: "Refund", from: 55, to: 60 },
  { n: 10, name: "Close", from: 61, to: 62 },
];

export interface StepInfo {
  n: number;
  title: string;
  phase: Phase;
  actor: Actor;
}

export const STEPS: readonly StepInfo[] = STEP_TITLES.map((title, i) => {
  const n = i + 1;
  const actor = (Object.keys(ACTOR_STEPS) as Actor[]).find((a) => ACTOR_STEPS[a].includes(n)) as Actor;
  const phase = PHASES.find((p) => n >= p.from && n <= p.to) as Phase;
  return { n, title, phase, actor };
});
