package com.imin.iminapi.audienceplan;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Real plan-mailable members for one org, so {@code ConsentGate} and {@code CandidateLoader} run for real.
 * JDBC only, so no projector event fires.
 */
public final class PlanPopulation {

    private static final Duration CONSENT_AGE = Duration.ofDays(10);

    private PlanPopulation() {}

    /** {@code n} new members with a proven checkout consent and a fan_features row of this class and taste. */
    public static List<UUID> mailable(JdbcTemplate jdbc, AudiencePlanLogic logic, Clock clock, UUID orgId,
                                      String fanClass, Map<String, Double> taste, int n) {
        List<UUID> ids = members(jdbc, orgId, "subscribed", "explicit", n);
        makeMailable(jdbc, logic, clock, orgId, ids, fanClass, taste);
        return ids;
    }

    /** The same consent and feature rows for memberships a fixture already made. */
    public static void makeMailable(JdbcTemplate jdbc, AudiencePlanLogic logic, Clock clock, UUID orgId,
                                    Collection<UUID> membershipIds, String fanClass, Map<String, Double> taste) {
        String version = logic.logic().legal().organizerNamedTextVersions().iterator().next();
        Instant now = clock.instant();
        List<UUID> ids = List.copyOf(membershipIds);
        jdbc.batchUpdate("update memberships set consent_status = 'subscribed', consent_basis = 'explicit',"
                        + " status = 'active' where membership_id = ? and org_id = ?",
                ids.stream().map(id -> new Object[] {id, orgId}).toList());
        consents(jdbc, ids, version, now);
        String tasteJson = json(taste);
        jdbc.batchUpdate("insert into fan_features (membership_id, org_id, paid_orders, class, taste, cities, formats,"
                        + " no_show_n, sends_30d, logic_version, updated_at)"
                        + " values (?, ?, 0, ?, ?, '[]', '[]', 0, 0, 1, ?)",
                ids.stream().map(id -> new Object[] {id, orgId, fanClass, tasteJson, Timestamp.from(now)}).toList());
    }

    /** {@code n} subscribed members whose only consent is a checkout without a text version: legacy_unproven. */
    public static List<UUID> legacyUnproven(JdbcTemplate jdbc, Clock clock, UUID orgId, int n) {
        List<UUID> ids = members(jdbc, orgId, "subscribed", "explicit", n);
        consents(jdbc, ids, null, clock.instant());
        return ids;
    }

    /** {@code n} members who unsubscribed. */
    public static List<UUID> unsubscribed(JdbcTemplate jdbc, UUID orgId, int n) {
        return members(jdbc, orgId, "unsubscribed", null, n);
    }

    private static List<UUID> members(JdbcTemplate jdbc, UUID orgId, String status, String basis, int n) {
        List<Object[]> consumers = new ArrayList<>();
        List<Object[]> memberships = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID consumerId = UUID.randomUUID();
            UUID membershipId = UUID.randomUUID();
            consumers.add(new Object[] {consumerId, "plan-" + UUID.randomUUID() + "@example.test"});
            memberships.add(new Object[] {membershipId, orgId, consumerId, status, basis});
            ids.add(membershipId);
        }
        jdbc.batchUpdate("insert into consumers (consumer_id, normalized_email) values (?, ?)", consumers);
        jdbc.batchUpdate("insert into memberships (membership_id, org_id, consumer_id, status, consent_status,"
                + " consent_basis) values (?, ?, ?, 'active', ?, ?)", memberships);
        return ids;
    }

    private static void consents(JdbcTemplate jdbc, List<UUID> ids, String version, Instant now) {
        Timestamp at = Timestamp.from(now.minus(CONSENT_AGE));
        jdbc.batchUpdate("insert into consent_records (id, membership_id, channel, status, lawful_basis, source,"
                        + " proof_text, text_version, occurred_at)"
                        + " values (?, ?, 'email', 'subscribed', 'explicit', 'checkout', 'proof', ?, ?)",
                ids.stream().map(id -> new Object[] {UUID.randomUUID(), id, version, at}).toList());
    }

    private static String json(Map<String, Double> taste) {
        return taste.entrySet().stream()
                .map(e -> "\"" + e.getKey().replace("\\", "\\\\").replace("\"", "\\\"") + "\":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
