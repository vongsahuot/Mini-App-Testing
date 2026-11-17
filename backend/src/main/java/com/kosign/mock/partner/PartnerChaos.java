package com.kosign.mock.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.Json;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Fault switches on the partner side (docs/CONTRACT.md, "Partner chaos"). */
@Component
public class PartnerChaos {
    public volatile boolean registerFail, ignoreNotifications, apiFail, createOrderSlow, badChecksum;
    public volatile int notifyFailCount;

    public Map<String, Object> view() {
        return Json.obj("registerFail", registerFail, "notifyFailCount", notifyFailCount, "ignoreNotifications", ignoreNotifications,
                "apiFail", apiFail, "createOrderSlow", createOrderSlow, "badChecksum", badChecksum);
    }

    public void apply(JsonNode b) {
        if (b.has("registerFail")) registerFail = b.get("registerFail").asBoolean();
        if (b.has("notifyFailCount")) notifyFailCount = Math.max(0, b.get("notifyFailCount").asInt());
        if (b.has("ignoreNotifications")) ignoreNotifications = b.get("ignoreNotifications").asBoolean();
        if (b.has("apiFail")) apiFail = b.get("apiFail").asBoolean();
        if (b.has("createOrderSlow")) createOrderSlow = b.get("createOrderSlow").asBoolean();
        if (b.has("badChecksum")) badChecksum = b.get("badChecksum").asBoolean();
    }

    public void reset() {
        registerFail = ignoreNotifications = apiFail = createOrderSlow = badChecksum = false;
        notifyFailCount = 0;
    }
}
