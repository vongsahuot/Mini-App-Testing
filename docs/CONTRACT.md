# Mini App Payment Flow — Mock Contract

Shared contract between the Spring Boot mocks (`backend/`), the Next.js web app (`web/`) and the
Android app (`android/`). Step numbers match `Mini App Payment Flow.html` (v3, 62 steps).

## Services and ports

| Service | Lane | Container | Host URL | In-Docker URL |
|---|---|---|---|---|
| KOSIGN Portal | KP | `portal` | http://localhost:8081 | http://portal:8081 |
| Bank Server (mock) | BS | `bank` | http://localhost:8082 | http://bank:8082 |
| Partner Server (mock "Movie tickets") | PS | `partner` | http://localhost:8083 | http://partner:8083 |
| Web (partner page, simulator, admin) | PP / APP | `web` | http://localhost:3000 | http://web:3000 |
| Postgres | – | `db` | localhost:5433 | db:5432 |

Next.js proxies (rewrites, so browsers never hit CORS):
`/portal-api/:path*` → portal, `/bank-api/:path*` → bank, `/partner-api/:path*` → partner.

Android reaches the host with `adb reverse tcp:3000 tcp:3000` (and 8081, 8082), so every URL is `http://localhost:<port>`.

## Seed data

- Bank users (PIN `1234` for all):
  - `alice` → customerId `C1001`, "Alice Sok", phone `+855 12 345 678`, account `001-100-1001` USD 500.00
  - `bob` → customerId `C1002`, "Bob Chan", phone `+855 98 765 432`, account `001-100-2001` USD 3.00 (insufficient funds test)
- Bank settlement (escrow) account: `999-ESCROW-01`
- Mini apps (portal):
  - `movie-tickets` "Movie tickets" icon `🎬` — enabled, partner clientId `movie-tickets`
  - `coffee` "Coffee" icon `☕` badge `Soon` — disabled
  - `taxi` "Taxi" icon `🚕` badge `Soon` — disabled
- Partner `movie-tickets`: entryUrl `http://localhost:3000/partner/movie-tickets`,
  allowedOrigins `["http://localhost:3000"]`, webhookUrl `http://partner:8083/webhooks/kosign`,
  secret `partner-secret-movie`.
- Partner catalog: `m1` "Dune: Part Three" 5.00 USD, `m2` "Inside Out 3" 4.50 USD, `m3` "Expensive IMAX" 25.00 USD.
- Bank ↔ Portal shared secret: `bank-shared-secret`.

## Conventions

- JSON everywhere. Money is a JSON number with 2 decimals (`5.00`), currency `"USD"`.
- Errors: HTTP 4xx/5xx with body `{"code": "SOME_CODE", "message": "human text"}`.
- Timestamps: ISO-8601 strings.

### Signatures (mock of mTLS / private_key_jwt)
Partner → Portal, Portal → Partner (webhooks), Bank ↔ Portal use HMAC-SHA256:
headers `X-Client-Id`, `X-Timestamp` (epoch seconds), `X-Nonce` (uuid), `X-Signature` =
lowercase hex of HMAC_SHA256(secret, `METHOD\nPATH\nTIMESTAMP\nNONCE\nBODY`). PATH excludes query string.
Reject if |now − ts| > 300 s or nonce reused.

### Device-signed approval (step 33)
At bank login the app receives `deviceSecret`. Approval signature = lowercase hex HMAC_SHA256(deviceSecret, paymentId).
(Stands in for a Keystore key unlocked by biometrics/PIN.)

## Trace (test observability)

Every step is recorded by exactly one actor: **the receiver** of the message (or the actor itself for notes).

| Actor | Steps |
|---|---|
| APP (Android app or web simulator) | 1, 4, 5, 6, 8, 10, 11, 12, 15, 26, 28, 29, 30, 33, 40, 61, 62 |
| PP (partner page) | 9, 16, 20, 25, 32, 50, 54 |
| KP (portal) | 2, 3, 7, 13, 14, 18, 22, 23, 27, 31, 35, 36, 39, 42, 43, 44, 45, 49, 52, 55, 56, 59 |
| BS (bank) | 34, 37, 38, 41, 57, 58 |
| PS (partner server) | 17, 19, 21, 24, 46, 47, 48, 51, 53, 60 |

Clients (APP, PP) report via `POST /portal-api/api/v1/admin/trace` (fire-and-forget):
```json
{ "actor": "APP", "step": 1, "title": "Tap \"Mini apps\"", "detail": "optional text", "ref": "paymentId/orderId/appId or null" }
```
Each backend exposes `GET /api/v1/admin/trace` → `[{ "id", "ts", "actor", "step", "title", "detail", "ref" }]`
(newest last) and `DELETE /api/v1/admin/trace`. The portal's list also contains APP/PP events.

### Step titles (1–62)
1 Tap "Mini apps" · 2 GET /client/mini-apps (JWT) · 3 Verify JWT via JWKS, filter apps · 4 Return sections, apps, banners ·
5 Render native list · 6 Tap mini app · 7 POST /client/mini-apps/{appId}/sessions · 8 Return partner URL + allowed origins ·
9 Open WebView (bridges on allowed origins) · 10 Bridge login(scopes) · 11 Show consent sheet · 12 Tap Allow ·
13 POST /client/mini-apps/{appId}/auth-codes · 14 Issue authCode (1 use, 60 s) · 15 Return authCode · 16 Resolve login() with authCode ·
17 Send authCode to partner backend · 18 POST /partner/oauth/token · 19 Burn code, return partnerCustomerId ·
20 Tap "Buy" · 21 Send itemId (price on server) · 22 POST /partner/payments (signed) · 23 Idempotency check, save PENDING ·
24 Return paymentId · 25 Pass paymentId to page · 26 Bridge pay(paymentId) · 27 GET /client/payments/{id} · 28 Return amount, merchant ·
29 Show native payment sheet · 30 Tap Cancel · 31 POST /client/payments/{id}/cancel · 32 Resolve pay() CANCELLED ·
33 Biometrics/PIN signs paymentId · 34 Send approval to bank · 35 POST /bank/payments/{id}/lock · 36 Atomic PENDING→PROCESSING ·
37 Return amount + settlement account · 38 Verify signature + balance, debit/credit · 39 POST /bank/payments/{id}/result ·
40 Return Done + bankTxnId · 41 GET /bank/payments/{id}/status (recovery) · 42 Return bank status · 43 Apply bank's answer ·
44 Settlement file received · 45 Reconcile, alert on mismatch · 46 Partner statement · 47 Webhook payment.* · 48 Verify webhook, mark paid, deliver ·
49 200 OK (stop retries) · 50 Resolve pay() (UI hint) · 51 Check order status · 52 GET /partner/payments/{id} (fallback) ·
53 Return payment status · 54 "Delivered" → show ticket · 55 POST /partner/payments/{id}/refunds · 56 Refund idempotency, REFUND_PENDING ·
57 POST /bank/refunds · 58 Debit settlement, credit customer · 59 REFUNDED + refundTxnId · 60 Webhook refund.* · 61 Bridge close() · 62 Close WebView → list

---

## Bank Server (port 8082)

### Bank app API (Android app + web simulator)
- `POST /api/v1/bank-app/login` `{ "username", "pin", "deviceId" }` →
  `{ "sessionToken", "customerId", "name", "phone", "deviceSecret", "accounts": [{ "accountNo", "name", "balance", "currency" }] }`
  401 `INVALID_CREDENTIALS`.
- `POST /api/v1/bank-app/jwt` (header `Authorization: Bearer <sessionToken>`) → `{ "jwt", "expiresIn": 300 }`
  JWT is RS256, `iss=kb-bank`, `aud=kosign-portal`, `sub=<customerId>`, `exp=now+300`.
- `GET /api/v1/bank-app/accounts` (Bearer sessionToken) → `[{ "accountNo", "name", "balance", "currency" }]`
- `POST /api/v1/bank-app/payments/{paymentId}/approve` (Bearer sessionToken)
  `{ "debitAccount": "001-100-1001", "signature": "<hex>" }` → **always HTTP 200** with
  `{ "paymentId", "status": "PAID" | "FAILED", "bankTxnId": "BTX-...|null", "amount", "currency", "reason": "INSUFFICIENT_FUNDS|INVALID_SIGNATURE|LOCK_REJECTED|DEBIT_FAILED|null" }`
  (steps 34–40).
- `GET /.well-known/jwks.json` → JWKS for the JWT.

### Bank ↔ Portal (signed, bank side)
- `GET /api/v1/bank/payments/{paymentId}/status` → `{ "paymentId", "status": "PAID"|"FAILED"|"NOT_FOUND", "bankTxnId" }` (41–42)
- `POST /api/v1/bank/refunds` `{ "refundId", "paymentId", "bankTxnId", "amount", "currency" }` → `{ "refundId", "status": "REFUNDED"|"FAILED", "refundTxnId" }` (57–59)

### Bank admin
- `GET /api/v1/admin/trace`, `DELETE /api/v1/admin/trace`
- `GET /api/v1/admin/accounts` → all accounts with balances (incl. escrow)
- `GET /api/v1/admin/transactions` → `[{ "txnId", "type": "PAYMENT"|"REFUND", "paymentId", "refundId", "debitAccount", "creditAccount", "amount", "currency", "status", "createdAt" }]`
- `POST /api/v1/admin/settlement/run` → bank sends today's settlement file to portal (44) and returns portal's reconciliation result.
- `GET /api/v1/admin/chaos` / `PUT /api/v1/admin/chaos`
  `{ "dropResultCallback": false, "failDebit": false, "failRefund": false, "settlementMismatch": false }`

## KOSIGN Portal (port 8081)

### Client API — `Authorization: Bearer <bank JWT>`
- `GET /api/v1/client/mini-apps` →
  `{ "sections": [{ "id", "title", "apps": [{ "appId", "name", "icon", "badge": "Soon"|null, "enabled": true }] }], "banners": [{ "id", "title", "subtitle", "appId", "color": "#hex" }], "cacheTtlSeconds": 60 }`
- `POST /api/v1/client/mini-apps/{appId}/sessions` → `{ "sessionId", "appId", "appName", "url", "allowedOrigins": ["http://localhost:3000"] }`
  409 `APP_DISABLED` for disabled apps.
- `GET /api/v1/client/mini-apps/{appId}/consents?scopes=profile,phone` →
  `{ "granted": false, "partnerName": "Movie tickets", "scopes": [{ "scope": "profile", "label": "Your name" }, { "scope": "phone", "label": "Your phone number" }] }`
- `POST /api/v1/client/mini-apps/{appId}/auth-codes` `{ "sessionId", "scopes": ["profile","phone"], "consentGiven": true }` →
  `{ "authCode", "expiresIn": 60 }`. 403 `CONSENT_REQUIRED` if no stored consent and `consentGiven` is not true.
- `GET /api/v1/client/payments/{paymentId}` (header `X-Page-Origin: http://localhost:3000`) →
  `{ "paymentId", "orderId", "amount", "currency", "merchantName", "description", "status", "expiresAt" }`
  409 `NOT_PENDING`, 403 `ORIGIN_MISMATCH` / `WRONG_CUSTOMER`, 404 `NOT_FOUND`.
- `POST /api/v1/client/payments/{paymentId}/cancel` → `{ "paymentId", "status": "CANCELLED" }`

### Partner API (signed with partner secret)
- `POST /api/v1/partner/oauth/token` `{ "authCode" }` → `{ "partnerCustomerId", "scopes": [...], "profile": { "name"?, "phone"? } }`
- `POST /api/v1/partner/payments` `{ "orderId", "partnerCustomerId", "amount", "currency", "description" }` → Payment
  409 `AMOUNT_MISMATCH` if same orderId with a different amount.
- `GET /api/v1/partner/payments/{paymentId}` and `GET /api/v1/partner/payments?orderId=` → Payment
- `POST /api/v1/partner/payments/{paymentId}/refunds` `{ "refundId", "amount", "reason" }` → Refund
- Payment: `{ "paymentId", "orderId", "amount", "currency", "status": "PENDING"|"PROCESSING"|"PAID"|"FAILED"|"CANCELLED"|"EXPIRED"|"REFUNDED"|"PARTIALLY_REFUNDED", "bankTxnId", "refundedAmount", "createdAt", "expiresAt" }`
- Refund: `{ "refundId", "paymentId", "amount", "status": "REFUND_PENDING"|"REFUNDED"|"FAILED", "refundTxnId", "reason" }`

### Bank API on portal (signed with bank secret)
- `POST /api/v1/bank/payments/{paymentId}/lock` `{ "customerId" }` → `{ "paymentId", "amount", "currency", "settlementAccount": "999-ESCROW-01", "merchantName" }`; 409 `LOCK_REJECTED`
- `POST /api/v1/bank/payments/{paymentId}/result` `{ "status": "PAID"|"FAILED", "bankTxnId", "reason" }` → `{ "ok": true }` (idempotent)
- `POST /api/v1/bank/settlement-files` `{ "date", "entries": [{ "bankTxnId", "type", "paymentId", "refundId", "amount", "status" }] }` → Reconciliation

### Portal admin (no auth — mock)
- `GET/DELETE /api/v1/admin/trace`, `POST /api/v1/admin/trace`
- `GET /api/v1/admin/payments`, `/refunds`, `/webhooks`, `/reconciliations`
- Webhook delivery: `{ "id", "eventId", "type", "paymentId", "status": "PENDING"|"DELIVERED"|"GAVE_UP", "attempts", "lastStatusCode", "nextAttemptAt", "updatedAt" }`
- Reconciliation: `{ "id", "date", "matched", "mismatches": ["text"], "createdAt" }`
- `POST /api/v1/admin/jobs/recovery` → runs the stuck-payment recovery now, returns `{ "checked": n }`
- `POST /api/v1/admin/reset` → clears payments, refunds, webhooks, consents, trace

## Partner Server (port 8083)

### Partner page API (used by the web page via `/partner-api`)
- `POST /api/login` `{ "authCode" }` → `{ "sessionToken", "partnerCustomerId", "profile": { "name", "phone" } }` (17–19)
- `GET /api/catalog` → `[{ "itemId", "title", "showtime", "price", "currency", "poster": "🎬" }]`
- `POST /api/orders` (Bearer partner sessionToken) `{ "itemId" }` → Order (21–24)
- `GET /api/orders` (Bearer) → `[Order]`
- `GET /api/orders/{orderId}` (Bearer) → Order; if not yet PAID it checks the portal (51–53); delivers once (54).
- `POST /api/orders/{orderId}/refund` (Bearer) `{ "amount": 5.00 | null (full), "reason" }` → Order
- Order: `{ "orderId", "itemId", "title", "amount", "currency", "paymentId", "status": "PENDING_PAYMENT"|"PAID"|"DELIVERED"|"CANCELLED"|"FAILED"|"REFUND_PENDING"|"REFUNDED"|"PARTIALLY_REFUNDED", "refundedAmount", "ticket": { "code", "seat" } | null, "createdAt" }`

### Webhook receiver
- `POST /webhooks/kosign` (signed by portal with partner secret)
  `{ "eventId", "type": "payment.paid"|"payment.failed"|"refund.succeeded"|"refund.failed"|"statement.daily", "data": { ... } }`

### Partner admin
- `GET/DELETE /api/v1/admin/trace`, `GET /api/v1/admin/orders`, `GET /api/v1/admin/statements`
- `GET/PUT /api/v1/admin/chaos` `{ "webhookFailCount": 0, "ignoreWebhooks": false }`
  (`webhookFailCount`: answer the next N webhooks with 500; `ignoreWebhooks`: answer 200 but do nothing, to exercise the status-check fallback)

---

## JS bridge (partner page ↔ bank app)

Partner page uses `web/lib/bridge.ts` which exposes `login(scopes)`, `pay(paymentId)`, `close()` returning Promises.

Transport A — Android WebView: the app injects `window.KosignNative` via `addJavascriptInterface(obj, "KosignNative")`.
Page sends `KosignNative.postMessage(JSON.stringify({ id, method, params }))`.
App replies with `evaluateJavascript("window.__kosignBridgeCallback('<id>', true|false, <json>)")`.

Transport B — web simulator (page inside an `<iframe>`): page sends `window.parent.postMessage({ source: "kosign-bridge", id, method, params }, "*")`;
simulator replies `iframe.contentWindow.postMessage({ source: "kosign-bridge-reply", id, ok, payload }, pageOrigin)`.

Methods:
- `login` params `{ scopes: ["profile","phone"] }` → ok payload `{ authCode }`; error payload `{ code: "USER_DENIED"|"ORIGIN_NOT_ALLOWED"|"ERROR", message }`
- `pay` params `{ paymentId }` → ok payload `{ status: "PAID"|"FAILED"|"CANCELLED", bankTxnId?, reason? }`
- `close` params `{}` → ok payload `{}`

The host (app/simulator) must refuse every bridge call whose page origin is not in `allowedOrigins` (`ORIGIN_NOT_ALLOWED`),
and open navigations to other origins outside the WebView (external browser / new tab).
Host also passes `X-Page-Origin: <page origin>` on step 27.
