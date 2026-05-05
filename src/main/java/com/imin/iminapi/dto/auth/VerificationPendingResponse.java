package com.imin.iminapi.dto.auth;

public record VerificationPendingResponse(String message, String email) {
    public static VerificationPendingResponse forEmail(String email) {
        return new VerificationPendingResponse("Verification email sent", email);
    }
}
