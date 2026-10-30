package com.kosign.mock.partner;

import org.springframework.stereotype.Component;

/** Fault switches for the webhook retry and status-check fallback paths. */
@Component
public class PartnerChaos {
    /** Answer the next N webhooks with HTTP 500 → KOSIGN retries with backoff. */
    public volatile int webhookFailCount;
    /** Answer 200 but ignore webhooks → the page's status check (51–53) must deliver the ticket. */
    public volatile boolean ignoreWebhooks;
}
