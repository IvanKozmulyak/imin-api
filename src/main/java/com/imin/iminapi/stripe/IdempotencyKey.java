package com.imin.iminapi.stripe;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * The {@code Idempotency-Key} request header, normalized once for both public
 * checkout endpoints.
 *
 * <p>{@code POST /public/events/{id}/payment-intent} (native, paid) and
 * {@code POST /public/events/{id}/checkout} (hosted; the free branch) are the
 * same promise made to the same client on two roads, so they must answer the
 * same way to the same header. Two copies of "trim, blank means absent, reject
 * anything longer than the column" is two chances to drift, and the drift would
 * be invisible: each endpoint would keep passing its own tests while a buyer
 * discovered that the same header behaves differently depending on whether the
 * ticket happened to be free.
 *
 * <p><b>Absent or blank is not an error.</b> It means "unkeyed", which is the
 * pre-existing behaviour of both endpoints and the only behaviour the shipped
 * web client has ever had. Rejecting it would break every current buyer, and
 * minting a key server-side would be worse than useless: a server-generated key
 * differs on every request, so it can never match a retry — it would buy the
 * appearance of protection and none of the substance.
 *
 * <p><b>Too long is a 400, never a truncation.</b> Two distinct 200-character
 * keys sharing a 128-character prefix would otherwise collapse into one
 * purchase — the exact duplicate this mechanism exists to prevent, reached from
 * the other direction. A 400 also keeps an over-long header from reaching the
 * INSERT, where the column width would turn it into a 500.
 */
public final class IdempotencyKey {

    /**
     * Matches {@code ticket_reservations.idempotency_key} (V93) and
     * {@code orders.idempotency_key} (V94). Both columns must stay this wide.
     */
    public static final int MAX_LENGTH = 128;

    private IdempotencyKey() {}

    /** @return the trimmed key, or null when the caller sent none. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String key = raw.trim();
        if (key.isEmpty()) return null;
        if (key.length() > MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must be at most " + MAX_LENGTH + " characters");
        }
        return key;
    }
}
