package com.kosign.mock.common;

/** Thrown by controllers and services; rendered as {@code {"code", "message"}} with the given HTTP status. */
public class ApiException extends RuntimeException {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) { return new ApiException(400, code, message); }
    public static ApiException unauthorized(String code, String message) { return new ApiException(401, code, message); }
    public static ApiException forbidden(String code, String message) { return new ApiException(403, code, message); }
    public static ApiException notFound(String message) { return new ApiException(404, "NOT_FOUND", message); }
    public static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
}
