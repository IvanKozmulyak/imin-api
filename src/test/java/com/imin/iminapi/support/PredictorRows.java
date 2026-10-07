package com.imin.iminapi.support;

import com.imin.iminapi.predictor.research.DateCheckResearchJobHandler;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The job runner claims the five oldest due jobs of every org, so a test that queues research deletes its own.
 * Deletes the given orgs' predictor rows (the tables without an org foreign key first), then {@link OrgRows}.
 */
public final class PredictorRows {

    private PredictorRows() {}

    public static void delete(JdbcTemplate jdbc, Collection<UUID> orgIds) {
        List<UUID> ids = orgIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        String in = "(" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
        String events = "(SELECT id FROM events WHERE org_id IN " + in + ")";

        List<RuntimeException> failures = new ArrayList<>();
        // predictor_job has no org column; a research job names its check in the payload.
        run(jdbc, failures, "DELETE FROM predictor_job j WHERE j.kind = '" + DateCheckResearchJobHandler.KIND
                + "' AND EXISTS (SELECT 1 FROM date_check d WHERE d.org_id IN " + in
                + " AND j.payload_json LIKE '%' || d.id::text || '%')", ids);
        // No foreign key to events or organizations, so nothing cascades these.
        run(jdbc, failures, "DELETE FROM prediction_ledger WHERE org_id IN " + in, ids);
        run(jdbc, failures, "DELETE FROM event_outcomes WHERE org_id IN " + in, ids);
        run(jdbc, failures, "DELETE FROM event_sales_daily WHERE event_id IN " + events, ids);
        run(jdbc, failures, "DELETE FROM prediction_feedback WHERE event_id IN " + events, ids);
        // Cascades date_check_date and date_check_finding; nulls the event, feedback and alert links.
        run(jdbc, failures, "DELETE FROM date_check WHERE org_id IN " + in, ids);
        try {
            OrgRows.delete(jdbc, ids);
        } catch (RuntimeException e) {
            failures.add(e);
        }
        if (failures.isEmpty()) return;
        RuntimeException first = failures.get(0);
        failures.subList(1, failures.size()).forEach(first::addSuppressed);
        throw first;
    }

    /** Binds the id list once per {@code IN (?, …)} in the statement. */
    private static void run(JdbcTemplate jdbc, List<RuntimeException> failures, String sql, List<UUID> ids) {
        long marks = sql.chars().filter(ch -> ch == '?').count();
        List<Object> args = new ArrayList<>();
        for (long i = 0; i < marks / ids.size(); i++) args.addAll(ids);
        try {
            jdbc.update(sql, args.toArray());
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
