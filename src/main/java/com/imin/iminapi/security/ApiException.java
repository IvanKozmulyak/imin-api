package com.imin.iminapi.security;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ErrorCode code;
    private final Map<String, String> fields;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, ErrorCode code, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    public HttpStatus status() { return status; }
    public ErrorCode code() { return code; }
    public Map<String, String> fields() { return fields; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, what + " not found");
    }
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, message);
    }
    public static ApiException invalidState(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, message);
    }
    public static ApiException staleWrite() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.STALE_WRITE, "Resource modified by another request");
    }
    public static ApiException duplicate(String field, String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE, message, Map.of(field, "already exists"));
    }
    public static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, "Too many requests");
    }
}
