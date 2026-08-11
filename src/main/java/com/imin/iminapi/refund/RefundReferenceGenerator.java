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
 * <p>32^4 = 1,048,576 codes per year. That is the size of the space, not the odds: what
 * governs a collision is the birthday bound, so at the ~1,200 requests a year this table
 * sees, the chance that SOME two codes in a year clash is already around 50%. The UNIQUE
 * index from V81 is the authority and the only thing that has to be right — the caller
 * mints one candidate and lets the constraint answer (see
 * {@link RefundRequestService#submitByToken}).
 */
@org.springframework.stereotype.Component
public class RefundReferenceGenerator {

    /** 32 symbols, no 0/O/1/I. */
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String PREFIX = "REQ-";
    private static final int BODY_LENGTH = 4;

    /**
     * Matches both families of code: {@code REQ-} + 4 alphabet symbols + {@code -} + either
     * the two-digit year of a generated code or the four alphabet symbols of a V81
     * backfilled one. They can never be confused for each other — the tails differ in length.
     *
     * <p><b>The year segment is plain decimal and must be matched as such.</b> Building it out
     * of {@link #ALPHABET} (which excludes {@code 0} and {@code 1}) made this pattern unable to
     * match its own generator's output for every year whose last two digits contain a 0 or a 1
     * — the first being 2030. {@code normalize("REQ-8K2M-30")} would return null, the
     * case/prefix-forgiving reference lookup would silently fall through to the buyer-email
     * LIKE, and an operator searching a code a customer just quoted would find nothing. Tested
     * against a clock past 2030 in {@code RefundReferenceGeneratorTest}.
     */
    public static final Pattern SHAPE =
            Pattern.compile("^REQ-[" + ALPHABET + "]{4}-([0-9]{2}|[" + ALPHABET + "]{4})$");

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
