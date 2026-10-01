package com.imin.iminapi.predictor.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * {@code predictor_alert}: at most one predictor alert (band crossing or radar) per event per event-local day.
 * JDBC only, so nothing is REST-exported.
 */
@Component
public class PredictorAlertStore {

    /** Must equal the V168 {@code ck_predictor_alert_kind} set. */
    public static final String KIND_BAND = "band";
    public static final String KIND_RADAR = "radar";

    // ON CONFLICT plus a read-back: a caught violation would leave the transaction rollback-only (aborted on Postgres).
    public static final String CLAIM_SQL = "INSERT INTO predictor_alert (id, event_id, alert_day, kind, date_check_id, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    public static final String WINNER_SQL = "SELECT id FROM predictor_alert WHERE event_id = ? AND alert_day = ?";
    public static final String RADAR_ALERTED_SQL = "SELECT date_check_id FROM predictor_alert"
            + " WHERE event_id = ? AND kind = 'radar' AND date_check_id IS NOT NULL";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PredictorAlertStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Claims the event's alert day; own transaction so the claim survives the caller. True only for the first claim. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID eventId, LocalDate day, String kind, UUID dateCheckId) {
        return won(eventId, day, kind, dateCheckId);
    }

    /**
     * Claims the day and, only when this call wins, runs {@code onWin} in the same new transaction: a failing
     * {@code onWin} rolls the claim back, so the day stays free and a claim row means its in-app alert exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimWith(UUID eventId, LocalDate day, String kind, UUID dateCheckId, Runnable onWin) {
        if (!won(eventId, day, kind, dateCheckId)) return false;
        onWin.run();
        return true;
    }

    // A concurrent claimer of the same day blocks on the unique key until this transaction ends, then reads the winner.
    private boolean won(UUID eventId, LocalDate day, String kind, UUID dateCheckId) {
        UUID id = UUID.randomUUID();
        jdbc.update(CLAIM_SQL, id, eventId, day, kind, dateCheckId,
                Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)));
        return id.equals(jdbc.queryForObject(WINNER_SQL, UUID.class, eventId, day));
    }

    /** The event's radar runs that claimed an alert day; {@link #claimWith} commits each with its in-app alert. */
    public Set<UUID> radarAlertedCheckIds(UUID eventId) {
        return Set.copyOf(jdbc.queryForList(RADAR_ALERTED_SQL, UUID.class, eventId));
    }
}
