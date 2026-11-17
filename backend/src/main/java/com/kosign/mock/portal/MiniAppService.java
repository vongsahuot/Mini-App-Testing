package com.kosign.mock.portal;

import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mini App Service (lane MAS): list, detail, consent + partner registration, auth codes and the code exchange. */
@Service
public class MiniAppService {
    static final Map<String, String> SCOPE_LABELS = Map.of("name", "Your name", "phone", "Your phone number", "email", "Your email address");

    private final MiniApp.Repo apps;
    private final Partner.Repo partners;
    private final Consent.Repo consents;
    private final ExchangeInfo.Repo exchange;
    private final AuthCode.Repo codes;
    private final PartnerGateway gateway;
    private final Http http;
    private final Trace trace;
    private final String salt;
    private final long codeTtl;

    public MiniAppService(MiniApp.Repo apps, Partner.Repo partners, Consent.Repo consents, ExchangeInfo.Repo exchange,
                          AuthCode.Repo codes, PartnerGateway gateway, Http http, Trace trace,
                          @Value("${mock.pairwise-salt}") String salt, @Value("${mock.auth-code-ttl-seconds}") long codeTtl) {
        this.apps = apps;
        this.partners = partners;
        this.consents = consents;
        this.exchange = exchange;
        this.codes = codes;
        this.gateway = gateway;
        this.http = http;
        this.trace = trace;
        this.salt = salt;
        this.codeTtl = codeTtl;
    }

    // ---------- 1:4–1:5 list ----------

    public Map<String, Object> list(Cust c, String appVersion, String etag) {
        trace.record("MAS", "1:4", null, "Request Mini App list", c.id(), null);
        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        int shown = 0, hidden = 0;
        for (MiniApp a : apps.findAllByOrderBySortOrder()) {
            if (!listed(a, c, appVersion)) {
                hidden++;
                continue;
            }
            shown++;
            Map<String, Object> s = sections.computeIfAbsent(a.sectionId, id -> Json.obj("id", id, "title", a.sectionTitle, "apps", new ArrayList<>()));
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) s.get("apps");
            list.add(Json.obj("appId", a.appId, "name", a.name, "icon", a.icon, "type", a.type));
        }
        List<Object> body = new ArrayList<>(sections.values());
        String tag = "\"" + sha256(http.write(body)).substring(0, 16) + "\"";
        trace.record("MAS", "1:5", null, "Query enabled Mini Apps: eligibility rules + min MB App version",
                shown + " listed, " + hidden + " filtered out (disabled / not eligible / needs newer app)", null);
        boolean notModified = tag.equals(etag);
        return Json.obj("notModified", notModified, "etag", tag, "sections", notModified ? null : body);
    }

    private boolean listed(MiniApp a, Cust c, String appVersion) {
        return a.enabled && eligible(a, c) && versionAtLeast(appVersion, a.minAppVersion);
    }

    static boolean eligible(MiniApp a, Cust c) {
        return a.segments == null || Partner.split(a.segments).contains(c.segment());
    }

    static boolean versionAtLeast(String have, String need) {
        if (need == null) return true;
        if (have == null) return false;
        String[] h = have.split("\\."), n = need.split("\\.");
        for (int i = 0; i < Math.max(h.length, n.length); i++) {
            int hv = i < h.length ? parse(h[i]) : 0, nv = i < n.length ? parse(n[i]) : 0;
            if (hv != nv) return hv > nv;
        }
        return true;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------- 2:4–2:5a detail ----------

    public Map<String, Object> detail(Cust c, String appId) {
        MiniApp a = app(appId);
        trace.record("MAS", "2:4", a.type, "Request Mini App detail", null, appId);
        if (!a.enabled) {
            trace.record("MAS", "2:5", a.type, "Load app, consent status: app is disabled", "refused", appId);
            throw ApiException.conflict("APP_DISABLED", a.name + " is not available right now");
        }
        if (!eligible(a, c)) {
            trace.record("MAS", "2:5", a.type, "Load app, consent status: customer not eligible", c.segment(), appId);
            throw ApiException.forbidden("NOT_ELIGIBLE", "This Mini App is not available for your account");
        }
        Consent consent = consents.findById(Consent.id(c.id(), appId)).orElse(null);
        boolean accepted = consent != null && a.consentVersion.equals(consent.version);
        boolean registered = exchange.existsById(Consent.id(c.id(), appId));
        trace.record("MAS", "2:5", a.type, "Load app, consent status (accepted version vs current), exchange info",
                "consent " + (accepted ? "ACCEPTED" : "NOT_ACCEPTED") + " (accepted v" + (consent == null ? "-" : consent.version)
                        + ", current v" + a.consentVersion + "), registered " + registered, appId);
        if (accepted && a.registrationRequired && !registered) {
            trace.record("MAS", "2:5a", a.type, "Retry partner registration now (same as steps 15–19, incl. failure branch)", null, appId);
            registered = register(c, a, consent);
            if (!registered) throw new ApiException(502, "REGISTRATION_FAILED", "The partner could not register you, try again");
        }
        return view(a, consent, accepted, registered);
    }

    private Map<String, Object> view(MiniApp a, Consent consent, boolean accepted, boolean registered) {
        Partner p = partners.findById(a.clientId).orElseThrow();
        Map<String, Object> launch = switch (a.type) {
            case MiniApp.WEBVIEW -> Json.obj("entryUrl", a.entryUrl, "allowedOrigins", Partner.split(a.allowedOrigins));
            case MiniApp.API -> Json.obj("screens", List.of("catalog", "orders"));
            case MiniApp.SDK -> Json.obj("sdkModule", a.sdkModule, "minSdkVersion", a.minSdkVersion, "sdkEnabled", a.sdkEnabled);
            default -> Json.obj("appLink", a.appLink, "returnLink", a.returnLink, "storeUrl", a.storeUrl);
        };
        List<Object> scopes = a.scopeList().stream().map(s -> (Object) Json.obj("scope", s, "label", SCOPE_LABELS.getOrDefault(s, s))).toList();
        return Json.obj("appId", a.appId, "name", a.name, "icon", a.icon, "type", a.type, "merchantName", p.name,
                "consent", Json.obj("status", accepted ? "ACCEPTED" : "NOT_ACCEPTED", "currentVersion", a.consentVersion,
                        "acceptedVersion", consent == null ? null : consent.version, "scopes", scopes),
                "registered", registered, "launch", launch);
    }

    // ---------- 2:13–2:20 consent + registration ----------

    public Map<String, Object> consent(Cust c, String appId, String version) {
        MiniApp a = app(appId);
        trace.record("MAS", "2:13", a.type, "Request save consent", "v" + version, appId);
        if (!a.consentVersion.equals(version)) {
            trace.record("MAS", "2:14", a.type, "Check consentVersion is current: no", "customer saw v" + version + ", current v" + a.consentVersion, appId);
            throw ApiException.conflict("CONSENT_VERSION_CHANGED", "The consent text changed, please review it again");
        }
        Consent consent = consents.findById(Consent.id(c.id(), appId)).orElseGet(Consent::new);
        consent.id = Consent.id(c.id(), appId);
        consent.customerId = c.id();
        consent.appId = appId;
        consent.version = version;
        consent.scopes = a.scopes;
        consent.name = c.name();
        consent.phone = c.phone();
        consent.acceptedAt = Instant.now();
        consents.save(consent);
        boolean registered = exchange.existsById(consent.id);
        trace.record("MAS", "2:14", a.type, "Check consentVersion is current; save consent; check registration config",
                "registration " + (a.registrationRequired ? registered ? "done already" : "required" : "not required"), appId);
        if (a.registrationRequired && !registered) {
            registered = register(c, a, consent);
            if (!registered) throw new ApiException(502, "REGISTRATION_FAILED", "Consent saved, but the partner could not register you yet");
        }
        trace.record("MAS", "2:20", a.type, "Consent saved; registration done (if required)", null, appId);
        return Json.obj("status", "ACCEPTED", "registered", registered, "appType", a.type);
    }

    /** Steps 2:15–2:19 / 2:19a. Sends only the consented info; a retry must give back the same partnerUserId. */
    private boolean register(Cust c, MiniApp a, Consent consent) {
        Partner p = partners.findById(a.clientId).orElseThrow();
        String ref = a.appId;
        Map<String, Object> info = userInfo(a.scopeList(), consent);
        String bankUserRef = "bu_" + Hmac.hex(salt, p.clientId + ":" + c.id()).substring(0, 20);
        trace.record("PG", "2:15", a.type, "Register User API (consented info, signed, timeout ~5 s)", "fields " + info.keySet(), ref);
        Http.Resp r = gateway.outbound(p, PartnerGateway.Target.REGISTER, null,
                Json.obj("appId", a.appId, "bankUserRef", bankUserRef, "userInfo", info, "scopes", a.scopeList()), Duration.ofSeconds(5));
        String partnerUserId = r.ok() ? Json.str(r.body(), "partnerUserId") : null;
        if (partnerUserId == null) {
            trace.note("PG", "Register User failed", r.status() == 0 ? r.error() : "HTTP " + r.status() + " " + r.error(), ref);
            trace.record("MAS", "2:19a", a.type, "Save no exchange info; consent stays saved; return REGISTRATION_FAILED", r.error(), ref);
            return false;
        }
        trace.record("PG", "2:17", a.type, "partnerUserId", partnerUserId, ref);
        trace.record("MAS", "2:18", a.type, "User exchange info", partnerUserId, ref);
        ExchangeInfo e = new ExchangeInfo();
        e.id = Consent.id(c.id(), a.appId);
        e.customerId = c.id();
        e.appId = a.appId;
        e.clientId = p.clientId;
        e.partnerUserId = partnerUserId;
        e.bankUserRef = bankUserRef;
        e.createdAt = Instant.now();
        exchange.save(e);
        trace.record("MAS", "2:19", a.type, "Save exchange info (userId, appId, partnerUserId)", partnerUserId, ref);
        return true;
    }

    static Map<String, Object> userInfo(List<String> scopes, Consent consent) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (scopes.contains("name")) info.put("name", consent.name);
        if (scopes.contains("phone")) info.put("phone", consent.phone);
        if (scopes.contains("email")) info.put("email", null);
        return info;
    }

    // ---------- 2:30–2:31 / 2:A9–A10 auth code ----------

    public Map<String, Object> authCode(Cust c, String appId, String codeChallenge, String method) {
        MiniApp a = app(appId);
        boolean a2a = MiniApp.A2A.equals(a.type);
        trace.record("MAS", a2a ? "2:A9" : "2:30", a.type, "Request auth code", codeChallenge == null ? null : "with S256 code challenge", appId);
        Consent consent = consents.findById(Consent.id(c.id(), appId)).orElse(null);
        boolean current = consent != null && a.consentVersion.equals(consent.version);
        boolean registered = !a.registrationRequired || exchange.existsById(Consent.id(c.id(), appId));
        String check = a2a ? "2:A10" : "2:31";
        if (!a.enabled) throw ApiException.conflict("APP_DISABLED", a.name + " is not available right now");
        if (!current || !registered) {
            trace.record("MAS", check, a.type, "Consent current + registered? No", current ? "not registered" : "consent missing or outdated", appId);
            throw ApiException.forbidden(current ? "NOT_REGISTERED" : "CONSENT_REQUIRED", current
                    ? "The customer is not registered with the partner yet" : "The customer has not accepted the current consent");
        }
        if (a2a && codeChallenge == null) throw ApiException.badRequest("CODE_CHALLENGE_REQUIRED", "App-to-App linking needs an S256 code challenge");
        if (codeChallenge != null && method != null && !"S256".equals(method)) {
            throw ApiException.badRequest("UNSUPPORTED_CHALLENGE_METHOD", "Only S256 is supported");
        }
        AuthCode code = new AuthCode();
        code.code = "ac_" + Hmac.random(16);
        code.appId = appId;
        code.clientId = a.clientId;
        code.customerId = c.id();
        code.codeChallenge = codeChallenge;
        code.expiresAt = Instant.now().plusSeconds(codeTtl);
        codes.save(code);
        trace.record("MAS", check, a.type, "Consent current + registered? Create authCode (1 use, " + codeTtl + " s, customer + appId"
                + (codeChallenge == null ? ")" : " + codeChallenge)"), null, appId);
        return Json.obj("authCode", code.code, "expiresIn", codeTtl, "appType", a.type);
    }

    // ---------- 2:38–2:39 / 2:A18–A19 exchange ----------

    /** The app type of the code's Mini App, for picking step keys before the code is checked. */
    public String codeType(String code) {
        return code == null ? null : codes.findById(code).flatMap(x -> apps.findById(x.appId)).map(x -> x.type).orElse(null);
    }

    public Map<String, Object> exchange(Partner caller, String codeValue, String verifier) {
        AuthCode code = codeValue == null ? null : codes.findById(codeValue).orElse(null);
        MiniApp a = code == null ? null : apps.findById(code.appId).orElse(null);
        String type = a == null ? null : a.type;
        boolean a2a = MiniApp.A2A.equals(type);
        trace.record("MAS", a2a ? "2:A18" : "2:38", type, "Forward API", null, code == null ? null : code.appId);
        String problem = code == null ? "unknown code"
                : !code.clientId.equals(caller.clientId) ? "code was issued for another partner's appId"
                : code.used ? "code already used"
                : code.expiresAt.isBefore(Instant.now()) ? "code expired"
                : code.codeChallenge != null && (verifier == null || !Hmac.equal(s256(verifier), code.codeChallenge)) ? "codeVerifier does not match"
                : codes.burn(codeValue) == 0 ? "code already used" : null;
        String check = a2a ? "2:A19" : "2:39";
        if (problem != null) {
            trace.record("MAS", check, type, "Check code: " + problem, null, code == null ? null : code.appId);
            throw ApiException.badRequest("invalid_grant", problem);
        }
        trace.record("MAS", check, type, "Check code: unused, unexpired, same appId as caller"
                + (code.codeChallenge != null ? ", SHA-256(codeVerifier) == codeChallenge" : "") + " → mark used", null, code.appId);
        Consent consent = consents.findById(Consent.id(code.customerId, code.appId)).orElseThrow();
        ExchangeInfo e = exchange.findById(Consent.id(code.customerId, code.appId)).orElseThrow();
        return Json.obj("appId", code.appId, "type", type, "partnerUserId", e.partnerUserId, "scopes", a.scopeList(),
                "userInfo", userInfo(a.scopeList(), consent));
    }

    static String s256(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    MiniApp app(String appId) {
        return apps.findById(appId).orElseThrow(() -> ApiException.notFound("Mini App " + appId + " not found"));
    }

    String typeOf(String appId) {
        return appId == null ? null : apps.findById(appId).map(x -> x.type).orElse(null);
    }
}
