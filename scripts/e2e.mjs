// End-to-end check of the mock Mini App payment flow against the running docker stack.
// Plays MB App, the partner page / SDK / partner app over HTTP for all four integration types, then asserts on
// every service. Usage: node scripts/e2e.mjs   (stack must be up: docker compose up -d)
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

const PORTAL = process.env.PORTAL_URL ?? "http://localhost:8081";
const BANK = process.env.BANK_URL ?? "http://localhost:8082";
const PARTNER = process.env.PARTNER_URL ?? "http://localhost:8083";
const GW = `${BANK}/gw/app/v1`;
const STAFF = { "X-Staff-User": "admin" };

let failures = 0;
const ok = (cond, msg) => { console.log(`${cond ? "  ✓" : "  ✗"} ${msg}`); if (!cond) failures++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function call(method, url, { body, token, headers = {}, cookie } = {}) {
  const res = await fetch(url, {
    method,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(cookie ? { Cookie: cookie } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json; try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
  return { status: res.status, body: json, headers: res.headers };
}
const must = async (p, what) => { const r = await p; if (r.status >= 300) throw new Error(`${what} → ${r.status} ${JSON.stringify(r.body)}`); return r.body; };
const put = (url, body, headers) => call("PUT", url, { body, headers });
const post = (url, body, headers) => call("POST", url, { body, headers });

async function waitFor(fn, what, timeoutMs = 25000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) { const v = await fn(); if (v) return v; await sleep(500); }
  throw new Error(`timed out waiting for ${what}`);
}

// ---------- MB App (app gateway) ----------
const APP_HEADERS = { "X-App-Version": "5.2.0", "X-Platform": "web" };
async function login(username) {
  const s = await must(post(`${GW}/login`, { username, pin: "1234", deviceId: randomUUID() }), "login");
  return { ...s, gw: (method, path, body, headers = {}) => call(method, `${GW}${path}`, { token: s.sessionToken, body, headers: { ...APP_HEADERS, ...headers } }) };
}
async function open(s, appId) {
  const d = await must(s.gw("GET", `/mini-apps/${appId}`), `detail ${appId}`);
  if (d.consent.status !== "ACCEPTED") await must(s.gw("POST", `/mini-apps/${appId}/consent`, { consentVersion: d.consent.currentVersion }), `consent ${appId}`);
  return d;
}
async function pay(s, paymentRef, { pin = "1234", otp } = {}) {
  const init = await s.gw("POST", `/payments/${paymentRef}/init`, { account: s.accounts[0].accountNo });
  if (init.status >= 300) return { init };
  let code = otp;
  if (init.body.authMethod === "OTP" && !code) code = await latestOtp(s, paymentRef);
  const confirm = await s.gw("POST", `/payments/${paymentRef}/confirm`, init.body.authMethod === "OTP" ? { otp: code } : { pin });
  return { init, confirm };
}
async function latestOtp(s, paymentRef) {
  const list = await must(s.gw("GET", "/notifications?after=0"), "notifications");
  const n = [...list].reverse().find(x => x.kind === "OTP" && x.ref === paymentRef);
  return n?.body.match(/OTP (\d{6})/)?.[1];
}

// ---------- partner clients ----------
async function partnerLogin(kind, authCode, codeVerifier) {
  const r = await call("POST", `${PARTNER}/api/login`, { body: { authCode, codeVerifier }, headers: { "X-Client-Kind": kind } });
  const cookie = r.headers.get("set-cookie")?.split(";")[0];
  return { ...r, cookie, token: r.body.sessionToken };
}
const papi = (sess, method, path, body) => call(method, `${PARTNER}/api${path}`, { body, token: sess.token, cookie: sess.cookie });
const pkce = () => { const verifier = randomBytes(32).toString("base64url"); return { verifier, challenge: createHash("sha256").update(verifier).digest("base64url") }; };

async function signIn(s, appId, kind) {
  await open(s, appId);
  const pk = kind === "a2a" ? pkce() : null;
  const { authCode } = await must(s.gw("POST", `/mini-apps/${appId}/auth-code`, pk ? { codeChallenge: pk.challenge, codeChallengeMethod: "S256" } : {}), "auth-code");
  const sess = await partnerLogin(kind, authCode, pk?.verifier);
  if (sess.status >= 300) throw new Error(`partner login → ${sess.status} ${JSON.stringify(sess.body)}`);
  return sess;
}
async function buyWebOrSdk(s, sess, appId, itemId) {
  const sealed = await must(papi(sess, "POST", "/orders", { itemId }), "partner order");
  const info = await must(s.gw("POST", "/payments/verify", { appId, order: sealed.order, checksum: sealed.checksum }), "verify");
  return { sealed, info };
}

// ---------- admin ----------
const adminOrder = async id => (await must(call("GET", `${PARTNER}/api/v1/admin/orders`), "orders")).find(o => o.orderId === id);
const portalPayment = async ref => (await must(call("GET", `${PORTAL}/api/v1/admin/payments`), "payments")).find(p => p.paymentRef === ref);
const account = async no => (await must(call("GET", `${BANK}/api/v1/admin/accounts`), "accounts")).find(a => a.accountNo === no);
const chaos = (svc, body) => put(`${svc}/api/v1/admin/chaos`, body);
const delivered = (orderId, ms) => waitFor(async () => { const o = await adminOrder(orderId); return o?.status === "DELIVERED" && o; }, `order ${orderId} delivered`, ms);

async function main() {
  process.stdout.write("Waiting for services");
  await waitFor(async () => {
    const up = await Promise.all([PORTAL, BANK, PARTNER].map(u => fetch(`${u}/actuator/health`).then(r => r.ok, () => false)));
    process.stdout.write(".");
    return up.every(Boolean);
  }, "services to be healthy", 180000);
  console.log(" ready");
  await Promise.all([PORTAL, BANK, PARTNER].map(u => must(post(`${u}/api/v1/admin/reset`), `reset ${u}`)));

  console.log("\n1. Mini App list (eligibility, version, ETag)");
  const alice = await login("alice"), bob = await login("bob");
  const list = await alice.gw("GET", "/mini-apps");
  const ids = list.body.sections.flatMap(x => x.apps.map(a => a.appId));
  ok(["starlight-web", "starlight-native", "starlight-sdk", "starlight-app", "vip-screening"].every(i => ids.includes(i)), `alice sees ${ids.length} apps incl. VIP`);
  ok(!ids.includes("coffee") && !ids.includes("taxi"), "disabled (coffee) and too-new (taxi) apps are filtered out");
  const bobIds = (await bob.gw("GET", "/mini-apps")).body.sections.flatMap(x => x.apps.map(a => a.appId));
  ok(!bobIds.includes("vip-screening"), "bob (MASS segment) does not see VIP screening");
  const again = await alice.gw("GET", "/mini-apps", undefined, { "If-None-Match": list.headers.get("etag") });
  ok(again.status === 304, "same ETag → 304 Not Modified");
  const coffee = await alice.gw("GET", "/mini-apps/coffee");
  ok(coffee.status === 409 && coffee.body.code === "APP_DISABLED", "tapping a disabled app → 409 APP_DISABLED");

  console.log("\n2. Link WebView: consent, registration, login(), checksum, PIN");
  const d0 = await must(alice.gw("GET", "/mini-apps/starlight-web"), "detail");
  ok(d0.consent.status === "NOT_ACCEPTED" && d0.launch.allowedOrigins.includes("http://localhost:3000"), "detail: consent needed, entryUrl + allowedOrigins");
  const c0 = await must(alice.gw("POST", "/mini-apps/starlight-web/consent", { consentVersion: "1" }), "consent");
  ok(c0.status === "ACCEPTED" && c0.registered, "consent saved and customer registered with the partner");
  const { authCode } = await must(alice.gw("POST", "/mini-apps/starlight-web/auth-code", {}), "auth-code");
  const web = await partnerLogin("webview", authCode);
  ok(web.status === 200 && web.cookie?.startsWith("starlight_session=") && !web.body.sessionToken, "partner session is an HttpOnly cookie (no token in the body)");
  ok(web.body.profile.name === "Alice Sok", "partner got only the consented info (name, phone)");
  const reuse = await partnerLogin("webview", authCode);
  ok(reuse.status === 400 && reuse.body.code === "invalid_grant", "authCode is one use → invalid_grant");
  const w = await buyWebOrSdk(alice, web, "starlight-web", "m1");
  ok(w.info.paymentRef && w.info.merchantName === "Starlight Cinema" && w.info.amount === 5, `verify → PENDING ${w.info.paymentRef}, merchant from registry`);
  const sameAgain = await must(alice.gw("POST", "/payments/verify", { appId: "starlight-web", order: w.sealed.order, checksum: w.sealed.checksum }), "verify again");
  ok(sameAgain.paymentRef === w.info.paymentRef, "same PENDING order → same paymentRef");
  const tampered = await alice.gw("POST", "/payments/verify", { appId: "starlight-web", order: { ...w.sealed.order, amount: 0.01 }, checksum: w.sealed.checksum });
  ok(tampered.status === 400 && tampered.body.code === "BAD_CHECKSUM", "changed amount → BAD_CHECKSUM");
  const otherApp = await alice.gw("POST", "/payments/verify", { appId: "starlight-sdk", order: w.sealed.order, checksum: w.sealed.checksum });
  ok(otherApp.status >= 400 && otherApp.body.code === "APP_MISMATCH", "order for another appId → APP_MISMATCH");
  const init = await must(alice.gw("POST", `/payments/${w.info.paymentRef}/init`, { account: alice.accounts[0].accountNo }), "init");
  ok(init.status === "INITIATED" && init.authMethod === "PIN", "init holds funds → INITIATED, auth method PIN");
  ok(Number((await account("001-100-1001")).held) === 5, "5.00 on hold");
  const wrong = await alice.gw("POST", `/payments/${w.info.paymentRef}/confirm`, { pin: "0000" });
  ok(wrong.status === 401 && wrong.body.attemptsLeft === 2, "wrong PIN → 401 AUTH_FAILED, 2 attempts left");
  const conf = await must(alice.gw("POST", `/payments/${w.info.paymentRef}/confirm`, { pin: "1234" }), "confirm");
  ok(conf.status === "SUCCESS" && conf.bankTxnId, `confirm → SUCCESS ${conf.bankTxnId}`);
  const dup = await must(alice.gw("POST", `/payments/${w.info.paymentRef}/confirm`, { pin: "1234" }), "confirm again");
  ok(dup.status === "SUCCESS" && dup.bankTxnId === conf.bankTxnId, "repeated confirm returns the same result (no second debit)");
  const st = await must(alice.gw("GET", `/payments/${w.info.paymentRef}/status`), "status");
  ok(st.status === "SUCCESS", "Get Payment Status (used after a lost confirm answer) → SUCCESS");
  const ticket = await delivered(w.sealed.order.orderId);
  ok(ticket.ticket?.code, `signed notification delivered the ticket ${ticket.ticket?.code}`);
  ok(Number((await account("001-100-1001")).balance) === 495 && Number((await account("999-STARLIGHT-01")).balance) === 5, "alice debited 5.00, merchant credited");
  const push = await must(alice.gw("GET", "/notifications?after=0"), "notifications");
  ok(push.some(n => n.kind === "PAYMENT" && n.ref === w.info.paymentRef), "push notification sent to the customer");

  console.log("\n3. OTP for amounts ≥ 20.00, and insufficient funds");
  const imax = await buyWebOrSdk(alice, web, "starlight-web", "m3");
  const big = await pay(alice, imax.info.paymentRef);
  ok(big.init.body.authMethod === "OTP" && big.confirm.body.status === "SUCCESS", "25.00 needs an OTP (delivered by push) → SUCCESS");
  const bobWeb = await signIn(bob, "starlight-web", "webview");
  const poor = await buyWebOrSdk(bob, bobWeb, "starlight-web", "m3");
  const poorPay = await pay(bob, poor.info.paymentRef);
  ok(poorPay.init.status === 422 && poorPay.init.body.code === "INSUFFICIENT_FUNDS", "bob (3.00) → 422 INSUFFICIENT_FUNDS before anything is held");
  const stolen = await bob.gw("POST", "/payments/verify", { appId: "starlight-web", order: w.sealed.order, checksum: w.sealed.checksum });
  ok(stolen.status >= 400 && ["WRONG_CUSTOMER", "ORDER_USED"].includes(stolen.body.code), `another customer cannot pay alice's order (${stolen.body.code})`);

  console.log("\n4. SDK embedding");
  const sdkDetail = await open(alice, "starlight-sdk");
  ok(sdkDetail.launch.sdkModule === "starlight-sdk" && sdkDetail.launch.sdkEnabled, "detail: sdkModule, minSdkVersion, kill switch on");
  const sdk = await signIn(alice, "starlight-sdk", "sdk");
  ok(sdk.token && !sdk.cookie, "SDK session is a bearer token (kept in SDK memory)");
  const s = await buyWebOrSdk(alice, sdk, "starlight-sdk", "m2");
  const sPay = await pay(alice, s.info.paymentRef);
  ok(sPay.confirm.body.status === "SUCCESS", "SDK order paid");
  await delivered(s.sealed.order.orderId);
  ok(true, "SDK order delivered");
  await must(put(`${PORTAL}/api/v1/admin/mini-apps/starlight-sdk`, { sdkEnabled: false }, STAFF), "kill switch");
  ok((await must(alice.gw("GET", "/mini-apps/starlight-sdk"), "detail")).launch.sdkEnabled === false, "kill switch: detail says sdkEnabled=false");
  await must(put(`${PORTAL}/api/v1/admin/mini-apps/starlight-sdk`, { sdkEnabled: true }, STAFF), "kill switch off");

  console.log("\n5. API linkage (native screens, no partner code on the device)");
  await open(alice, "starlight-native");
  const cat = await must(alice.gw("POST", "/mini-apps/starlight-native/content", { operation: "catalog", params: {} }), "content");
  ok(cat.data.length === 3 && !("internalCost" in cat.data[0]) && !String(cat.data[0].subtitle).includes("<"), "catalog: unknown fields dropped, markup stripped");
  const key = randomUUID();
  const apiPay = await must(alice.gw("POST", "/mini-apps/starlight-native/orders", { items: [{ itemId: "m2", qty: 1 }], idempotencyKey: key }), "create order");
  const apiPay2 = await must(alice.gw("POST", "/mini-apps/starlight-native/orders", { items: [{ itemId: "m2", qty: 1 }], idempotencyKey: key }), "create order again");
  ok(apiPay.paymentRef && apiPay2.paymentRef === apiPay.paymentRef, "bank calls partner Create Order; same idempotencyKey → same order and paymentRef");
  ok((await pay(alice, apiPay.paymentRef)).confirm.body.status === "SUCCESS", "API linkage order paid");
  await delivered(apiPay.orderId);
  const hist = await must(alice.gw("POST", "/mini-apps/starlight-native/content", { operation: "orders", params: {} }), "orders");
  ok(hist.data.some(o => o.orderId === apiPay.orderId && o.status === "DELIVERED"), "order history fetched from the partner shows DELIVERED");
  const badOp = await alice.gw("POST", "/mini-apps/starlight-native/content", { operation: "admin", params: {} });
  ok(badOp.status === 400 && badOp.body.code === "UNKNOWN_OPERATION", "unregistered operation → refused");
  await chaos(PARTNER, { apiFail: true });
  const cached = await must(alice.gw("POST", "/mini-apps/starlight-native/content", { operation: "catalog", params: {} }), "content (partner down)");
  ok(cached.cached === true, "partner down → cached catalog served");
  const histDown = await alice.gw("POST", "/mini-apps/starlight-native/content", { operation: "orders", params: {} });
  ok(histDown.status === 502 && histDown.body.code === "PARTNER_UNAVAILABLE", "partner down, nothing cached → PARTNER_UNAVAILABLE");
  await chaos(PARTNER, { apiFail: false, createOrderSlow: true });
  const slow = await alice.gw("POST", "/mini-apps/starlight-native/orders", { items: [{ itemId: "m1", qty: 1 }], idempotencyKey: randomUUID() });
  ok(slow.status === 200 && slow.body.paymentRef, "slow Create Order → bank retries once with the same idempotencyKey → one order");
  await chaos(PARTNER, { createOrderSlow: false, badChecksum: true });
  const badSeal = await alice.gw("POST", "/mini-apps/starlight-native/orders", { items: [{ itemId: "m1", qty: 1 }], idempotencyKey: randomUUID() });
  ok(badSeal.status === 502 && badSeal.body.code === "BAD_CHECKSUM", "partner order with a wrong checksum → no txn saved");
  await chaos(PARTNER, { badChecksum: false });

  console.log("\n6. App-to-App (state + PKCE, payLink with paymentRef only)");
  const a2aDetail = await open(alice, "starlight-app");
  ok(a2aDetail.launch.appLink.endsWith("/links/starlight/open") && a2aDetail.launch.returnLink, "detail: verified appLink, returnLink, storeUrl");
  const pk = pkce();
  const noPkce = await alice.gw("POST", "/mini-apps/starlight-app/auth-code", {});
  ok(noPkce.status === 400, "App-to-App auth code without a code challenge → refused");
  const a2aCode = (await must(alice.gw("POST", "/mini-apps/starlight-app/auth-code", { codeChallenge: pk.challenge, codeChallengeMethod: "S256" }), "auth-code")).authCode;
  const badVerifier = await partnerLogin("a2a", a2aCode, pkce().verifier);
  ok(badVerifier.status === 400 && badVerifier.body.code === "invalid_grant", "intercepted code with the wrong codeVerifier → invalid_grant");
  const a2aCode2 = (await must(alice.gw("POST", "/mini-apps/starlight-app/auth-code", { codeChallenge: pk.challenge, codeChallengeMethod: "S256" }), "auth-code")).authCode;
  const app = await partnerLogin("a2a", a2aCode2, pk.verifier);
  ok(app.status === 200 && app.token, "partner app linked (code + verifier + partner's mTLS + signature)");
  const a2aOrder = await must(papi(app, "POST", "/orders", { itemId: "m1" }), "partner app order");
  ok(a2aOrder.payLink === `http://localhost:3000/links/mbapp/pay?paymentRef=${a2aOrder.paymentRef}`, "partner server created the payment; payLink carries the paymentRef only");
  await chaos(PARTNER, { badChecksum: true });
  const badA2a = await papi(app, "POST", "/orders", { itemId: "m1" });
  ok(badA2a.status === 400 && badA2a.body.code === "BAD_CHECKSUM", "Create Payment with a wrong checksum → refused, nothing created");
  await chaos(PARTNER, { badChecksum: false });
  const bobLink = await bob.gw("GET", `/payments/${a2aOrder.paymentRef}`);
  ok(bobLink.status === 403 && bobLink.body.code === "WRONG_CUSTOMER", "the link opened by another customer → WRONG_CUSTOMER");
  const linkInfo = await must(alice.gw("GET", `/payments/${a2aOrder.paymentRef}`), "payment info");
  ok(linkInfo.amount === 5 && linkInfo.merchantName === "Starlight Cinema", "MB App loads amount + merchant from the bank, not from the link");
  ok((await pay(alice, a2aOrder.paymentRef)).confirm.body.status === "SUCCESS", "App-to-App payment SUCCESS");
  const a2aFinal = await waitFor(async () => { const o = await must(papi(app, "GET", `/orders/${a2aOrder.orderId}`), "order"); return o.status === "DELIVERED" && o; }, "a2a delivered");
  ok(a2aFinal.ticket, "partner app shows the delivered ticket");

  console.log("\n7. Gateways: mTLS, signature, IP allowlist, egress, Mini App Admin");
  const unsigned = await call("POST", `${PORTAL}/gw/partner/v1/payments/inquiry`, { body: { paymentRef: w.info.paymentRef }, headers: { "X-Client-Id": "starlight" } });
  ok(unsigned.status === 401 && unsigned.body.code === "MTLS_REQUIRED", "partner call without the client certificate → 401 MTLS_REQUIRED");
  const noSig = await call("POST", `${PORTAL}/gw/partner/v1/payments/inquiry`, { body: {}, headers: { "X-Client-Id": "starlight", "X-Client-Cert": "sha256:starlight-client-cert-01" } });
  ok(noSig.status === 401 && noSig.body.code === "MISSING_SIGNATURE", "certificate but no ES256 signature → 401");
  const fakeBank = await call("POST", `${PARTNER}/bank/v1/payments/notify`, { body: { paymentRef: "x", status: "SUCCESS" }, headers: { "X-Client-Id": "mb-bank", "X-Client-Cert": "sha256:mb-bank-client-cert-01" } });
  ok(fakeBank.status === 401, "forged bank → partner notification (no signature) → 401");
  const noStaff = await put(`${PORTAL}/api/v1/admin/mini-apps/starlight-web`, { enabled: false });
  ok(noStaff.status === 403 && noStaff.body.code === "AD_REQUIRED", "Mini App Admin without an AD account → 403");
  await must(put(`${PORTAL}/api/v1/admin/partners/starlight`, { allowedIps: ["203.0.113.7/32"] }, STAFF), "narrow allowlist");
  const blockedOrder = await call("POST", `${PARTNER}/api/orders`, { body: { itemId: "m1" }, token: app.token });
  ok(blockedOrder.status === 403 && blockedOrder.body.code === "IP_NOT_ALLOWED", "partner server from an unregistered IP → 403 IP_NOT_ALLOWED");
  await must(put(`${PORTAL}/api/v1/admin/partners/starlight`, { allowedIps: ["127.0.0.1/32", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"] }, STAFF), "restore allowlist");
  const audit = await must(call("GET", `${PORTAL}/api/v1/admin/audit`), "audit");
  ok(audit.some(a => a.action === "UPDATE_PARTNER" && a.actor === "admin"), "admin actions are audited");

  console.log("\n8. Consent version change and registration failure");
  await must(post(`${PORTAL}/api/v1/admin/mini-apps/starlight-web/consent-version`, undefined, STAFF), "bump consent");
  const d2 = await must(alice.gw("GET", "/mini-apps/starlight-web"), "detail");
  ok(d2.consent.status === "NOT_ACCEPTED" && d2.consent.currentVersion === "2", "new consentVersion → customer is asked again");
  const old = await alice.gw("POST", "/mini-apps/starlight-web/consent", { consentVersion: "1" });
  ok(old.status === 409 && old.body.code === "CONSENT_VERSION_CHANGED", "accepting the old version → 409");
  const noCode = await alice.gw("POST", "/mini-apps/starlight-web/auth-code", {});
  ok(noCode.status === 403 && noCode.body.code === "CONSENT_REQUIRED", "no authCode until the current consent is accepted");
  await must(alice.gw("POST", "/mini-apps/starlight-web/consent", { consentVersion: "2" }), "consent v2");
  await chaos(PARTNER, { registerFail: true });
  const regFail = await bob.gw("POST", "/mini-apps/starlight-native/consent", { consentVersion: "1" });
  ok(regFail.status === 502 && regFail.body.code === "REGISTRATION_FAILED", "partner registration fails → REGISTRATION_FAILED");
  const retry5a = await bob.gw("GET", "/mini-apps/starlight-native");
  ok(retry5a.status === 502, "next open retries registration (2:5a), still failing");
  await chaos(PARTNER, { registerFail: false });
  const retried = await must(bob.gw("GET", "/mini-apps/starlight-native"), "detail");
  ok(retried.consent.status === "ACCEPTED" && retried.registered, "partner back → registration done on open, consent kept");

  console.log("\n9. Notifications: retry topics, ignored notification → inquiry");
  await sleep(1500);
  await chaos(PARTNER, { notifyFailCount: 2 });
  const r1 = await buyWebOrSdk(alice, web, "starlight-web", "m2");
  await pay(alice, r1.info.paymentRef);
  await delivered(r1.sealed.order.orderId, 40000);
  const msgs = (await must(call("GET", `${PORTAL}/api/v1/admin/topics`), "topics")).filter(m => m.paymentRef === r1.info.paymentRef);
  ok(msgs.some(m => m.topic.endsWith("retry-1")) && msgs.some(m => m.topic.endsWith("retry-2") && m.result === "ACK"), "delivered from retry-2 after two failures");
  await chaos(PARTNER, { ignoreNotifications: true });
  const r2 = await buyWebOrSdk(alice, web, "starlight-web", "m2");
  await pay(alice, r2.info.paymentRef);
  await sleep(2500);
  ok((await adminOrder(r2.sealed.order.orderId)).status === "PENDING_PAYMENT", "notification ACKed but ignored");
  const viaInquiry = await must(papi(web, "GET", `/orders/${r2.sealed.order.orderId}`), "order");
  ok(viaInquiry.status === "DELIVERED", "page asks partner server → Inquiry Payment Status → DELIVERED");
  await chaos(PARTNER, { ignoreNotifications: false });

  console.log("\n10. Local Gateway failures, recovery and expiry");
  await chaos(BANK, { initFail: true });
  const f1 = await buyWebOrSdk(alice, web, "starlight-web", "m2");
  const f1init = await alice.gw("POST", `/payments/${f1.info.paymentRef}/init`, { account: alice.accounts[0].accountNo });
  ok(f1init.status === 502 && f1init.body.code === "INIT_FAILED" && (await portalPayment(f1.info.paymentRef)).status === "PENDING", "hold refused → INIT_FAILED, txn stays PENDING");
  await chaos(BANK, { initFail: false });
  ok((await pay(alice, f1.info.paymentRef)).confirm.body.status === "SUCCESS", "customer retries → SUCCESS");

  await chaos(BANK, { completeFail: true });
  const f2 = await buyWebOrSdk(alice, web, "starlight-web", "m2");
  ok((await pay(alice, f2.info.paymentRef)).confirm.body.status === "FAILED", "Core Banking refuses the debit → FAILED");
  await chaos(BANK, { completeFail: false });
  await waitFor(async () => (await adminOrder(f2.sealed.order.orderId)).status === "FAILED", "failed notification");
  ok(true, "FAILED notification reached the partner");

  await chaos(BANK, { completeTimeout: true });
  const f3 = await buyWebOrSdk(alice, web, "starlight-web", "m1");
  const f3pay = await pay(alice, f3.info.paymentRef);
  ok(f3pay.confirm.body.status === "PROCESSING", "Local Gateway answers too late → PROCESSING (never guessed)");
  await chaos(BANK, { completeTimeout: false });
  await must(post(`${PORTAL}/api/v1/admin/jobs/recovery`), "recovery");
  ok((await portalPayment(f3.info.paymentRef)).status === "SUCCESS", "recovery job: inquiry → SUCCESS");
  await delivered(f3.sealed.order.orderId);
  ok(true, "ticket delivered after recovery");

  await chaos(BANK, { completeLost: true });
  const f4 = await buyWebOrSdk(alice, web, "starlight-web", "m1");
  await pay(alice, f4.info.paymentRef);
  await chaos(BANK, { completeLost: false });
  await must(post(`${PORTAL}/api/v1/admin/jobs/recovery`), "recovery");
  ok((await portalPayment(f4.info.paymentRef)).status === "PROCESSING", "NOT_FOUND inside the safe window → still PROCESSING");
  await chaos(BANK, { refundLost: true });
  const lostRefund = await must(post(`${PARTNER}/api/v1/admin/orders/${apiPay.orderId}/refund`, {}), "refund (lost)");
  ok(lostRefund.status === "REFUND_PENDING", "refund with no answer and nothing posted → REFUND_PENDING");
  await chaos(BANK, { refundLost: false });
  console.log("    (waiting 31 s for the NOT_FOUND safe window)");
  await sleep(31000);
  await must(post(`${PORTAL}/api/v1/admin/jobs/recovery`), "recovery");
  ok((await portalPayment(f4.info.paymentRef)).status === "FAILED", "after the safe window: void confirmed → FAILED");
  await must(post(`${PORTAL}/api/v1/admin/jobs/refund-recovery`), "refund recovery");
  await waitFor(async () => (await adminOrder(apiPay.orderId)).status === "DELIVERED", "refund failed → order back to DELIVERED");
  ok(true, "refund NOT_FOUND after the safe window → void → FAILED; the amount is free to refund again");

  const f5 = await buyWebOrSdk(alice, web, "starlight-web", "m2");
  await must(alice.gw("POST", `/payments/${f5.info.paymentRef}/init`, { account: alice.accounts[0].accountNo }), "init");
  const heldBefore = Number((await account("001-100-1001")).held);
  await must(post(`${PORTAL}/api/v1/admin/jobs/expiry`, { force: true }), "expiry");
  ok((await portalPayment(f5.info.paymentRef)).status === "EXPIRED", "INITIATED past expiry → EXPIRED");
  ok(Number((await account("001-100-1001")).held) === heldBefore - 4.5, "hold released (cancel init)");
  await waitFor(async () => (await adminOrder(f5.sealed.order.orderId)).status === "EXPIRED", "expired notification");
  ok(true, "EXPIRED notification reached the partner");
  await chaos(BANK, { cancelFail: true });
  const f6 = await buyWebOrSdk(alice, web, "starlight-web", "m2");
  await must(alice.gw("POST", `/payments/${f6.info.paymentRef}/init`, { account: alice.accounts[0].accountNo }), "init");
  await must(post(`${PORTAL}/api/v1/admin/jobs/expiry`, { force: true }), "expiry");
  ok((await portalPayment(f6.info.paymentRef)).cancelStatus === "PENDING", "cancel init failed → cancelStatus PENDING (retried next run)");
  await chaos(BANK, { cancelFail: false });
  await must(post(`${PORTAL}/api/v1/admin/jobs/expiry`, {}), "expiry retry");
  ok((await portalPayment(f6.info.paymentRef)).cancelStatus === "DONE", "next run released the hold");

  console.log("\n11. Refunds and refund recovery");
  const p1 = await must(post(`${PARTNER}/api/v1/admin/orders/${w.sealed.order.orderId}/refund`, { amount: 1 }), "partial refund");
  ok(p1.status === "PARTIALLY_REFUNDED" && Number(p1.refundedAmount) === 1, "partial refund 1.00");
  const p2 = await must(post(`${PARTNER}/api/v1/admin/orders/${w.sealed.order.orderId}/refund`, {}), "full refund");
  ok(p2.status === "REFUNDED" && Number(p2.refundedAmount) === 5, "remaining 4.00 refunded");
  const over = await post(`${PARTNER}/api/v1/admin/orders/${w.sealed.order.orderId}/refund`, { amount: 1 });
  ok(over.status === 409, "refund beyond what was paid → 409");
  await chaos(BANK, { refundTimeout: true });
  const p3 = await must(post(`${PARTNER}/api/v1/admin/orders/${s.sealed.order.orderId}/refund`, {}), "refund (timeout)");
  ok(p3.status === "REFUND_PENDING", "Local Gateway answers too late → REFUND_PENDING");
  await chaos(BANK, { refundTimeout: false });
  await must(post(`${PORTAL}/api/v1/admin/jobs/refund-recovery`), "refund recovery");
  await waitFor(async () => (await adminOrder(s.sealed.order.orderId)).status === "REFUNDED", "partner sees REFUNDED via Get Refund Status");
  ok(true, "refund recovery → REFUNDED; partner reads it with Get Refund Status");

  console.log("\n12. Daily reconciliation");
  const rec = await must(post(`${BANK}/api/v1/admin/settlement/run`), "settlement");
  ok(rec.mismatches.length === 0 && rec.matched > 0, `reconciled: ${rec.matched} matched, ${rec.mismatches.length} mismatches`);
  await chaos(BANK, { settlementMismatch: true });
  const rec2 = await must(post(`${BANK}/api/v1/admin/settlement/run`), "settlement 2");
  ok(rec2.mismatches.length === 1, `mismatch detected: ${rec2.mismatches[0]}`);
  await chaos(BANK, { settlementMismatch: false });
  const items = await must(call("GET", `${PORTAL}/api/v1/admin/ops-items`), "ops items");
  ok(items.some(i => i.kind === "RECONCILIATION" && i.status === "OPEN"), "mismatch opened an ops item");
  await sleep(2000);

  console.log("\nCoverage of server-side steps (client steps come from the simulator, partner page, SDK and partner app)");
  const flow = JSON.parse(readFileSync(new URL("../web/lib/flow-data.json", import.meta.url), "utf8"));
  const clientLanes = new Set(["APP", "PC", "SDK", "PA", "U"]);
  const pushSteps = new Set(["3:42", "3:R6c", "4:10", "4:F8"]);
  const traces = (await Promise.all([PORTAL, BANK, PARTNER].map(u => must(call("GET", `${u}/api/v1/admin/trace`), "trace")))).flat();
  const seen = new Set(traces.map(t => t.step).filter(Boolean));
  for (const t of flow.types) {
    const expected = new Set();
    for (const k of flow.flows[t.id].phases) for (const st of flow.phases[k].steps) {
      const key = `${flow.phases[k].sec}:${st.n}`;
      if (!clientLanes.has(st.actor) || pushSteps.has(key)) expected.add(key);
    }
    const missing = [...expected].filter(k => !seen.has(k));
    console.log(`  ${t.name}: ${expected.size - missing.length}/${expected.size} server steps observed${missing.length ? ` · not seen: ${missing.join(" ")}` : ""}`);
  }

  console.log(failures ? `\n${failures} check(s) FAILED` : "\nAll checks passed");
  process.exit(failures ? 1 : 0);
}

main().catch(e => { console.error("\nFATAL:", e.message); process.exit(1); });
