package com.imin.iminapi.email;

public interface EmailService {
    /**
     * Send an email synchronously. Throws ApiException on failure.
     * Callers decide whether to propagate or swallow per the spec's sync split.
     */
    void send(String to, String subject, String html, String text);
}
