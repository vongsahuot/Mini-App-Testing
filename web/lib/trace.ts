import { PORTAL } from "./api";
import flowData from "./flow-data.json";

/** Lanes of the sequence diagrams (docs/CONTRACT.md, "Lanes and where they run"). */
export type Actor = "U" | "APP" | "PC" | "SDK" | "PA" | "PS" | "PG" | "MBE" | "MAS" | "KF" | "LG" | "CB";
/** Integration types; also the `flow` of type-specific trace steps. */
export type Flow = "webview" | "api" | "sdk" | "a2a";

export interface StepDef {
  n: string;
  label: string;
  actor: Actor;
  from?: Actor;
  to?: Actor;
  reply?: boolean;
  note?: boolean;
}
export interface PhaseDef {
  sec: number;
  title: string;
  steps: StepDef[];
}
interface FlowData {
  lanes: Record<Actor, string>;
  types: { id: Flow; name: string }[];
  phases: Record<string, PhaseDef>;
  flows: Record<Flow, { sections: { n: number; title: string }[]; phases: string[] }>;
}

/** Step catalog generated from "Mini App Payment Flow.html" by scripts/gen-flow.mjs. */
export const FLOW = flowData as unknown as FlowData;

export const ACTOR_LABEL: Record<Actor, string> = FLOW.lanes;

/** Phases that are identical in every integration type. */
export const SHARED_PHASES = new Set(
  Object.keys(FLOW.phases).filter((k) => FLOW.types.every((t) => FLOW.flows[t.id].phases.includes(k))),
);

export const stepKey = (sec: number, n: string) => `${sec}:${n}`;

/** Label of a step in the diagram of one type (labels of the same key can differ between types). */
export function stepLabel(key: string, flow?: Flow | null): string | undefined {
  const order: Flow[] = flow ? [flow, ...FLOW.types.map((t) => t.id).filter((t) => t !== flow)] : FLOW.types.map((t) => t.id);
  for (const t of order) {
    for (const k of FLOW.flows[t].phases) {
      const p = FLOW.phases[k];
      const s = p.steps.find((x) => stepKey(p.sec, x.n) === key);
      if (s) return s.label;
    }
  }
  return undefined;
}

/**
 * Fire-and-forget trace report for the client lanes (APP, U, PC, SDK, PA); backends record their own steps.
 * The title defaults to the step's label in the diagram.
 */
export function trace(
  actor: Actor,
  step: string | null,
  flow: Flow | null,
  detail?: string | null,
  ref?: string | null,
  title?: string,
): void {
  try {
    fetch(`${PORTAL}/api/v1/admin/trace`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        actor,
        step,
        flow,
        title: title ?? (step ? (stepLabel(step, flow) ?? step) : "note"),
        detail: detail ?? null,
        ref: ref ?? null,
      }),
      keepalive: true,
    }).catch(() => {});
  } catch {
    /* never break the flow because of tracing */
  }
}
