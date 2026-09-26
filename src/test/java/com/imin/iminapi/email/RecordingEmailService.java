package com.imin.iminapi.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecordingEmailService implements EmailService {
    public record SentEmail(String to, String subject, String html, String text, java.util.Map<String, String> headers) {
        public SentEmail(String to, String subject, String html, String text) {
            this(to, subject, html, text, java.util.Map.of());
        }
    }

    private final List<SentEmail> sent = new ArrayList<>();
    private RuntimeException nextFailure;

    @Override
    public synchronized void send(String to, String subject, String html, String text) {
        send(to, subject, html, text, java.util.Map.of());
    }

    @Override
    public synchronized void send(String to, String subject, String html, String text,
                                  java.util.Map<String, String> headers) {
        if (nextFailure != null) {
            RuntimeException toThrow = nextFailure;
            nextFailure = null;
            throw toThrow;
        }
        sent.add(new SentEmail(to, subject, html, text, headers == null ? java.util.Map.of() : headers));
    }

    public synchronized List<SentEmail> sent() { return Collections.unmodifiableList(new ArrayList<>(sent)); }
    public synchronized SentEmail lastSent() { return sent.isEmpty() ? null : sent.get(sent.size() - 1); }
    public synchronized void clear() { sent.clear(); nextFailure = null; }
    public synchronized void failNextSendWith(RuntimeException ex) { this.nextFailure = ex; }
}
