package com.imin.iminapi.audience.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Re-applies V155's consent step to door QR and survey records written without the confirmation flag, which an
 * instance running the previous release can still do during a rolling deploy. Idempotent: a second pass finds nothing.
 */
@Component
public class ConsentConfirmationReconciler {

    private static final Logger log = LoggerFactory.getLogger(ConsentConfirmationReconciler.class);

    private static final String FIND_UNFLAGGED =
            "SELECT id, membership_id FROM consent_records"
            + " WHERE source IN ('door_qr', 'survey') AND confirmation_required = FALSE AND confirmed_at IS NULL";

    private static final String FLAG =
            "UPDATE consent_records SET confirmation_required = TRUE WHERE id = ? AND confirmation_required = FALSE";

    // Same rule as V155: a member whose current email state came from a pending sign-up falls back to the
    // latest other email record, or the never-consented default. Members with a later record keep their state.
    private static final String RESTORE =
            "UPDATE memberships"
            + "   SET consent_status = COALESCE((SELECT p.status FROM consent_records p"
            + "                                   WHERE p.membership_id = memberships.membership_id AND p.channel = 'email'"
            + "                                     AND (p.confirmation_required = FALSE OR p.confirmed_at IS NOT NULL)"
            + "                                   ORDER BY p.occurred_at DESC, p.id DESC LIMIT 1), 'never'),"
            + "       consent_basis = (SELECT p.lawful_basis FROM consent_records p"
            + "                         WHERE p.membership_id = memberships.membership_id AND p.channel = 'email'"
            + "                           AND (p.confirmation_required = FALSE OR p.confirmed_at IS NOT NULL)"
            + "                         ORDER BY p.occurred_at DESC, p.id DESC LIMIT 1)"
            + " WHERE memberships.membership_id = ?"
            + "   AND EXISTS (SELECT 1 FROM consent_records d"
            + "                WHERE d.membership_id = memberships.membership_id AND d.channel = 'email'"
            + "                  AND d.confirmation_required = TRUE AND d.confirmed_at IS NULL"
            + "                  AND NOT EXISTS (SELECT 1 FROM consent_records l"
            + "                                   WHERE l.membership_id = d.membership_id AND l.channel = 'email'"
            + "                                     AND (l.confirmation_required = FALSE OR l.confirmed_at IS NOT NULL)"
            + "                                     AND l.occurred_at >= d.occurred_at))";

    private final JdbcTemplate jdbc;
    // Calls reach run() through the proxy so the ShedLock and transaction advice apply.
    private final ObjectProvider<ConsentConfirmationReconciler> self;

    public ConsentConfirmationReconciler(JdbcTemplate jdbc, ObjectProvider<ConsentConfirmationReconciler> self) {
        this.jdbc = jdbc;
        this.self = self;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runLogged();
    }

    // The old instance keeps serving until it drains, after this one's startup pass.
    @Scheduled(fixedDelay = 900_000, initialDelay = 900_000)
    public void periodic() {
        runLogged();
    }

    void runLogged() {
        try {
            self.getObject().run();
        } catch (Exception e) {
            log.warn("ConsentConfirmationReconciler failed (next pass retries): {}", e.getMessage());
        }
    }

    /** Flags unflagged, unconfirmed door/survey records and restores their members; empty when another node holds the lock. */
    @SchedulerLock(name = "consent_confirmation_reconcile", lockAtMostFor = "PT10M")
    @Transactional
    public Optional<Integer> run() {
        List<Map<String, Object>> rows = jdbc.queryForList(FIND_UNFLAGGED);
        if (rows.isEmpty()) return Optional.of(0);
        int flagged = 0;
        Set<Object> members = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            flagged += jdbc.update(FLAG, row.get("id"));
            members.add(row.get("membership_id"));
        }
        for (Object m : members) jdbc.update(RESTORE, m);
        log.info("ConsentConfirmationReconciler: flagged {} sign-up(s) across {} member(s)", flagged, members.size());
        return Optional.of(flagged);
    }
}
