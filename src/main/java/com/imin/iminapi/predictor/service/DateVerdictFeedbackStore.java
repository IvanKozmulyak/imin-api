package com.imin.iminapi.predictor.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** {@code date_verdict_feedback}: one after-event verdict answer per event. JDBC only, so nothing is REST-exported. */
@Component
public class DateVerdictFeedbackStore {

    public record Row(UUID id, UUID eventId, UUID dateCheckId, LocalDate forDate, String verdict, String answer,
                      String comment, Instant createdAt, Instant answeredAt) {}

    private final JdbcTemplate jdbc;

    public DateVerdictFeedbackStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(UUID eventId) {
        return jdbc.query("""
                SELECT id, event_id, date_check_id, for_date, verdict, answer, comment, created_at, answered_at
                  FROM date_verdict_feedback WHERE event_id = ?""",
                (rs, i) -> row(rs), eventId).stream().findFirst();
    }

    /** Writes the snapshot of the first answer; a row already there (a concurrent first answer) is left as is. */
    public void insertIfAbsent(UUID id, UUID eventId, UUID dateCheckId, LocalDate forDate, String verdict,
                               String answer, String comment, Instant createdAt, Instant answeredAt) {
        jdbc.update("""
                INSERT INTO date_verdict_feedback
                       (id, event_id, date_check_id, for_date, verdict, answer, comment, created_at, answered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                id, eventId, dateCheckId, forDate, verdict, answer, comment, ts(createdAt),
                ts(answeredAt));
    }

    /** Replaces the answer and comment and moves {@code answered_at}; the snapshot columns are never touched. */
    public int update(UUID eventId, String answer, String comment, Instant answeredAt) {
        return jdbc.update("UPDATE date_verdict_feedback SET answer = ?, comment = ?, answered_at = ? WHERE event_id = ?",
                answer, comment, ts(answeredAt), eventId);
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("date_check_id", UUID.class), rs.getObject("for_date", LocalDate.class),
                rs.getString("verdict"), rs.getString("answer"), rs.getString("comment"),
                instant(rs, "created_at"), instant(rs, "answered_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
