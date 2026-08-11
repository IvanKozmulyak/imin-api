package com.imin.iminapi.refund;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Mints the buyer-facing refund reference: {@code REQ-8K2M-26}.
 *
 * <p>Shape is {@code REQ-} + four random symbols + {@code -} + the two-digit year.
 * The alphabet drops {@code 0/O} and {@code 1/I} so the code survives being read out
 * over a phone line, and everything is uppercase so it survives being written down.
 * The year suffix scopes the collision space per year and lets support date a request
 * at a glance.
 *
 * <p>32^4 = 1,048,576 codes per year. Callers still pre-check the DB and retry (see
 * {@link RefundRequestService#submitByToken}); the UNIQUE index from V81 is the
 * authority.
 */
@org.springframework.stereotype.Component
public class RefundReferenceGenerator {

    /** 32 symbols, no 0/O/1/I. */
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String PREFIX = "REQ-";
    private static final int BODY_LENGTH = 4;

    /** Matches both freshly generated codes and the V81 UUID-derived legacy codes. */
    public static final Pattern SHAPE = Pattern.compile("^REQ-[" + ALPHABET + "]{4}-[" + ALPHABET + "]{2,4}$");

    private final SecureRandom rng = new SecureRandom();
    private final Clock clock;

    public RefundReferenceGenerator(Clock clock) {
        this.clock = clock;
    }

    /** A fresh candidate. Not checked for uniqueness — that is the caller's job. */
    public String next() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + BODY_LENGTH + 3);
        sb.append(PREFIX);
        for (int i = 0; i < BODY_LENGTH; i++) {
            sb.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
        }
        int year = clock.instant().atZone(ZoneOffset.UTC).getYear();
        sb.append('-').append(String.format("%02d", year % 100));
        return sb.toString();
    }

    /**
     * Canonicalises operator/buyer input for lookup: trims, uppercases, and adds the
     * {@code REQ-} prefix back when someone pasted only the body ({@code 8K2M-26}).
     * Returns null when the input can't be a reference at all.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '-');
        if (s.isEmpty()) return null;
        if (!s.startsWith(PREFIX)) s = PREFIX + s;
        return SHAPE.matcher(s).matches() ? s : null;
    }
}
