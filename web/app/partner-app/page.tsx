import type { Metadata } from "next";
import PartnerApp from "./PartnerApp";

export const metadata: Metadata = { title: "Starlight Cinema · App" };

/** The partner app opened directly (app icon). */
export default function PartnerAppPage() {
  return <PartnerApp />;
}
