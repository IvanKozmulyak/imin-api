package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The set-based materialization writes the same snapshot as the per-member one did, at a statement count
 * that grows with batches rather than members.
 */
@IminIntegrationTest
class BulkMaterializeTest {

    private static final String PROVEN = "checkout-org-named-2026-09";
    private static final int LARGE_AUDIENCE = 5_000;
    private static final Duration LARGE_AUDIENCE_BOUND = Duration.ofSeconds(30);

    @Autowired RecipientMaterializer materializer;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired SegmentRepository segments;
    @Autowired SuppressionRepository suppressions;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired AudienceExperimentRepository experiments;
    @Autowired AudienceAssignmentRepository assignments;
    @Autowired EntityManagerFactory emf;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private UUID bulkOrgId;

    @AfterEach
    void deleteOwnRows() {
        try {
            CampaignRows.delete(jdbc, orgIds);
        } finally {
            // The 5,000 bulk members are not worth keeping in every later test's audience tables.
            if (bulkOrgId != null) {
                jdbc.update("WITH d AS (DELETE FROM memberships WHERE org_id = ? RETURNING consumer_id) "
                        + "DELETE FROM consumers WHERE consumer_id IN (SELECT consumer_id FROM d)", bulkOrgId);
            }
        }
    }

    /** One member per outcome, on a consent-gated campaign about a real event. */
    private record Audience(Organization org, Event event, Map<String, Membership> byRole, UUID segmentId) {}

    private Audience everyOutcome() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        Event event = fx.event(org, fx.owner(org), EventStatus.LIVE, Instant.now().plus(7, ChronoUnit.DAYS));
        UUID orgId = org.getId();
        Map<String, Membership> m = new LinkedHashMap<>();
        m.put("pending", proven(member(orgId, "subscribed", "explicit")));
        m.put("unsubscribed", proven(member(orgId, "unsubscribed", "explicit")));
        m.put("marketingSuppressed", proven(member(orgId, "subscribed", "explicit")));
        m.put("deliverabilitySuppressed", proven(member(orgId, "subscribed", "explicit")));
        m.put("noBasis", proven(member(orgId, "subscribed", null)));
        m.put("heldOut", proven(member(orgId, "subscribed", "explicit")));
        m.put("eventCapped", proven(member(orgId, "subscribed", "explicit")));
        m.put("monthlyCapped", proven(member(orgId, "subscribed", "explicit")));
        m.put("unproven", member(orgId, "subscribed", "explicit"));
        m.put("frequencyCapped", proven(member(orgId, "subscribed", "explicit")));

        SuppressionEntry mkt = new SuppressionEntry();
        mkt.setScope("marketing");
        mkt.setOrgId(orgId);
        mkt.setMembershipId(m.get("marketingSuppressed").getMembershipId());
        mkt.setReason("manual");
        suppressions.save(mkt);
        SuppressionEntry deliv = new SuppressionEntry();
        deliv.setScope("deliverability");
        deliv.setNormalizedEmail(emailOf(m.get("deliverabilitySuppressed")));
        deliv.setReason("hard_bounce");
        suppressions.save(deliv);

        AudienceExperiment e = new AudienceExperiment();
        e.setOrgId(orgId);
        e.setEventId(event.getId());
        e.setArm("holdout");
        e.setSeed(1L);
        e.setMembers(1);
        e = experiments.save(e);
        AudienceAssignment a = new AudienceAssignment();
        a.setExperimentId(e.getId());
        a.setMembershipId(m.get("heldOut").getMembershipId());
        a.setArm("holdout");
        a.setAssignedAt(Instant.now());
        assignments.save(a);

        Instant old = Instant.now().minus(5, ChronoUnit.DAYS);
        for (int i = 0; i < 2; i++) sent(prior(orgId, event.getId()), m.get("eventCapped"), old);
        for (int i = 0; i < 4; i++) sent(prior(orgId, null), m.get("monthlyCapped"), old);
        sent(prior(orgId, null), m.get("frequencyCapped"), Instant.now().minus(1, ChronoUnit.HOURS));

        return new Audience(org, event, m, segmentOf(orgId, List.copyOf(m.values())));
    }

    private static final Map<String, String> EXPECTED = expected();

    // Captured from the per-member implementation (base e1a08052) before the change.
    private static Map<String, String> expected() {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("pending", "pending/null");
        e.put("unsubscribed", "skipped/marketing_unsubscribed");
        e.put("marketingSuppressed", "skipped/marketing_suppressed");
        e.put("deliverabilitySuppressed", "skipped/deliverability_suppressed");
        e.put("noBasis", "skipped/no_lawful_basis");
        e.put("heldOut", "skipped/experiment_holdout");
        e.put("eventCapped", "skipped/event_cap");
        e.put("monthlyCapped", "skipped/monthly_cap");
        e.put("unproven", "skipped/consent_gate");
        e.put("frequencyCapped", "skipped/frequency_capped");
        return e;
    }

    private static final String EXPECTED_SUMMARY = "{\"consent_gate\":1,\"deliverability_suppressed\":1,"
            + "\"event_cap\":1,\"experiment_holdout\":1,\"frequency_capped\":1,\"marketing_suppressed\":1,"
            + "\"marketing_unsubscribed\":1,\"monthly_cap\":1,\"no_lawful_basis\":1}";

    @Test
    void everySkipReasonAndThePendingMember_matchThePerMemberSnapshot() {
        Audience a = everyOutcome();
        Campaign c = campaign(a.org().getId(), a.event().getId(), a.segmentId());

        materializer.materialize(c);

        assertThat(statesByRole(c, a)).isEqualTo(EXPECTED);
        assertThat(emailsByRole(c, a)).isEqualTo(expectedEmails(a));
        Map<String, Object> row = campaignRow(c);
        assertThat(row.get("recipient_count")).isEqualTo(1);
        assertThat(row.get("excluded_count")).isEqualTo(9);
        assertThat(row.get("exclusion_summary")).isEqualTo(EXPECTED_SUMMARY);
        assertThat(row.get("status")).isEqualTo("sending");
    }

    @Test
    void aCampaignCanceledBeforeMaterialize_keepsEverySkipReasonAndStopsOnlyThePendingMember() {
        Audience a = everyOutcome();
        Campaign c = campaign(a.org().getId(), a.event().getId(), a.segmentId());
        jdbc.update("UPDATE campaigns SET status = 'canceled', updated_at = now() WHERE id = ?", c.getId());

        materializer.materialize(c);

        Map<String, String> expected = new LinkedHashMap<>(EXPECTED);
        expected.put("pending", "skipped/" + CampaignRecipient.SKIP_CAMPAIGN_CANCELED);
        assertThat(statesByRole(c, a)).isEqualTo(expected);
        Map<String, Object> row = campaignRow(c);
        assertThat(row.get("recipient_count")).as("the audience the campaign was stopped against").isEqualTo(1);
        assertThat(row.get("excluded_count")).isEqualTo(9);
        assertThat(row.get("exclusion_summary")).isEqualTo(EXPECTED_SUMMARY);
        assertThat(row.get("status")).isEqualTo("canceled");
    }

    @Test
    void aSecondMaterializeAfterASend_changesNoRowAndNoCount() {
        Audience a = everyOutcome();
        Campaign c = campaign(a.org().getId(), a.event().getId(), a.segmentId());
        materializer.materialize(c);
        // The drive sent the pending member; a resumed drive must not re-snapshot over it.
        jdbc.update("UPDATE campaign_recipients SET status = 'sent', last_event_at = now() "
                + "WHERE campaign_id = ? AND status = 'pending'", c.getId());
        Map<String, Object> before = campaignRow(c);

        materializer.materialize(c);

        assertThat(recipients.countByCampaignId(c.getId())).isEqualTo(10L);
        Map<String, String> expected = new LinkedHashMap<>(EXPECTED);
        expected.put("pending", "sent/null");
        assertThat(statesByRole(c, a)).isEqualTo(expected);
        assertThat(campaignRow(c)).isEqualTo(before);
    }

    @Test
    void fiveThousandMembers_materializeInBatchesWithinTheBound() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        bulkOrgId = org.getId();
        List<UUID> ids = jdbc.queryForList("""
                WITH c AS (
                  INSERT INTO consumers (consumer_id, normalized_email)
                  SELECT gen_random_uuid(), 'bulk-' || gen_random_uuid() || '@test.invalid' FROM generate_series(1, ?)
                  RETURNING consumer_id)
                INSERT INTO memberships (membership_id, org_id, consumer_id, consent_status, consent_basis)
                SELECT gen_random_uuid(), ?, consumer_id, 'subscribed', 'explicit' FROM c
                RETURNING membership_id
                """, UUID.class, LARGE_AUDIENCE, org.getId());
        Segment seg = new Segment();
        seg.setOrgId(org.getId());
        seg.setName("Bulk " + UUID.randomUUID());
        seg.setKind("static");
        seg.setSnapshotIds(ids.stream().map(id -> "\"" + id + "\"").toList().toString());
        Campaign c = campaign(org.getId(), null, segments.save(seg).getId());
        c.setOrigin("manual");
        campaigns.save(c);

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean statsWereOn = stats.isStatisticsEnabled();
        long elapsedMs;
        long hibernateStatements;
        int insertStatements;
        try (InsertCounter inserts = InsertCounter.open(jdbc, c.getId())) {
            stats.clear();
            stats.setStatisticsEnabled(true);
            long start = System.nanoTime();
            materializer.materialize(c);
            elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            hibernateStatements = stats.getPrepareStatementCount();
            insertStatements = inserts.count();
        } finally {
            stats.setStatisticsEnabled(statsWereOn);
            stats.clear();
        }
        System.out.printf("[bulk-materialize] %d members: %d ms, %d insert statements, %d hibernate statements%n",
                LARGE_AUDIENCE, elapsedMs, insertStatements, hibernateStatements);

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(LARGE_AUDIENCE);
        assertThat(campaignRow(c).get("recipient_count")).isEqualTo(LARGE_AUDIENCE);
        assertThat(insertStatements).as("one INSERT per 1000 rows, not per row").isLessThanOrEqualTo(10);
        assertThat(hibernateStatements).as("no per-member select or cap query").isLessThan(100);
        assertThat(Duration.ofMillis(elapsedMs)).isLessThan(LARGE_AUDIENCE_BOUND);
    }

    /** Counts INSERT statements that wrote rows of one campaign, via a statement-level trigger. */
    private static final class InsertCounter implements AutoCloseable {
        private final JdbcTemplate jdbc;
        private final String name;

        private InsertCounter(JdbcTemplate jdbc, String name) {
            this.jdbc = jdbc;
            this.name = name;
        }

        static InsertCounter open(JdbcTemplate jdbc, UUID campaignId) {
            String name = "imin_test_inserts_" + UUID.randomUUID().toString().replace("-", "");
            InsertCounter counter = new InsertCounter(jdbc, name);
            try {
                jdbc.execute("CREATE TABLE " + name + " (n INT)");
                jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                        + "IF EXISTS (SELECT 1 FROM new_rows WHERE campaign_id = '" + campaignId + "'::uuid) THEN "
                        + "INSERT INTO " + name + " VALUES (1); END IF; RETURN NULL; END $$");
                jdbc.execute("CREATE TRIGGER " + name + " AFTER INSERT ON campaign_recipients "
                        + "REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION " + name + "()");
            } catch (RuntimeException e) {
                try {
                    counter.close();
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
            return counter;
        }

        int count() {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM " + name, Integer.class);
            return n == null ? 0 : n;
        }

        @Override
        public void close() {
            RuntimeException first = null;
            for (String sql : List.of("DROP TRIGGER IF EXISTS " + name + " ON campaign_recipients",
                    "DROP FUNCTION IF EXISTS " + name + "()", "DROP TABLE IF EXISTS " + name)) {
                try {
                    jdbc.execute(sql);
                } catch (RuntimeException e) {
                    if (first == null) first = e;
                    else first.addSuppressed(e);
                }
            }
            if (first != null) throw first;
        }
    }

    private Membership member(UUID orgId, String consentStatus, String consentBasis) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(fx.email("bulk"));
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus(consentStatus);
        m.setConsentBasis(consentBasis);
        return memberships.save(m);
    }

    // A subscribing checkout consent with a named-org text version: ConsentGate-mailable.
    private Membership proven(Membership m) {
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(m.getMembershipId());
        r.setStatus("subscribed");
        r.setLawfulBasis("explicit");
        r.setSource("checkout");
        r.setProofText("proof");
        r.setTextVersion(PROVEN);
        r.setOccurredAt(Instant.now().minus(10, ChronoUnit.DAYS));
        consentRecords.save(r);
        return m;
    }

    private String emailOf(Membership m) {
        return jdbc.queryForObject("SELECT normalized_email FROM consumers WHERE consumer_id = ?", String.class, m.getConsumerId());
    }

    private Campaign prior(UUID orgId, UUID eventId) {
        Campaign c = campaign(orgId, eventId, null);
        c.setStatus("sent");
        return campaigns.save(c);
    }

    private void sent(Campaign prior, Membership m, Instant at) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(prior.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(emailOf(m));
        r.setStatus("sent");
        r.setLastEventAt(at);
        recipients.save(r);
    }

    private Campaign campaign(UUID orgId, UUID eventId, UUID segmentId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Bulk materialize");
        c.setStatus("sending");
        c.setOrigin("audience_plan");
        c.setEventId(eventId);
        c.setSegmentId(segmentId);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private UUID segmentOf(UUID orgId, List<Membership> members) {
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Bulk " + UUID.randomUUID());
        seg.setKind("static");
        seg.setSnapshotIds(members.stream().map(m -> "\"" + m.getMembershipId() + "\"").toList().toString());
        return segments.save(seg).getId();
    }

    private Map<String, String> statesByRole(Campaign c, Audience a) {
        Map<UUID, String> byMember = new HashMap<>();
        jdbc.query("SELECT membership_id, status, skip_reason FROM campaign_recipients WHERE campaign_id = ?",
                rs -> {
                    String prev = byMember.put(rs.getObject("membership_id", UUID.class),
                            rs.getString("status") + "/" + rs.getString("skip_reason"));
                    assertThat(prev).as("one row per member").isNull();
                }, c.getId());
        assertThat(byMember).hasSize(a.byRole().size());
        Map<String, String> out = new LinkedHashMap<>();
        a.byRole().forEach((role, m) -> out.put(role, byMember.get(m.getMembershipId())));
        return out;
    }

    private Map<String, String> emailsByRole(Campaign c, Audience a) {
        Map<UUID, String> byMember = new HashMap<>();
        jdbc.query("SELECT membership_id, email FROM campaign_recipients WHERE campaign_id = ?",
                rs -> { byMember.put(rs.getObject("membership_id", UUID.class), rs.getString("email")); }, c.getId());
        Map<String, String> out = new LinkedHashMap<>();
        a.byRole().forEach((role, m) -> out.put(role, byMember.get(m.getMembershipId())));
        return out;
    }

    private Map<String, String> expectedEmails(Audience a) {
        Map<String, String> out = new LinkedHashMap<>();
        a.byRole().forEach((role, m) -> out.put(role, emailOf(m)));
        return out;
    }

    private Map<String, Object> campaignRow(Campaign c) {
        return jdbc.queryForMap("SELECT status, recipient_count, excluded_count, exclusion_summary "
                + "FROM campaigns WHERE id = ?", c.getId());
    }
}
