package com.imin.iminapi.email;

public interface EmailService {
    /**
     * Send an email synchronously. Throws ApiException on failure.
     * Callers decide whether to propagate or swallow per the spec's sync split.
     */
    void send(String to, String subject, String html, String text);

    /**
     * As {@link #send(String, String, String, String)} with extra MIME headers (e.g. the AI Act
     * Art.50 marker). Implementations that cannot carry headers must not silently drop them.
     */
    default void send(String to, String subject, String html, String text, java.util.Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot send extra headers");
        }
        send(to, subject, html, text);
    }

    /**
     * As {@link #send(String, String, String, String)} but From {@code fromHeader} instead of the configured
     * transactional identity (e.g. {@code "<Organizer> via IMIN" <addr>}). Implementations must not drop it.
     */
    default void sendFrom(String fromHeader, String to, String subject, String html, String text) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot send with another From");
    }
}
