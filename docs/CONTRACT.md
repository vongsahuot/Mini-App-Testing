# Mini App Payment Flow — Mock Contract

Shared contract between the Spring Boot mocks (`backend/`), the Next.js web app (`web/`), the Android app
(`android/`) and `scripts/e2e.mjs`. Step numbers match [`Mini App Payment Flow.html`](../Mini%20App%20Payment%20Flow.html)
(four integration types: Link WebView, API linkage, SDK embedding, App-to-App).

## Lanes and where they run

| Lane | Name | Runs in |
|---|---|---|
| U | Customer | Recorded by whoever shows it (app, page, SDK, partner app; the bank for push) |
| APP | MB App | Web simulator `/simulator` **or** Android app |
| PC | Partner client (WebView page) | Web `/partner/movie-tickets` |
| SDK | Partner SDK | Web `web/lib/sdk/` (bundled into the simulator); Android `sdk/` package |
| PA | Partner app | Web `/partner-app` and `/links/starlight/*` (shown inside the simulator's phone) |
| PS | Partner server ("Starlight Cinema") | Spring Boot profile `partner`, port 8083 |
| PG | Public Gateway | App gateway: bank service `/gw/app/v1`. Partner gateway: portal service `/gw/partner/v1` (inbound) and its outbound client |
| MBE | Mobile Backend | Bank service, port 8082 |
| MAS | Mini App Service | Portal service, port 8081 |
| KF | Kafka | Portal service (outbox + topic tables, see "Kafka mock") |
| LG | Local Gateway | Bank service `/lg/v1` |
| CB | Core Banking | Bank service (ledger) |

| Service | Container | Host URL | In-Docker URL |
|---|---|---|---|
| Portal (MAS, partner gateway, Kafka mock) | `portal` | http://localhost:8081 | http://portal:8081 |
| Bank (app gateway, MBE, LG, CB) | `bank` | http://localhost:8082 | http://bank:8082 |
| Partner server | `partner` | http://localhost:8083 | http://partner:8083 |
| Web (simulator, partner page, partner app, console) | `web` | http://localhost:3000 | http://web:3000 |
| Postgres | `db` | localhost:5433 | db:5432 |

Next.js rewrites (browsers never hit CORS): `/portal-api/*` → portal, `/bank-api/*` → bank, `/partner-api/*` → partner.
Android reaches the host through `adb reverse` on 3000, 8081, 8082, 8083.

## Conventions

- JSON everywhere. Money is a JSON number with 2 decimals (`5.00`). Currency `"USD"` only.
- Errors: HTTP 4xx/5xx with `{"code": "SOME_CODE", "message": "human text"}` (plus extra fields where noted).
- Timestamps: ISO-8601 UTC strings.
- Integration type ids (`type` on a Mini App, `flow` on trace events): `webview`, `api`, `sdk`, `a2a`.

## Seed data

Bank customers (PIN `1234`):

| Username | customerId | Name | Phone | Segment | Account | Balance |
|---|---|---|---|---|---|---|
| `alice` | `C1001` | Alice Sok | +855 12 345 678 | PREMIUM | `001-100-1001` | USD 500.00 |
| `bob` | `C1002` | Bob Chan | +855 98 765 432 | MASS | `001-100-2001` | USD 3.00 |

Merchant settlement account `999-STARLIGHT-01` (credited on payment, debited on refund).
Per-payment limit USD 1000.00. Auth method: **OTP** when amount ≥ 20.00, otherwise **PIN**.

Partner `starlight` "Starlight Cinema" (registered at onboarding, held by the portal):

| Field | Value |
|---|---|
| clientId | `starlight` |
| allowedIps | `127.0.0.1/32, ::1/128, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16` |
| certFingerprint (mock mTLS) | `sha256:starlight-client-cert-01` |
| ES256 public key | `mock.partner-public-key` in `application-portal.yml` |
| HMAC order key | `order-key-starlight` |
| registerUserUrl | `http://partner:8083/bank/v1/users/register` |
| notifyUrl | `http://partner:8083/bank/v1/payments/notify` |
| apiBaseUrl | `http://partner:8083/bank/v1/operations` (operations `catalog`, `orders`) |
| createOrderUrl | `http://partner:8083/bank/v1/orders` |

The bank side uses clientId `mb-bank`, certFingerprint `sha256:mb-bank-client-cert-01` and its own ES256 key.

Mini Apps (all of partner `starlight` unless noted; consentVersion `1`, scopes `name, phone`, registration required):

| appId | Name | Icon | Type | Launch | Notes |
|---|---|---|---|---|---|
| `starlight-web` | Cinema · Web | 🎬 | webview | entryUrl `http://localhost:3000/partner/movie-tickets`, allowedOrigins `http://localhost:3000` | |
| `starlight-native` | Cinema · Native | 🎟️ | api | screens `catalog`, `orders` | |
| `starlight-sdk` | Cinema · SDK | 🍿 | sdk | sdkModule `starlight-sdk`, minSdkVersion `2.0.0` | kill switch `sdkEnabled` |
| `starlight-app` | Cinema · App | 📱 | a2a | appLink `http://localhost:3000/links/starlight/open`, returnLink `http://localhost:3000/links/starlight/return`, storeUrl `http://localhost:3000/store/starlight` | |
| `vip-screening` | VIP screening | ⭐ | webview | entryUrl `http://localhost:3000/partner/movie-tickets?room=vip` | segment `PREMIUM` only (alice) |
| `coffee` | Coffee | ☕ | webview | – | disabled (never listed) |
| `taxi` | Taxi | 🚕 | webview | – | minAppVersion `9.0.0` (too new, never listed) |

App versions: the simulator and the Android app send `X-App-Version: 5.2.0`. Bundled SDK version `2.1.0`.

Partner catalog (prices live on the partner server): `m1` "Dune: Part Three" 5.00, `m2` "Inside Out 3" 4.50,
`m3` "IMAX Premiere" 25.00 (needs OTP; bob cannot afford it).

## Security mocks

| Real mechanism | Mock |
|---|---|
| Customer session (C1) | `Authorization: Bearer <sessionToken>` from `/gw/app/v1/login`. The customer is always taken from the session. |
| Internal calls (C2, C3, C8) | HMAC-SHA256 headers `X-Client-Id`, `X-Timestamp`, `X-Nonce`, `X-Signature` = hex HMAC(secret, `METHOD\nPATH\nTS\nNONCE\nBODY`). Secret `mock.bank-secret`. Callers `mobile-backend` (→ MAS) and `mini-app-service` (→ MBE / LG). |
| mTLS (C6, C7) | Header `X-Client-Cert: <certFingerprint>` must equal the fingerprint registered for `X-Client-Id`. |
| IP allowlist (C6) | Real check of the request's remote address against the partner's `allowedIps` CIDRs. |
| ES256 signature (C6, C7) | Real ECDSA P-256 / SHA-256. Headers `X-Client-Id`, `X-Timestamp`, `X-Nonce`, `X-Signature` = base64url of the IEEE-P1363 (r‖s) signature over `METHOD\nPATH\nTS\nNONCE\nBODY`. Reject if \|now − ts\| > 300 s or the nonce was used. Keys are fixed in the yml files. |
| Egress allowlist (C7) | The portal only calls the partner URLs stored in the partner registry, never a URL from a request or order. |
| PIN / OTP | PIN = the customer's PIN. OTP = 6 digits, delivered as a push notification (`kind: "OTP"`), bound to paymentRef + amount, valid 5 min. 3 wrong entries lock the customer's payment authentication (`AUTH_LOCKED`) until reset. |
| Push (C10) | The bank stores notifications per customer; apps poll `GET /gw/app/v1/notifications`. |
| Active Directory (C11) | Mutating Mini App Admin calls need `X-Staff-User: admin` or `ops`; every such call is written to the audit log. |
| Verified app links | The simulator's "OS" only routes `http(s)` links whose path is claimed by an installed app (`/links/mbapp/*` → MB App, `/links/starlight/*` → partner app). Custom schemes are refused. |

### Order checksum (HMAC)

Canonical string, fields joined by `|`, in this order:

```
appId|orderId|amount|currency|partnerUserId|expiry
starlight-web|ord_8f2a1c|5.00|USD|pu_4b19e0c2|2026-11-17T10:15:00Z
```

- `amount`: exactly two decimals, `.` separator, no grouping (`5.00`, `25.00`).
- `expiry`: ISO-8601 UTC, seconds precision, `Z` suffix.
- `checksum` = lowercase hex HMAC-SHA256(partner order key, canonical string).

Order object (as sent by the partner client and as returned by Create Order):
`{ "appId", "orderId", "amount", "currency", "partnerUserId", "expiry", "description" }` (`description` is not covered by the checksum and is not shown by MB App).

## Trace (test observability)

Every step is recorded by exactly one lane: **the receiver** of the message, or the owner of a note. Steps received
by `U` are recorded by whoever shows them. Step key = `"<section>:<n>"`, e.g. `"3:16"`, `"2:A5"`, `"4:F1"`.

Event: `{ "id", "ts", "actor": "<lane>", "step": "3:16" | null, "flow": "webview"|"api"|"sdk"|"a2a"|null, "title", "detail", "ref" }`.
`step: null` marks an un-numbered note (retry, chaos, rejection). `flow` is set when the step belongs to a type-specific
phase (null = shared or unknown). `ref` is a paymentRef, orderId, refundId or appId.

Each backend exposes `GET /api/v1/admin/trace` (oldest first) and `DELETE /api/v1/admin/trace`. Clients
(APP, U, PC, SDK, PA) report with `POST /portal-api/api/v1/admin/trace` (fire-and-forget) using the same fields.

Steps recorded by clients (from the diagram's receiver lanes):

| Type | APP | PC / SDK / PA | U (shown by) |
|---|---|---|---|
| all | 1:1 1:8 2:1 2:8 2:10 2:19d 2:19e 2:23 3:15 3:27 3:29 3:22d 3:22e 3:32b 3:32c 3:44 3:45 3:38e 3:38f 3:38j 3:38k 3:45f 3:45g | | 1:9 2:9 3:14 3:28 (APP) |
| webview / sdk | 2:24 2:25a 2:26 2:27 2:34 3:5 3:5a 3:13 3:57 4:20 4:21 | 2:25 2:35 2:43 2:39c 2:39d 3:1 3:4 3:58 3:46 3:48 | 2:44 3:59 (PC/SDK), 4:22 (APP) |
| api | 2:L1 2:L13 2:L8d 2:L8e 3:L15 3:L28 3:L24d 3:L30 | | 2:L14 3:L24e 3:L29 (APP) |
| a2a | 2:A1 2:A2a 2:A5 2:A6 2:A13 3:D30 3:D31 3:D38 3:D35c 3:D35d 3:D39 | PA: 2:A2 2:A4 2:A14 2:A15 2:A23 2:A19c 2:A19d 3:D20 3:D29 3:D25c 3:D30a 3:D40 3:D42 3:D44 | 2:A3 2:A24 3:D25d 3:D43 (PA) |

Push steps (3:42, 3:R6c, 4:10, 4:F8) are recorded by the bank as `U`.

---

## Bank service (port 8082)

### App gateway (PG → MBE), `/gw/app/v1`

Headers: `Authorization: Bearer <sessionToken>` (all but login), `X-App-Version`, `X-Platform: web|android`.
The gateway checks the session and a rate limit (`429 RATE_LIMITED`), then forwards to Mobile Backend in-process.

| Method + path | Body | Response | Steps |
|---|---|---|---|
| `POST /login` | `{username, pin, deviceId}` | `{sessionToken, customerId, name, phone, segment, accounts}`; 401 `INVALID_CREDENTIALS` | – |
| `GET /accounts` | – | `[{accountNo, name, balance, held, available, currency}]` | – |
| `GET /notifications?after=<id>` | – | `[{id, kind: "PAYMENT"\|"REFUND"\|"OTP", title, body, ref, createdAt}]` | – |
| `GET /mini-apps` | header `If-None-Match` | `200 {sections: [{id, title, apps: [{appId, name, icon, type}]}], etag}` + `ETag` header, or `304` | 1:2–1:7 |
| `GET /mini-apps/{appId}` | – | MiniAppDetail; 409 `APP_DISABLED`, 403 `NOT_ELIGIBLE`, 404, 502 `REGISTRATION_FAILED` | 2:2–2:7, 2:5a |
| `POST /mini-apps/{appId}/consent` | `{consentVersion}` | `{status: "ACCEPTED", registered}`; 409 `CONSENT_VERSION_CHANGED`; 502 `REGISTRATION_FAILED` | 2:11–2:22, 2:19a–19c |
| `POST /mini-apps/{appId}/auth-code` | `{codeChallenge?, codeChallengeMethod?: "S256"}` | `{authCode, expiresIn: 60}`; 403 `CONSENT_REQUIRED` / `NOT_REGISTERED` | 2:28–2:33, 2:A7–A12 |
| `POST /mini-apps/{appId}/content` | `{operation, params}` | `{operation, data, cached}`; 502 `PARTNER_UNAVAILABLE`; 400 `UNKNOWN_OPERATION` | 2:L2–L12, 2:L8a–L8c |
| `POST /mini-apps/{appId}/orders` | `{items: [{itemId, qty}], idempotencyKey}` | PaymentInfo; 502 `PARTNER_UNAVAILABLE` / `BAD_CHECKSUM` | 3:L16–L27, 3:L24a–L24c |
| `POST /payments/verify` | `{appId, order, checksum}` | PaymentInfo; 400 `BAD_CHECKSUM` / `APP_MISMATCH` / `ORDER_EXPIRED` / `WRONG_CUSTOMER` / `INVALID_ORDER`; 409 `ORDER_USED` | 3:6–3:12, 3:55–56 |
| `GET /payments/{paymentRef}` | – | PaymentInfo (only PENDING, unexpired, this customer); 404 `NOT_FOUND`, 409 `NOT_PENDING` / `EXPIRED` | 3:D32–D37, 3:D35a–b |
| `GET /payments/{paymentRef}/status` | – | `{paymentRef, status, failReason, bankTxnId}` | 3:45a–45e |
| `POST /payments/{paymentRef}/init` | `{account}` | `{paymentRef, status: "INITIATED", amount, currency, merchantName, authMethod: "PIN"\|"OTP"}`; 422 `INSUFFICIENT_FUNDS` / `ACCOUNT_NOT_OWNED` / `CURRENCY_MISMATCH` / `LIMIT_EXCEEDED`; 409 `NOT_PENDING` / `EXPIRED`; 502 `INIT_FAILED`; 504 `INIT_TIMEOUT` | 3:16–3:26, 3:22b–22c |
| `POST /payments/{paymentRef}/confirm` | `{pin}` or `{otp}` | `{paymentRef, status: "SUCCESS"\|"FAILED"\|"PROCESSING", bankTxnId, failReason}`; 401 `AUTH_FAILED` `{attemptsLeft}`; 423 `AUTH_LOCKED` | 3:30–3:43, 3:32a, 3:38c–d, 3:38h–i |

MiniAppDetail:
```json
{ "appId", "name", "icon", "type", "merchantName",
  "consent": { "status": "ACCEPTED"|"NOT_ACCEPTED", "currentVersion": "1", "acceptedVersion": "1"|null,
               "scopes": [{ "scope": "name", "label": "Your name" }] },
  "registered": true,
  "launch": { "entryUrl", "allowedOrigins" }            // webview
         | { "screens": ["catalog", "orders"] }         // api
         | { "sdkModule", "minSdkVersion", "sdkEnabled" } // sdk
         | { "appLink", "returnLink", "storeUrl" } }    // a2a
```
PaymentInfo: `{ "paymentRef", "appId", "orderId", "merchantName", "amount", "currency", "status", "expiresAt" }`.
`merchantName` always comes from the partner registry, never from the order.

### Mobile Backend ← Mini App Service (signed, caller `mini-app-service`)
- `POST /internal/v1/notify` `{customerId, kind, title, body, ref}` → `{ok}` (records MBE step, then the push as `U`). Steps 3:R6b/R6c, 4:9/10, 4:F7/F8.

### Local Gateway + Core Banking, `/lg/v1` (signed, caller `mini-app-service`)
Every money call is idempotent by `paymentRef` or `refundId`.

| Path | Body | Response | Steps |
|---|---|---|---|
| `POST /payments/init` | `{paymentRef, account, amount, currency}` | `{status: "HELD"\|"REFUSED", reason}` (places a hold) | 3:21 |
| `POST /payments/complete` | `{paymentRef, account, merchantAccount, amount, currency}` | `{status: "SUCCESS"\|"FAILED", bankTxnId, reason}` (releases hold, debits customer, credits merchant) | 3:35–3:37 |
| `POST /payments/inquiry` | `{paymentRef}` | `{status: "SUCCESS"\|"FAILED"\|"NOT_FOUND", bankTxnId}` | 3:R1–R3 |
| `POST /payments/void` | `{paymentRef}` | `{status: "VOIDED"}` (a later complete is refused) | 3:R5a |
| `POST /payments/cancel` | `{paymentRef}` | `{status: "CANCELLED"\|"NOTHING_HELD"}` (releases hold) | 3:R8 |
| `POST /refunds` | `{refundId, paymentRef, bankTxnId, amount, currency}` | `{status: "REFUNDED"\|"FAILED", refundTxnId, reason}` | 4:4–4:6 |
| `POST /refunds/inquiry` | `{refundId}` | `{status: "REFUNDED"\|"FAILED"\|"NOT_FOUND", refundTxnId}` | 4:F1–F3 |
| `POST /refunds/void` | `{refundId}` | `{status: "VOIDED"}` | 4:F5a |

### Bank admin (no auth, test only)
`GET/DELETE /api/v1/admin/trace` · `GET /accounts` · `GET /transactions` · `GET /holds` · `GET /customers` ·
`GET /notifications` · `POST /customers/{customerId}/unlock` (clears AUTH_LOCKED) · `POST /settlement/run` (Core Banking sends the settlement file to MAS, returns MAS's report, step 4:17) ·
`GET/PUT /chaos` · `POST /reset`.

Bank chaos (`PUT /api/v1/admin/chaos`, all booleans):

| Switch | Effect | Exercises |
|---|---|---|
| `initFail` | LG refuses the hold | 3:22a–22e (failed) |
| `initTimeout` | LG places the hold but answers too late | 3:22a (timeout → INITIATED), R7–R9 |
| `completeFail` | Core Banking refuses the debit | 3:38a–38f |
| `completeTimeout` | LG posts the debit but answers too late | 3:38g–38k, then R1–R6c → SUCCESS |
| `completeLost` | LG answers too late without posting | 3:38g, then R1–R5b NOT_FOUND → void → FAILED |
| `lgDown` | Inquiry / void get no answer | R5 retry + ops alert |
| `cancelFail` | Cancel init fails | R9a |
| `refundFail` | Core Banking refuses refunds | 4:7 FAILED |
| `refundTimeout` | LG posts the refund but answers too late | REFUND_PENDING, then F1–F9 → REFUNDED |
| `refundLost` | LG answers too late without posting | REFUND_PENDING, then F1–F5b NOT_FOUND → void → FAILED |
| `settlementMismatch` | First file entry's amount is off by 0.01 | 4:18 mismatch |

## Portal service (port 8081)

### Mini App Service internal API, `/internal/v1` (signed, caller `mobile-backend`)
Every body carries `customer: {id, name, phone, segment}`. Paths mirror the app gateway:
`mini-apps/list` `{appVersion, platform, etag}` · `mini-apps/detail` `{appId}` · `mini-apps/consent` `{appId, consentVersion}` ·
`mini-apps/auth-code` `{appId, codeChallenge, codeChallengeMethod}` · `mini-apps/content` `{appId, operation, params}` ·
`mini-apps/orders` `{appId, items, idempotencyKey}` · `payments/verify` `{appId, order, checksum}` ·
`payments/get` `{paymentRef, purpose: "init"|"pay-link"|"status"}` · `payments/init` `{paymentRef, account}` · `payments/confirm` `{paymentRef}` ·
`settlement-files` `{date, entries: [{bankTxnId, type: "PAYMENT"|"REFUND", paymentRef, refundId, amount, status}]}` (caller `core-banking`).

Payment statuses: `PENDING → INITIATED → PROCESSING → SUCCESS | FAILED`; `PENDING | INITIATED → EXPIRED`. Every change is a
conditional update. Refund statuses: `REFUND_PENDING → REFUNDED | FAILED`.

### Partner gateway, inbound (C6), `/gw/partner/v1`
Checks in order: IP allowlist (`403 IP_NOT_ALLOWED`), mTLS fingerprint (`401 MTLS_REQUIRED`), rate limit (`429`),
ES256 signature (`401 BAD_SIGNATURE` / `STALE_TIMESTAMP` / `NONCE_REUSED`). Mini App Service then checks the resource belongs to the caller.

| Path | Body | Response | Steps |
|---|---|---|---|
| `POST /auth/exchange` | `{authCode, codeVerifier?}` | `{appId, partnerUserId, scopes, userInfo: {name?, phone?}}`; 400 `invalid_grant` | 2:37–2:40, 2:39a, 2:A17–A20, 2:A19a |
| `POST /payments/inquiry` | `{paymentRef}` or `{orderId}` | `{paymentRef, orderId, appId, amount, currency, status, failReason, bankTxnId}` | 3:47a–47c, 3:L31–L34, 3:D41a–D41d |
| `POST /payments` | `{appId, order, checksum}` | `{paymentRef, payLink}`; 400 `BAD_CHECKSUM` / `NOT_LINKED` / `ORDER_EXPIRED`; 409 `ORDER_USED` | 3:D23–D27, 3:D25a |
| `POST /refunds` | `{paymentRef, refundId, amount, reason}` | Refund; 409 `NOT_REFUNDABLE` / `AMOUNT_EXCEEDS_REMAINING` / `REFUND_ID_CONFLICT` | 4:1–4:11 |
| `POST /refunds/status` | `{paymentRef, refundId}` | Refund | 4:13–4:15 |

Refund: `{refundId, paymentRef, amount, status, refundTxnId, reason, refundedTotal, remaining}`.
payLink: `http://localhost:3000/links/mbapp/pay?paymentRef=<ref>`.

### Partner gateway, outbound (C7) → partner server
Signed ES256 with the bank key, `X-Client-Id: mb-bank`, `X-Client-Cert: sha256:mb-bank-client-cert-01`, only to registered URLs.
Timeouts: register 5 s, partner API 5 s, create order 5 s (retried once with the same idempotencyKey), notification 10 s.
The API-linkage circuit breaker opens after 3 failures in a row for 30 s; a catalog fetched in the last 60 s is served from cache while open.

### Kafka mock
- Outbox: a row is written in the **same DB transaction** as the payment's final status (SUCCESS, FAILED, EXPIRED).
- Relay (every 0.5 s): moves outbox rows to topic `payment-notification` (step 3:40 / 3:38b / 3:R6 / 3:R9b, actor KF).
- Main consumer (every 0.5 s): consumes, signs and POSTs to the registered notifyUrl (3:49–3:54). No ACK / error / timeout →
  publish to the next retry topic (3:54a), commit (3:54b) and keep going.
- Retry topics `payment-notification-retry-1|2|3` with delays 5 s, 10 s, 20 s (stand-ins for 10 s, 1 min, 10 min), then
  `payment-notification-dlt` + ops alert (3:54c).

### Scheduled jobs (one node at a time via a `job_locks` row, like ShedLock)
| Job | Every | Picks | Steps |
|---|---|---|---|
| Payment recovery | 10 s | PROCESSING > 15 s | 3:R1–R6c; NOT_FOUND counts only after 30 s + confirmed void; ops alert after 60 s without an answer |
| Expiry | 10 s | PENDING / INITIATED past `expiresAt` | 3:R7–R9b |
| Refund recovery | 10 s | REFUND_PENDING > 15 s | 4:F1–F9 |
| Settlement | daily 01:00 (cron in the bank) or `POST /api/v1/admin/settlement/run` on the bank | – | 4:17–4:19 |

(The real system: every 1 min, 2 min, 10 min safe window, 15 min alert, retry topics 10 s / 1 min / 10 min.)

### Portal admin
No auth except where noted (`X-Staff-User` = mock AD account, audited).
`GET/POST/DELETE /api/v1/admin/trace` · `GET /payments` · `GET /refunds` · `GET /outbox` · `GET /topics` (Kafka messages) ·
`GET /reconciliations` · `GET /ops-items` · `POST /ops-items/{id}/resolve` (staff) · `GET /audit` · `GET /mini-apps` ·
`PUT /mini-apps/{appId}` `{enabled?, sdkEnabled?}` (staff) · `POST /mini-apps/{appId}/consent-version` (staff, bumps the version) ·
`GET /partners` · `PUT /partners/{clientId}` `{allowedIps?}` (staff) · `POST /jobs/recovery` · `POST /jobs/expiry` (`{"force": true}` expires every open payment now) ·
`POST /jobs/refund-recovery` · `POST /reset`.

## Partner server (port 8083)

### Partner client API (page, SDK, partner app), `/api`
Session: WebView gets an HttpOnly cookie `starlight_session`; SDK and partner app get `sessionToken` in the body and send `Authorization: Bearer`.

| Method + path | Body | Response | Steps |
|---|---|---|---|
| `POST /api/login` | `{authCode, codeVerifier?}` | `{appId, type, partnerUserId, profile: {name, phone}, sessionToken?}` (+ cookie for webview); errors pass through `invalid_grant` | 2:36–2:43, 2:A16–A23 |
| `GET /api/me` | – | `{appId, type, partnerUserId, profile}`; 401 | – |
| `POST /api/logout` | – | `{ok}` | – |
| `GET /api/catalog` | – | `[{itemId, title, showtime, price, currency, poster}]` | – |
| `POST /api/orders` | `{itemId}` | webview / sdk: `{order, checksum, title}` (3:2–3:4). a2a: `{orderId, title, amount, currency, paymentRef, payLink}` (3:D21–D29) | |
| `GET /api/orders` | – | `[Order]` | – |
| `GET /api/orders/{orderId}` | – | Order; asks the bank (inquiry) while the order is not final | 3:47–3:48, 3:D41–D42 |
| `POST /api/orders/{orderId}/refund` | `{amount?}` (null = all that is left) | Order | 4:1–4:12 |

Order: `{orderId, appId, itemId, title, amount, currency, paymentRef, status: "PENDING_PAYMENT"|"DELIVERED"|"FAILED"|"EXPIRED"|"REFUND_PENDING"|"PARTIALLY_REFUNDED"|"REFUNDED", refundedAmount, ticket: {code, seat}|null, createdAt}`.

### Bank → partner (C7, ES256 + mTLS fingerprint of `mb-bank`)
- `POST /bank/v1/users/register` `{appId, bankUserRef, userInfo: {name, phone}, scopes}` → `{partnerUserId}` (same bankUserRef → same id). Step 2:16.
- `POST /bank/v1/payments/notify` `{eventId, paymentRef, orderId, appId, amount, currency, status, failReason}` → `{"ack": true}`. Steps 3:51–3:52.
- `POST /bank/v1/operations/{operation}` `{appId, partnerUserId, params}` → `{data}`. Steps 2:L7–L8.
- `POST /bank/v1/orders` `{appId, partnerUserId, items, idempotencyKey}` → `{order, checksum}`. Steps 3:L20–L22.

A partner job (every 5 s) asks the bank's inquiry API about orders still PENDING_PAYMENT after 20 s (3:L31 for API linkage),
and polls Get Refund Status for refunds still REFUND_PENDING (4:13–4:16).

### Partner admin
`GET/DELETE /api/v1/admin/trace` · `GET /orders` · `GET /users` · `GET /refunds` · `GET /notifications` · `POST /orders/{orderId}/refund` `{amount?}` ·
`GET/PUT /chaos` · `POST /reset`.

Partner chaos: `registerFail` (registration answers 500), `notifyFailCount` (answer the next N notifications with 500),
`ignoreNotifications` (ACK but do nothing), `apiFail` (operations answer 500), `createOrderSlow` (first create-order call per
idempotencyKey sleeps 6 s → bank retries once), `badChecksum` (sign orders with a wrong key).

---

## On-device contracts

### JS bridge (WebView page ↔ MB App)

Page side: `web/lib/bridge.ts` → `bridge.login()`, `bridge.openPayment(order, checksum)`, `bridge.close()`.

- Android: the app injects `window.MBAppNative` (`addJavascriptInterface`). Page calls `MBAppNative.postMessage(JSON.stringify({id, method, params}))`;
  app replies with `window.__mbappBridgeCallback('<id>', true|false, <json>)`.
- Web simulator (page in an `<iframe>`): page sends `parent.postMessage({source: "mbapp-bridge", id, method, params}, "*")`;
  simulator replies `{source: "mbapp-bridge-reply", id, ok, payload}` to the page origin.

| Method | Params | OK payload | Error codes |
|---|---|---|---|
| `login` | `{}` | `{authCode}` | `ORIGIN_NOT_ALLOWED`, `NOT_MAIN_FRAME`, `CONSENT_REQUIRED`, `ERROR` |
| `openPayment` | `{order, checksum}` | `{status: "SUCCESS"|"FAILED"|"PROCESSING"|"CANCELLED", paymentRef?, errorCode?}` | `ORIGIN_NOT_ALLOWED`, `NOT_MAIN_FRAME`, `SHEET_OPEN`, `ERROR` |
| `close` | `{}` | `{}` | – |

The host answers only the main frame of an allowed origin, uses the appId of the Mini App it opened (never one from the page),
and opens any other origin outside the WebView. The page shows the result as a hint and asks its own server for the real status.

### SDK host API (partner SDK ↔ MB App)

```ts
interface HostApi {               // given only to the SDK instance MB App started
  login(): Promise<{ authCode: string }>;
  openPayment(order: Order, checksum: string): Promise<PayResult>; // same payload as the bridge
  close(): void;
}
// web: <StarlightSdk host={host} appId={appId} locale="en" />  (web/lib/sdk/StarlightSdk.tsx)
// android: StarlightSdk.Screen(host, appId, locale)                         (sdk/StarlightSdk.kt)
```
The SDK only calls its registered hosts (`/partner-api` on web, `http://localhost:8083` on Android) and keeps its session token in memory.

### App-to-App links

| Link | Direction | Carries |
|---|---|---|
| `http://localhost:3000/links/starlight/open` | MB App → partner app (A2) | nothing |
| `http://localhost:3000/links/mbapp/link?appId&state&codeChallenge` | partner app → MB App (A5) | state, S256 code challenge |
| `http://localhost:3000/links/starlight/return?authCode&state` | MB App → partner app (A14) | one-time authCode + same state, or `error=access_denied&state` |
| `http://localhost:3000/links/mbapp/pay?paymentRef` | partner app → MB App (D30) | paymentRef only |
| `http://localhost:3000/links/starlight/return?paymentRef&status` | MB App → partner app (D40) | status hint (`SUCCESS`, `FAILED`, `PROCESSING`, `CANCELLED`) + `errorCode?` |

Web: the partner app runs in an `<iframe>` inside the simulator phone. It opens a link with
`parent.postMessage({source: "os", type: "openUrl", url}, "*")`; the simulator's OS routes it (or opens the store page if the
target app is "not installed"). Opening the partner app = loading the link URL in that iframe.
Android: MB App handles `/links/mbapp/*` with an `autoVerify` intent filter; the partner app is the web partner app in the browser.
