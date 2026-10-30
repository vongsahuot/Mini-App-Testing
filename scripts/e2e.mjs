// End-to-end check of the mock payment flow against the running docker stack.
// Plays the bank app (APP) and the partner page (PP) over HTTP, then asserts on every service.
// Usage: node scripts/e2e.mjs   (stack must be up: docker compose up -d)
import { createHmac, randomUUID } from "node:crypto";

const PORTAL = process.env.PORTAL_URL ?? "http://localhost:8081";
const BANK = process.env.BANK_URL ?? "http://localhost:8082";
const PARTNER = process.env.PARTNER_URL ?? "http://localhost:8083";
const ORIGIN = "http://localhost:3000";

let failures = 0;
const ok = (cond, msg) => { console.log(`${cond ? "  ✓" : "  ✗"} ${msg}`); if (!cond) failures++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function call(method, url, { body, token, headers = {} } = {}) {
  const res = await fetch(url, {
    method,
    headers: { ...(body ? { "Content-Type": "application/json" } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json; try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
  return { status: res.status, body: json };
}
const must = async (p, what) => { const r = await p; if (r.status >= 300) throw new Error(`${what} → ${r.status} ${JSON.stringify(r.body)}`); return r.body; };
const put = (url, body) => call("PUT", url, { body });

async function waitFor(fn, what, timeoutMs = 20000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) { const v = await fn(); if (v) return v; await sleep(500); }
  throw new Error(`timed out waiting for ${what}`);
}

// ---- bank app helpers
async function bankLogin(username) {
  return must(call("POST", `${BANK}/api/v1/bank-app/login`, { body: { username, pin: "1234", deviceId: randomUUID() } }), "login");
}
const jwtFor = async s => (await must(call("POST", `${BANK}/api/v1/bank-app/jwt`, { token: s.sessionToken }), "jwt")).jwt;

async function openAndLogin(s) {
  const list = await must(call("GET", `${PORTAL}/api/v1/client/mini-apps`, { token: await jwtFor(s) }), "mini-apps");
  ok(list.sections.length === 2 && list.banners.length === 2, "mini apps list: 2 sections, 2 banners");
  const disabled = await call("POST", `${PORTAL}/api/v1/client/mini-apps/coffee/sessions`, { token: await jwtFor(s) });
  ok(disabled.status === 409 && disabled.body.code === "APP_DISABLED", "disabled app → 409 APP_DISABLED");
  const session = await must(call("POST", `${PORTAL}/api/v1/client/mini-apps/movie-tickets/sessions`, { token: await jwtFor(s) }), "session");
  ok(session.allowedOrigins.includes(ORIGIN), "session returns allowed origins");
  const noConsent = await call("POST", `${PORTAL}/api/v1/client/mini-apps/movie-tickets/auth-codes`,
    { token: await jwtFor(s), body: { sessionId: session.sessionId, scopes: ["profile", "phone"] } });
  ok(noConsent.status === 403 && noConsent.body.code === "CONSENT_REQUIRED", "auth-code without consent → 403 CONSENT_REQUIRED");
  const { authCode } = await must(call("POST", `${PORTAL}/api/v1/client/mini-apps/movie-tickets/auth-codes`,
    { token: await jwtFor(s), body: { sessionId: session.sessionId, scopes: ["profile", "phone"], consentGiven: true } }), "auth-code");
  const consent = await must(call("GET", `${PORTAL}/api/v1/client/mini-apps/movie-tickets/consents?scopes=profile,phone`, { token: await jwtFor(s) }), "consents");
  ok(consent.granted === true, "consent stored (sheet shown only first time)");
  const partner = await must(call("POST", `${PARTNER}/api/login`, { body: { authCode } }), "partner login");
  ok(partner.profile.name === s.name, `partner got consented profile (${partner.profile.name})`);
  const reuse = await call("POST", `${PARTNER}/api/login`, { body: { authCode } });
  ok(reuse.status === 400 && reuse.body.code === "CODE_ALREADY_USED", "authCode is one use");
  return partner.sessionToken;
}

async function buy(s, partnerToken, itemId, { approve = true, badSignature = false } = {}) {
  const order = await must(call("POST", `${PARTNER}/api/orders`, { token: partnerToken, body: { itemId } }), "create order");
  const pay = await call("GET", `${PORTAL}/api/v1/client/payments/${order.paymentId}`, { token: await jwtFor(s), headers: { "X-Page-Origin": ORIGIN } });
  if (pay.status !== 200) throw new Error(`payment sheet → ${pay.status} ${JSON.stringify(pay.body)}`);
  if (!approve) {
    return { order, result: await must(call("POST", `${PORTAL}/api/v1/client/payments/${order.paymentId}/cancel`, { token: await jwtFor(s) }), "cancel") };
  }
  const signature = createHmac("sha256", s.deviceSecret).update(badSignature ? "tampered" : order.paymentId).digest("hex");
  const result = await must(call("POST", `${BANK}/api/v1/bank-app/payments/${order.paymentId}/approve`,
    { token: s.sessionToken, body: { debitAccount: s.accounts[0].accountNo, signature } }), "approve");
  return { order, result };
}
const getOrder = (token, id) => must(call("GET", `${PARTNER}/api/orders/${id}`, { token }), "get order");
const adminOrder = async id => (await must(call("GET", `${PARTNER}/api/v1/admin/orders`), "orders")).find(o => o.orderId === id);
const portalPayment = async id => (await must(call("GET", `${PORTAL}/api/v1/admin/payments`), "payments")).find(p => p.paymentId === id);

async function main() {
  process.stdout.write("Waiting for services");
  await waitFor(async () => {
    const up = await Promise.all([PORTAL, BANK, PARTNER].map(u => fetch(`${u}/actuator/health`).then(r => r.ok, () => false)));
    process.stdout.write(".");
    return up.every(Boolean);
  }, "services to be healthy", 120000);
  console.log(" ready");
  await Promise.all([PORTAL, BANK, PARTNER].map(u => must(call("POST", `${u}/api/v1/admin/reset`), `reset ${u}`)));

  console.log("\n1. Happy path (alice buys m1 for 5.00)");
  const alice = await bankLogin("alice");
  const aliceToken = await openAndLogin(alice);
  const happy = await buy(alice, aliceToken, "m1");
  ok(happy.result.status === "PAID" && happy.result.bankTxnId, `bank approved: ${happy.result.bankTxnId}`);
  const delivered = await waitFor(async () => { const o = await adminOrder(happy.order.orderId); return o.status === "DELIVERED" && o; }, "webhook delivery");
  ok(delivered.ticket?.code, `delivered by webhook, ticket ${delivered.ticket?.code} seat ${delivered.ticket?.seat}`);
  const dup = await call("POST", `${BANK}/api/v1/bank-app/payments/${happy.order.paymentId}/approve`,
    { token: alice.sessionToken, body: { debitAccount: alice.accounts[0].accountNo, signature: "x" } });
  ok(dup.body.status === "PAID" && dup.body.bankTxnId === happy.result.bankTxnId, "double approve is idempotent (no second debit)");

  console.log("\n2. Cancel");
  const cancelled = await buy(alice, aliceToken, "m2", { approve: false });
  ok(cancelled.result.status === "CANCELLED", "payment cancelled");
  ok((await getOrder(aliceToken, cancelled.order.orderId)).status === "CANCELLED", "partner order → CANCELLED via status check");

  console.log("\n3. Tampered device signature");
  const bad = await buy(alice, aliceToken, "m2", { badSignature: true });
  ok(bad.result.status === "FAILED" && bad.result.reason === "INVALID_SIGNATURE", "bank rejects bad signature");
  await waitFor(async () => (await adminOrder(bad.order.orderId)).status === "FAILED", "failed webhook");
  ok(true, "partner order → FAILED via payment.failed webhook");

  console.log("\n4. Insufficient funds (bob has 3.00, buys 25.00)");
  const bob = await bankLogin("bob");
  const bobToken = await openAndLogin(bob);
  const poor = await buy(bob, bobToken, "m3");
  ok(poor.result.status === "FAILED" && poor.result.reason === "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS");

  console.log("\n5. Wrong origin + wrong customer on payment sheet");
  const o5 = await must(call("POST", `${PARTNER}/api/orders`, { token: aliceToken, body: { itemId: "m2" } }), "order");
  const evil = await call("GET", `${PORTAL}/api/v1/client/payments/${o5.paymentId}`, { token: await jwtFor(alice), headers: { "X-Page-Origin": "https://evil.example" } });
  ok(evil.status === 403 && evil.body.code === "ORIGIN_MISMATCH", "foreign page origin → 403 ORIGIN_MISMATCH");
  const other = await call("GET", `${PORTAL}/api/v1/client/payments/${o5.paymentId}`, { token: await jwtFor(bob), headers: { "X-Page-Origin": ORIGIN } });
  ok(other.status === 403 && other.body.code === "WRONG_CUSTOMER", "another customer → 403 WRONG_CUSTOMER");
  const badJwt = await call("GET", `${PORTAL}/api/v1/client/mini-apps`, { token: "eyJhbGciOiJSUzI1NiJ9.e30.AAAA" });
  ok(badJwt.status === 401, "forged JWT → 401");

  console.log("\n6. Webhook retries (partner answers 500 twice)");
  await sleep(2000); // let earlier webhooks drain so they don't consume the injected failures
  await put(`${PARTNER}/api/v1/admin/chaos`, { webhookFailCount: 2 });
  const retry = await buy(alice, aliceToken, "m2");
  await waitFor(async () => (await adminOrder(retry.order.orderId)).status === "DELIVERED", "delivery after retries", 40000);
  const wh = (await must(call("GET", `${PORTAL}/api/v1/admin/webhooks`), "webhooks")).find(w => w.paymentId === retry.order.paymentId && w.type === "payment.paid");
  ok(wh.status === "DELIVERED" && wh.attempts === 3, `delivered on attempt ${wh.attempts} with backoff`);

  console.log("\n7. Webhook ignored → page status check delivers");
  await put(`${PARTNER}/api/v1/admin/chaos`, { ignoreWebhooks: true });
  const ignored = await buy(alice, aliceToken, "m2");
  await sleep(2500);
  ok((await adminOrder(ignored.order.orderId)).status === "PENDING_PAYMENT", "webhook acknowledged but ignored");
  ok((await getOrder(aliceToken, ignored.order.orderId)).status === "DELIVERED", "GET order → status check → DELIVERED");
  await put(`${PARTNER}/api/v1/admin/chaos`, { ignoreWebhooks: false });

  console.log("\n8. Lost bank result → recovery");
  await put(`${BANK}/api/v1/admin/chaos`, { dropResultCallback: true });
  const lost = await buy(alice, aliceToken, "m1");
  ok(lost.result.status === "PAID", "customer debited and sees receipt");
  ok((await portalPayment(lost.order.paymentId)).status === "PROCESSING", "KOSIGN still PROCESSING (result call dropped)");
  await put(`${BANK}/api/v1/admin/chaos`, { dropResultCallback: false });
  await must(call("POST", `${PORTAL}/api/v1/admin/jobs/recovery`), "recovery");
  ok((await portalPayment(lost.order.paymentId)).status === "PAID", "recovery asked bank → PAID");
  await waitFor(async () => (await adminOrder(lost.order.orderId)).status === "DELIVERED", "delivery after recovery");
  ok(true, "ticket delivered after recovery");

  console.log("\n9. Refunds (partial 1.00 then full)");
  const partial = await must(call("POST", `${PARTNER}/api/orders/${happy.order.orderId}/refund`, { token: aliceToken, body: { amount: 1.0 } }), "partial refund");
  ok(partial.status === "PARTIALLY_REFUNDED" && Number(partial.refundedAmount) === 1, "partial refund 1.00");
  const full = await must(call("POST", `${PARTNER}/api/orders/${happy.order.orderId}/refund`, { token: aliceToken, body: {} }), "full refund");
  ok(full.status === "REFUNDED" && Number(full.refundedAmount) === 5, "remaining 4.00 refunded");
  const over = await call("POST", `${PARTNER}/api/orders/${happy.order.orderId}/refund`, { token: aliceToken, body: { amount: 1 } });
  ok(over.status === 409, "refund beyond paid amount → 409");

  console.log("\n10. Daily settlement");
  const rec = await must(call("POST", `${BANK}/api/v1/admin/settlement/run`), "settlement");
  ok(rec.mismatches.length === 0 && rec.matched > 0, `reconciled: ${rec.matched} matched, ${rec.mismatches.length} mismatches`);
  await put(`${BANK}/api/v1/admin/chaos`, { settlementMismatch: true });
  const rec2 = await must(call("POST", `${BANK}/api/v1/admin/settlement/run`), "settlement 2");
  ok(rec2.mismatches.length === 1, `mismatch detected: ${rec2.mismatches[0]}`);
  await put(`${BANK}/api/v1/admin/chaos`, { settlementMismatch: false });
  await sleep(2500);

  console.log("\nCoverage (server-side steps; APP/PP steps come from the app, simulator or partner page)");
  const traces = (await Promise.all([PORTAL, BANK, PARTNER].map(u => must(call("GET", `${u}/api/v1/admin/trace`), "trace")))).flat();
  const seen = new Set(traces.map(t => t.step).filter(Boolean));
  const serverSteps = [2,3,7,13,14,18,22,23,27,31,35,36,39,42,43,44,45,49,52,55,56,59,34,37,38,41,57,58,17,19,21,24,46,47,48,51,53,60];
  const missing = serverSteps.filter(n => !seen.has(n));
  ok(missing.length === 0, `server steps observed: ${serverSteps.length - missing.length}/${serverSteps.length}${missing.length ? " missing " + missing : ""}`);

  console.log(failures ? `\n${failures} check(s) FAILED` : "\nAll checks passed");
  process.exit(failures ? 1 : 0);
}

main().catch(e => { console.error("\nFATAL:", e.message); process.exit(1); });
