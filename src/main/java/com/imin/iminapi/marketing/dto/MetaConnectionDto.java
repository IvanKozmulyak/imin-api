package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.model.MetaPixelConnection;

/**
 * Connection view. The CAPI token is write-only: this DTO exposes only
 * {@code hasToken} (whether one is stored), never the token itself (spec §5).
 */
public record MetaConnectionDto(
        boolean connected,
        String pixelId,
        boolean hasToken,
        String testEventCode,
        String status
) {
    public static MetaConnectionDto notConnected() {
        return new MetaConnectionDto(false, null, false, null, null);
    }

    public static MetaConnectionDto from(MetaPixelConnection c) {
        return new MetaConnectionDto(true, c.getPixelId(),
                c.getCapiAccessTokenEnc() != null && !c.getCapiAccessTokenEnc().isBlank(),
                c.getTestEventCode(), c.getStatus());
    }
}
