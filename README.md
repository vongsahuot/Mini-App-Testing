# Mini App Payment Flow — Mock Test Environment

A runnable mock of [Mini App Payment Flow.html](Mini%20App%20Payment%20Flow.html) (v3, 10 phases, 62 steps).
Each lane of the sequence diagram is a real service, so you can drive the whole flow from an Android
phone or a browser and watch every step land in a live trace.

| Lane | What runs it | URL |
|---|---|---|
| Customer + Bank App (APP) | Android app in [android/](android/) **or** the browser simulator | http://localhost:3000/simulator |
| KOSIGN Portal (KP) | Spring Boot, profile `portal` | http://localhost:8081 |
| Partner Page (PP) | Next.js page loaded in the WebView / iframe | http://localhost:3000/partner/movie-tickets |
| Partner Server (PS) | Spring Boot, profile `partner` | http://localhost:8083 |
| Bank Server (BS) | Spring Boot, profile `bank` | http://localhost:8082 |
| Test console | Next.js | http://localhost:3000/admin |

The three Spring Boot services are one codebase ([backend/](backend/)) built into one image and started with
different profiles. Postgres holds each service's data in its own schema (`portal`, `bank`, `partner`), on host port 5433.
The full API and bridge contract is in [docs/CONTRACT.md](docs/CONTRACT.md).

## Run it

```powershell
docker compose up -d --build     # first build takes a few minutes; services need ~50 s to boot
node scripts/e2e.mjs             # optional: 40 automated checks across all scenarios
```

Then open http://localhost:3000/simulator and log in as **alice / 1234**. Keep http://localhost:3000/admin open
beside it to watch the trace and the 62-step coverage fill in.

Test users (PIN `1234`): `alice` has USD 500.00, `bob` has USD 3.00 (use him for insufficient funds).

To stop, run `docker compose down`. Add `-v` to also wipe the database. The console's **Reset all data** button
does the same reset without a restart.

## Android (Jetpack Compose)

1. Open [android/](android/) in Android Studio and let Gradle sync. The project has not been compiled yet, so fix anything the first sync reports.
2. Start an emulator or plug in a phone, then run `android/adb-reverse.ps1`. This forwards ports 3000 and 8081–8083, so `localhost` on the device reaches your Docker host.
3. Run the app and log in as alice / 1234.

See [android/README.md](android/README.md) for details.

## Test scenarios

All the switches are in the console's **Actions** tab.

| Scenario | How | Expect |
|---|---|---|
| Happy path | Sign in on the partner page, buy *Dune*, approve | Ticket delivered by webhook (47–49), receipt in app |
| Cancel | Tap Cancel on the payment sheet | `pay()` → CANCELLED, order CANCELLED (30–32) |
| Insufficient funds | Log in as bob, buy *Expensive IMAX* | FAILED / INSUFFICIENT_FUNDS |
| Lost bank result → recovery | Bank chaos `dropResultCallback` on, pay, then **Run recovery now** (or wait ~60 s) | Payment stays PROCESSING, then the bank status check flips it to PAID and delivers (41–43) |
| Webhook retries | Partner chaos `webhookFailCount = 3`, pay | Retries every 5 s, 10 s, 20 s, then delivered |
| Webhook lost | Partner chaos `ignoreWebhooks` on, pay | Page's status check delivers the ticket instead (51–54) |
| Refunds | **Refund 1.00**, then **Refund** on the order | PARTIALLY_REFUNDED, then REFUNDED (55–60) |
| Reconciliation | **Run daily settlement**; then turn on `settlementMismatch` and run it again | All matched; then 1 mismatch alert (44–46) |

## What is mocked

Each item below is a test stand-in for the real mechanism.

- **mTLS and `private_key_jwt`**: replaced by HMAC-SHA256 request signing (method, path, timestamp, nonce, body). The 5-minute timestamp window and nonce replay checks are real.
- **Bank JWT**: real RS256 JWT, verified by the portal through the bank's real JWKS endpoint. The key is regenerated each time the bank restarts.
- **Biometrics / Keystore key**: a PIN prompt plus HMAC(deviceSecret, paymentId). The bank does verify this signature.
- **Timing**: shortened so tests run quickly. Recovery scans every 15 s for payments PROCESSING longer than 60 s (the real system uses 1–2 min and 3 min). The first webhook retry is after 5 s. These are set in [application-portal.yml](backend/src/main/resources/application-portal.yml).
- **Trace**: kept in memory in each service, so restarting a service clears its trace.
