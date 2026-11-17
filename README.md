# Mini App Payment Flow — Mock Test Environment

A runnable mock of [Mini App Payment Flow.html](Mini%20App%20Payment%20Flow.html): four ways a partner connects to
MB App (**Link WebView**, **API linkage**, **SDK embedding**, **App-to-App**) on one shared bank-side payment, notification,
refund and reconciliation flow. Every lane of the sequence diagrams is a real piece of software, so you can drive each
integration type from a browser (or an Android phone) and watch every step land in a live trace.

| Lane | What runs it | URL |
|---|---|---|
| Customer + MB App (U, APP) | Browser simulator **or** the Android app in [android/](android/) | http://localhost:3000/simulator |
| Partner client: WebView page (PC) | Next.js page loaded in the simulator's WebView | http://localhost:3000/partner/movie-tickets |
| Partner client: SDK (SDK) | React component bundled into the simulator ([web/lib/sdk/](web/lib/sdk/)) | – |
| Partner client: partner app (PA) | Next.js app shown in the simulator phone | http://localhost:3000/partner-app |
| Partner server (PS) | Spring Boot, profile `partner` | http://localhost:8083 |
| Public Gateway app side (PG), Mobile Backend (MBE), Local Gateway (LG), Core Banking (CB) | Spring Boot, profile `bank` | http://localhost:8082 |
| Mini App Service (MAS), Public Gateway partner side (PG), Kafka (KF) | Spring Boot, profile `portal` | http://localhost:8081 |
| Test console | Next.js | http://localhost:3000/admin |

The three Spring Boot services are one codebase ([backend/](backend/)) built into one image and started with different
profiles. Postgres keeps each service's data in its own schema (`portal`, `bank`, `partner`) on host port 5433.
The full API, bridge, SDK host API and app-link contract is in [docs/CONTRACT.md](docs/CONTRACT.md).

## Run it

```powershell
docker compose up -d --build     # first build takes a few minutes; services need ~50 s to boot
node scripts/e2e.mjs             # optional: ~100 automated checks across all four types and the failure paths (~1.5 min)
```

Then open http://localhost:3000/simulator and log in as **alice / 1234**. Keep http://localhost:3000/admin open beside
it to watch the trace and the per-type step coverage fill in.

| User | PIN | Segment | Account | Balance |
|---|---|---|---|---|
| `alice` | 1234 | PREMIUM (sees *VIP screening*) | 001-100-1001 | USD 500.00 |
| `bob` | 1234 | MASS | 001-100-2001 | USD 3.00 (insufficient funds) |

Payments under USD 20.00 are approved with the PIN; from 20.00 (e.g. *IMAX Premiere*, 25.00) MB App asks for an OTP,
which arrives as a push notification in the simulator. Three wrong entries lock payment authentication until reset.

To stop, run `docker compose down`. Add `-v` to also wipe the database. The console's **Reset all data** button does
the same reset without a restart. If you ran an older version of this mock, start once with `docker compose down -v`:
the database schema changed.

After changing the diagram, run `node scripts/gen-flow.mjs` to regenerate the step catalog the console uses.

## The four Mini Apps

| Mini App | Type | What you'll see |
|---|---|---|
| Cinema · Web | Link WebView | The partner page opens in a WebView; it calls `login()` and `openPayment(order, checksum)` on the JS bridge. |
| Cinema · Native | API linkage | Native MB App screens; the catalog and orders come from the partner server-to-server; the bank calls the partner's Create Order API. |
| Cinema · SDK | SDK embedding | The partner's SDK runs inside MB App with only the host API (`login`, `openPayment`, `close`). Kill switch in the console. |
| Cinema · App | App-to-App | MB App opens the separate Starlight app with a verified link. The app links itself (state + PKCE), creates the payment on its server and opens MB App with a payLink carrying only the paymentRef. |

The simulator's **Phone OS** panel switches between MB App and the Starlight app and can "uninstall" either one to see
the store fallbacks. Apps only hand over through verified links (`/links/mbapp/*`, `/links/starlight/*`).

## Android (Jetpack Compose)

1. Open [android/](android/) in Android Studio and let Gradle sync. The app has not been compiled in this environment, so
   fix anything the first sync reports (see [android/README.md](android/README.md)).
2. Start an emulator or plug in a phone, then run `android/adb-reverse.ps1`. This forwards ports 3000 and 8081–8083, so
   `localhost` on the device reaches your Docker host.
3. Run the app and log in as alice / 1234. For App-to-App, the Starlight app is the web partner app in the device browser.

## Test scenarios

The console's **Scenarios** tab has step-by-step recipes; the switches are in its **Actions** tab. Highlights:

| Scenario | How | Expect |
|---|---|---|
| Happy path, each type | Open each *Cinema* app, consent, buy *Dune*, PIN 1234 | Ticket delivered by the signed notification (3:49–3:54) |
| OTP | Buy *IMAX Premiere* (25.00) as alice | OTP arrives by push; wrong code 3× → AUTH_LOCKED |
| Insufficient funds | Log in as bob, buy *IMAX Premiere* | 422 before anything is held |
| Registration failure | Partner chaos `registerFail`, open a Cinema app as a new customer | REGISTRATION_FAILED; the next open retries it (2:5a) |
| Consent change | Mini App Admin → Bump consent version | Customer is asked again; old version refused |
| Notification retries | Partner chaos `notifyFailCount = 2` | Delivered from retry topic 2; 4 failures → dead-letter + ops item |
| Notification ignored | Partner chaos `ignoreNotifications` | The partner's status inquiry fixes the order |
| Lost bank answer | Bank chaos `completeTimeout`, pay, **Run recovery** | PROCESSING, then SUCCESS from the recovery job (R1–R6c) |
| Never posted | Bank chaos `completeLost`, pay, wait 30 s, **Run recovery** | NOT_FOUND → void → FAILED (never guessed) |
| Expiry | Init a payment, **Expire all open payments** | EXPIRED and the hold released (R7–R9b) |
| Refunds | Partner page → My orders → Refund 1.00, then Refund all | PARTIALLY_REFUNDED then REFUNDED; `refundTimeout` / `refundLost` → refund recovery (F1–F9) |
| Partner API down | Partner chaos `apiFail`, open Cinema · Native | Cached catalog, or PARTNER_UNAVAILABLE |
| Gateways | Mini App Admin → set the partner's IP allowlist to another IP | Partner calls → 403 IP_NOT_ALLOWED |
| Reconciliation | **Run daily settlement**, then with `settlementMismatch` | All matched; then a mismatch and an open ops item |

## What is mocked

Each item below is a test stand-in for the real mechanism.

- **mTLS**: an `X-Client-Cert` header carrying the certificate fingerprint registered for the client id. The **IP
  allowlist** is a real check of the caller's address against the partner's CIDRs.
- **ES256 request signatures** between partner and bank are real (ECDSA P-256, timestamp + nonce, 5-minute window, replay
  check). The key pairs are fixed in the `application-*.yml` files.
- **Bank internal calls** (gateway → Mobile Backend → Mini App Service, Mini App Service → Local Gateway) are signed with an
  HMAC secret instead of the bank's internal service auth.
- **Kafka** is a pair of database tables: an outbox written in the same transaction as the payment status, and topic
  messages (main, three retry topics, dead-letter) polled by a relay and a consumer.
- **Push notifications and OTP SMS** are stored by the bank and polled by the app every 3 s.
- **Active Directory** for Mini App Admin is an `X-Staff-User: admin | ops` header.
- **Verified app links** are routed by the simulator's mock phone OS; on Android, by an intent filter (localhost links
  cannot be Android-verified, see [android/README.md](android/README.md)).
- **Timing** is shortened so tests run quickly: recovery every 10 s for txns PROCESSING > 15 s, NOT_FOUND safe window 30 s,
  ops alert after 60 s, retry topics 5 s / 10 s / 20 s (real: 1 min, 2 min, 10 min, 15 min, 10 s / 1 min / 10 min). They are
  set in [application-portal.yml](backend/src/main/resources/application-portal.yml).
- **Trace** is kept in memory in each service, so restarting a service clears its trace.
