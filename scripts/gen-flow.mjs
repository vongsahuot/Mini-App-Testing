// Extracts the step catalog (LANES, TYPES, LIB, FLOWS) from "Mini App Payment Flow.html" into
// web/lib/flow-data.json, so the test console's coverage always matches the diagram.
// Usage: node scripts/gen-flow.mjs
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const html = readFileSync(root + "Mini App Payment Flow.html", "utf8");
const grab = name => {
  const m = html.match(new RegExp(`^const ${name} = (.*);$`, "m"));
  if (!m) throw new Error(`const ${name} not found`);
  return JSON.parse(m[1]);
};
const LANES = grab("LANES"), TYPES = grab("TYPES"), LIB = grab("LIB"), FLOWS = grab("FLOWS");

// Keep only what the console needs: per phase, its steps with the lane that records them (the receiver).
const phases = {};
for (const [key, p] of Object.entries(LIB)) {
  const steps = [];
  for (const it of p.items) {
    if (it.type === "msg") steps.push({ n: String(it.n), label: it.label, from: it.f, to: it.t, reply: !!it.reply, actor: it.t });
    else if (it.type === "note") steps.push({ n: String(it.n), label: it.label, actor: it.at, note: true });
  }
  phases[key] = { sec: p.sec, title: p.title, steps };
}
const flows = Object.fromEntries(Object.entries(FLOWS).map(([t, f]) => [t, { sections: f.sections.map(s => ({ n: s.n, title: s.title })), phases: f.phases }]));
const lanes = Object.fromEntries(Object.entries(LANES).map(([k, v]) => [k, v[0]]));
writeFileSync(root + "web/lib/flow-data.json", JSON.stringify({ lanes, types: TYPES, phases, flows }, null, 1) + "\n");
const count = t => FLOWS[t].phases.reduce((n, k) => n + phases[k].steps.length, 0);
console.log("wrote web/lib/flow-data.json:", TYPES.map(t => `${t.id} ${count(t.id)} steps`).join(", "));
