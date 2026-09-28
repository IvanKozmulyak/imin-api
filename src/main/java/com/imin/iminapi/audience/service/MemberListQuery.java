package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.AudienceMemberClass;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The member list as one native query built from the filters present, so no null parameter is ever bound
 * (a nullable String in lower()/like breaks on Postgres). Keyset-paged on (sort key, membership_id), descending.
 */
@Component
public class MemberListQuery {

    /** Sort keys the list accepts; all descending, nulls last, ties by membership_id. */
    public enum Sort {
        CREATED_AT("created_at", "m.created_at", true, false),
        SPEND_MINOR("spend_minor", "m.spend_minor", false, false),
        LAST_PURCHASE("last_purchase", "m.last_purchase", true, true),
        EVENTS("events", "m.events", false, false);

        final String key;
        final String column;
        final boolean timestamp;
        final boolean nullable;

        Sort(String key, String column, boolean timestamp, boolean nullable) {
            this.key = key;
            this.column = column;
            this.timestamp = timestamp;
            this.nullable = nullable;
        }

        public String key() { return key; }

        /** created_at for null or blank; 400 for an unknown key. */
        public static Sort parse(String key) {
            if (key == null || key.isBlank()) return CREATED_AT;
            return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst()
                    .orElseThrow(() -> badRequest("sort", "must be one of created_at, spend_minor, last_purchase, events"));
        }

        Object valueOf(Membership m) {
            return switch (this) {
                case CREATED_AT -> m.getCreatedAt();
                case SPEND_MINOR -> m.getSpendMinor();
                case LAST_PURCHASE -> m.getLastPurchase();
                case EVENTS -> (long) m.getEvents();
            };
        }
    }

    /**
     * Filters; null means "not filtered". {@code genre} is a validated bucket key and
     * {@code mailableSql}/{@code mailableParams} the ConsentGate mailable-ids subquery with its parameters.
     */
    public record Filter(String lifecycle, String search, AudienceMemberClass guestClass, String genre,
                         Boolean mailable, String mailableSql, Map<String, Object> mailableParams) {}

    /** Position after the last row of a page; {@code key} is null only for a null sort value. */
    public record Cursor(Sort sort, Object key, UUID membershipId) {

        public static Cursor after(Sort sort, Membership last) {
            return new Cursor(sort, sort.valueOf(last), last.getMembershipId());
        }

        public String encode() {
            String k = key == null ? "" : key.toString();
            String raw = "v2," + sort.key + "," + k + "," + membershipId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        /** Also reads the original created_at cursor ({@code epochMillis,uuid}). */
        public static Cursor decode(String cursor, Sort expected) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] p = raw.split(",", -1);
                if (p.length == 2) {
                    if (expected != Sort.CREATED_AT) throw new IllegalArgumentException("sort mismatch");
                    return new Cursor(Sort.CREATED_AT, Instant.ofEpochMilli(Long.parseLong(p[0])), UUID.fromString(p[1]));
                }
                if (p.length != 4 || !"v2".equals(p[0]) || !expected.key.equals(p[1])) {
                    throw new IllegalArgumentException("cursor shape");
                }
                Object key;
                if (p[2].isEmpty()) {
                    if (!expected.nullable) throw new IllegalArgumentException("null key");
                    key = null;
                } else {
                    key = expected.timestamp ? Instant.parse(p[2]) : (Object) Long.parseLong(p[2]);
                }
                return new Cursor(expected, key, UUID.fromString(p[3]));
            } catch (RuntimeException e) {
                // Client input: a mangled cursor or one from another sort is a bad request.
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid cursor");
            }
        }
    }

    @PersistenceContext
    private EntityManager em;

    /** Up to {@code limit} live members of the org, in sort order, after the cursor when given. */
    @SuppressWarnings("unchecked")
    public List<Membership> page(UUID orgId, Filter f, Sort sort, Cursor cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT m.* FROM memberships m");
        Map<String, Object> params = new LinkedHashMap<>();
        if (f.guestClass() != null || f.genre() != null) {
            sql.append(" LEFT JOIN fan_features f ON f.membership_id = m.membership_id");
        }
        sql.append(" WHERE m.org_id = :orgId AND m.status <> 'erase_pending'");
        params.put("orgId", orgId);

        if (f.lifecycle() != null) {
            sql.append(" AND m.lifecycle = :lifecycle");
            params.put("lifecycle", f.lifecycle());
        }
        if (f.search() != null && !f.search().isBlank()) {
            // Email matches only exactly: the organizer already sees each member's address.
            sql.append(" AND (LOWER(m.display_name) LIKE :search"
                    + " OR LOWER(CAST(m.membership_id AS VARCHAR)) LIKE :search"
                    + " OR m.consumer_id IN (SELECT c.consumer_id FROM consumers c WHERE c.normalized_email = :searchEmail))");
            params.put("search", "%" + f.search().toLowerCase(Locale.ROOT) + "%");
            params.put("searchEmail", EmailNormalizer.normalize(f.search()));
        }
        if (f.guestClass() != null) {
            // No feature row counts as none, as in the metrics class counts.
            sql.append(" AND COALESCE(f.class, 'none') = :guestClass");
            params.put("guestClass", f.guestClass().key());
        }
        if (f.genre() != null) {
            // Taste is a JSON object of bucket to weight > 0; bucket keys hold no LIKE wildcards.
            sql.append(" AND f.taste LIKE :genre");
            params.put("genre", "%\"" + f.genre() + "\":%");
        }
        if (f.mailable() != null) {
            sql.append(f.mailable() ? " AND m.membership_id IN (" : " AND m.membership_id NOT IN (")
                    .append(f.mailableSql()).append(")");
            params.putAll(f.mailableParams());
        }
        if (cursor != null) {
            appendKeyset(sql, params, sort, cursor);
        }
        sql.append(" ORDER BY ").append(sort.column).append(" DESC NULLS LAST, m.membership_id DESC");

        Query q = em.createNativeQuery(sql.toString(), Membership.class);
        params.forEach(q::setParameter);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    private static void appendKeyset(StringBuilder sql, Map<String, Object> params, Sort sort, Cursor c) {
        params.put("cursorId", c.membershipId());
        if (c.key() == null) {
            sql.append(" AND (").append(sort.column).append(" IS NULL AND m.membership_id < :cursorId)");
            return;
        }
        params.put("cursorKey", c.key());
        sql.append(" AND (").append(sort.column).append(" < :cursorKey OR (")
                .append(sort.column).append(" = :cursorKey AND m.membership_id < :cursorId)");
        if (sort.nullable) sql.append(" OR ").append(sort.column).append(" IS NULL");
        sql.append(")");
    }

    static ApiException badRequest(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                Map.of(field, message));
    }
}
