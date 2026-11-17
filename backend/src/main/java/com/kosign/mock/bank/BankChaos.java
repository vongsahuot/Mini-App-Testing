package com.kosign.mock.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.Json;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Fault switches for Local Gateway / Core Banking (docs/CONTRACT.md, "Bank chaos"). */
@Component
public class BankChaos {
    public volatile boolean initFail, initTimeout, completeFail, completeTimeout, completeLost, lgDown, cancelFail,
            refundFail, refundTimeout, refundLost, settlementMismatch;

    public Map<String, Object> view() {
        return Json.obj("initFail", initFail, "initTimeout", initTimeout, "completeFail", completeFail,
                "completeTimeout", completeTimeout, "completeLost", completeLost, "lgDown", lgDown, "cancelFail", cancelFail,
                "refundFail", refundFail, "refundTimeout", refundTimeout, "refundLost", refundLost, "settlementMismatch", settlementMismatch);
    }

    public void apply(JsonNode b) {
        if (b.has("initFail")) initFail = b.get("initFail").asBoolean();
        if (b.has("initTimeout")) initTimeout = b.get("initTimeout").asBoolean();
        if (b.has("completeFail")) completeFail = b.get("completeFail").asBoolean();
        if (b.has("completeTimeout")) completeTimeout = b.get("completeTimeout").asBoolean();
        if (b.has("completeLost")) completeLost = b.get("completeLost").asBoolean();
        if (b.has("lgDown")) lgDown = b.get("lgDown").asBoolean();
        if (b.has("cancelFail")) cancelFail = b.get("cancelFail").asBoolean();
        if (b.has("refundFail")) refundFail = b.get("refundFail").asBoolean();
        if (b.has("refundTimeout")) refundTimeout = b.get("refundTimeout").asBoolean();
        if (b.has("refundLost")) refundLost = b.get("refundLost").asBoolean();
        if (b.has("settlementMismatch")) settlementMismatch = b.get("settlementMismatch").asBoolean();
    }

    public void reset() {
        initFail = initTimeout = completeFail = completeTimeout = completeLost = lgDown = cancelFail = refundFail =
                refundTimeout = refundLost = settlementMismatch = false;
    }
}
