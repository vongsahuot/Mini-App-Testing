import Link from "next/link";

/**
 * Shown only when an MB App link is opened outside the simulator (a real phone would hand it to the installed
 * MB App). Inside the simulator the phone OS routes /links/mbapp/* to MB App before the URL is ever loaded.
 */
export default function MbAppLinkFallback() {
  return (
    <main className="wrap">
      <div className="eyebrow">Verified link · MB App</div>
      <h1>Open this link in MB App</h1>
      <p>
        This link belongs to MB App. Open it from the Starlight app inside the <Link href="/simulator">simulator</Link>, or on Android
        with MB App installed. If MB App is not installed, get it from the <Link href="/store/mbapp">store page</Link>.
      </p>
    </main>
  );
}
