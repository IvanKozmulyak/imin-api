package com.imin.iminapi.marketing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Upsert body. {@code capiAccessToken} is the organizer-minted plaintext token;
 * it is encrypted before persistence and never echoed back. When blank on an
 * update, the existing stored token is preserved (so a re-save of just the pixel
 * id doesn't wipe the token).
 */
public record PutMetaConnectionRequest(
        @NotBlank String pixelId,
        String capiAccessToken,
        String testEventCode
) {}
