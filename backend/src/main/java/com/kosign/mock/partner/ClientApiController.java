package com.kosign.mock.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API for Starlight's own clients (C5): the WebView page, the SDK and the partner app. The bank never sees this
 * traffic. WebView sessions are HttpOnly cookies; SDK and partner app sessions are bearer tokens.
 */
@RestController
@RequestMapping("/api")
public class ClientApiController {
    static final String COOKIE = "starlight_session";

    private final PartnerSession.Repo sessions;
    private final PartnerOrder.Repo orders;
    private final OrderService orderService;
    private final BankApi bank;
    private final Trace trace;

    public ClientApiController(PartnerSession.Repo sessions, PartnerOrder.Repo orders, OrderService orderService, BankApi bank, Trace trace) {
        this.sessions = sessions;
        this.orders = orders;
        this.orderService = orderService;
        this.bank = bank;
        this.trace = trace;
    }

    /** 2:36–2:43 / 2:A16–A23: exchange the one-time authCode server-to-server, then create our own session. */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody JsonNode body, @RequestHeader(value = "X-Client-Kind", required = false) String kind) {
        String authCode = Json.required(body, "authCode");
        String verifier = Json.str(body, "codeVerifier");
        boolean a2a = "a2a".equals(kind);
        trace.record("PS", a2a ? "2:A16" : "2:36", kind, a2a ? "Send authCode + codeVerifier" : "Send authCode", null, null);
        Http.Resp r = bank.post("/auth/exchange", Json.obj("authCode", authCode, "codeVerifier", verifier));
        if (!r.ok()) {
            trace.record("PS", a2a ? "2:A19b" : "2:39b", kind, "Error", r.error() + ": " + Json.str(r.body(), "message"), null);
            throw new ApiException(r.status() == 0 ? 502 : r.status(), r.error(), String.valueOf(Json.str(r.body(), "message")));
        }
        JsonNode b = r.body();
        String appId = Json.str(b, "appId");
        String type = orderService.typeOf(appId);
        trace.record("PS", a2a ? "2:A21" : "2:41", type, "partnerUserId + user info", Json.str(b, "partnerUserId"), appId);
        PartnerSession s = new PartnerSession();
        s.token = "pss_" + Hmac.random(16);
        s.appId = appId;
        s.type = type;
        s.partnerUserId = Json.str(b, "partnerUserId");
        s.name = Json.str(b.path("userInfo"), "name");
        s.phone = Json.str(b.path("userInfo"), "phone");
        s.createdAt = Instant.now();
        sessions.save(s);
        trace.record("PS", a2a ? "2:A22" : "2:42", type, "Find partner user; create session", s.partnerUserId, appId);
        Map<String, Object> out = me(s);
        if ("webview".equals(type)) {
            ResponseCookie cookie = ResponseCookie.from(COOKIE, s.token).httpOnly(true).sameSite("Lax").path("/").build();
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(out);
        }
        out.put("sessionToken", s.token);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        return me(session(req));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest req) {
        String token = token(req);
        if (token != null) sessions.deleteById(token);
        ResponseCookie gone = ResponseCookie.from(COOKIE, "").httpOnly(true).sameSite("Lax").path("/").maxAge(0).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, gone.toString()).body(Json.obj("ok", true));
    }

    @GetMapping("/catalog")
    public List<OrderService.Item> catalog() {
        return OrderService.CATALOG;
    }

    /** Webview / SDK: 3:2–3:3, returns the sealed order. App-to-App: 3:D21–D28, registers the payment and returns the payLink. */
    @PostMapping("/orders")
    public Map<String, Object> create(HttpServletRequest req, @RequestBody JsonNode body) {
        PartnerSession s = session(req);
        String itemId = Json.required(body, "itemId");
        boolean a2a = "a2a".equals(s.type);
        trace.record("PS", a2a ? "3:D21" : "3:2", s.type, "Create order (items, partner session)", itemId, s.appId);
        PartnerOrder o = orderService.create(s.appId, s.partnerUserId, itemId, null, a2a ? "3:D22" : "3:3");
        if (!a2a) return orderService.sealed(o);

        Map<String, Object> sealed = orderService.sealed(o);
        Http.Resp r = bank.post("/payments", Json.obj("appId", s.appId, "order", sealed.get("order"), "checksum", sealed.get("checksum")));
        if (!r.ok()) {
            o.status = PartnerOrder.FAILED;
            orders.save(o);
            trace.record("PS", "3:D25b", s.type, "Error", r.error(), o.orderId);
            throw new ApiException(r.status() == 0 ? 502 : r.status(), r.error(), String.valueOf(Json.str(r.body(), "message")));
        }
        o.paymentRef = Json.str(r.body(), "paymentRef");
        orders.save(o);
        trace.record("PS", "3:D28", s.type, "paymentRef + payLink", o.paymentRef, o.orderId);
        Map<String, Object> out = OrderService.view(o);
        out.put("payLink", Json.str(r.body(), "payLink"));
        return out;
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> list(HttpServletRequest req) {
        PartnerSession s = session(req);
        return orders.findByPartnerUserIdOrderByCreatedAtDesc(s.partnerUserId).stream().filter(o -> o.appId.equals(s.appId))
                .map(OrderService::view).toList();
    }

    /** 3:47 / 3:D41: the client asks us for the real status; while the order is unpaid we ask the bank (inquiry). */
    @GetMapping("/orders/{orderId}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String orderId) {
        PartnerSession s = session(req);
        PartnerOrder o = owned(orderId, s);
        trace.record("PS", "a2a".equals(s.type) ? "3:D41" : "3:47", s.type, "Inquiry order status", o.status, orderId);
        return OrderService.view(orderService.inquire(o));
    }

    @PostMapping("/orders/{orderId}/refund")
    public Map<String, Object> refund(HttpServletRequest req, @PathVariable String orderId, @RequestBody(required = false) JsonNode body) {
        PartnerOrder o = owned(orderId, session(req));
        return OrderService.view(orderService.refund(o, Json.money(body, "amount")));
    }

    private Map<String, Object> me(PartnerSession s) {
        return Json.obj("appId", s.appId, "type", s.type, "partnerUserId", s.partnerUserId, "profile", Json.obj("name", s.name, "phone", s.phone));
    }

    private PartnerOrder owned(String orderId, PartnerSession s) {
        return orders.findById(orderId).filter(o -> o.partnerUserId.equals(s.partnerUserId) && o.appId.equals(s.appId))
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
    }

    private PartnerSession session(HttpServletRequest req) {
        String token = token(req);
        if (token == null) throw ApiException.unauthorized("MISSING_SESSION", "Sign in first");
        return sessions.findById(token).orElseThrow(() -> ApiException.unauthorized("INVALID_SESSION", "Session expired, sign in again"));
    }

    private static String token(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7).trim();
        if (req.getCookies() != null) {
            for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
