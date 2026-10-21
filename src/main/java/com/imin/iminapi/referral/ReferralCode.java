package com.imin.iminapi.referral;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * The share code on {@code orders.referral_code}: 10 random base62 characters for new orders; V199's column default
 * gives 12 hex characters to older rows and to any insert that omits the column. A code is a pointer to an order,
 * never a credential: it opens nothing.
 */
public final class ReferralCode {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int LENGTH = 10;
    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9]{6,16}$");
    private static final SecureRandom RNG = new SecureRandom();

    private ReferralCode() {}

    // ponytail: no retry on a unique clash; 62^10 codes make one unlikely, and the insert's rollback is retried upstream.
    public static String random() {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) out[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        return new String(out);
    }

    /** The only shape a code can take on the wire; anything else is dropped, never a validation error. */
    public static boolean isWellFormed(String code) {
        return code != null && WELL_FORMED.matcher(code).matches();
    }
}
