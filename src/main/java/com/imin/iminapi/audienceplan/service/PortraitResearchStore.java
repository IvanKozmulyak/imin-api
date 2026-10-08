package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code audience_portraits}: one research row per (genre bucket, city), shared by every org. */
@Component
public class PortraitResearchStore {

    public static final String PENDING = "pending";
    public static final String READY = "ready";
    public static final String EMPTY = "empty";

    /** A GET moves {@code requested_at} at most this often, so reads are not a write each. */
    static final Duration TOUCH_EVERY = Duration.ofHours(1);

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final TypeReference<List<StoredGroup>> GROUPS = new TypeReference<>() {};

    /** A page one research group cites; only URLs from the run's own search results are stored as cited. */
    public record WebSource(String url, String title) {}

    /** A vetted research group: text and URLs only, never a size. */
    public record StoredGroup(String label, String description, String basis, List<String> towns,
                              List<WebSource> sources, String confidence) {}

    public record Row(String genreKey, String cityKey, String status, List<StoredGroup> groups, int version,
                      Instant generatedAt, Instant expiresAt, Instant requestedAt, String reviewedBy) {}

    /** What one generation spent. */
    public record Spend(String model, int tokensIn, int tokensOut, BigDecimal costUsd) {}

    public record Pair(String genreKey, String cityKey) {}

    private final JdbcTemplate jdbc;

    public PortraitResearchStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(String genreKey, String cityKey) {
        return jdbc.query("""
                SELECT genre_key, city_key, status, research_groups, version, generated_at, expires_at, requested_at, reviewed_by
                  FROM audience_portraits WHERE genre_key = ? AND city_key = ?""",
                (rs, i) -> row(rs), genreKey, cityKey).stream().findFirst();
    }

    /** Records a request of the pair now (creating a pending row the first time) and returns the row. */
    public Row touch(String genreKey, String cityKey, Instant now) {
        int moved = jdbc.update("UPDATE audience_portraits SET requested_at = ?"
                        + " WHERE genre_key = ? AND city_key = ? AND requested_at < ?",
                ts(now), genreKey, cityKey, ts(now.minus(TOUCH_EVERY)));
        if (moved == 0) {
            // ON CONFLICT: a concurrent first request or a recent touch must not abort the caller.
            jdbc.update("INSERT INTO audience_portraits (id, genre_key, city_key, status, version, requested_at, created_at)"
                            + " VALUES (?, ?, ?, ?, 0, ?, ?) ON CONFLICT DO NOTHING",
                    UUID.randomUUID(), genreKey, cityKey, PENDING, ts(now), ts(now));
        }
        return find(genreKey, cityKey).orElseThrow();
    }

    /** Stores a new answer; the hand review belongs to the text it reviewed, so it is cleared. */
    public void saveReady(Pair pair, List<StoredGroup> groups, Instant now, Instant expiresAt, Spend spend) {
        jdbc.update("""
                UPDATE audience_portraits SET status = ?, research_groups = ?, version = version + 1, generated_at = ?,
                       expires_at = ?, reviewed_by = NULL, reviewed_at = NULL, model_id = ?, tokens_in = ?,
                       tokens_out = ?, cost_usd = ?
                 WHERE genre_key = ? AND city_key = ?""",
                READY, write(groups), ts(now), ts(expiresAt), spend.model(), spend.tokensIn(), spend.tokensOut(),
                spend.costUsd(), pair.genreKey(), pair.cityKey());
    }

    /**
     * Records that a generation gave nothing usable; retried after {@code retryAt}. A ready row keeps its older
     * groups (shown as stale) for the next refresh, but the failed attempt's spend is added to its totals.
     */
    public void saveEmpty(Pair pair, Instant now, Instant retryAt, Spend spend) {
        int updated = jdbc.update("""
                UPDATE audience_portraits SET status = ?, research_groups = NULL, version = version + 1, generated_at = ?,
                       expires_at = ?, model_id = ?, tokens_in = ?, tokens_out = ?, cost_usd = ?
                 WHERE genre_key = ? AND city_key = ? AND status <> ?""",
                EMPTY, ts(now), ts(retryAt), spend.model(), spend.tokensIn(), spend.tokensOut(), spend.costUsd(),
                pair.genreKey(), pair.cityKey(), READY);
        if (updated == 0 && spend.costUsd() != null) {
            jdbc.update("""
                    UPDATE audience_portraits SET tokens_in = COALESCE(tokens_in, 0) + ?,
                           tokens_out = COALESCE(tokens_out, 0) + ?, cost_usd = COALESCE(cost_usd, 0) + ?
                     WHERE genre_key = ? AND city_key = ? AND status = ?""",
                    spend.tokensIn(), spend.tokensOut(), spend.costUsd(), pair.genreKey(), pair.cityKey(), READY);
        }
    }

    /** Stamps a refresh-job attempt, whatever it ended with, so a row that keeps failing cannot hog the batch. */
    public void markRefreshAttempted(Pair pair, Instant now) {
        jdbc.update("UPDATE audience_portraits SET refresh_attempted_at = ? WHERE genre_key = ? AND city_key = ?",
                ts(now), pair.genreKey(), pair.cityKey());
    }

    /**
     * Pairs generated before {@code generatedBefore}, requested since {@code requestedSince} and not attempted by
     * the refresh since {@code attemptedBefore}, oldest first. Pending rows never match: they have no generated_at.
     */
    public List<Pair> dueForRefresh(Instant generatedBefore, Instant requestedSince, Instant attemptedBefore,
                                    int limit) {
        return jdbc.query("""
                SELECT genre_key, city_key FROM audience_portraits
                 WHERE generated_at < ? AND requested_at >= ?
                   AND (refresh_attempted_at IS NULL OR refresh_attempted_at < ?)
                 ORDER BY generated_at, genre_key, city_key LIMIT ?""",
                (rs, i) -> new Pair(rs.getString(1), rs.getString(2)),
                ts(generatedBefore), ts(requestedSince), ts(attemptedBefore), limit);
    }

    private static Row row(ResultSet rs) throws SQLException {
        String groups = rs.getString("research_groups");
        return new Row(rs.getString("genre_key"), rs.getString("city_key"), rs.getString("status"),
                groups == null ? List.of() : read(groups), rs.getInt("version"), instant(rs, "generated_at"),
                instant(rs, "expires_at"), instant(rs, "requested_at"), rs.getString("reviewed_by"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    static String write(List<StoredGroup> groups) {
        try {
            return JSON.writeValueAsString(groups);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write portrait groups JSON", e);
        }
    }

    /** A row only caches research, so an unreadable one reads as no groups. */
    static List<StoredGroup> read(String json) {
        try {
            List<StoredGroup> g = JSON.readValue(json, GROUPS);
            return g == null ? List.of() : g;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
