/**
 * The simulator's mock phone OS (docs/CONTRACT.md, "App-to-App links"). Apps hand over only through verified
 * HTTPS-style links whose path is claimed by an installed app; custom URL schemes are refused.
 *
 * Partner app → OS: parent.postMessage({source: "os", type: "openUrl", url}, "*")
 * OS → partner app: {source: "os-reply", type: "openUrlResult", url, routedTo: "app" | "store" | "refused"}
 */

export type RoutedTo = "app" | "store" | "refused";

/** Link prefixes each app claims (its verified domain + path, registered at onboarding). */
export const APP_LINKS = {
  mbapp: "/links/mbapp/",
  starlight: "/links/starlight/",
} as const;
export type OsApp = keyof typeof APP_LINKS;

export const STORE_URL: Record<OsApp, string> = {
  mbapp: "/store/mbapp",
  starlight: "/store/starlight",
};

/** Which installed app claims this URL, or null (custom scheme, foreign host, unclaimed path). */
export function claimedBy(url: string, origin: string): OsApp | null {
  let u: URL;
  try {
    u = new URL(url, origin);
  } catch {
    return null;
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") return null;
  if (u.origin !== origin) return null;
  return (Object.keys(APP_LINKS) as OsApp[]).find((a) => u.pathname.startsWith(APP_LINKS[a])) ?? null;
}

/** Called by an app running inside the simulator's phone to open a link. Resolves with where the OS sent it. */
export function openUrl(url: string): Promise<RoutedTo> {
  if (typeof window === "undefined" || window.parent === window) {
    window.location.href = url;
    return Promise.resolve("app");
  }
  return new Promise((resolve) => {
    const onReply = (e: MessageEvent) => {
      const d = e.data as { source?: string; type?: string; url?: string; routedTo?: RoutedTo } | null;
      if (e.source !== window.parent || !d || d.source !== "os-reply" || d.type !== "openUrlResult" || d.url !== url) return;
      window.removeEventListener("message", onReply);
      resolve(d.routedTo ?? "refused");
    };
    window.addEventListener("message", onReply);
    window.parent.postMessage({ source: "os", type: "openUrl", url }, "*");
    setTimeout(() => {
      window.removeEventListener("message", onReply);
      resolve("refused");
    }, 5000);
  });
}
