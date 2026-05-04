package com.imin.iminapi.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecordingEmailService implements EmailService {
    public record SentEmail(String to, String subject, String html, String text) {}

    private final List<SentEmail> sent = new ArrayList<>();
    private RuntimeException nextFailure;

    @Override
    public synchronized void send(String to, String subject, String html, String text) {
        if (nextFailure != null) {
            RuntimeException toThrow = nextFailure;
            nextFailure = null;
            throw toThrow;
        }
        sent.add(new SentEmail(to, subject, html, text));
    }

    public synchronized List<SentEmail> sent() { return Collections.unmodifiableList(new ArrayList<>(sent)); }
    public synchronized SentEmail lastSent() { return sent.isEmpty() ? null : sent.get(sent.size() - 1); }
    public synchronized void clear() { sent.clear(); nextFailure = null; }
    public synchronized void failNextSendWith(RuntimeException ex) { this.nextFailure = ex; }
}
