package com.kosign.mock.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * HMAC order checksum (docs/CONTRACT.md, "Order checksum"): the partner server seals
 * appId|orderId|amount|currency|partnerUserId|expiry with its per-partner order key.
 */
public final class Checksum {
    private Checksum() {}

    public static String canonical(String appId, String orderId, BigDecimal amount, String currency, String partnerUserId, Instant expiry) {
        return String.join("|", appId, orderId, amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currency,
                partnerUserId, expiry(expiry));
    }

    /** ISO-8601 UTC, seconds precision, "Z" suffix. */
    public static String expiry(Instant expiry) {
        return expiry.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public static String sign(String key, String appId, String orderId, BigDecimal amount, String currency, String partnerUserId, Instant expiry) {
        return Hmac.hex(key, canonical(appId, orderId, amount, currency, partnerUserId, expiry));
    }
}
