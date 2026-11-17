import type { Metadata } from "next";
import PartnerApp from "@/app/partner-app/PartnerApp";

export const metadata: Metadata = { title: "Starlight Cinema · App" };

/**
 * Verified links claimed by the partner app (`/links/starlight/open`, `/links/starlight/return?...`).
 * The client component reads the path and query itself (and drops one-time codes from the URL).
 */
export default function StarlightLinkPage() {
  return <PartnerApp />;
}
