package com.imin.iminapi.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The dispatcher claims every org's due campaigns ten at a time, so a test that leaves one claimable deletes its own.
 * Recipients and AI-suggestion rows cascade; experiments are set null.
 */
public final class CampaignRows {

    private CampaignRows() {}

    public static void delete(JdbcTemplate jdbc, Collection<UUID> orgIds) {
        List<UUID> ids = orgIds.stream().filter(Objects::nonNull).distinct().toList();
        List<RuntimeException> failures = new ArrayList<>();
        for (UUID orgId : ids) {
            run(jdbc, failures, "DELETE FROM provider_events WHERE campaign_id IN "
                    + "(SELECT id FROM campaigns WHERE org_id = ?)", orgId);
            run(jdbc, failures, "DELETE FROM campaigns WHERE org_id = ?", orgId);
        }
        if (failures.isEmpty()) return;
        RuntimeException first = failures.get(0);
        failures.subList(1, failures.size()).forEach(first::addSuppressed);
        throw first;
    }

    private static void run(JdbcTemplate jdbc, List<RuntimeException> failures, String sql, UUID orgId) {
        try {
            jdbc.update(sql, orgId);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
