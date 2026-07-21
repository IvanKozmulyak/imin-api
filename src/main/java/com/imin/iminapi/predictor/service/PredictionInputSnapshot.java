package com.imin.iminapi.predictor.service;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The versioned scoring input snapshot (spec §7.2, task 86cav4741) — everything a score is a
 * function of, and nothing else. Scoring MUST be a pure function of this record (spec §11:
 * that purity is what makes a future what-if simulator nearly free).
 *
 * <p><b>Canonical serialization → SHA-256 → cache key.</b> Records serialize in component
 * declaration order with Jackson, so the JSON is canonical as long as the declaration order
 * is stable. Therefore: reordering, adding or removing components, or changing a component's
 * meaning REQUIRES bumping {@link #SNAPSHOT_VERSION} — the version is the first component, so
 * a bump alone changes every hash and cleanly invalidates all cached scores.
 *
 * <p>Time enters deliberately and coarsely: {@code leadTimeDays} is whole days from the
 * scoring clock to the event date, so an untouched draft re-hashes at most once per day (the
 * bet genuinely changes as the event approaches) instead of on every request.
 */
public record PredictionInputSnapshot(
        // ---- versioning (FIRST component — see class doc) ----
        String snapshotVersion,
        UUID eventId,
        // ---- draft attributes ----
        String city,
        String country,
        String genreFamily,
        Integer capacity,
        String capacityBand,
        String eventDateIso,
        Integer dayOfWeek,          // ISO 1=Mon..7=Sun in the event's timezone
        String season,
        Integer leadTimeDays,       // scoring day -> event day, whole days
        String currency,
        // ---- full tier structure ----
        List<TierLine> tiers,
        // ---- promo config ----
        List<PromoLine> promos,
        // ---- organizer history ----
        Integer organizerTenureDays,
        Integer priorEventCount,
        // ---- calendar signals (static table, §6.3) ----
        boolean holidayTableCovers, // false = table has no data for this market/date (unknown ≠ none)
        List<HolidayLine> holidaysNearEvent,
        // ---- comparable corpus (retrieval result is an input: new outcomes → new hash → re-score) ----
        CorpusLine comparables
) {

    /**
     * Snapshot schema version. BUMP THIS on any change to the component list, order, or
     * semantics of this record (or of the nested lines) — it is what keeps two hashes
     * comparable. Cached scores from older versions simply miss and re-score.
     */
    public static final String SNAPSHOT_VERSION = "1";

    /** One ticket tier as frozen into the snapshot. */
    public record TierLine(String name, int priceMinor, int quantity,
                           String saleStartsAtIso, String saleClosesAtIso) {}

    /** One promo code config line. */
    public record PromoLine(String code, int discountPct, int maxUses) {}

    /** One nearby public holiday (static table). */
    public record HolidayLine(String dateIso, String name) {}

    /** One of the org's own comparable outcomes (sorted by eventId for determinism). */
    public record OwnComparableLine(UUID eventId, Integer attendance, Integer soldTotal,
                                    Integer capacity, Boolean sellOut, Long grossRevenueMinor) {}

    /** Privacy-preserved foreign aggregate (null when the ≥5 cluster floor suppressed it). */
    public record ForeignAggregateLine(int count, int avgAttendanceRounded, int medianAttendanceRounded,
                                       long avgRevenueMinorRounded, double sellOutRate) {}

    /** The comparable-corpus retrieval result the score leaned on. */
    public record CorpusLine(String relaxation, int densityTotal, int ownCount, int foreignCount,
                             List<OwnComparableLine> ownEvents, ForeignAggregateLine foreignAggregate) {}

    /** Canonical JSON of this snapshot (component order = declaration order). */
    public String canonicalJson() {
        try {
            return PredictorJson.MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            // Record of simple values — cannot fail in practice; surface loudly if it ever does.
            throw new IllegalStateException("Snapshot serialization failed", e);
        }
    }

    /** SHA-256 hex of the canonical JSON — the cache key and the ledger's input_snapshot_hash. */
    public String sha256() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
