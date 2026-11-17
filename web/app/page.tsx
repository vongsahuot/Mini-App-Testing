import Link from "next/link";
import styles from "./home.module.css";

const TOOLS = [
  {
    href: "/simulator",
    lane: "var(--device)",
    name: "MB App simulator",
    text: "A browser copy of MB App: login, Mini App list, consent, payment sheet with PIN / OTP, push notifications. Runs all four integration types — WebView with the JS bridge, native API-linkage screens, the embedded partner SDK and, for App-to-App, the partner app inside the simulated phone.",
  },
  {
    href: "/partner/movie-tickets",
    lane: "var(--partner)",
    name: "Partner page · Starlight Cinema",
    text: "The Link WebView partner site. Only works inside the simulator or the MB App WebView, where the bridge exists.",
  },
  {
    href: "/partner-app",
    lane: "var(--partner)",
    name: "Partner app · Starlight",
    text: "The App-to-App partner app: links the account with MB App and opens payLinks. Runs inside the simulator’s phone (Cinema · App).",
  },
  {
    href: "/admin",
    lane: "var(--bank)",
    name: "Test console",
    text: "Live trace from all services, per-type step coverage, payments, Kafka notifications, ops & audit, bank and partner data, Mini App Admin, chaos switches and test recipes.",
  },
];

export default function Home() {
  return (
    <main className="wrap">
      <div className="eyebrow">Mock test environment · 4 integration types</div>
      <h1 className={styles.title}>Mini App Payment Flow</h1>
      <p className={styles.lead}>
        Run the whole flow end to end — Link WebView, API linkage, SDK embedding and App-to-App — against the mock
        Portal (Mini App Service, partner gateway, Kafka), Bank (gateway, Mobile Backend, Local Gateway, Core Banking)
        and Partner server, then check which steps of each sequence were observed.
      </p>

      <div className={styles.grid}>
        {TOOLS.map((t) => (
          <Link key={t.href} href={t.href} className={styles.tool} style={{ borderTopColor: t.lane }}>
            <strong>{t.name}</strong>
            <span>{t.text}</span>
            <code>{t.href}</code>
          </Link>
        ))}
      </div>

      <section className="panel" style={{ marginTop: 24 }}>
        <h2>How to test</h2>
        <ol className={styles.steps}>
          <li>
            Open <Link href="/admin">/admin</Link> in one tab and <Link href="/simulator">/simulator</Link> in another.
          </li>
          <li>
            Log in to the simulator as <code>alice</code> / <code>1234</code> (USD 500, PREMIUM) or <code>bob</code> /{" "}
            <code>1234</code> (USD 3 — for insufficient funds).
          </li>
          <li>
            Open Mini Apps and pick a Cinema app of each type: <b>Cinema · Web</b> (WebView), <b>Cinema · Native</b> (API
            linkage), <b>Cinema · SDK</b> (SDK embedding), <b>Cinema · App</b> (App-to-App).
          </li>
          <li>Accept the consent (name, phone), then buy a ticket.</li>
          <li>
            Approve with PIN <code>1234</code>. Amounts of 20.00 or more (IMAX Premiere, 25.00) need an OTP, which arrives
            as a push notification in the simulator.
          </li>
          <li>Refund the ticket (partly or fully) from “My orders” in the partner page / app.</li>
          <li>
            Check the console’s Coverage tab for each type (clear the trace between runs to measure one type at a time),
            then work through the Scenarios tab (chaos switches, jobs, Mini App Admin).
          </li>
        </ol>
        <p className={styles.note}>
          Android: run <code>adb reverse tcp:3000 tcp:3000</code> and the same for <code>8081</code>, <code>8082</code>{" "}
          and <code>8083</code> so the app reaches this host at <code>http://localhost</code>.
        </p>
      </section>
    </main>
  );
}
