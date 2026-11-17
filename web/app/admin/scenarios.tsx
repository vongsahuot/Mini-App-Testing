import s from "./admin.module.css";

interface Scenario {
  name: string;
  /** Integration types the recipe applies to ("all" = any). */
  types: string;
  steps: string[];
  expect: string;
  /** Console tabs to look at. */
  look: string;
}

/* Timings are the mock's shortened ones (application-portal.yml): recovery every 10 s, stuck after 15 s,
   NOT_FOUND safe window 30 s, ops alert after 60 s, notification retries 5 s / 10 s / 20 s, payment TTL 10 min. */
const SCENARIOS: Scenario[] = [
  {
    name: "WebView happy path",
    types: "webview",
    steps: [
      "Simulator: log in as alice / 1234 → Mini Apps → Cinema · Web.",
      "Accept the consent (name, phone) → the partner page signs you in via bridge.login().",
      "Buy “Dune: Part Three” (5.00) → check the payment sheet → PIN 1234.",
      "The page returns and shows the ticket; close the Mini App.",
    ],
    expect:
      "Payment SUCCESS, outbox row published (3:40), main topic message ACK, partner order DELIVERED with a ticket, PAYMENT push in Bank → Push notifications.",
    look: "Coverage (Link WebView), Payments, Notifications, Partner",
  },
  {
    name: "SDK happy path + kill switch",
    types: "sdk",
    steps: [
      "Open Cinema · SDK as alice, consent, buy a movie, PIN 1234.",
      "Mini App Admin (signed in as admin): untick “SDK enabled” for starlight-sdk.",
      "Re-open Cinema · SDK in the simulator, then tick it again.",
    ],
    expect:
      "First run: same as WebView but the SDK lane (SDK) records the client steps. With the kill switch off the detail answers sdkEnabled=false and MB App must not start the SDK. Audit log shows UPDATE_MINI_APP by admin.",
    look: "Coverage (SDK embedding), Mini App Admin, Ops & audit",
  },
  {
    name: "API linkage happy path",
    types: "api",
    steps: [
      "Open Cinema · Native as alice and consent.",
      "MB App renders the catalog from the partner (via MAS, 2:L2–L12), buy a movie, PIN 1234.",
      "The native result screen asks MAS for the status (3:L28–L30).",
    ],
    expect: "Payment SUCCESS with type api; order created by the bank server-to-server (3:L16–L27); partner order DELIVERED.",
    look: "Coverage (API linkage), Payments, Partner",
  },
  {
    name: "API linkage: partner API down → cache / breaker",
    types: "api",
    steps: [
      "Open Cinema · Native once (fills the 60 s catalog cache).",
      "Actions → Partner chaos: turn on apiFail.",
      "Re-open the catalog within 60 s, then again three times / after 60 s.",
      "Turn apiFail off; the breaker closes 30 s after it opened.",
    ],
    expect:
      "Within 60 s the catalog is served from cache (cached: true, 2:L8a–L8c). Without a cached copy MB App shows PARTNER_UNAVAILABLE. After 3 failures the breaker is OPEN (Mini App Admin → partner registry).",
    look: "Trace (flow api), Mini App Admin",
  },
  {
    name: "API linkage: slow create-order → retry",
    types: "api",
    steps: ["Partner chaos: createOrderSlow on.", "Buy a movie in Cinema · Native."],
    expect:
      "The first create-order call times out after 5 s; the bank retries once with the same idempotencyKey and gets the same order (no duplicate partner order).",
    look: "Trace (filter by the orderId), Partner → Orders",
  },
  {
    name: "App-to-App linking + payment",
    types: "a2a",
    steps: [
      "Open Cinema · App as alice → MB App opens the partner app (2:A2).",
      "In the partner app tap “Link with MB App” → MB App consent → back to the partner app with an authCode (2:A14).",
      "Buy a movie: the partner server creates the payment (3:D23–D27) and the partner app opens the payLink in MB App.",
      "Pay with PIN 1234 → MB App returns to the partner app (3:D40) which asks its server for the real status.",
    ],
    expect: "Payment SUCCESS with type a2a; partner user linked (Partner → Users, appId starlight-app); order DELIVERED.",
    look: "Coverage (App-to-App), Payments, Partner",
  },
  {
    name: "App-to-App: app not installed",
    types: "a2a",
    steps: [
      "Simulator OS bar: switch “Starlight app not installed” on, open Cinema · App.",
      "Switch it off again; in the partner app switch “MB App not installed” on and tap Link / Pay.",
    ],
    expect: "The OS opens the store page instead of the missing app (storeUrl / store page); no link or payment happens.",
    look: "Trace (flow a2a)",
  },
  {
    name: "OTP for amounts ≥ 20.00",
    types: "all",
    steps: [
      "As alice buy “IMAX Premiere” (25.00) in any Cinema app.",
      "The payment sheet asks for an OTP; the code arrives as a push notification (kind OTP).",
      "Enter the 6 digits.",
    ],
    expect: "init answers authMethod OTP; Bank → Push notifications shows the OTP row; payment SUCCESS.",
    look: "Bank, Payments",
  },
  {
    name: "Wrong PIN ×3 → AUTH_LOCKED",
    types: "all",
    steps: ["Buy any movie, enter a wrong PIN three times."],
    expect:
      "AUTH_FAILED with attemptsLeft 2, 1, then 423 AUTH_LOCKED; Bank → Customers shows authLocked = true. Reset all data to unlock.",
    look: "Bank → Customers, Trace",
  },
  {
    name: "Insufficient funds (bob)",
    types: "all",
    steps: ["Log in as bob / 1234 (USD 3.00).", "Buy “Dune: Part Three” (5.00)."],
    expect: "init answers 422 INSUFFICIENT_FUNDS; no hold placed, payment stays PENDING (expires later), bob’s balance unchanged.",
    look: "Payments, Bank → Holds",
  },
  {
    name: "Consent version bump",
    types: "all",
    steps: [
      "Mini App Admin (admin): “Bump consent version” on an app you already consented to.",
      "Open that app again in the simulator.",
    ],
    expect: "Detail shows consent NOT_ACCEPTED with the new currentVersion; MB App asks again (2:10–2:22). Audit: BUMP_CONSENT_VERSION.",
    look: "Mini App Admin, Ops & audit → Audit log",
  },
  {
    name: "Registration fails, retried on next open",
    types: "all",
    steps: [
      "Reset all data (so alice is not registered), Partner chaos: registerFail on.",
      "Open a Cinema app and accept the consent.",
      "Turn registerFail off and open the app again.",
    ],
    expect:
      "Consent is stored but registration answers 502 REGISTRATION_FAILED (2:19a–19c). On the next open the detail call registers the user (2:5a) and the app opens.",
    look: "Trace, Partner → Users",
  },
  {
    name: "Notification retries (notifyFailCount 2)",
    types: "all",
    steps: ["Partner chaos: notifyFailCount = 2, Apply.", "Buy a movie."],
    expect:
      "Topic messages: main topic NO_ACK (HTTP 500), retry-1 NO_ACK after 5 s, retry-2 ACK after 10 s more. Partner order becomes DELIVERED once the notification gets through (or earlier via the page’s status check).",
    look: "Notifications",
  },
  {
    name: "Dead-letter + ops alert (notifyFailCount 4)",
    types: "all",
    steps: ["Partner chaos: notifyFailCount = 4, Apply.", "Buy a movie and wait ~35 s."],
    expect:
      "main → retry-1 → retry-2 → retry-3 all NO_ACK, then a payment-notification-dlt row and an OPEN ops item NOTIFICATION_DLT (3:54c). Resolve it with a note.",
    look: "Notifications, Ops & audit",
  },
  {
    name: "Notifications ignored → inquiry",
    types: "all",
    steps: ["Partner chaos: ignoreNotifications on.", "Buy a movie (API linkage shows it best)."],
    expect:
      "The partner ACKs but does nothing; the page / partner app asks the bank (3:47a–47c, 3:D41) and its job asks after 20 s (3:L31) → order DELIVERED anyway.",
    look: "Trace, Partner → Received notifications",
  },
  {
    name: "Init fails",
    types: "all",
    steps: ["Bank chaos: initFail on.", "Buy a movie and confirm the account."],
    expect: "Local Gateway refuses the hold → 502 INIT_FAILED (3:22a–22e); no hold, the payment stays PENDING (you can retry after turning initFail off).",
    look: "Trace, Bank → Holds",
  },
  {
    name: "Init timeout + expiry → hold released",
    types: "all",
    steps: [
      "Bank chaos: initTimeout on.",
      "Buy a movie: init answers 504 INIT_TIMEOUT but the hold exists (Bank → Holds: HELD).",
      "Actions → “Expire all open payments”.",
    ],
    expect: "Payment EXPIRED, cancel (3:R8) releases the hold (RELEASED), cancelStatus DONE, EXPIRED notification sent (3:R9b).",
    look: "Payments, Bank → Holds, Notifications",
  },
  {
    name: "Hold release fails (cancelFail)",
    types: "all",
    steps: ["Bank chaos: initTimeout + cancelFail on.", "Buy a movie, then “Expire all open payments”."],
    expect: "Payment EXPIRED with cancelStatus PENDING and an ops item CANCEL_PENDING (R9a); turn cancelFail off and run expiry → DONE.",
    look: "Payments, Ops & audit",
  },
  {
    name: "Complete fails",
    types: "all",
    steps: ["Bank chaos: completeFail on.", "Buy a movie, PIN 1234."],
    expect: "Core Banking refuses the debit → payment FAILED (3:38a–38f), hold released, FAILED notification to the partner.",
    look: "Payments, Bank",
  },
  {
    name: "Complete timeout → recovery SUCCESS",
    types: "all",
    steps: ["Bank chaos: completeTimeout on.", "Buy a movie, PIN 1234 → MB App shows PROCESSING.", "Wait ~15–25 s or press “Run recovery”."],
    expect: "Recovery inquiry (3:R1–R3) finds SUCCESS → payment SUCCESS (R6), push “Payment successful” (R6c), order DELIVERED.",
    look: "Payments, Trace (filter by paymentRef)",
  },
  {
    name: "Complete lost → NOT_FOUND → void → FAILED",
    types: "all",
    steps: ["Bank chaos: completeLost on.", "Buy a movie, PIN 1234 → PROCESSING.", "Wait ~30–40 s (safe window 30 s)."],
    expect:
      "Inquiries answer NOT_FOUND; after 30 s MAS voids the payment at LG (3:R5a) and marks it FAILED, hold released, no debit.",
    look: "Payments, Bank → Transactions / Holds",
  },
  {
    name: "Local Gateway down → ops alert",
    types: "all",
    steps: ["Bank chaos: completeTimeout + lgDown on.", "Buy a movie, PIN 1234, wait ~60 s.", "Turn lgDown off; the next recovery run settles the payment."],
    expect: "Inquiry gets no answer (R5 retry), payment stays PROCESSING; after 60 s an ops item PAYMENT_STUCK appears.",
    look: "Ops & audit, Payments",
  },
  {
    name: "Refunds: partial then full",
    types: "all",
    steps: [
      "After a successful 5.00 payment: partner page / app → My orders → refund 1.00, then refund the rest.",
      "Or use Partner → Orders → “Refund 1.00” / “Refund rest” here.",
    ],
    expect:
      "Refunds REFUNDED (4:1–4:11), order PARTIALLY_REFUNDED then REFUNDED, payment refundedAmount 1.00 → 5.00, REFUND pushes, merchant account debited.",
    look: "Refunds, Partner, Bank",
  },
  {
    name: "Refund fails",
    types: "all",
    steps: ["Bank chaos: refundFail on.", "Refund an order."],
    expect: "Refund FAILED (4:7), partner refund FAILED, nothing moved.",
    look: "Refunds, Partner → Refunds",
  },
  {
    name: "Refund timeout → refund recovery",
    types: "all",
    steps: ["Bank chaos: refundTimeout on.", "Refund an order → REFUND_PENDING.", "Wait ~15–25 s or press “Run refund recovery”."],
    expect: "Refund recovery inquiry (4:F1–F3) finds REFUNDED → refund REFUNDED, push sent (4:F8); partner polls status (4:13–4:16).",
    look: "Refunds, Trace",
  },
  {
    name: "Refund lost → void → FAILED",
    types: "all",
    steps: ["Bank chaos: refundLost on.", "Refund an order, wait ~30–40 s."],
    expect: "Inquiry answers NOT_FOUND; after the safe window the refund is voided (4:F5a) and FAILED; the amount can be refunded again.",
    look: "Refunds, Bank → Transactions",
  },
  {
    name: "Daily settlement + mismatch",
    types: "all",
    steps: ["Actions → “Run daily settlement” (should match).", "Bank chaos: settlementMismatch on, run it again."],
    expect: "First report: every entry matched. Second: one mismatch (amount off by 0.01) and an ops item RECONCILIATION (4:18).",
    look: "Actions (result), Ops & audit → Reconciliation",
  },
  {
    name: "Partner IP allowlist → IP_NOT_ALLOWED",
    types: "a2a / refunds",
    steps: [
      "Mini App Admin (admin): set starlight’s allowedIps to 203.0.113.0/24 and Save.",
      "Make an A2A payment or a refund (partner → partner gateway).",
      "Restore the seed list afterwards.",
    ],
    expect: "The partner gateway answers 403 IP_NOT_ALLOWED before any other check; nothing is created. Audit: UPDATE_PARTNER.",
    look: "Trace (actor PG), Mini App Admin, Ops & audit",
  },
  {
    name: "Staff permissions",
    types: "admin",
    steps: ["Header: switch “Signed in as” to ops.", "Try any Mini App Admin change, then resolve an ops item."],
    expect: "Mini App Admin calls answer 403 AD_REQUIRED (admin only); resolving an ops item works for ops and is audited.",
    look: "Mini App Admin, Ops & audit",
  },
];

export function Scenarios() {
  return (
    <section className={s.scenarios}>
      {SCENARIOS.map((sc, i) => (
        <article key={sc.name} className="panel">
          <h3>
            {i + 1}. {sc.name}
          </h3>
          <div className={s.scenarioMeta}>
            <span className="pill muted">{sc.types}</span>
            <span>Look at: {sc.look}</span>
          </div>
          <ol>
            {sc.steps.map((st, j) => (
              <li key={j}>{st}</li>
            ))}
          </ol>
          <p>
            <b>Expect:</b> {sc.expect}
          </p>
        </article>
      ))}
    </section>
  );
}
