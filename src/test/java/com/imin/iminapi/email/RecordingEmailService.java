package com.imin.iminapi.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.transaction.support.TransactionSynchronizationManager;

public class RecordingEmailService implements EmailService {
    public record SentEmail(String to, String subject, String html, String text, java.util.Map<String, String> headers) {
        public SentEmail(String to, String subject, String html, String text) {
            this(to, subject, html, text, java.util.Map.of());
        }
    }

    private final List<SentEmail> sent = new ArrayList<>();
    // From header per sent email; null when the configured identity was used.
    private final List<String> froms = new ArrayList<>();
    // Whether each sent email went out while a transaction was active (a pooled connection held).
    private final List<Boolean> inTransaction = new ArrayList<>();
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
        froms.add(null);
        inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Override
    public synchronized void sendFrom(String fromHeader, String to, String subject, String html, String text) {
        send(to, subject, html, text, java.util.Map.of());
        froms.set(froms.size() - 1, fromHeader);
    }

    /** From header of the last email, or null when it used the configured identity. */
    public synchronized String lastFrom() { return froms.isEmpty() ? null : froms.get(froms.size() - 1); }

    public synchronized List<SentEmail> sent() { return Collections.unmodifiableList(new ArrayList<>(sent)); }
    public synchronized SentEmail lastSent() { return sent.isEmpty() ? null : sent.get(sent.size() - 1); }
    public synchronized boolean sentInTransaction(int index) { return inTransaction.get(index); }
    public synchronized void clear() { sent.clear(); froms.clear(); inTransaction.clear(); nextFailure = null; }
    public synchronized void failNextSendWith(RuntimeException ex) { this.nextFailure = ex; }
}
