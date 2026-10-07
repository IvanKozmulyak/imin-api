package com.imin.iminapi.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Own-row cleanup for money tests whose code under test sweeps every org: deletes only the given orgs' rows,
 * in foreign-key order (the NO ACTION edges first; events, then users, cascade the rest).
 */
public final class OrgRows {

    private OrgRows() {}

    public static void delete(JdbcTemplate jdbc, Collection<UUID> orgIds) {
        List<UUID> ids = orgIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        String in = "(" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
        String events = "(SELECT id FROM events WHERE org_id IN " + in + ")";
        String orders = "(SELECT id FROM orders WHERE org_id IN " + in + " OR event_id IN " + events + ")";
        String refunds = "(SELECT id FROM refunds WHERE order_id IN " + orders + ")";
        String tickets = "(SELECT id FROM tickets WHERE order_id IN " + orders + ")";
        String users = "(SELECT id FROM users WHERE org_id IN " + in + ")";
        String generated = "(SELECT id FROM generated_event WHERE org_id IN " + in + ")";

        List<RuntimeException> failures = new ArrayList<>();
        run(jdbc, failures, "DELETE FROM refund_tickets WHERE refund_id IN " + refunds
                + " OR ticket_id IN " + tickets, ids);
        run(jdbc, failures, "DELETE FROM refund_requests WHERE order_id IN " + orders
                + " OR decided_by_user_id IN " + users, ids);
        run(jdbc, failures, "DELETE FROM refund_request_tokens WHERE order_id IN " + orders, ids);
        run(jdbc, failures, "DELETE FROM refunds WHERE order_id IN " + orders
                + " OR initiated_by_user_id IN " + users, ids);
        run(jdbc, failures, "DELETE FROM disputes WHERE org_id IN " + in + " OR event_id IN " + events
                + " OR order_id IN " + orders, ids);
        run(jdbc, failures, "DELETE FROM payout_runs WHERE org_id IN " + in + " OR event_id IN " + events, ids);
        run(jdbc, failures, "DELETE FROM settlements WHERE org_id IN " + in, ids);
        for (String child : List.of("concept", "social_copy", "poster_generations")) {
            run(jdbc, failures, "DELETE FROM " + child + " WHERE generated_event_id IN " + generated, ids);
        }
        // Events cascade orders, tickets, tiers and reservations; organizations cascade users and the rest.
        run(jdbc, failures, "DELETE FROM events WHERE org_id IN " + in, ids);
        run(jdbc, failures, "DELETE FROM organizations WHERE id IN " + in, ids);
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
