"use client";

import { useEffect, useRef, useState } from "react";
import { errorText, isApiError, money } from "@/lib/api";
import type { PayResult } from "@/lib/bridge";
import { trace } from "@/lib/trace";
import type { Account, Gw, PaymentInfo } from "./types";
import s from "./sim.module.css";

type Stage =
  | { k: "choose"; error?: string; initFailed?: string }
  | { k: "busy"; text: string }
  | { k: "auth"; method: "PIN" | "OTP"; error?: string }
  | { k: "done"; result: PayResult; title: string; detail?: string };

interface ConfirmResult {
  paymentRef: string;
  status: "SUCCESS" | "FAILED" | "PROCESSING";
  bankTxnId: string | null;
  failReason: string | null;
}

/**
 * MB App's own payment bottom sheet, shared by every integration type (section 3, steps 14–45g).
 * Merchant and amount always come from the bank's PaymentInfo, never from the partner side.
 * Resolves with a UI hint for the partner side: SUCCESS / FAILED / PROCESSING / CANCELLED.
 */
export function PaymentSheet({
  info,
  accounts,
  gw,
  otp,
  onDone,
}: {
  info: PaymentInfo;
  accounts: Account[];
  gw: Gw;
  /** Latest OTP received by push for this paymentRef (mock SMS inbox). */
  otp: string | null;
  onDone: (r: PayResult) => void;
}) {
  const [stage, setStage] = useState<Stage>({ k: "choose" });
  const [account, setAccount] = useState(accounts[0]?.accountNo ?? "");
  const [code, setCode] = useState("");
  const initiated = useRef(false);
  const ref = info.paymentRef;

  useEffect(() => {
    trace("U", "3:14", null, `${info.merchantName} ${money(info.amount, info.currency)}`, ref);
  }, [info, ref]);

  async function pay() {
    trace("APP", "3:15", null, `account ${account}`, ref);
    setStage({ k: "busy", text: "Checking your account…" });
    try {
      const r = await gw<{ authMethod: "PIN" | "OTP" }>(`/payments/${encodeURIComponent(ref)}/init`, { body: { account } });
      initiated.current = true;
      trace("APP", "3:27", null, `auth method ${r.authMethod}`, ref);
      trace("U", "3:28", null, r.authMethod, ref);
      setCode("");
      setStage({ k: "auth", method: r.authMethod });
    } catch (e) {
      const code = isApiError(e) ? e.code : "ERROR";
      trace("APP", "3:22d", null, code, ref);
      trace("APP", "3:22e", null, errorText(e), ref);
      setStage({ k: "choose", error: errorText(e), initFailed: code });
    }
  }

  async function confirm(method: "PIN" | "OTP") {
    trace("APP", "3:29", null, method, ref);
    setStage({ k: "busy", text: "Processing payment…" });
    let r: ConfirmResult;
    try {
      r = await gw<ConfirmResult>(`/payments/${encodeURIComponent(ref)}/confirm`, { body: method === "OTP" ? { otp: code } : { pin: code } });
    } catch (e) {
      if (isApiError(e) && e.status === 401) {
        const left = e.details?.attemptsLeft;
        trace("APP", "3:32b", null, e.code, ref);
        trace("APP", "3:32c", null, `attempts left ${left ?? "?"}`, ref);
        setCode("");
        setStage({ k: "auth", method, error: e.message });
        return;
      }
      if (isApiError(e) && e.status === 423) {
        trace("APP", "3:32b", null, "AUTH_LOCKED", ref);
        finish({ status: "FAILED", paymentRef: ref, errorCode: "AUTH_LOCKED" }, "Authentication locked", e.message);
        return;
      }
      if (isApiError(e) && (e.status === 0 || e.status >= 500)) return statusFallback();
      finish({ status: "FAILED", paymentRef: ref, errorCode: isApiError(e) ? e.code : "ERROR" }, "Payment failed", errorText(e));
      return;
    }
    const [a, b] = r.status === "SUCCESS" ? ["3:44", "3:45"] : r.status === "FAILED" ? ["3:38e", "3:38f"] : ["3:38j", "3:38k"];
    trace("APP", a, null, `${r.status} ${r.bankTxnId ?? r.failReason ?? ""}`.trim(), ref);
    trace("APP", b, null, null, ref);
    finish({ status: r.status, paymentRef: ref, errorCode: r.failReason }, titleFor(r.status), r.bankTxnId ?? r.failReason ?? undefined);
  }

  /** MB App got no answer: ask for the real status (3:45a–45g). */
  async function statusFallback() {
    setStage({ k: "busy", text: "Checking the payment status…" });
    try {
      const r = await gw<ConfirmResult>(`/payments/${encodeURIComponent(ref)}/status`);
      trace("APP", "3:45f", null, r.status, ref);
      trace("APP", "3:45g", null, r.status === "PROCESSING" || r.status === ("INITIATED" as string) ? "being processed" : r.status, ref);
      const status = r.status === "SUCCESS" || r.status === "FAILED" ? r.status : "PROCESSING";
      finish({ status, paymentRef: ref, errorCode: r.failReason }, titleFor(status), r.bankTxnId ?? r.failReason ?? undefined);
    } catch (e) {
      finish({ status: "PROCESSING", paymentRef: ref }, titleFor("PROCESSING"), errorText(e));
    }
  }

  function finish(result: PayResult, title: string, detail?: string) {
    setStage({ k: "done", result, title, detail });
  }

  function cancel() {
    const st = stage.k === "choose" ? stage : null;
    onDone(
      st?.initFailed
        ? { status: "FAILED", paymentRef: ref, errorCode: st.initFailed }
        : { status: "CANCELLED", paymentRef: initiated.current ? ref : null },
    );
  }

  return (
    <>
      <h3>{stage.k === "done" ? stage.title : "Pay with MB Bank"}</h3>
      <div className={s.amount}>{money(info.amount, info.currency)}</div>
      <dl className={s.kv}>
        <dt>Merchant</dt>
        <dd>{info.merchantName}</dd>
        <dt>Order</dt>
        <dd className="mono">{info.orderId}</dd>
        <dt>Payment</dt>
        <dd className="mono">{ref}</dd>
      </dl>

      {stage.k === "choose" && (
        <>
          <label className={s.field}>
            Pay from
            <select value={account} onChange={(e) => setAccount(e.target.value)}>
              {accounts.map((a) => (
                <option key={a.accountNo} value={a.accountNo}>
                  {a.accountNo} · {money(a.available ?? a.balance, a.currency)} available
                </option>
              ))}
            </select>
          </label>
          {stage.error && <div className={s.error}>{stage.error}</div>}
          <div className={s.sheetActions}>
            <button className={s.ghost} onClick={cancel}>
              Cancel
            </button>
            <button className={s.primary} disabled={!account} onClick={pay}>
              {stage.error ? "Try again" : "Pay"}
            </button>
          </div>
        </>
      )}

      {stage.k === "busy" && (
        <div className={s.wait}>
          <div className={s.spinner} />
          {stage.text}
        </div>
      )}

      {stage.k === "auth" && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void confirm(stage.method);
          }}
        >
          <p className={s.hint}>
            {stage.method === "OTP"
              ? `Enter the 6-digit OTP sent to your phone (amounts ≥ 20.00).${otp ? ` Push says: ${otp}` : ""}`
              : "Enter your PIN (mock: 1234). Only MB App ever asks for it."}
          </p>
          <input
            className={s.pin}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
            type="password"
            inputMode="numeric"
            autoFocus
            aria-label={stage.method}
          />
          {stage.error && <div className={s.error}>{stage.error}</div>}
          <div className={s.sheetActions}>
            <button type="button" className={s.ghost} onClick={cancel}>
              Cancel
            </button>
            <button type="submit" className={s.primary} disabled={code.length < 4}>
              Confirm
            </button>
          </div>
        </form>
      )}

      {stage.k === "done" && (
        <>
          <div className={stage.result.status === "SUCCESS" ? s.receiptOk : s.receiptBad}>
            {stage.result.status === "SUCCESS" ? "✓" : stage.result.status === "PROCESSING" ? "…" : "!"}
          </div>
          {stage.detail && <p className={`${s.hint} mono`}>{stage.detail}</p>}
          <div className={s.sheetActions}>
            <button className={s.primary} onClick={() => onDone(stage.result)}>
              Done
            </button>
          </div>
        </>
      )}
    </>
  );
}

function titleFor(status: string) {
  return status === "SUCCESS" ? "Payment successful" : status === "FAILED" ? "Payment failed" : "Payment is being processed";
}
