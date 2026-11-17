package com.kosign.mock.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thrown by controllers and services; rendered as {@code {"code", "message", ...extra}} with the given HTTP status. */
public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final Map<String, Object> extra;

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(int status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public static ApiException badRequest(String code, String message) { return new ApiException(400, code, message); }
    public static ApiException unauthorized(String code, String message) { return new ApiException(401, code, message); }
    public static ApiException forbidden(String code, String message) { return new ApiException(403, code, message); }
    public static ApiException notFound(String message) { return new ApiException(404, "NOT_FOUND", message); }
    public static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
    public static ApiException unprocessable(String code, String message) { return new ApiException(422, code, message); }

    /** Re-throws another service's error answer with the same status, code, message and extra fields. */
    public static ApiException from(Http.Resp r) {
        int status = r.status() == 0 ? 504 : r.status();
        Map<String, Object> extra = new LinkedHashMap<>();
        r.body().fields().forEachRemaining(e -> {
            if (!e.getKey().equals("code") && !e.getKey().equals("message")) extra.put(e.getKey(), e.getValue());
        });
        String message = Json.str(r.body(), "message");
        return new ApiException(status, r.error(), message == null ? r.error() : message, extra);
    }
}
