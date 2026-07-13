package com.imin.iminapi.service.audience;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalizes a raw phone string to E.164 for the SMS opt-in (§4).
 *
 * <p>Strips spaces, dashes, and parentheses, requires a leading {@code '+'},
 * and validates the E.164 shape: {@code '+'} followed by 8–15 digits (fits the
 * {@code VARCHAR(20)} column). Returns {@link Optional#empty()} for anything
 * that does not validate — the caller treats that as a 400.
 */
public final class PhoneNormalizer {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private PhoneNormalizer() {}

    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String stripped = raw.trim().replaceAll("[\\s()\\-]", "");
        if (stripped.isEmpty()) return Optional.empty();
        if (!E164.matcher(stripped).matches()) return Optional.empty();
        return Optional.of(stripped);
    }
}
