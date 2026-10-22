package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.model.MetaPixelConnection;

/**
 * Connection view. The CAPI token is write-only: this DTO exposes only
 * {@code hasToken} (whether one is stored), never the token itself (spec §5).
 *
 * @param available true when the server can store a CAPI token (META_CAPI_ENC_KEY is a valid
 *                  32-byte key); while false, saving a connection fails, so clients offer no form
 */
public record MetaConnectionDto(
        boolean connected,
        String pixelId,
        boolean hasToken,
        String testEventCode,
        String status,
        boolean available
) {
    public static MetaConnectionDto notConnected(boolean available) {
        return new MetaConnectionDto(false, null, false, null, null, available);
    }

    public static MetaConnectionDto from(MetaPixelConnection c, boolean available) {
        return new MetaConnectionDto(true, c.getPixelId(),
                c.getCapiAccessTokenEnc() != null && !c.getCapiAccessTokenEnc().isBlank(),
                c.getTestEventCode(), c.getStatus(), available);
    }
}
