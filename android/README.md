# KB Bank (Mock) — Android app

Native "bank app" (lane **APP**) of the Mini App Payment Flow test environment. It logs in to the mock
Bank Server, shows the native mini-apps list from the KOSIGN Portal, opens the partner page in a WebView
with the `KosignNative` JS bridge (`login`, `pay`, `close`) and reports every APP step to the portal trace.
Contract: [`../docs/CONTRACT.md`](../docs/CONTRACT.md).

## Requirements

- Android Studio (Ladybug or newer), JDK 17, Android SDK 35
- An emulator or USB device (Android 8.0 / API 26+) with `adb` on your PATH
- The backend stack running (Docker)

Toolchain: Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01.
The repo only ships `gradle/wrapper/gradle-wrapper.properties`. Android Studio handles this on import;
to build from a terminal, generate the wrapper once with a local Gradle: `gradle wrapper --gradle-version 8.9`.

## Run

1. Start the backend + web from the repo root:
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
   Re-run this after every emulator restart or USB reconnect. The app calls `http://localhost:8081/8082`
   (set in `app/build.gradle.kts` as `BuildConfig.PORTAL_URL` / `BANK_URL`), and the partner page is loaded
   from `http://localhost:3000`, so on the device these must point at your machine.
3. Open the `android/` folder in Android Studio, let Gradle sync, and run the `app` configuration.

## Test accounts

| User | PIN | Account | Balance |
|---|---|---|---|
| `alice` | `1234` | `001-100-1001` | USD 500.00 |
| `bob` | `1234` | `001-100-2001` | USD 3.00 (insufficient-funds test) |

The payment approval PIN (stand-in for biometrics/Keystore) is also `1234`.

## Flow walkthrough

1. Log in → Home shows the balance. Tap **Mini apps** (step 1).
2. The list renders from cache if it is younger than `cacheTtlSeconds`, then refreshes from the portal
   (steps 4, 5). "Coffee" and "Taxi" are disabled → "Coming soon".
3. Tap **Movie tickets** (6) → session created (8) → WebView opens the partner page.
4. The page calls `login(["profile","phone"])` (10) → consent sheet (11) → Allow (12) → authCode (15).
5. Buy a ticket → `pay(paymentId)` (26) → payment loaded (28) → payment sheet (29) →
   Cancel (30) or Approve + PIN (33) → bank result (40) → receipt → result returned to the page.
6. `close()` (61) or the X button / back → back to the list (62).

Watch the trace in the web admin (`http://localhost:3000`) or `GET http://localhost:8081/api/v1/admin/trace`.
WebView debugging is enabled in debug builds: open `chrome://inspect` on your computer.

## Code map

| File | Purpose |
|---|---|
| `Api.kt` | OkHttp wrapper for bank + portal, `ApiException`, fire-and-forget `trace()`, HMAC helper |
| `Models.kt` | Data classes, JSON parsing, sheet requests (`CompletableDeferred`) |
| `AppViewModel.kt` | All state + the flow (login, list cache, open app, bridge `login`/`pay`/`close`) |
| `Bridge.kt` | `@JavascriptInterface postMessage(json)` → main thread |
| `ui/WebAppScreen.kt` | WebView, origin checks, consent/payment sheets, PIN and receipt dialogs |
| `ui/MiniAppsScreen.kt` | Banners + sections grid |
| `ui/AppRoot.kt` | Login, Home, root navigation, snackbar, progress overlay |

## Troubleshooting

- **"Cannot reach localhost:8082 … Did you run adb reverse?"** Run the adb-reverse script again.
- **Bridge replies `ORIGIN_NOT_ALLOWED`.** The page origin must match the partner's `allowedOrigins`
  (`http://localhost:3000`). Load the page through `localhost`, not `10.0.2.2`.
- Links to other origins open in the external browser on purpose.
