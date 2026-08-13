package com.imin.iminapi.buyer.service;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The opaque page cursor for {@code GET /buyer/orders}.
 *
 * <h2>Keyset, not offset</h2>
 *
 * <p>The list is ordered {@code (created_at DESC, id DESC)} and the cursor
 * carries both halves. {@code created_at} alone is not a key — two orders can
 * share a microsecond, and an offset would skip or repeat rows as new orders
 * arrive while a buyer pages. The {@code id} tiebreak makes the ordering total,
 * so every row appears exactly once across a full walk.
 *
 * <h2>Opaque, but not secret</h2>
 *
 * <p>Base64url of {@code <ISO-8601 instant>|<uuid>}. It is not signed and does
 * not need to be: the values inside are a timestamp and the id of an order the
 * caller was just shown, and the query it feeds is scoped to the caller's own
 * verified addresses no matter what the cursor says. A forged cursor can only
 * move the caller around their own list. That property is worth stating,
 * because the moment a cursor is reused on an endpoint that is <i>not</i>
 * re-scoped per request, it needs signing.
 *
 * <p>A malformed cursor is a 400 rather than a silent fall back to page one:
 * silently restarting would show a buyer the top of their list again and read
 * as data loss.
 */
public record BuyerOrdersCursor(Instant createdAt, UUID id) {

    private static final String SEPARATOR = "|";

    public static String encode(Instant createdAt, UUID id) {
        String raw = createdAt.toString() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static BuyerOrdersCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int split = raw.lastIndexOf(SEPARATOR);
            if (split < 0) throw new IllegalArgumentException("no separator");
            return new BuyerOrdersCursor(
                    Instant.parse(raw.substring(0, split)),
                    UUID.fromString(raw.substring(split + 1)));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid cursor");
        }
    }
}
