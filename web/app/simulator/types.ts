import type { ApiOptions } from "@/lib/api";
import type { Flow } from "@/lib/trace";

export interface Account {
  accountNo: string;
  name: string;
  balance: number;
  held?: number;
  available?: number;
  currency: string;
}
export interface BankSession {
  sessionToken: string;
  customerId: string;
  name: string;
  phone: string;
  segment: string;
  accounts: Account[];
}
export interface MiniAppTile {
  appId: string;
  name: string;
  icon: string;
  type: Flow;
}
export interface MiniAppList {
  sections: { id: string; title: string; apps: MiniAppTile[] }[];
  etag: string;
}
export interface MiniAppDetail {
  appId: string;
  name: string;
  icon: string;
  type: Flow;
  merchantName: string;
  consent: {
    status: "ACCEPTED" | "NOT_ACCEPTED";
    currentVersion: string;
    acceptedVersion: string | null;
    scopes: { scope: string; label: string }[];
  };
  registered: boolean;
  launch: {
    entryUrl?: string;
    allowedOrigins?: string[];
    screens?: string[];
    sdkModule?: string;
    minSdkVersion?: string;
    sdkEnabled?: boolean;
    appLink?: string;
    returnLink?: string;
    storeUrl?: string;
  };
}
export interface PaymentInfo {
  paymentRef: string;
  appId: string;
  orderId: string;
  merchantName: string;
  amount: number;
  currency: string;
  status: string;
  expiresAt: string;
}
export interface Notice {
  id: number;
  kind: "PAYMENT" | "REFUND" | "OTP";
  title: string;
  body: string;
  ref: string | null;
  createdAt: string;
}

/** Calls the app gateway (C1) with the customer session. */
export type Gw = <T>(path: string, opts?: ApiOptions) => Promise<T>;
