import type { Metadata } from "next";
import { notFound } from "next/navigation";
import s from "./store.module.css";

interface StoreApp {
  name: string;
  developer: string;
  icon: string;
  color: string;
  tagline: string;
  about: string;
}

const APPS: Record<string, StoreApp> = {
  mbapp: {
    name: "MB App",
    developer: "MB Bank",
    icon: "🏦",
    color: "var(--bank)",
    tagline: "Mobile banking and Mini Apps",
    about: "Accounts, transfers and Mini Apps from partners such as Starlight Cinema — paid securely with your PIN or OTP.",
  },
  starlight: {
    name: "Starlight Cinema",
    developer: "Starlight Entertainment",
    icon: "★",
    color: "var(--partner)",
    tagline: "Movie tickets in seconds",
    about: "Browse what's showing and buy tickets. Link your MB Bank account once and pay with MB App.",
  },
};

type Props = { params: Promise<{ app: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const app = APPS[(await params).app];
  return { title: app ? `${app.name} · Store` : "Not found · Store" };
}

/** Mock app store page (the simulator's OS opens it when the target app is not installed). */
export default async function StorePage({ params }: Props) {
  const app = APPS[(await params).app];
  if (!app) notFound();
  return (
    <main className={s.page}>
      <div className={s.store}>Store</div>
      <section className={s.head}>
        <div className={s.icon} style={{ background: app.color }} aria-hidden>
          {app.icon}
        </div>
        <div className={s.title}>
          <h1>{app.name}</h1>
          <div className={s.dev}>{app.developer}</div>
          <div className={s.tag}>{app.tagline}</div>
        </div>
      </section>
      <button className={s.install} type="button" disabled aria-describedby="store-note">
        Install
      </button>
      <p id="store-note" className={s.note}>
        This is a mock store page: in the simulator, toggle &ldquo;installed&rdquo; in the phone&rsquo;s OS bar.
      </p>
      <section className={s.about}>
        <h2>About this app</h2>
        <p>{app.about}</p>
      </section>
    </main>
  );
}
