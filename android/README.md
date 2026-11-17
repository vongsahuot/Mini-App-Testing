# MB App (Mock) — Android app

Native mobile banking app (lane **APP**) of the Mini App Payment Flow test environment. It signs in through
the bank's app gateway, lists Mini Apps, and opens them in the four integration types of the diagram:
**Link WebView**, **API linkage**, **SDK embedding** and **App-to-App**. Every APP step (and every customer
`U` step it shows) is reported to the portal trace.

Contract: [`../docs/CONTRACT.md`](../docs/CONTRACT.md) · Diagrams: [`../Mini App Payment Flow.html`](../Mini%20App%20Payment%20Flow.html)
· Step catalog: [`../web/lib/flow-data.json`](../web/lib/flow-data.json).

## Requirements

- Android Studio (Ladybug or newer), JDK 17, Android SDK 35
- An emulator or USB device (Android 8.0 / API 26+) with `adb` on your PATH
- The backend stack + web running (Docker)

Toolchain: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, OkHttp 4.12, coroutines 1.9.
No other libraries (no androidx.webkit, no navigation, no JSON library besides `org.json`).

## Run

1. Start everything from the repo root:
   ```sh
   docker compose up -d --build
   ```
2. Connect the emulator/device and forward the ports (3000 web, 8081 portal, 8082 bank, 8083 partner):
   ```powershell
   ./adb-reverse.ps1      # Windows
   ```
   ```sh
   sh adb-reverse.sh      # macOS / Linux
   ```
   Re-run this after every emulator restart or USB reconnect. The device then reaches your machine on `localhost`.
3. Open the `android/` folder in Android Studio, let Gradle sync, and run the `app` configuration.
4. **For App-to-App only:** allow MB App to open its links (see [Limitations](#limitations)):
   Settings → Apps → MB App (Mock) → Open by default → Open supported links → add `localhost`, or:
   ```sh
   adb shell pm set-app-links-user-selection --user cur --package com.kosign.minibank true localhost
   ```

Base URLs are `BuildConfig` fields in `app/build.gradle.kts`:

| Field | Value | Used for |
|---|---|---|
| `BANK_URL` | `http://localhost:8082` | App gateway `/gw/app/v1` (every bank call) |
| `PORTAL_URL` | `http://localhost:8081` | Only `POST /api/v1/admin/trace` |
| `WEB_URL` | `http://localhost:3000` | Partner page, partner app, `/links/*` (come from the Mini App detail) |
| `PARTNER_URL` | `http://localhost:8083` | Only the embedded Starlight SDK |
| `APP_VERSION` | `5.2.0` | `X-App-Version` header |

Every gateway call sends `Authorization: Bearer <sessionToken>` (except login), `X-App-Version: 5.2.0` and `X-Platform: android`.

## Test accounts

| User | PIN | Segment | Account | Balance |
|---|---|---|---|---|
| `alice` | `1234` | PREMIUM | `001-100-1001` | USD 500.00 (sees "VIP screening") |
| `bob` | `1234` | MASS | `001-100-2001` | USD 3.00 (insufficient-funds tests) |

Payments below USD 20.00 are confirmed with the PIN (`1234`); 20.00 and above need an **OTP**. The OTP arrives as a
push notification (polled every 3 s): it shows as a snackbar `🔑 OTP 123456 …` and as a tappable hint
"Latest OTP: 123456" on the OTP dialog. 3 wrong entries lock payment authentication (`AUTH_LOCKED`) until the bank is reset.

Watch the trace in the console (`http://localhost:3000/admin`) or `GET http://localhost:8081/api/v1/admin/trace`.

## Walkthrough per type

**Common start.** Log in → Home shows each account's available amount, balance and held amount. Tap **Mini Apps**
(1:1). The list is requested with `If-None-Match` (cached per customer with its ETag in SharedPreferences) → 1:8
(`200` or `304`) → rendered (1:9, U). Each tile shows its type. Tapping a tile → 2:1 → detail → 2:8. When consent is
`NOT_ACCEPTED`, a native consent sheet lists the scopes (2:9, U); **Decline** closes, **Accept** (2:10) registers you with
the partner (2:23, or 2:19d + 2:19e "Can't open right now, try again" — e.g. with partner chaos `registerFail`).

**Shared payment UI** (all types). Bottom sheet with merchant + amount from the bank's PaymentInfo (3:14) → choose account
→ **Pay** (3:15) → init (3:27 with PIN/OTP, or 3:22d + 3:22e with the error in the sheet; retry or cancel) → PIN / OTP
dialog (3:28) → **Confirm** (3:29) → SUCCESS 3:44 + 3:45 · FAILED 3:38e + 3:38f · PROCESSING 3:38j + 3:38k ·
wrong PIN/OTP 3:32b + 3:32c (attempts left shown) · network error/timeout → `GET /status` → 3:45f + 3:45g. Receipt
dialog, accounts refresh. The caller gets `{status, paymentRef, errorCode?}`; closing the sheet before init returns
`CANCELLED` without an API call.

1. **Cinema · Web (webview).** 2:24 → WebView on `launch.entryUrl` with `window.MBAppNative`. If the page doesn't load
   within ~15 s (or fails), a native error with **Retry** is shown (2:25a). The page's `login()` → 2:26, 2:27 →
   auth code (2:34). Buy → `openPayment(order, checksum)` → 3:5, 3:5a → verify (3:13, or 3:57 → `{status: FAILED, errorCode}`)
   → shared payment UI → reply. `close()` or the native X → 4:20, 4:21, 4:22. Navigation to other origins opens in the
   browser. Try "VIP screening" as alice (bob doesn't see it).
2. **Cinema · Native (api).** 2:L1 → bank-built Compose screens. **Movies** = `content {operation: "catalog"}`
   (2:L13 + 2:L14, or 2:L8d + 2:L8e "Service unavailable, try again" with partner chaos `apiFail`). **Buy** → confirmation
   screen → **Confirm & pay** (3:L15) → `POST /orders` with an idempotency key (3:L28, or 3:L24d + 3:L24e) → shared payment UI
   → native result screen (3:L29, 3:L30). **My orders** = `content {operation: "orders"}`. Partner content is plain text only.
3. **Cinema · SDK (sdk).** 2:24 checks the kill switch (`sdkEnabled`) and bundled SDK `2.1.0` ≥ `minSdkVersion`; on failure a
   native error (2:25a) — try `PUT /api/v1/admin/mini-apps/starlight-sdk {"sdkEnabled": false}` with `X-Staff-User: admin`.
   The Starlight SDK screen starts (2:25, SDK) with only the host API: `login()` (APP 2:26, 2:27, 2:34; SDK 2:35, 2:43,
   2:44 or 2:39c + 2:39d and one retry), buy (3:1, 3:4) → `openPayment` (APP 3:5, 3:5a, 3:13/3:57) → SDK 3:46 (or 3:58 + 3:59)
   → the SDK polls its own server (3:48) and shows the ticket / failure / processing. **My orders** has refund buttons
   (all, or 1.00). The SDK's **Close** or the native X → 4:20, 4:21, 4:22.
4. **Cinema · App (a2a).** 2:A1 opens `launch.appLink` (`http://localhost:3000/links/starlight/open`) in the browser, which plays
   the partner app; if nothing can open it, `launch.storeUrl` (2:A2a). In the partner app:
   - **Link MB App** → `…/links/mbapp/link?appId&state&codeChallenge` opens MB App (2:A5). If you're signed out, the login
     screen comes first; otherwise a PIN unlock (2:A6). Consent runs if it's missing, then "Link to Cinema · App?" →
     auth code with the S256 challenge (2:A13) → `returnLink?authCode&state` (or `error=access_denied&state`).
   - **Pay** → `…/links/mbapp/pay?paymentRef` (3:D30) → sign-in / PIN unlock (3:D31) → `GET /payments/{ref}` (3:D38, or
     3:D35c + 3:D35d → native error → `returnLink?paymentRef&status=FAILED&errorCode`) → shared payment UI → 3:D39 →
     `returnLink?paymentRef&status[&errorCode]`.

   You can also fire the links by hand:
   ```sh
   adb shell am start -a android.intent.action.VIEW -d "http://localhost:3000/links/mbapp/pay?paymentRef=PAY-XXXX"
   ```

## Code map

| File | Purpose |
|---|---|
| `Api.kt` | OkHttp wrapper for the app gateway (`/gw/app/v1`, ETag/304), `ApiException`, fire-and-forget `trace()` |
| `Models.kt` | Data classes + JSON parsing (MiniAppDetail, PaymentInfo, …), dialog requests (`CompletableDeferred`) |
| `AppViewModel.kt` | All state: login, notifications poll, list cache, detail + consent, WebView bridge, SDK host API, API-linkage screens |
| `PaymentFlow.kt` | Shared payment UI logic (init / PIN-OTP / confirm / status / receipt), one sheet at a time |
| `Links.kt` | App-to-App: appLink / storeUrl, incoming `/links/mbapp/link` and `/links/mbapp/pay`, returnLink |
| `Bridge.kt` | `window.MBAppNative.postMessage(json)` → main thread |
| `MainActivity.kt` | Single activity, `singleTask`; passes VIEW intents (launch + `onNewIntent`) to `A2aLinks` |
| `sdk/StarlightSdk.kt` | The partner's SDK (`HostApi`, version `2.1.0`): own pinned HTTP client to `PARTNER_URL`, its own screens |
| `ui/AppRoot.kt` | Login, Home, navigation, snackbar, busy overlay, consent sheet, link / PIN / notice dialogs |
| `ui/MiniAppsScreen.kt` | Sections grid with a type label per tile |
| `ui/WebAppScreen.kt` | WebView, origin checks, load watchdog + Retry, bridge replies |
| `ui/NativeAppScreen.kt` | API-linkage catalog / orders / confirm / result screens, and the SDK host screen |
| `ui/PaymentSheet.kt` | Payment bottom sheet, PIN/OTP dialog, receipt |

## Troubleshooting

- **"Cannot reach localhost:8082 … Did you run adb reverse?"** Run the adb-reverse script again.
- **Mini App list never changes.** It is cached with its ETag per customer; a `304` keeps the cache. Clear the app data to start over.
- **Bridge replies `ORIGIN_NOT_ALLOWED`.** The page origin must be in `allowedOrigins` (`http://localhost:3000`).
  Load it through `localhost`, not `10.0.2.2`.
- **`SHEET_OPEN`.** A payment sheet is already open (also from another Mini App or a pay link); finish or cancel it.
- **Partner links open in the browser instead of MB App.** Enable "Open supported links" (see Run, step 4), and tap the
  link in the partner app (a navigation without a user gesture usually stays in the browser).
- **OTP never shows.** Notifications are polled every 3 s; check `GET http://localhost:8082/api/v1/admin/notifications`.
- **"Session expired".** The bank restarted or was reset: log in again.

## Limitations

- **Not compiled here.** This code was written without an Android SDK and has not been built or run in this environment;
  expect to fix small things on the first Gradle sync.
- **App links on localhost can't be verified.** `android:autoVerify="true"` needs `https://<host>/.well-known/assetlinks.json`;
  Android never verifies `http://localhost:3000`. On Android 12+ you must allow MB App to open the links manually (Run,
  step 4); otherwise the browser keeps showing the web page for `/links/mbapp/*`. Custom URL schemes are not used on purpose.
- **Main-frame check.** `addJavascriptInterface` exposes `MBAppNative` to every frame, and Android doesn't tell the app which
  frame called. Without androidx.webkit (`WebViewCompat.addWebMessageListener`, which can restrict origins and report the
  frame) MB App checks the origin of the WebView's **main-frame URL** only, never answers OK to a page that is no longer on
  an allowed origin, blocks sub-frame navigations to other origins and disables pop-up windows. A cross-origin iframe
  embedded by an allowed page could still call the bridge; the `NOT_MAIN_FRAME` code is therefore never sent on Android.
- **PIN unlock** (App-to-App) is a mock of biometrics: it signs the same customer in again with `POST /login`.
- **Cancel after init.** Closing the PIN/OTP dialog after init returns `CANCELLED`; the hold stays until the expiry job
  releases it. Cancelling after a failed init returns `FAILED` with that init error code.
- **Amount precision.** `org.json` turns `5.00` into `5.0`, so an order passed through the bridge or the SDK is re-serialized;
  the bank must format the amount with two decimals itself when it checks the checksum (the contract's canonical form).
