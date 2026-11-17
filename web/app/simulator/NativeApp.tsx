"use client";

import { useCallback, useEffect, useState } from "react";
import { errorText, money } from "@/lib/api";
import type { PayResult } from "@/lib/bridge";
import { trace } from "@/lib/trace";
import type { Gw, MiniAppDetail, PaymentInfo } from "./types";
import s from "./sim.module.css";

interface CatalogRow {
  itemId: string;
  title: string;
  subtitle?: string;
  price: number;
  currency: string;
  poster?: string;
}
interface OrderRow {
  orderId: string;
  title: string;
  amount: number;
  currency: string;
  status: string;
  ticketCode?: string;
  seat?: string;
}
type View =
  | { k: "catalog" }
  | { k: "orders" }
  | { k: "confirm"; item: CatalogRow; key: string }
  | { k: "result"; result: PayResult; item: CatalogRow };

/**
 * API linkage (lane APP, flow "api"): native screens built by the bank. All partner data comes server-to-server
 * through Mini App Service and is rendered as plain text, never as HTML.
 */
export function NativeApp({
  detail,
  gw,
  pay,
  onClose,
}: {
  detail: MiniAppDetail;
  gw: Gw;
  pay: (info: PaymentInfo) => Promise<PayResult>;
  onClose: () => void;
}) {
  const appId = detail.appId;
  const [view, setView] = useState<View>({ k: "catalog" });
  const [rows, setRows] = useState<CatalogRow[] | OrderRow[] | null>(null);
  const [cached, setCached] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    trace("APP", "2:L1", "api", detail.launch.screens?.join(", "), appId);
  }, [appId, detail.launch.screens]);

  const load = useCallback(
    async (operation: "catalog" | "orders") => {
      setRows(null);
      setError(null);
      try {
        const r = await gw<{ data: CatalogRow[] | OrderRow[]; cached: boolean }>(`/mini-apps/${encodeURIComponent(appId)}/content`, {
          body: { operation, params: {} },
        });
        trace("APP", "2:L13", "api", `${operation}: ${r.data.length} rows${r.cached ? " (cached)" : ""}`, appId);
        setCached(r.cached);
        setRows(r.data);
        trace("U", "2:L14", "api", operation, appId);
      } catch (e) {
        trace("APP", "2:L8d", "api", errorText(e), appId);
        trace("APP", "2:L8e", "api", null, appId);
        setError("Service unavailable, try again");
      }
    },
    [appId, gw],
  );

  useEffect(() => {
    if (view.k === "catalog" || view.k === "orders") void load(view.k);
  }, [view.k, load]);

  async function buy(item: CatalogRow, key: string) {
    trace("APP", "3:L15", "api", `${item.title} · key ${key.slice(0, 8)}`, appId);
    setBusy("Creating your order…");
    let info: PaymentInfo;
    try {
      info = await gw<PaymentInfo>(`/mini-apps/${encodeURIComponent(appId)}/orders`, {
        body: { items: [{ itemId: item.itemId, qty: 1 }], idempotencyKey: key },
      });
      trace("APP", "3:L28", "api", `${info.paymentRef} ${money(info.amount, info.currency)}`, info.paymentRef);
    } catch (e) {
      trace("APP", "3:L24d", "api", errorText(e), appId);
      trace("U", "3:L24e", "api", errorText(e), appId);
      setError(`Could not create the order — ${errorText(e)}`);
      return;
    } finally {
      setBusy(null);
    }
    const result = await pay(info);
    trace("U", "3:L29", "api", result.status, info.paymentRef);
    trace("APP", "3:L30", "api", "result from the bank's own payment status; no callback to partner code", info.paymentRef);
    setView({ k: "result", result, item });
  }

  return (
    <div className={s.webview}>
      <div className={s.webviewBar}>
        <strong>
          {detail.icon} {detail.name}
        </strong>
        <button onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>
      <div className={s.native}>
        <div className={s.nativeTabs}>
          <button aria-pressed={view.k === "catalog" || view.k === "confirm"} onClick={() => setView({ k: "catalog" })}>
            Now showing
          </button>
          <button aria-pressed={view.k === "orders"} onClick={() => setView({ k: "orders" })}>
            My tickets
          </button>
        </div>
        <p className={s.hint}>Native MB App screens · data from {detail.merchantName} via the bank{cached ? " · cached" : ""}</p>
        {error && (
          <div className={s.error}>
            {error}{" "}
            {(view.k === "catalog" || view.k === "orders") && (
              <button className={s.link} onClick={() => void load(view.k as "catalog" | "orders")}>
                Retry
              </button>
            )}
          </div>
        )}

        {view.k === "catalog" &&
          !error &&
          (rows === null ? (
            <div className={s.hint}>Loading…</div>
          ) : (
            (rows as CatalogRow[]).map((r) => (
              <div key={r.itemId} className={s.nativeRow}>
                <span className={s.appIcon}>{r.poster ?? "🎬"}</span>
                <div>
                  <strong>{r.title}</strong>
                  <small>{r.subtitle}</small>
                  <b className="mono">{money(r.price, r.currency)}</b>
                </div>
                <button className={s.primary} onClick={() => setView({ k: "confirm", item: r, key: crypto.randomUUID() })}>
                  Buy
                </button>
              </div>
            ))
          ))}

        {view.k === "orders" &&
          !error &&
          (rows === null ? (
            <div className={s.hint}>Loading…</div>
          ) : rows.length === 0 ? (
            <div className={s.hint}>No tickets yet.</div>
          ) : (
            (rows as OrderRow[]).map((o) => (
              <div key={o.orderId} className={s.nativeRow}>
                <div>
                  <strong>{o.title}</strong>
                  <small className="mono">{o.orderId}</small>
                  <small>
                    {money(o.amount, o.currency)} · {o.status}
                    {o.ticketCode ? ` · 🎟 ${o.ticketCode} seat ${o.seat}` : ""}
                  </small>
                </div>
              </div>
            ))
          ))}

        {view.k === "confirm" && (
          <div className={s.nativeCard}>
            <h3>Confirm your order</h3>
            <dl className={s.kv}>
              <dt>Movie</dt>
              <dd>{view.item.title}</dd>
              <dt>Shown price</dt>
              <dd>{money(view.item.price, view.item.currency)}</dd>
            </dl>
            <p className={s.hint}>The partner sets the final price and seals the order; MB App shows the bank&apos;s amount on the payment sheet.</p>
            <div className={s.sheetActions}>
              <button className={s.ghost} onClick={() => setView({ k: "catalog" })}>
                Back
              </button>
              <button className={s.primary} disabled={!!busy} onClick={() => void buy(view.item, view.key)}>
                Confirm order
              </button>
            </div>
          </div>
        )}

        {view.k === "result" && (
          <div className={s.nativeCard}>
            <div className={view.result.status === "SUCCESS" ? s.receiptOk : s.receiptBad}>
              {view.result.status === "SUCCESS" ? "✓" : view.result.status === "PROCESSING" ? "…" : view.result.status === "CANCELLED" ? "×" : "!"}
            </div>
            <h3 style={{ textAlign: "center" }}>
              {view.result.status === "SUCCESS"
                ? "Paid — your ticket is on its way"
                : view.result.status === "PROCESSING"
                  ? "Payment is being processed"
                  : view.result.status === "CANCELLED"
                    ? "Payment cancelled"
                    : `Payment failed${view.result.errorCode ? ` (${view.result.errorCode})` : ""}`}
            </h3>
            <p className={s.hint}>{view.item.title}. The partner learns the result from the bank&apos;s signed notification.</p>
            <div className={s.sheetActions}>
              <button className={s.ghost} onClick={() => setView({ k: "catalog" })}>
                Keep browsing
              </button>
              <button className={s.primary} onClick={() => setView({ k: "orders" })}>
                My tickets
              </button>
            </div>
          </div>
        )}

        {busy && (
          <div className={s.wait}>
            <div className={s.spinner} />
            {busy}
          </div>
        )}
      </div>
    </div>
  );
}
