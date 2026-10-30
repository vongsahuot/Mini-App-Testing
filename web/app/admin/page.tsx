"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, BANK, errorText, isApiError, money, PARTNER, PORTAL } from "@/lib/api";
import { ACTOR_LABEL, PHASES, STEPS, type Actor } from "@/lib/trace";
import s from "./admin.module.css";

type Svc = "portal" | "bank" | "partner";
type Row = Record<string, unknown>;
interface TraceEv {
  id: string | number;
  ts: string;
  actor: Actor;
  step: number;
  title: string;
  detail: string | null;
  ref: string | null;
}
interface BankChaos {
  dropResultCallback: boolean;
  failDebit: boolean;
  failRefund: boolean;
  settlementMismatch: boolean;
}
interface PartnerChaos {
  webhookFailCount: number;
  ignoreWebhooks: boolean;
}

const ENDPOINTS = {
  portalTrace: ["portal", `${PORTAL}/api/v1/admin/trace`],
  bankTrace: ["bank", `${BANK}/api/v1/admin/trace`],
  partnerTrace: ["partner", `${PARTNER}/api/v1/admin/trace`],
  payments: ["portal", `${PORTAL}/api/v1/admin/payments`],
  refunds: ["portal", `${PORTAL}/api/v1/admin/refunds`],
  webhooks: ["portal", `${PORTAL}/api/v1/admin/webhooks`],
  reconciliations: ["portal", `${PORTAL}/api/v1/admin/reconciliations`],
  orders: ["partner", `${PARTNER}/api/v1/admin/orders`],
  accounts: ["bank", `${BANK}/api/v1/admin/accounts`],
  transactions: ["bank", `${BANK}/api/v1/admin/transactions`],
  bankChaos: ["bank", `${BANK}/api/v1/admin/chaos`],
  partnerChaos: ["partner", `${PARTNER}/api/v1/admin/chaos`],
} as const satisfies Record<string, readonly [Svc, string]>;
type Key = keyof typeof ENDPOINTS;
type Result = { ok: true; data: unknown } | { ok: false; error: string; offline: boolean };

const SVC_LABEL: Record<Svc, string> = { portal: "Portal :8081", bank: "Bank :8082", partner: "Partner :8083" };
const TABS = [
  "Trace",
  "Coverage",
  "Payments",
  "Refunds",
  "Webhooks",
  "Reconciliations",
  "Partner orders",
  "Bank",
  "Actions",
  "Scenarios",
] as const;
type Tab = (typeof TABS)[number];

const GOOD = ["PAID", "DELIVERED", "REFUNDED", "SUCCESS", "COMPLETED", "MATCHED", "OK"];
const BAD = ["FAILED", "GAVE_UP", "CANCELLED", "EXPIRED", "MISMATCH"];

function statusPill(v: unknown) {
  const t = String(v ?? "–");
  const cls = GOOD.includes(t) ? "good" : BAD.includes(t) ? "bad" : t.includes("PENDING") || t === "PROCESSING" ? "warn" : "muted";
  return <span className={`pill ${cls}`}>{t}</span>;
}
const time = (v: unknown) => {
  if (typeof v !== "string" || !v) return "–";
  const d = new Date(v);
  return isNaN(d.getTime()) ? v : d.toLocaleTimeString();
};
const txt = (v: unknown): ReactNode =>
  v == null || v === "" ? "–" : typeof v === "object" ? <code>{JSON.stringify(v)}</code> : String(v);

type Col = [label: string, render: string | ((r: Row) => ReactNode)];

function Table({ rows, cols, empty = "Nothing yet." }: { rows: Row[]; cols: Col[]; empty?: string }) {
  if (!rows.length) return <div className={s.empty}>{empty}</div>;
  return (
    <div className={s.tableWrap}>
      <table className={s.table}>
        <thead>
          <tr>
            {cols.map(([l]) => (
              <th key={l}>{l}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={String(r.id ?? r.paymentId ?? r.orderId ?? r.txnId ?? r.accountNo ?? i)}>
              {cols.map(([l, f]) => (
                <td key={l} data-label={l}>
                  {typeof f === "string" ? txt(r[f]) : f(r)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function AdminPage() {
  const [results, setResults] = useState<Partial<Record<Key, Result>>>({});
  const [tab, setTab] = useState<Tab>("Trace");
  const [live, setLive] = useState(true);
  const [filter, setFilter] = useState("");
  const [newestFirst, setNewestFirst] = useState(false);
  const [actionLog, setActionLog] = useState<{ ts: string; text: string; ok: boolean }[]>([]);
  const [settlement, setSettlement] = useState<unknown>(null);
  const [failCountDraft, setFailCountDraft] = useState<string | null>(null);
  const inFlight = useRef(false);

  const refresh = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const keys = Object.keys(ENDPOINTS) as Key[];
      const settled = await Promise.allSettled(keys.map((k) => api(ENDPOINTS[k][1])));
      const next: Partial<Record<Key, Result>> = {};
      settled.forEach((r, i) => {
        next[keys[i]] =
          r.status === "fulfilled"
            ? { ok: true, data: r.value }
            : { ok: false, error: errorText(r.reason), offline: isApiError(r.reason) && r.reason.code === "OFFLINE" };
      });
      setResults(next);
    } finally {
      inFlight.current = false;
    }
  }, []);

  useEffect(() => {
    void refresh();
    if (!live) return;
    const t = window.setInterval(() => void refresh(), 2000);
    return () => window.clearInterval(t);
  }, [live, refresh]);

  const rows = useCallback(
    (k: Key): Row[] => {
      const r = results[k];
      return r?.ok && Array.isArray(r.data) ? (r.data as Row[]) : [];
    },
    [results],
  );

  const svcStatus = (svc: Svc): "online" | "offline" | "error" | "unknown" => {
    const rs = (Object.keys(ENDPOINTS) as Key[]).filter((k) => ENDPOINTS[k][0] === svc).map((k) => results[k]);
    if (rs.every((r) => !r)) return "unknown";
    if (rs.some((r) => r?.ok)) return "online";
    return rs.some((r) => r && !r.ok && r.offline) ? "offline" : "error";
  };

  /** Shows "<service> offline" / error text for a section's endpoint, or null when fine. */
  const problem = (k: Key) => {
    const r = results[k];
    if (!r || r.ok) return null;
    const svc = ENDPOINTS[k][0];
    return (
      <div className={s.offline}>
        {r.offline ? `${SVC_LABEL[svc]} offline` : `${SVC_LABEL[svc]}: ${r.error}`}
      </div>
    );
  };

  const traceAll = useMemo(() => {
    const all: (TraceEv & { svc: Svc })[] = [];
    (["portalTrace", "bankTrace", "partnerTrace"] as const).forEach((k) =>
      rows(k).forEach((e) => all.push({ ...(e as unknown as TraceEv), svc: ENDPOINTS[k][0] })),
    );
    all.sort((a, b) => Date.parse(a.ts) - Date.parse(b.ts) || Number(a.step) - Number(b.step));
    return all;
  }, [rows]);

  const observed = useMemo(() => new Set(traceAll.map((e) => Number(e.step))), [traceAll]);

  const shownTrace = useMemo(() => {
    const f = filter.trim().toLowerCase();
    const list = f ? traceAll.filter((e) => (e.ref ?? "").toLowerCase().includes(f)) : traceAll;
    return newestFirst ? [...list].reverse() : list;
  }, [traceAll, filter, newestFirst]);

  async function act(label: string, fn: () => Promise<unknown>) {
    const ts = new Date().toLocaleTimeString();
    try {
      const r = await fn();
      setActionLog((l) => [{ ts, ok: true, text: `${label}: ${r == null ? "ok" : JSON.stringify(r)}` }, ...l].slice(0, 30));
      return r;
    } catch (e) {
      setActionLog((l) => [{ ts, ok: false, text: `${label}: ${errorText(e)}` }, ...l].slice(0, 30));
      return undefined;
    } finally {
      void refresh();
    }
  }

  const clearTrace = () =>
    act("Clear trace", async () => {
      const r = await Promise.allSettled(
        [PORTAL, BANK, PARTNER].map((b) => api(`${b}/api/v1/admin/trace`, { method: "DELETE" })),
      );
      const failed = r.filter((x) => x.status === "rejected").length;
      return failed ? `${3 - failed}/3 cleared (${failed} offline/failed)` : "all cleared";
    });

  const bankChaos = results.bankChaos?.ok ? (results.bankChaos.data as BankChaos) : null;
  const partnerChaos = results.partnerChaos?.ok ? (results.partnerChaos.data as PartnerChaos) : null;

  const tabCount: Partial<Record<Tab, number>> = {
    Trace: traceAll.length,
    Payments: rows("payments").length,
    Refunds: rows("refunds").length,
    Webhooks: rows("webhooks").length,
    Reconciliations: rows("reconciliations").length,
    "Partner orders": rows("orders").length,
  };

  return (
    <main className="wrap">
      <header className={s.header}>
        <div>
          <div className="eyebrow">Test console · polls every 2 s</div>
          <h1>Mini App Payment Flow</h1>
        </div>
        <div className={s.status}>
          {(["portal", "bank", "partner"] as Svc[]).map((svc) => {
            const st = svcStatus(svc);
            return (
              <span key={svc} className={`pill ${st === "online" ? "good" : st === "unknown" ? "muted" : "bad"}`}>
                {SVC_LABEL[svc]} · {st}
              </span>
            );
          })}
          <label className={s.check}>
            <input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} /> Live
          </label>
        </div>
      </header>

      <nav className={s.tabs} aria-label="Sections">
        {TABS.map((t) => (
          <button key={t} aria-pressed={tab === t} onClick={() => setTab(t)}>
            {t}
            {t === "Coverage" ? <b>{observed.size}/62</b> : tabCount[t] ? <b>{tabCount[t]}</b> : null}
          </button>
        ))}
      </nav>

      {tab === "Trace" && (
        <section className="panel">
          <div className={s.toolbar}>
            <input
              className="input"
              placeholder="Filter by ref (paymentId, orderId, appId…)"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
            <label className={s.check}>
              <input type="checkbox" checked={newestFirst} onChange={(e) => setNewestFirst(e.target.checked)} />{" "}
              Newest first
            </label>
            <button className="btn danger" onClick={clearTrace}>
              Clear all
            </button>
          </div>
          {problem("portalTrace")}
          {problem("bankTrace")}
          {problem("partnerTrace")}
          {shownTrace.length === 0 ? (
            <div className={s.empty}>No events{filter ? " match this ref" : " yet"}.</div>
          ) : (
            <ol className={s.trace}>
              {shownTrace.map((e) => (
                <li key={`${e.svc}-${e.id}`} className={s.lane} data-actor={e.actor}>
                  <span className={s.stepBadge} title={e.step ? undefined : "Un-numbered note (retry, chaos, rejection)"}>
                    {e.step || "·"}
                  </span>
                  <span className={s.actor} title={ACTOR_LABEL[e.actor] ?? e.actor}>
                    {e.actor}
                  </span>
                  <div className={s.traceBody}>
                    <strong>{e.title}</strong>
                    {e.detail && <span>{e.detail}</span>}
                  </div>
                  <div className={s.traceMeta}>
                    {e.ref && (
                      <button className={s.ref} onClick={() => setFilter(e.ref ?? "")} title="Filter by this ref">
                        {e.ref}
                      </button>
                    )}
                    <time>{time(e.ts)}</time>
                  </div>
                </li>
              ))}
            </ol>
          )}
        </section>
      )}

      {tab === "Coverage" && (
        <section className="panel">
          <div className={s.coverageHead}>
            <strong>
              {observed.size} / 62 steps observed
            </strong>
            <div className={s.bar}>
              <div style={{ width: `${(observed.size / 62) * 100}%` }} />
            </div>
          </div>
          <div className={s.phases}>
            {PHASES.map((p) => {
              const steps = STEPS.filter((x) => x.phase.n === p.n);
              const seen = steps.filter((x) => observed.has(x.n)).length;
              return (
                <div key={p.n} className={s.phase}>
                  <h3>
                    <span>Phase {p.n}</span> {p.name}
                    <em>
                      {seen}/{steps.length}
                    </em>
                  </h3>
                  <ul>
                    {steps.map((x) => (
                      <li key={x.n} className={s.lane} data-seen={observed.has(x.n)} data-actor={x.actor}>
                        <span className={s.tick}>{observed.has(x.n) ? "✓" : ""}</span>
                        <b>{x.n}</b>
                        <span className={s.actor}>{x.actor}</span>
                        <span>{x.title}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            })}
          </div>
        </section>
      )}

      {tab === "Payments" && (
        <section className="panel">
          {problem("payments")}
          <Table
            rows={rows("payments")}
            cols={[
              ["Payment", (r) => <code>{txt(r.paymentId)}</code>],
              ["Order", (r) => <code>{txt(r.orderId)}</code>],
              ["Amount", (r) => money(r.amount, r.currency)],
              ["Status", (r) => statusPill(r.status)],
              ["Bank txn", (r) => <code>{txt(r.bankTxnId)}</code>],
              ["Refunded", (r) => (r.refundedAmount ? money(r.refundedAmount, r.currency) : "–")],
              ["Created", (r) => time(r.createdAt)],
              ["Expires", (r) => time(r.expiresAt)],
            ]}
          />
        </section>
      )}

      {tab === "Refunds" && (
        <section className="panel">
          {problem("refunds")}
          <Table
            rows={rows("refunds")}
            cols={[
              ["Refund", (r) => <code>{txt(r.refundId)}</code>],
              ["Payment", (r) => <code>{txt(r.paymentId)}</code>],
              ["Amount", (r) => money(r.amount, r.currency)],
              ["Status", (r) => statusPill(r.status)],
              ["Refund txn", (r) => <code>{txt(r.refundTxnId)}</code>],
              ["Reason", "reason"],
            ]}
          />
        </section>
      )}

      {tab === "Webhooks" && (
        <section className="panel">
          {problem("webhooks")}
          <Table
            rows={rows("webhooks")}
            cols={[
              ["Type", (r) => <code>{txt(r.type)}</code>],
              ["Payment", (r) => <code>{txt(r.paymentId)}</code>],
              ["Status", (r) => statusPill(r.status)],
              ["Attempts", "attempts"],
              ["Last HTTP", "lastStatusCode"],
              ["Next attempt", (r) => time(r.nextAttemptAt)],
              ["Updated", (r) => time(r.updatedAt)],
              ["Event", (r) => <code>{txt(r.eventId)}</code>],
            ]}
          />
        </section>
      )}

      {tab === "Reconciliations" && (
        <section className="panel">
          {problem("reconciliations")}
          <Table
            rows={rows("reconciliations")}
            cols={[
              ["Date", "date"],
              ["Result", (r) => (r.matched ? statusPill("MATCHED") : statusPill("MISMATCH"))],
              [
                "Mismatches",
                (r) =>
                  Array.isArray(r.mismatches) && r.mismatches.length ? (
                    <ul className={s.mismatches}>
                      {r.mismatches.map((m, i) => (
                        <li key={i}>{String(m)}</li>
                      ))}
                    </ul>
                  ) : (
                    "–"
                  ),
              ],
              ["Created", (r) => time(r.createdAt)],
            ]}
          />
        </section>
      )}

      {tab === "Partner orders" && (
        <section className="panel">
          {problem("orders")}
          <Table
            rows={rows("orders")}
            cols={[
              ["Order", (r) => <code>{txt(r.orderId)}</code>],
              ["Item", (r) => `${txt(r.title)}`],
              ["Amount", (r) => money(r.amount, r.currency)],
              ["Status", (r) => statusPill(r.status)],
              ["Payment", (r) => <code>{txt(r.paymentId)}</code>],
              ["Refunded", (r) => (r.refundedAmount ? money(r.refundedAmount, r.currency) : "–")],
              [
                "Ticket",
                (r) => {
                  const t = r.ticket as { code?: string; seat?: string } | null;
                  return t ? `${t.code} · ${t.seat}` : "–";
                },
              ],
              ["Created", (r) => time(r.createdAt)],
            ]}
          />
        </section>
      )}

      {tab === "Bank" && (
        <div className={s.stack}>
          <section className="panel">
            <h2>Accounts</h2>
            {problem("accounts")}
            <Table
              rows={rows("accounts")}
              cols={[
                ["Account", (r) => <code>{txt(r.accountNo)}</code>],
                ["Name", "name"],
                ["Customer", "customerId"],
                ["Balance", (r) => <strong>{money(r.balance, r.currency)}</strong>],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Transactions</h2>
            {problem("transactions")}
            <Table
              rows={[...rows("transactions")].reverse()}
              cols={[
                ["Txn", (r) => <code>{txt(r.txnId)}</code>],
                ["Type", "type"],
                ["Payment / refund", (r) => <code>{txt(r.refundId ?? r.paymentId)}</code>],
                ["Debit", (r) => <code>{txt(r.debitAccount)}</code>],
                ["Credit", (r) => <code>{txt(r.creditAccount)}</code>],
                ["Amount", (r) => money(r.amount, r.currency)],
                ["Status", (r) => statusPill(r.status)],
                ["At", (r) => time(r.createdAt)],
              ]}
            />
          </section>
        </div>
      )}

      {tab === "Actions" && (
        <div className={s.grid2}>
          <section className="panel">
            <h2>Bank chaos</h2>
            {problem("bankChaos")}
            {bankChaos &&
              (
                [
                  ["dropResultCallback", "Drop result callback (bank debits but never tells the portal → recovery)"],
                  ["failDebit", "Fail debit (bank answers FAILED / DEBIT_FAILED)"],
                  ["failRefund", "Fail refund (refund → FAILED)"],
                  ["settlementMismatch", "Settlement mismatch (tamper with the daily file)"],
                ] as [keyof BankChaos, string][]
              ).map(([k, label]) => (
                <label key={k} className={s.toggle}>
                  <input
                    type="checkbox"
                    checked={!!bankChaos[k]}
                    onChange={(e) =>
                      act(`Bank chaos ${k}=${e.target.checked}`, () =>
                        api(`${BANK}/api/v1/admin/chaos`, { method: "PUT", body: { ...bankChaos, [k]: e.target.checked } }),
                      )
                    }
                  />
                  <span>
                    <code>{k}</code> {label}
                  </span>
                </label>
              ))}
          </section>

          <section className="panel">
            <h2>Partner chaos</h2>
            {problem("partnerChaos")}
            {partnerChaos && (
              <>
                <div className={s.toggle}>
                  <span>
                    <code>webhookFailCount</code> answer the next N webhooks with HTTP 500 (now{" "}
                    <b>{partnerChaos.webhookFailCount}</b>)
                  </span>
                </div>
                <div className={s.toolbar}>
                  <input
                    className="input"
                    type="number"
                    min={0}
                    style={{ width: 90 }}
                    value={failCountDraft ?? String(partnerChaos.webhookFailCount ?? 0)}
                    onChange={(e) => setFailCountDraft(e.target.value)}
                  />
                  <button
                    className="btn"
                    onClick={() =>
                      act("Partner chaos webhookFailCount", () =>
                        api(`${PARTNER}/api/v1/admin/chaos`, {
                          method: "PUT",
                          body: { ...partnerChaos, webhookFailCount: Math.max(0, Number(failCountDraft ?? 0) || 0) },
                        }),
                      ).then(() => setFailCountDraft(null))
                    }
                  >
                    Apply
                  </button>
                </div>
                <label className={s.toggle}>
                  <input
                    type="checkbox"
                    checked={!!partnerChaos.ignoreWebhooks}
                    onChange={(e) =>
                      act(`Partner chaos ignoreWebhooks=${e.target.checked}`, () =>
                        api(`${PARTNER}/api/v1/admin/chaos`, {
                          method: "PUT",
                          body: { ...partnerChaos, ignoreWebhooks: e.target.checked },
                        }),
                      )
                    }
                  />
                  <span>
                    <code>ignoreWebhooks</code> answer 200 but do nothing (forces the status-check fallback)
                  </span>
                </label>
              </>
            )}
          </section>

          <section className="panel">
            <h2>Jobs</h2>
            <div className={s.buttons}>
              <button
                className="btn"
                onClick={() => act("Run recovery", () => api(`${PORTAL}/api/v1/admin/jobs/recovery`, { method: "POST" }))}
              >
                Run recovery now
              </button>
              <button
                className="btn"
                onClick={async () => {
                  const r = await act("Run daily settlement", () =>
                    api(`${BANK}/api/v1/admin/settlement/run`, { method: "POST" }),
                  );
                  if (r !== undefined) setSettlement(r);
                }}
              >
                Run daily settlement
              </button>
              <button className="btn" onClick={clearTrace}>
                Clear all traces
              </button>
              <button
                className="btn danger"
                onClick={() => {
                  if (window.confirm("Reset all services? Clears payments, orders, bank transactions (balances back to seed), consents, chaos and trace."))
                    void act("Reset all", () =>
                      Promise.all(
                        [PORTAL, BANK, PARTNER].map(base => api(`${base}/api/v1/admin/reset`, { method: "POST" })),
                      ),
                    );
                }}
              >
                Reset all data
              </button>
            </div>
            {settlement != null && (
              <div className={s.result}>
                <h3>Last settlement result</h3>
                {(() => {
                  const r = settlement as { matched?: boolean; mismatches?: unknown[]; date?: string };
                  return (
                    <>
                      <p>
                        {r.date ?? ""} {statusPill(r.matched ? "MATCHED" : "MISMATCH")}
                      </p>
                      {Array.isArray(r.mismatches) && r.mismatches.length > 0 && (
                        <ul className={s.mismatches}>
                          {r.mismatches.map((m, i) => (
                            <li key={i}>{String(m)}</li>
                          ))}
                        </ul>
                      )}
                      <pre>{JSON.stringify(settlement, null, 2)}</pre>
                    </>
                  );
                })()}
              </div>
            )}
          </section>

          <section className="panel">
            <h2>Action log</h2>
            {actionLog.length === 0 ? (
              <div className={s.empty}>No actions yet.</div>
            ) : (
              <ul className={s.actionLog}>
                {actionLog.map((a, i) => (
                  <li key={i} data-ok={a.ok}>
                    <time>{a.ts}</time> {a.text}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}

      {tab === "Scenarios" && <Scenarios />}
    </main>
  );
}

const SCENARIOS: { name: string; steps: ReactNode[]; expect: string }[] = [
  {
    name: "Happy path",
    steps: [
      "Simulator: log in as alice / 1234 → Mini apps → Movie tickets.",
      "Sign in with KB → Allow → Buy “Dune: Part Three” → Approve → PIN 1234.",
      "Ticket appears; X or Close returns to the list.",
    ],
    expect: "Payment PAID, webhook DELIVERED, order DELIVERED. Phases 1–6, 8 and 10 covered.",
  },
  {
    name: "Cancel",
    steps: ["Buy any movie, then tap Cancel on the payment sheet."],
    expect: "Steps 30–32; payment CANCELLED, page shows “Payment cancelled”.",
  },
  {
    name: "Insufficient funds",
    steps: ["Log out, log in as bob / 1234 (USD 3.00).", "Buy “Expensive IMAX” (m3, 25.00) and approve."],
    expect: "Receipt FAILED with INSUFFICIENT_FUNDS; payment FAILED, order FAILED, no bank transaction.",
  },
  {
    name: "Lost bank result → recovery",
    steps: [
      "Actions: turn on dropResultCallback.",
      "Pay as alice. The bank debits but the portal stays PROCESSING.",
      "Wait for the recovery job or press “Run recovery now”.",
    ],
    expect: "Steps 41–43: portal asks the bank, applies PAID, then the webhook delivers the ticket.",
  },
  {
    name: "Webhook retries",
    steps: ["Actions: set webhookFailCount = 3 and Apply.", "Buy a ticket."],
    expect: "Webhooks tab: attempts climb with lastStatusCode 500 and a nextAttemptAt, then DELIVERED.",
  },
  {
    name: "Webhook ignored → status-check fallback",
    steps: ["Actions: turn on ignoreWebhooks.", "Buy a ticket."],
    expect: "The partner page polls the order; the partner checks the portal (51–53) and delivers.",
  },
  {
    name: "Partial + full refund",
    steps: ["Partner page → My orders → “Refund 1.00”, then “Refund all”."],
    expect: "Steps 55–60; order PARTIALLY_REFUNDED then REFUNDED; escrow debited, customer credited.",
  },
  {
    name: "Settlement mismatch",
    steps: ["Run daily settlement once (should match).", "Turn on settlementMismatch and run it again."],
    expect: "Steps 44–46; Reconciliations shows a mismatch entry with text.",
  },
];

function Scenarios() {
  return (
    <section className={s.scenarios}>
      {SCENARIOS.map((sc, i) => (
        <article key={sc.name} className="panel">
          <h3>
            {i + 1}. {sc.name}
          </h3>
          <ol>
            {sc.steps.map((st, j) => (
              <li key={j}>{st}</li>
            ))}
          </ol>
          <p>
            <b>Expect:</b> {sc.expect}
          </p>
        </article>
      ))}
    </section>
  );
}
