import Link from "next/link";
import styles from "./home.module.css";

const TOOLS = [
  {
    href: "/simulator",
    lane: "var(--device)",
    name: "Bank app simulator",
    text: "A browser copy of the bank app: login, native mini apps list, WebView with the JS bridge, consent and payment sheets.",
  },
  {
    href: "/partner/movie-tickets",
    lane: "var(--partner)",
    name: "Partner page · Movie tickets",
    text: "The partner mini app. Only works inside the bank app WebView or the simulator, where the bridge exists.",
  },
  {
    href: "/admin",
    lane: "var(--bank)",
    name: "Test console",
    text: "Live trace from all services, 62-step coverage, payments, webhooks, refunds, chaos switches and test recipes.",
  },
];

export default function Home() {
  return (
    <main className="wrap">
      <div className="eyebrow">Mock test environment · 62 steps</div>
      <h1 className={styles.title}>Mini App Payment Flow</h1>
      <p className={styles.lead}>
        Run the whole flow end to end against the mock KOSIGN Portal, Bank Server and Partner Server, then check which
        steps of the sequence were observed.
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
            Log in to the simulator as <code>alice</code> (PIN <code>1234</code>, USD 500) or <code>bob</code> (PIN{" "}
            <code>1234</code>, USD 3 — for insufficient funds).
          </li>
          <li>Tap “Mini apps”, open “Movie tickets”, sign in with KB, allow the consent, buy a ticket.</li>
          <li>Approve with PIN 1234, watch the ticket appear, then refund it from “My orders”.</li>
          <li>Check the console’s Coverage tab, then try the recipes under “Test scenarios” (chaos switches).</li>
        </ol>
        <p className={styles.note}>
          On Android, run <code>adb reverse tcp:3000 tcp:3000</code> (and 8081, 8082) so the app reaches this host at{" "}
          <code>http://localhost</code>.
        </p>
      </section>
    </main>
  );
}
