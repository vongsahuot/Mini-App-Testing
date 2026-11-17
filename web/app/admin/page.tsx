"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, BANK, errorText, isApiError, money, PARTNER, PORTAL, store } from "@/lib/api";
import { ACTOR_LABEL, FLOW, SHARED_PHASES, stepKey, type Flow } from "@/lib/trace";
import { Scenarios } from "./scenarios";
import s from "./admin.module.css";

type Svc = "portal" | "bank" | "partner";
type Row = Record<string, unknown>;
type Staff = "admin" | "ops";
interface TraceEv {
  id: string | number;
  ts: string;
  actor: string;
  step: string | null;
  flow: Flow | null;
  title: string;
  detail: string | null;
  ref: string | null;
}
type TraceRow = TraceEv & { svc: Svc; t: number };
type Chaos = Record<string, boolean | number>;

const ENDPOINTS = {
  portalTrace: ["portal", `${PORTAL}/api/v1/admin/trace`],
  payments: ["portal", `${PORTAL}/api/v1/admin/payments`],
  refunds: ["portal", `${PORTAL}/api/v1/admin/refunds`],
  outbox: ["portal", `${PORTAL}/api/v1/admin/outbox`],
  topics: ["portal", `${PORTAL}/api/v1/admin/topics`],
  reconciliations: ["portal", `${PORTAL}/api/v1/admin/reconciliations`],
  opsItems: ["portal", `${PORTAL}/api/v1/admin/ops-items`],
  audit: ["portal", `${PORTAL}/api/v1/admin/audit`],
  miniApps: ["portal", `${PORTAL}/api/v1/admin/mini-apps`],
  partners: ["portal", `${PORTAL}/api/v1/admin/partners`],
  bankTrace: ["bank", `${BANK}/api/v1/admin/trace`],
  accounts: ["bank", `${BANK}/api/v1/admin/accounts`],
  transactions: ["bank", `${BANK}/api/v1/admin/transactions`],
  holds: ["bank", `${BANK}/api/v1/admin/holds`],
  customers: ["bank", `${BANK}/api/v1/admin/customers`],
  bankNotifications: ["bank", `${BANK}/api/v1/admin/notifications`],
  bankChaos: ["bank", `${BANK}/api/v1/admin/chaos`],
  partnerTrace: ["partner", `${PARTNER}/api/v1/admin/trace`],
  orders: ["partner", `${PARTNER}/api/v1/admin/orders`],
  partnerUsers: ["partner", `${PARTNER}/api/v1/admin/users`],
  partnerRefunds: ["partner", `${PARTNER}/api/v1/admin/refunds`],
  partnerNotifications: ["partner", `${PARTNER}/api/v1/admin/notifications`],
  partnerChaos: ["partner", `${PARTNER}/api/v1/admin/chaos`],
} as const satisfies Record<string, readonly [Svc, string]>;
type Key = keyof typeof ENDPOINTS;
type Result = { ok: true; data: unknown } | { ok: false; error: string; offline: boolean };

const SVC_LABEL: Record<Svc, string> = { portal: "Portal :8081", bank: "Bank :8082", partner: "Partner :8083" };
const SVC_ORDER: Record<Svc, number> = { portal: 0, bank: 1, partner: 2 };
const TABS = [
  "Trace",
  "Coverage",
  "Payments",
  "Refunds",
  "Notifications",
  "Ops & audit",
  "Partner",
  "Bank",
  "Mini App Admin",
  "Actions",
  "Scenarios",
] as const;
type Tab = (typeof TABS)[number];

/** Lane groups, coloured as in the flow diagram. */
const LANE_GROUP: Record<string, "device" | "partner" | "bank" | "core"> = {
  U: "device",
  APP: "device",
  PC: "device",
  SDK: "device",
  PA: "device",
  PS: "partner",
  PG: "bank",
  MBE: "bank",
  MAS: "bank",
  KF: "bank",
  LG: "core",
  CB: "core",
};
const laneGroup = (a: string) => LANE_GROUP[a] ?? "core";
const actorTitle = (a: string) => (ACTOR_LABEL as Record<string, string>)[a] ?? a;
const FLOW_NAME = Object.fromEntries(FLOW.types.map((t) => [t.id, t.name])) as Record<Flow, string>;

/** Bank chaos switches, explained as in docs/CONTRACT.md ("Bank chaos"). */
const BANK_CHAOS_HELP: Record<string, string> = {
  initFail: "LG refuses the hold → 3:22a–22e (failed)",
  initTimeout: "LG places the hold but answers too late → INITIATED, released by expiry (R7–R9)",
  completeFail: "Core Banking refuses the debit → 3:38a–38f",
  completeTimeout: "LG posts the debit but answers too late → 3:38g–38k, then recovery R1–R6c → SUCCESS",
  completeLost: "LG answers too late without posting → 3:38g, then R1–R5b NOT_FOUND → void → FAILED",
  lgDown: "Inquiry / void get no answer → R5 retry + ops alert after 60 s",
  cancelFail: "Cancel init (hold release) fails → R9a, CANCEL_PENDING ops item",
  refundFail: "Core Banking refuses refunds → 4:7 FAILED",
  refundTimeout: "LG posts the refund but answers too late → REFUND_PENDING, then F1–F9 → REFUNDED",
  refundLost: "LG answers too late without posting → REFUND_PENDING, then F1–F5b NOT_FOUND → void → FAILED",
  settlementMismatch: "First settlement file entry's amount is off by 0.01 → 4:18 mismatch",
};
const PARTNER_CHAOS_HELP: Record<string, string> = {
  registerFail: "User registration answers 500 → REGISTRATION_FAILED (2:19a–19c), retried on next open",
  ignoreNotifications: "ACK payment notifications but do nothing → partner falls back to inquiry",
  apiFail: "API-linkage operations answer 500 → cached catalog / PARTNER_UNAVAILABLE, breaker",
  createOrderSlow: "First create-order call per idempotencyKey sleeps 6 s → bank retries once",
  badChecksum: "Sign orders with a wrong key → BAD_CHECKSUM",
};
const SEED_IPS = "127.0.0.1/32, ::1/128, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16";

const GOOD = ["SUCCESS", "DELIVERED", "REFUNDED", "ACK", "RESOLVED", "CAPTURED", "MATCHED", "DONE", "ACCEPTED", "OK", "CLOSED", "PUBLISHED"];
const BAD = ["FAILED", "EXPIRED", "MISMATCH", "REFUSED", "VOIDED", "REJECTED", "LOCKED", "OPEN", "DLT", "CANCELLED"];
const WARN = ["PROCESSING", "INITIATED", "HELD", "REQUESTED", "RETRY"];

function statusPill(v: unknown) {
  const t = v == null || v === "" ? "–" : String(v);
  const cls = GOOD.includes(t)
    ? "good"
    : BAD.includes(t) || t.startsWith("NO_ACK")
      ? "bad"
      : t.includes("PENDING") || t.includes("PARTIAL") || WARN.includes(t)
        ? "warn"
        : "muted";
  return (
    <span className={`pill ${cls}`} title={t}>
      {t.length > 40 ? `${t.slice(0, 40)}…` : t}
    </span>
  );
}

/** Epoch ms of an ISO timestamp, keeping sub-millisecond digits (Java sends up to nanoseconds). */
function tsMs(v: string): number {
  const m = /^(.*?)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)?$/.exec(v);
  if (!m) return NaN;
  return Date.parse(m[1] + (m[3] ?? "Z")) + (m[2] ? Number(`0.${m[2]}`) * 1000 : 0);
}
const time = (v: unknown) => {
  if (typeof v !== "string" || !v) return "–";
  const ms = tsMs(v);
  if (isNaN(ms)) return v;
  const d = new Date(ms);
  return `${d.toLocaleTimeString([], { hour12: false })}.${String(d.getMilliseconds()).padStart(3, "0")}`;
};
const txt = (v: unknown): ReactNode =>
  v == null || v === "" ? "–" : typeof v === "object" ? <code>{JSON.stringify(v)}</code> : String(v);
const num = (v: unknown) => (typeof v === "number" ? v : Number(v ?? 0) || 0);

type Col = [label: string, render: string | ((r: Row) => ReactNode)];

function Table({
  rows,
  cols,
  idKey,
  empty = "Nothing yet.",
  rowKind,
}: {
  rows: Row[];
  cols: Col[];
  idKey: string;
  empty?: string;
  rowKind?: (r: Row) => string | undefined;
}) {
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
            <tr key={String(r[idKey] ?? i)} data-kind={rowKind?.(r)}>
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

interface LogEntry {
  ts: string;
  text: string;
  ok: boolean;
}
function ActionLog({ log }: { log: LogEntry[] }) {
  if (log.length === 0) return <div className={s.empty}>No actions yet.</div>;
  return (
    <ul className={s.actionLog}>
      {log.map((a, i) => (
        <li key={i} data-ok={a.ok}>
          <time>{a.ts}</time> {a.text}
        </li>
      ))}
    </ul>
  );
}

export default function AdminPage() {
  const [results, setResults] = useState<Partial<Record<Key, Result>>>({});
  const [tab, setTab] = useState<Tab>("Trace");
  const [live, setLive] = useState(true);
  const [staff, setStaffState] = useState<Staff>("admin");
  const [filter, setFilter] = useState("");
  const [flowFilter, setFlowFilter] = useState<Flow | "all">("all");
  const [withShared, setWithShared] = useState(false);
  const [newestFirst, setNewestFirst] = useState(false);
  const [covType, setCovType] = useState<Flow>("webview");
  const [onlyMissing, setOnlyMissing] = useState(false);
  const [actionLog, setActionLog] = useState<LogEntry[]>([]);
  const [staffError, setStaffError] = useState<string | null>(null);
  const [settlement, setSettlement] = useState<unknown>(null);
  const [jobResult, setJobResult] = useState<{ label: string; data: unknown } | null>(null);
  const [failCountDraft, setFailCountDraft] = useState<string | null>(null);
  const [ipDrafts, setIpDrafts] = useState<Record<string, string>>({});
  const [opsNotes, setOpsNotes] = useState<Record<string, string>>({});
  const inFlight = useRef(false);

  useEffect(() => {
    const saved = store("local").get<Staff>("console.staff");
    if (saved === "admin" || saved === "ops") setStaffState(saved);
  }, []);
  const setStaff = (v: Staff) => {
    setStaffState(v);
    setStaffError(null);
    store("local").set("console.staff", v);
  };

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
  const problem = (...ks: Key[]) =>
    ks.map((k) => {
      const r = results[k];
      if (!r || r.ok) return null;
      const svc = ENDPOINTS[k][0];
      return (
        <div key={k} className={s.offline}>
          {r.offline ? `${SVC_LABEL[svc]} offline` : `${SVC_LABEL[svc]} ${k}: ${r.error}`}
        </div>
      );
    });

  // ---------- trace ----------
  const traceAll = useMemo(() => {
    const all: TraceRow[] = [];
    (["portalTrace", "bankTrace", "partnerTrace"] as const).forEach((k) =>
      rows(k).forEach((e) => {
        const ev = e as unknown as TraceEv;
        all.push({ ...ev, svc: ENDPOINTS[k][0], t: tsMs(String(ev.ts)) });
      }),
    );
    all.sort((a, b) => a.t - b.t || SVC_ORDER[a.svc] - SVC_ORDER[b.svc] || Number(a.id) - Number(b.id));
    return all;
  }, [rows]);

  const shownTrace = useMemo(() => {
    const f = filter.trim().toLowerCase();
    const list = traceAll.filter(
      (e) =>
        (!f || (e.ref ?? "").toLowerCase().includes(f)) &&
        (flowFilter === "all" || e.flow === flowFilter || (withShared && e.flow == null)),
    );
    return newestFirst ? list.reverse() : list;
  }, [traceAll, filter, flowFilter, withShared, newestFirst]);

  // ---------- coverage ----------
  /** step key → flows it was observed with ("-" = flow null). */
  const seen = useMemo(() => {
    const m = new Map<string, Set<string>>();
    for (const e of traceAll) {
      if (!e.step) continue;
      let set = m.get(e.step);
      if (!set) m.set(e.step, (set = new Set()));
      set.add(e.flow ?? "-");
    }
    return m;
  }, [traceAll]);
  const isSeen = useCallback(
    (t: Flow, key: string) => {
      const x = seen.get(key);
      return !!x && (x.has(t) || x.has("-"));
    },
    [seen],
  );
  const coverage = useMemo(() => {
    const out = {} as Record<Flow, { ok: number; total: number }>;
    for (const t of FLOW.types) {
      let ok = 0;
      let total = 0;
      for (const k of FLOW.flows[t.id].phases) {
        const p = FLOW.phases[k];
        for (const st of p.steps) {
          total++;
          if (isSeen(t.id, stepKey(p.sec, st.n))) ok++;
        }
      }
      out[t.id] = { ok, total };
    }
    return out;
  }, [isSeen]);

  // ---------- actions ----------
  async function act(label: string, fn: () => Promise<unknown>, staffCall = false) {
    const ts = new Date().toLocaleTimeString([], { hour12: false });
    try {
      const r = await fn();
      setActionLog((l) => [{ ts, ok: true, text: `${label}: ${r == null ? "ok" : JSON.stringify(r)}` }, ...l].slice(0, 40));
      if (staffCall) setStaffError(null);
      return r;
    } catch (e) {
      const msg = `${label}: ${isApiError(e) && e.status ? `HTTP ${e.status} ` : ""}${errorText(e)}`;
      setActionLog((l) => [{ ts, ok: false, text: msg }, ...l].slice(0, 40));
      if (staffCall) setStaffError(msg);
      return undefined;
    } finally {
      void refresh();
    }
  }
  /** Mutating Mini App Admin / ops call, sent as the "Signed in as" AD account. */
  const staffCall = (label: string, url: string, method: string, body?: unknown) =>
    act(`${label} (as ${staff})`, () => api(url, { method, body: body ?? {}, headers: { "X-Staff-User": staff } }), true);

  const clearTrace = () =>
    act("Clear trace", async () => {
      const r = await Promise.allSettled(
        [PORTAL, BANK, PARTNER].map((b) => api(`${b}/api/v1/admin/trace`, { method: "DELETE" })),
      );
      const failed = r.filter((x) => x.status === "rejected").length;
      return failed ? `${3 - failed}/3 cleared (${failed} offline/failed)` : "all cleared";
    });

  const resetAll = () => {
    if (
      !window.confirm(
        "Reset all three services? Clears payments, refunds, Kafka topics, ops items, audit, orders, partner users, bank transactions, holds and notifications (balances back to seed), consents, chaos switches, Mini App / partner registry changes and all traces.",
      )
    )
      return;
    void act("Reset all", async () => {
      const r = await Promise.allSettled(
        [PORTAL, BANK, PARTNER].map((b) => api(`${b}/api/v1/admin/reset`, { method: "POST" })),
      );
      const failed = r.filter((x) => x.status === "rejected").length;
      setIpDrafts({});
      setFailCountDraft(null);
      setSettlement(null);
      setJobResult(null);
      return failed ? `${3 - failed}/3 reset (${failed} offline/failed)` : "all three reset";
    });
  };

  const runJob = async (label: string, url: string, body?: unknown) => {
    const r = await act(label, () => api(url, { method: "POST", body }));
    if (r !== undefined) setJobResult({ label, data: r });
  };

  const bankChaos = results.bankChaos?.ok ? (results.bankChaos.data as Chaos) : null;
  const partnerChaos = results.partnerChaos?.ok ? (results.partnerChaos.data as Chaos) : null;

  const showRef = (ref: unknown) => {
    if (typeof ref !== "string" || !ref) return "–";
    return (
      <button
        className={s.ref}
        title="Show this ref in the trace"
        onClick={() => {
          setFilter(ref);
          setTab("Trace");
        }}
      >
        {ref}
      </button>
    );
  };

  const openOps = rows("opsItems").filter((r) => r.status === "OPEN").length;
  const topicKind = (topic: unknown) => {
    const t = String(topic ?? "");
    return t.endsWith("-dlt") ? "dlt" : t.includes("-retry-") ? "retry" : undefined;
  };
  const tabCount: Partial<Record<Tab, ReactNode>> = {
    Trace: traceAll.length,
    Coverage: `${coverage[covType].ok}/${coverage[covType].total}`,
    Payments: rows("payments").length,
    Refunds: rows("refunds").length,
    Notifications: rows("topics").length,
    "Ops & audit": openOps ? <span className={s.alertCount}>{openOps} open</span> : undefined,
    Partner: rows("orders").length,
  };

  return (
    <main className="wrap">
      <header className={s.header}>
        <div>
          <div className="eyebrow">Test console · 4 integration types · polls every 2 s</div>
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
          <label className={s.check} title="X-Staff-User sent with Mini App Admin and ops calls (mock Active Directory)">
            Signed in as
            <select className={s.select} value={staff} onChange={(e) => setStaff(e.target.value as Staff)}>
              <option value="admin">admin</option>
              <option value="ops">ops</option>
            </select>
          </label>
        </div>
      </header>

      <nav className={s.tabs} aria-label="Sections">
        {TABS.map((t) => (
          <button key={t} aria-pressed={tab === t} onClick={() => setTab(t)}>
            {t}
            {tabCount[t] ? <b>{tabCount[t]}</b> : null}
          </button>
        ))}
      </nav>

      {tab === "Trace" && (
        <section className="panel">
          <div className={s.toolbar}>
            <input
              className="input"
              placeholder="Filter by ref (paymentRef, orderId, refundId, appId…)"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
            {filter && (
              <button className="btn" onClick={() => setFilter("")}>
                ✕
              </button>
            )}
            <select
              className={s.select}
              value={flowFilter}
              onChange={(e) => setFlowFilter(e.target.value as Flow | "all")}
              aria-label="Flow"
            >
              <option value="all">All flows</option>
              {FLOW.types.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.id} · {t.name}
                </option>
              ))}
            </select>
            {flowFilter !== "all" && (
              <label className={s.check} title="Also show events with flow = null (shared phases, notes)">
                <input type="checkbox" checked={withShared} onChange={(e) => setWithShared(e.target.checked)} /> + shared
              </label>
            )}
            <label className={s.check}>
              <input type="checkbox" checked={newestFirst} onChange={(e) => setNewestFirst(e.target.checked)} /> Newest
              first
            </label>
            <button className="btn danger" onClick={clearTrace}>
              Clear all
            </button>
          </div>
          {problem("portalTrace", "bankTrace", "partnerTrace")}
          <div className={s.hint}>
            {shownTrace.length} of {traceAll.length} events
          </div>
          {shownTrace.length === 0 ? (
            <div className={s.empty}>No events{filter || flowFilter !== "all" ? " match these filters" : " yet"}.</div>
          ) : (
            <ol className={s.trace}>
              {shownTrace.map((e) => (
                <li key={`${e.svc}-${e.id}`} className={s.lane} data-group={laneGroup(e.actor)}>
                  <span
                    className={s.stepBadge}
                    data-note={!e.step}
                    title={e.step ? `Step ${e.step}` : "Un-numbered note (retry, chaos, rejection)"}
                  >
                    {e.step || "·"}
                  </span>
                  <span className={s.actor} title={actorTitle(e.actor)}>
                    {e.actor}
                  </span>
                  <div className={s.traceBody}>
                    <strong>
                      {e.flow && (
                        <span className={s.flowChip} data-flow={e.flow} title={FLOW_NAME[e.flow]}>
                          {e.flow}
                        </span>
                      )}
                      {e.title}
                    </strong>
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
          {problem("portalTrace", "bankTrace", "partnerTrace")}
          <div className={s.typeSelect} role="group" aria-label="Integration type">
            {FLOW.types.map((t) => {
              const c = coverage[t.id];
              return (
                <button key={t.id} aria-pressed={covType === t.id} onClick={() => setCovType(t.id)}>
                  <strong>{t.name}</strong>
                  <span>
                    {c.ok}/{c.total}
                  </span>
                  <div className={s.bar}>
                    <div style={{ width: `${c.total ? (c.ok / c.total) * 100 : 0}%` }} />
                  </div>
                </button>
              );
            })}
          </div>
          <div className={s.coverageHead}>
            <div className={s.coverageTitle}>
              <strong>
                {FLOW_NAME[covType]}: {coverage[covType].ok} / {coverage[covType].total} steps observed
              </strong>
              <label className={s.check}>
                <input type="checkbox" checked={onlyMissing} onChange={(e) => setOnlyMissing(e.target.checked)} /> Only
                missing steps
              </label>
            </div>
            <div className={s.bar}>
              <div style={{ width: `${(coverage[covType].ok / Math.max(1, coverage[covType].total)) * 100}%` }} />
            </div>
            <small className={s.hint}>
              A step counts for {covType} when an event has its key and flow {covType} or no flow (shared steps).
              Clear the trace before a run to measure one type at a time.
            </small>
          </div>
          {FLOW.flows[covType].sections.map((sec) => {
            const phases = FLOW.flows[covType].phases.filter((k) => FLOW.phases[k].sec === sec.n);
            if (!phases.length) return null;
            return (
              <div key={sec.n} className={s.covSection}>
                <h2>
                  {sec.n}. {sec.title}
                </h2>
                <div className={s.phases}>
                  {phases.map((k) => {
                    const p = FLOW.phases[k];
                    const steps = p.steps.map((st) => {
                      const key = stepKey(p.sec, st.n);
                      return { st, key, ok: isSeen(covType, key) };
                    });
                    const done = steps.filter((x) => x.ok).length;
                    const shown = onlyMissing ? steps.filter((x) => !x.ok) : steps;
                    const shared = SHARED_PHASES.has(k);
                    return (
                      <div key={k} className={s.phase} data-complete={done === steps.length}>
                        <h3>
                          <span data-shared={shared}>{shared ? "Same in all types" : "Specific"}</span>
                          {p.title}
                          <em>
                            {done}/{steps.length}
                          </em>
                        </h3>
                        {shown.length === 0 ? (
                          <div className={s.hint}>All steps observed.</div>
                        ) : (
                          <ul>
                            {shown.map(({ st, key, ok }) => (
                              <li
                                key={key}
                                className={s.lane}
                                data-group={laneGroup(st.actor)}
                                data-seen={ok}
                                data-note={!!st.note}
                                title={
                                  st.from && st.to
                                    ? `${key}: ${actorTitle(st.from)} → ${actorTitle(st.to)}${st.reply ? " (reply)" : ""}`
                                    : `${key}: note on ${actorTitle(st.actor)}`
                                }
                              >
                                <span className={s.tick}>{ok ? "✓" : ""}</span>
                                <b>{st.n}</b>
                                <span className={s.actor}>{st.actor}</span>
                                <span className={s.stepLabel}>
                                  {st.reply ? "↩ " : ""}
                                  {st.label}
                                </span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </section>
      )}

      {tab === "Payments" && (
        <section className="panel">
          {problem("payments")}
          <Table
            rows={rows("payments")}
            idKey="paymentRef"
            cols={[
              ["Payment", (r) => showRef(r.paymentRef)],
              ["App", (r) => <code>{txt(r.appId)}</code>],
              ["Type", (r) => <span className={s.flowChip} data-flow={String(r.type)}>{txt(r.type)}</span>],
              ["Order", (r) => <code>{txt(r.orderId)}</code>],
              ["Amount", (r) => money(r.amount, r.currency)],
              ["Status", (r) => statusPill(r.status)],
              ["Fail reason", "failReason"],
              ["Bank txn", (r) => <code>{txt(r.bankTxnId)}</code>],
              ["Refunded", (r) => (num(r.refundedAmount) ? money(r.refundedAmount, r.currency) : "–")],
              ["Cancel", (r) => (r.cancelStatus ? statusPill(r.cancelStatus) : "–")],
              ["Expires", (r) => time(r.expiresAt)],
              ["Created", (r) => time(r.createdAt)],
            ]}
          />
        </section>
      )}

      {tab === "Refunds" && (
        <section className="panel">
          {problem("refunds")}
          <Table
            rows={rows("refunds")}
            idKey="refundId"
            cols={[
              ["Refund", (r) => showRef(r.refundId)],
              ["Payment", (r) => showRef(r.paymentRef)],
              ["Amount", (r) => money(r.amount, r.currency)],
              ["Status", (r) => statusPill(r.status)],
              ["Refund txn", (r) => <code>{txt(r.refundTxnId)}</code>],
              ["Reason", "reason"],
              ["Alerted", (r) => (r.alerted ? statusPill("ALERTED") : "–")],
              ["Created", (r) => time(r.createdAt)],
              ["Updated", (r) => time(r.updatedAt)],
            ]}
          />
        </section>
      )}

      {tab === "Notifications" && (
        <div className={s.stack}>
          <section className="panel">
            <h2>Kafka topics · payment-notification, retry-1|2|3 (5 s / 10 s / 20 s), dlt</h2>
            {problem("topics")}
            <div className={s.legend}>
              {(() => {
                const t = rows("topics");
                const main = t.filter((r) => !topicKind(r.topic)).length;
                const retry = t.filter((r) => topicKind(r.topic) === "retry").length;
                const dlt = t.filter((r) => topicKind(r.topic) === "dlt").length;
                const waiting = t.filter((r) => !r.consumedAt).length;
                return (
                  <>
                    <span className="pill muted">main {main}</span>
                    <span className="pill warn">retry {retry}</span>
                    <span className={`pill ${dlt ? "bad" : "muted"}`}>dead-letter {dlt}</span>
                    <span className="pill muted">not consumed yet {waiting}</span>
                  </>
                );
              })()}
            </div>
            <Table
              rows={rows("topics")}
              idKey="id"
              rowKind={(r) => topicKind(r.topic)}
              cols={[
                ["#", "id"],
                [
                  "Topic",
                  (r) => (
                    <span className={s.topic} data-kind={topicKind(r.topic) ?? "main"}>
                      {txt(r.topic)}
                    </span>
                  ),
                ],
                ["Attempt", "attempt"],
                ["Event", (r) => <code>{txt(r.eventId)}</code>],
                ["Payment", (r) => showRef(r.paymentRef)],
                ["Available", (r) => time(r.availableAt)],
                ["Consumed", (r) => (r.consumedAt ? time(r.consumedAt) : statusPill("WAITING"))],
                ["Result", (r) => (r.result ? statusPill(r.result) : "–")],
                ["HTTP", "lastStatusCode"],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Outbox (written with the final payment status, relayed every 0.5 s)</h2>
            {problem("outbox")}
            <Table
              rows={rows("outbox")}
              idKey="id"
              cols={[
                ["#", "id"],
                ["Event", (r) => <code>{txt(r.eventId)}</code>],
                ["Payment", (r) => showRef(r.paymentRef)],
                ["Status", (r) => statusPill(r.status)],
                ["Publish step", (r) => <code>{txt(r.publishStep)}</code>],
                ["Published", (r) => (r.publishedAt ? time(r.publishedAt) : statusPill("PENDING"))],
                ["Created", (r) => time(r.createdAt)],
              ]}
            />
          </section>
        </div>
      )}

      {tab === "Ops & audit" && (
        <div className={s.stack}>
          <section className="panel">
            <h2>Ops items · resolve as {staff}</h2>
            {problem("opsItems")}
            {staffError && <div className={s.offline}>{staffError}</div>}
            <Table
              rows={rows("opsItems")}
              idKey="id"
              empty="No ops alerts."
              rowKind={(r) => (r.status === "OPEN" ? "open" : undefined)}
              cols={[
                ["#", "id"],
                ["Kind", (r) => <code>{txt(r.kind)}</code>],
                ["Ref", (r) => showRef(r.ref)],
                ["Message", "message"],
                ["Status", (r) => statusPill(r.status)],
                ["Created", (r) => time(r.createdAt)],
                [
                  "Resolution",
                  (r) => {
                    const id = String(r.id);
                    if (r.status !== "OPEN")
                      return (
                        <span>
                          {txt(r.resolvedBy)} · {time(r.resolvedAt)}
                          {r.resolution ? ` · ${String(r.resolution)}` : ""}
                        </span>
                      );
                    return (
                      <div className={s.inline}>
                        <input
                          className="input"
                          placeholder="Note"
                          value={opsNotes[id] ?? ""}
                          onChange={(e) => setOpsNotes((n) => ({ ...n, [id]: e.target.value }))}
                        />
                        <button
                          className="btn"
                          onClick={() =>
                            staffCall(`Resolve ops item ${id}`, `${PORTAL}/api/v1/admin/ops-items/${id}/resolve`, "POST", {
                              note: opsNotes[id] || null,
                            })
                          }
                        >
                          Resolve
                        </button>
                      </div>
                    );
                  },
                ],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Reconciliation reports</h2>
            {problem("reconciliations")}
            <Table
              rows={rows("reconciliations")}
              idKey="id"
              empty="No settlement run yet (Actions → Run daily settlement)."
              cols={[
                ["Date", "date"],
                [
                  "Result",
                  (r) =>
                    statusPill(Array.isArray(r.mismatches) && r.mismatches.length ? "MISMATCH" : "MATCHED"),
                ],
                ["Entries", "entries"],
                ["Matched", "matched"],
                ["Fixed", "fixed"],
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
          <section className="panel">
            <h2>Audit log</h2>
            {problem("audit")}
            <Table
              rows={rows("audit")}
              idKey="id"
              cols={[
                ["At", (r) => time(r.at)],
                ["Actor", (r) => <code>{txt(r.actor)}</code>],
                ["Action", (r) => <code>{txt(r.action)}</code>],
                ["Ref", (r) => showRef(r.ref)],
                ["Detail", "detail"],
              ]}
            />
          </section>
        </div>
      )}

      {tab === "Partner" && (
        <div className={s.stack}>
          <section className="panel">
            <h2>Partner orders</h2>
            {problem("orders")}
            <Table
              rows={rows("orders")}
              idKey="orderId"
              cols={[
                ["Order", (r) => showRef(r.orderId)],
                ["App", (r) => <code>{txt(r.appId)}</code>],
                ["Item", (r) => `${txt(r.title)}`],
                ["Amount", (r) => money(r.amount, r.currency)],
                ["Status", (r) => statusPill(r.status)],
                ["Payment", (r) => showRef(r.paymentRef)],
                ["Refunded", (r) => (num(r.refundedAmount) ? money(r.refundedAmount, r.currency) : "–")],
                [
                  "Ticket",
                  (r) => {
                    const t = r.ticket as { code?: string; seat?: string } | null;
                    return t ? `${t.code} · ${t.seat}` : "–";
                  },
                ],
                ["Created", (r) => time(r.createdAt)],
                [
                  "Refund",
                  (r) => {
                    const left = num(r.amount) - num(r.refundedAmount);
                    if (!(r.status === "DELIVERED" || r.status === "PARTIALLY_REFUNDED") || left <= 0) return "–";
                    const id = String(r.orderId);
                    const url = `${PARTNER}/api/v1/admin/orders/${id}/refund`;
                    return (
                      <div className={s.inline}>
                        {left > 1 && (
                          <button className="btn" onClick={() => act(`Refund 1.00 of ${id}`, () => api(url, { body: { amount: 1 } }))}>
                            1.00
                          </button>
                        )}
                        <button className="btn" onClick={() => act(`Refund rest of ${id}`, () => api(url, { body: {} }))}>
                          Rest
                        </button>
                      </div>
                    );
                  },
                ],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Partner refunds</h2>
            {problem("partnerRefunds")}
            <Table
              rows={rows("partnerRefunds")}
              idKey="refundId"
              cols={[
                ["Refund", (r) => showRef(r.refundId)],
                ["Order", (r) => showRef(r.orderId)],
                ["Payment", (r) => showRef(r.paymentRef)],
                ["Amount", (r) => money(r.amount, r.currency)],
                ["Status", (r) => statusPill(r.status)],
                ["Created", (r) => time(r.createdAt)],
                ["Updated", (r) => time(r.updatedAt)],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Received payment notifications</h2>
            {problem("partnerNotifications")}
            <Table
              rows={rows("partnerNotifications")}
              idKey="id"
              cols={[
                ["#", "id"],
                ["Event", (r) => <code>{txt(r.eventId)}</code>],
                ["Payment", (r) => showRef(r.paymentRef)],
                ["Status", (r) => statusPill(r.status)],
                ["Received", (r) => time(r.receivedAt)],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Partner users</h2>
            {problem("partnerUsers")}
            <Table
              rows={rows("partnerUsers")}
              idKey="partnerUserId"
              cols={[
                ["Partner user", (r) => <code>{txt(r.partnerUserId)}</code>],
                ["App", (r) => <code>{txt(r.appId)}</code>],
                ["Bank user ref", (r) => <code>{txt(r.bankUserRef)}</code>],
                ["Name", "name"],
                ["Phone", "phone"],
                ["Created", (r) => time(r.createdAt)],
              ]}
            />
          </section>
        </div>
      )}

      {tab === "Bank" && (
        <div className={s.stack}>
          <section className="panel">
            <h2>Accounts</h2>
            {problem("accounts")}
            <Table
              rows={rows("accounts")}
              idKey="accountNo"
              cols={[
                ["Account", (r) => <code>{txt(r.accountNo)}</code>],
                ["Name", "name"],
                ["Customer", "customerId"],
                ["Balance", (r) => <strong>{money(r.balance, r.currency)}</strong>],
                ["Held", (r) => (num(r.held) ? money(r.held, r.currency) : "–")],
                ["Available", (r) => money(r.available, r.currency)],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Customers</h2>
            {problem("customers")}
            <Table
              rows={rows("customers")}
              idKey="customerId"
              cols={[
                ["Customer", (r) => <code>{txt(r.customerId)}</code>],
                ["Username", "username"],
                ["Name", "name"],
                ["Segment", "segment"],
                ["Wrong PIN/OTP", "authFailures"],
                [
                  "Payment auth",
                  (r) =>
                    r.authLocked ? (
                      <>
                        {statusPill("LOCKED")}{" "}
                        <button
                          className="btn"
                          onClick={() =>
                            act(`Unlock ${String(r.customerId)}`, () =>
                              api(`${BANK}/api/v1/admin/customers/${encodeURIComponent(String(r.customerId))}/unlock`, { method: "POST" }),
                            )
                          }
                        >
                          Unlock
                        </button>
                      </>
                    ) : (
                      statusPill("OK")
                    ),
                ],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Holds</h2>
            {problem("holds")}
            <Table
              rows={rows("holds")}
              idKey="paymentRef"
              cols={[
                ["Payment", (r) => showRef(r.paymentRef)],
                ["Account", (r) => <code>{txt(r.accountNo)}</code>],
                ["Amount", (r) => money(r.amount, r.currency)],
                ["Status", (r) => statusPill(r.status)],
                ["Created", (r) => time(r.createdAt)],
                ["Updated", (r) => time(r.updatedAt)],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Transactions</h2>
            {problem("transactions")}
            <Table
              rows={rows("transactions")}
              idKey="txnId"
              cols={[
                ["Txn", (r) => <code>{txt(r.txnId)}</code>],
                ["Type", "type"],
                ["Payment", (r) => showRef(r.paymentRef)],
                ["Refund", (r) => (r.refundId ? showRef(r.refundId) : "–")],
                ["Debit", (r) => <code>{txt(r.debitAccount)}</code>],
                ["Credit", (r) => <code>{txt(r.creditAccount)}</code>],
                ["Amount", (r) => money(r.amount, r.currency)],
                ["Status", (r) => statusPill(r.status)],
                ["Reason", "reason"],
                ["At", (r) => time(r.createdAt)],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Push notifications (C10; OTP codes arrive here)</h2>
            {problem("bankNotifications")}
            <Table
              rows={rows("bankNotifications")}
              idKey="id"
              rowKind={(r) => (r.kind === "OTP" ? "otp" : undefined)}
              cols={[
                ["#", "id"],
                ["Customer", "customerId"],
                ["Kind", (r) => <code>{txt(r.kind)}</code>],
                ["Title", "title"],
                ["Body", "body"],
                ["Ref", (r) => showRef(r.ref)],
                ["At", (r) => time(r.createdAt)],
              ]}
            />
          </section>
        </div>
      )}

      {tab === "Mini App Admin" && (
        <div className={s.stack}>
          <div className={s.hint}>
            Mutating calls send <code>X-Staff-User: {staff}</code> (configuration needs <code>admin</code>) and are written
            to the audit log.
          </div>
          {staffError && <div className={s.offline}>{staffError}</div>}
          <section className="panel">
            <h2>Mini Apps</h2>
            {problem("miniApps")}
            <Table
              rows={rows("miniApps")}
              idKey="appId"
              cols={[
                [
                  "App",
                  (r) => (
                    <span>
                      {txt(r.icon)} {txt(r.name)} <code>{txt(r.appId)}</code>
                    </span>
                  ),
                ],
                ["Type", (r) => <span className={s.flowChip} data-flow={String(r.type)}>{txt(r.type)}</span>],
                [
                  "Enabled",
                  (r) => (
                    <label className={s.check}>
                      <input
                        type="checkbox"
                        checked={!!r.enabled}
                        onChange={(e) =>
                          staffCall(`${String(r.appId)} enabled=${e.target.checked}`, `${PORTAL}/api/v1/admin/mini-apps/${String(r.appId)}`, "PUT", {
                            enabled: e.target.checked,
                          })
                        }
                      />
                      {r.enabled ? "on" : "off"}
                    </label>
                  ),
                ],
                [
                  "SDK kill switch",
                  (r) =>
                    r.type === "sdk" ? (
                      <label className={s.check} title="sdkEnabled: off = MB App must not start the SDK">
                        <input
                          type="checkbox"
                          checked={!!r.sdkEnabled}
                          onChange={(e) =>
                            staffCall(
                              `${String(r.appId)} sdkEnabled=${e.target.checked}`,
                              `${PORTAL}/api/v1/admin/mini-apps/${String(r.appId)}`,
                              "PUT",
                              { sdkEnabled: e.target.checked },
                            )
                          }
                        />
                        SDK enabled
                      </label>
                    ) : (
                      "–"
                    ),
                ],
                [
                  "Consent",
                  (r) => (
                    <div className={s.inline}>
                      <code>v{txt(r.consentVersion)}</code>
                      <button
                        className="btn"
                        onClick={() =>
                          staffCall(
                            `Bump consent version of ${String(r.appId)}`,
                            `${PORTAL}/api/v1/admin/mini-apps/${String(r.appId)}/consent-version`,
                            "POST",
                          )
                        }
                      >
                        Bump consent version
                      </button>
                    </div>
                  ),
                ],
                ["Scopes", "scopes"],
                [
                  "Rules",
                  (r) =>
                    [r.segments ? `segments ${String(r.segments)}` : "", r.minAppVersion ? `app ≥ ${String(r.minAppVersion)}` : ""]
                      .filter(Boolean)
                      .join(" · ") || "–",
                ],
                [
                  "Launch",
                  (r) => (
                    <code className={s.small}>
                      {r.type === "webview"
                        ? txt(r.entryUrl)
                        : r.type === "sdk"
                          ? `${String(r.sdkModule)} ≥ ${String(r.minSdkVersion)}`
                          : r.type === "a2a"
                            ? txt(r.appLink)
                            : "native screens"}
                    </code>
                  ),
                ],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Partner registry</h2>
            {problem("partners")}
            <Table
              rows={rows("partners")}
              idKey="clientId"
              cols={[
                [
                  "Partner",
                  (r) => (
                    <span>
                      {txt(r.name)} <code>{txt(r.clientId)}</code>
                    </span>
                  ),
                ],
                [
                  "Allowed IPs (comma list)",
                  (r) => {
                    const id = String(r.clientId);
                    const current = Array.isArray(r.allowedIps) ? r.allowedIps.map(String).join(", ") : String(r.allowedIps ?? "");
                    const draft = ipDrafts[id] ?? current;
                    const save = (value: string) =>
                      staffCall(`Partner ${id} allowedIps`, `${PORTAL}/api/v1/admin/partners/${id}`, "PUT", {
                        allowedIps: value
                          .split(",")
                          .map((x) => x.trim())
                          .filter(Boolean),
                      }).then((res) => {
                        if (res !== undefined)
                          setIpDrafts((d) => {
                            const n = { ...d };
                            delete n[id];
                            return n;
                          });
                      });
                    return (
                      <div className={s.ipEdit}>
                        <input
                          className="input"
                          value={draft}
                          onChange={(e) => setIpDrafts((d) => ({ ...d, [id]: e.target.value }))}
                        />
                        <div className={s.inline}>
                          <button className="btn" disabled={draft === current} onClick={() => void save(draft)}>
                            Save
                          </button>
                          <button className="btn" disabled={current === SEED_IPS} onClick={() => void save(SEED_IPS)}>
                            Restore seed
                          </button>
                          <button
                            className="btn"
                            title="A range that excludes the partner → IP_NOT_ALLOWED"
                            onClick={() => setIpDrafts((d) => ({ ...d, [id]: "203.0.113.0/24" }))}
                          >
                            Narrow
                          </button>
                        </div>
                      </div>
                    );
                  },
                ],
                [
                  "API breaker",
                  (r) => {
                    const b = (r.breaker ?? {}) as Record<string, unknown>;
                    const { open, failures, ...rest } = b;
                    return (
                      <span>
                        {statusPill(open ? "OPEN" : "CLOSED")} failures {txt(failures)}
                        {Object.keys(rest).length > 0 && <code className={s.small}> {JSON.stringify(rest)}</code>}
                      </span>
                    );
                  },
                ],
                ["Cert", (r) => <code className={s.small}>{txt(r.certFingerprint)}</code>],
                [
                  "Registered URLs",
                  (r) => (
                    <div className={s.urls}>
                      <code>register {txt(r.registerUserUrl)}</code>
                      <code>notify {txt(r.notifyUrl)}</code>
                      <code>api {txt(r.apiBaseUrl)}</code>
                      <code>orders {txt(r.createOrderUrl)}</code>
                    </div>
                  ),
                ],
                ["Merchant account", (r) => <code>{txt(r.merchantAccount)}</code>],
              ]}
            />
          </section>
          <section className="panel">
            <h2>Action log</h2>
            <ActionLog log={actionLog} />
          </section>
        </div>
      )}

      {tab === "Actions" && (
        <div className={s.grid2}>
          <section className="panel">
            <h2>Bank chaos</h2>
            {problem("bankChaos")}
            {bankChaos &&
              Object.keys(bankChaos).map((k) => (
                <label key={k} className={s.toggle}>
                  <input
                    type="checkbox"
                    checked={!!bankChaos[k]}
                    onChange={(e) =>
                      act(`Bank chaos ${k}=${e.target.checked}`, () =>
                        api(`${BANK}/api/v1/admin/chaos`, { method: "PUT", body: { [k]: e.target.checked } }),
                      )
                    }
                  />
                  <span>
                    <code>{k}</code> {BANK_CHAOS_HELP[k] ?? ""}
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
                    <code>notifyFailCount</code> answer the next N payment notifications with HTTP 500 (2 → retry topics,
                    4 → dead-letter + ops alert). Now <b>{String(partnerChaos.notifyFailCount ?? 0)}</b>
                  </span>
                </div>
                <div className={s.toolbar}>
                  <input
                    className="input"
                    type="number"
                    min={0}
                    style={{ flex: "0 0 90px" }}
                    value={failCountDraft ?? String(partnerChaos.notifyFailCount ?? 0)}
                    onChange={(e) => setFailCountDraft(e.target.value)}
                  />
                  <button
                    className="btn"
                    onClick={() =>
                      act("Partner chaos notifyFailCount", () =>
                        api(`${PARTNER}/api/v1/admin/chaos`, {
                          method: "PUT",
                          body: { notifyFailCount: Math.max(0, Math.floor(Number(failCountDraft ?? partnerChaos.notifyFailCount ?? 0)) || 0) },
                        }),
                      ).then(() => setFailCountDraft(null))
                    }
                  >
                    Apply
                  </button>
                </div>
                {Object.keys(partnerChaos)
                  .filter((k) => typeof partnerChaos[k] === "boolean")
                  .map((k) => (
                    <label key={k} className={s.toggle}>
                      <input
                        type="checkbox"
                        checked={!!partnerChaos[k]}
                        onChange={(e) =>
                          act(`Partner chaos ${k}=${e.target.checked}`, () =>
                            api(`${PARTNER}/api/v1/admin/chaos`, { method: "PUT", body: { [k]: e.target.checked } }),
                          )
                        }
                      />
                      <span>
                        <code>{k}</code> {PARTNER_CHAOS_HELP[k] ?? ""}
                      </span>
                    </label>
                  ))}
              </>
            )}
          </section>

          <section className="panel">
            <h2>Jobs (also run on their own every 10 s)</h2>
            <div className={s.buttons}>
              <button className="btn" onClick={() => runJob("Run recovery", `${PORTAL}/api/v1/admin/jobs/recovery`)}>
                Run recovery
              </button>
              <button className="btn" onClick={() => runJob("Run expiry", `${PORTAL}/api/v1/admin/jobs/expiry`, {})}>
                Run expiry
              </button>
              <button
                className="btn"
                title="Expires every PENDING / INITIATED payment now (releases holds)"
                onClick={() => runJob("Expire all open payments", `${PORTAL}/api/v1/admin/jobs/expiry`, { force: true })}
              >
                Expire all open payments
              </button>
              <button
                className="btn"
                onClick={() => runJob("Run refund recovery", `${PORTAL}/api/v1/admin/jobs/refund-recovery`)}
              >
                Run refund recovery
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
            </div>
            <div className={s.buttons} style={{ marginTop: 10 }}>
              <button className="btn" onClick={clearTrace}>
                Clear all traces
              </button>
              <button className="btn danger" onClick={resetAll}>
                Reset all data
              </button>
            </div>
            {jobResult && (
              <div className={s.result}>
                <h3>{jobResult.label}</h3>
                <pre>{JSON.stringify(jobResult.data, null, 2)}</pre>
              </div>
            )}
            {settlement != null && (
              <div className={s.result}>
                <h3>Last settlement result</h3>
                {(() => {
                  const r = settlement as {
                    date?: string;
                    entries?: number;
                    matched?: number;
                    fixed?: number;
                    mismatches?: unknown[];
                  };
                  const bad = Array.isArray(r.mismatches) && r.mismatches.length > 0;
                  return (
                    <>
                      <p>
                        {r.date ?? ""} {statusPill(bad ? "MISMATCH" : "MATCHED")} {r.matched ?? "?"}/{r.entries ?? "?"} matched
                        {r.fixed ? `, ${r.fixed} fixed` : ""}
                      </p>
                      {bad && (
                        <ul className={s.mismatches}>
                          {r.mismatches!.map((m, i) => (
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
            <ActionLog log={actionLog} />
          </section>
        </div>
      )}

      {tab === "Scenarios" && <Scenarios />}
    </main>
  );
}
