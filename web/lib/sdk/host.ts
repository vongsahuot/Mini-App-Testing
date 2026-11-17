import type { Order, PayResult } from "../bridge";

/**
 * The only thing MB App gives the partner SDK (docs/CONTRACT.md, "SDK host API").
 * The SDK gets no session, no customer data and no access to MB App storage or screens.
 */
export interface HostApi {
  login(): Promise<{ authCode: string }>;
  openPayment(order: Order, checksum: string): Promise<PayResult>;
  close(): void;
}

/** Version of the partner SDK bundled into this MB App build (checked against minSdkVersion). */
export const BUNDLED_SDK_VERSION = "2.1.0";

export function versionAtLeast(have: string, need: string | null | undefined): boolean {
  if (!need) return true;
  const h = have.split(".").map((x) => parseInt(x, 10) || 0);
  const n = need.split(".").map((x) => parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(h.length, n.length); i++) {
    if ((h[i] ?? 0) !== (n[i] ?? 0)) return (h[i] ?? 0) > (n[i] ?? 0);
  }
  return true;
}
