package com.imin.iminapi.security;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

public record ApiError(Body error) {

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(new Body(code.name(), message, null));
    }

    public static ApiError of(ErrorCode code, String message, Map<String, String> fields) {
        return new ApiError(new Body(code.name(), message, fields));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String code, String message, Map<String, String> fields) {}
}
