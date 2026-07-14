package com.imin.iminapi.marketing.dto;

/**
 * Relayed Meta CAPI response. {@code eventsReceived} is Meta's echo of accepted
 * events; {@code messages} carries any validation warnings; {@code fbtraceId} is
 * Meta's trace id for support. {@code ok} = HTTP 2xx from Graph.
 */
public record MetaTestEventResult(
        boolean ok,
        Integer eventsReceived,
        String messages,
        String fbtraceId
) {
    public static MetaTestEventResult failure(String message) {
        return new MetaTestEventResult(false, 0, message, null);
    }
}
