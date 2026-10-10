package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.RecipientMaterializer;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Past Postgres's 65,535 bind-parameter limit the send gate still gives every member its verdict: every reason
 * survives, no member is dropped or doubled, and a static segment that size materializes.
 */
@IminIntegrationTest
class SendGateChunksTest {

    private static final int LARGE_AUDIENCE = 70_000;
    private static final String EMAIL_PREFIX = "send-gate-chunks-";

    @Autowired SendGateService sendGate;
    @Autowired RecipientMaterializer materializer;
    @Autowired CampaignRepository campaigns;
    @Autowired SegmentRepository segments;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<String> deliverabilityEmails = new ArrayList<>();

    @AfterEach
    void deleteOwnRows() {
        try {
            CampaignRows.delete(jdbc, orgIds);
        } finally {
            try {
                for (String email : deliverabilityEmails) {
                    jdbc.update("DELETE FROM suppression_entries WHERE scope = 'deliverability' AND normalized_email = ?",
                            email);
                }
            } finally {
                try {
                    List<UUID> consumerIds = new ArrayList<>();
                    for (UUID orgId : orgIds) {
                        jdbc.update("DELETE FROM segments WHERE org_id = ?", orgId);
                        consumerIds.addAll(jdbc.queryForList(
                                "DELETE FROM memberships WHERE org_id = ? RETURNING consumer_id", UUID.class, orgId));
                    }
                    if (!consumerIds.isEmpty()) jdbc.execute((ConnectionCallback<Void>) con -> deleteConsumers(con, consumerIds));
                } finally {
                    OrgRows.delete(jdbc, orgIds);
                }
            }
        }
    }

    // These consumers are this test's own and their memberships are gone; memberships.consumer_id has no index of
    // its own, so the per-row FK check would seq-scan memberships 70,000 times (~45 s). Replica role skips it.
    private static Void deleteConsumers(Connection con, List<UUID> consumerIds) throws SQLException {
        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        Throwable failure = null;
        try (Statement role = con.createStatement();
             PreparedStatement delete = con.prepareStatement("DELETE FROM consumers WHERE consumer_id = ANY(?)")) {
            role.execute("SET LOCAL session_replication_role = replica");
            delete.setArray(1, con.createArrayOf("uuid", consumerIds.toArray()));
            delete.executeUpdate();
            con.commit();
        } catch (SQLException | RuntimeException e) {
            failure = e;
            try {
                con.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            try {
                con.setAutoCommit(autoCommit);
            } catch (SQLException restore) {
                if (failure == null) throw restore;
                failure.addSuppressed(restore);
            }
        }
        return null;
    }

    @Test
    void seventyThousandMembers_gateAndMaterializeWithEveryReason() {
        UUID orgId = org();
        UUID otherOrgId = org();
        List<UUID> ids = members(orgId, LARGE_AUDIENCE);
        // One member per reason, each in a different 10,000-id chunk.
        List<UUID> spread = new ArrayList<>();
        for (int i = 0; i < 6; i++) spread.add(ids.get(5 + i * 12_000));
        Map<String, UUID> role = assignRoles(orgId, spread);
        UUID foreign = members(otherOrgId, 1).get(0);
        List<UUID> audience = new ArrayList<>(ids);
        audience.add(65_000, foreign);
        audience.add(ids.get(0));          // the first id again, in the last chunk

        SendGateService.GateResult r = sendGate.evaluate(orgId, audience);

        Set<UUID> expectedSendable = new HashSet<>(ids);
        role.forEach((name, id) -> { if (!name.equals("sendable")) expectedSendable.remove(id); });
        assertThat(r.sendable()).hasSize(expectedSendable.size());
        assertThat(new HashSet<>(r.sendable())).isEqualTo(expectedSendable);
        assertThat(r.excluded()).containsExactlyInAnyOrderElementsOf(expectedExclusions(role));

        Campaign c = campaign(orgId, staticSegment(orgId, ids));
        materializer.materialize(c);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT recipient_count, excluded_count, exclusion_summary FROM campaigns WHERE id = ?", c.getId());
        assertThat(row.get("recipient_count")).isEqualTo(LARGE_AUDIENCE - 5);
        assertThat(row.get("excluded_count")).isEqualTo(4);
        assertThat(row.get("exclusion_summary")).isEqualTo("{\"deliverability_suppressed\":1,"
                + "\"marketing_suppressed\":1,\"marketing_unsubscribed\":1,\"no_lawful_basis\":1}");
        Map<UUID, String> states = new HashMap<>();
        jdbc.query("SELECT membership_id, status, skip_reason FROM campaign_recipients WHERE campaign_id = ?",
                rs -> { states.put(rs.getObject("membership_id", UUID.class),
                        rs.getString("status") + "/" + rs.getString("skip_reason")); }, c.getId());
        assertThat(states).hasSize(LARGE_AUDIENCE - 1);
        assertThat(states.get(role.get("sendable"))).isEqualTo("pending/null");
        assertThat(states.get(role.get("unsubscribed"))).isEqualTo("skipped/marketing_unsubscribed");
        assertThat(states.get(role.get("marketingSuppressed"))).isEqualTo("skipped/marketing_suppressed");
        assertThat(states.get(role.get("deliverabilitySuppressed"))).isEqualTo("skipped/deliverability_suppressed");
        assertThat(states.get(role.get("noBasis"))).isEqualTo("skipped/no_lawful_basis");
        assertThat(states).doesNotContainKey(role.get("erasePending"));
    }

    private UUID org() {
        UUID id = fx.org().getId();
        orgIds.add(id);
        return id;
    }

    /** n subscribed, explicitly consented members with their own addresses, in insertion order. */
    private List<UUID> members(UUID orgId, int n) {
        return jdbc.queryForList("""
                WITH c AS (
                  INSERT INTO consumers (consumer_id, normalized_email)
                  SELECT gen_random_uuid(), ? || gen_random_uuid() || '@test.invalid' FROM generate_series(1, ?)
                  RETURNING consumer_id)
                INSERT INTO memberships (membership_id, org_id, consumer_id, consent_status, consent_basis)
                SELECT gen_random_uuid(), ?, consumer_id, 'subscribed', 'explicit' FROM c
                RETURNING membership_id
                """, UUID.class, EMAIL_PREFIX, n, orgId);
    }

    /** Turns six members into one sendable member and one per DB-reachable gate outcome. */
    private Map<String, UUID> assignRoles(UUID orgId, List<UUID> six) {
        Map<String, UUID> role = new LinkedHashMap<>();
        role.put("sendable", six.get(0));
        role.put("unsubscribed", six.get(1));
        role.put("marketingSuppressed", six.get(2));
        role.put("deliverabilitySuppressed", six.get(3));
        role.put("noBasis", six.get(4));
        role.put("erasePending", six.get(5));
        jdbc.update("UPDATE memberships SET consent_status = 'unsubscribed' WHERE membership_id = ?",
                role.get("unsubscribed"));
        jdbc.update("INSERT INTO suppression_entries (id, scope, org_id, membership_id, reason, system_owned) "
                + "VALUES (gen_random_uuid(), 'marketing', ?, ?, 'manual', false)", orgId, role.get("marketingSuppressed"));
        String email = jdbc.queryForObject("SELECT c.normalized_email FROM consumers c JOIN memberships m "
                + "ON m.consumer_id = c.consumer_id WHERE m.membership_id = ?", String.class,
                role.get("deliverabilitySuppressed"));
        deliverabilityEmails.add(email);
        jdbc.update("INSERT INTO suppression_entries (id, scope, normalized_email, reason, system_owned) "
                + "VALUES (gen_random_uuid(), 'deliverability', ?, 'hard_bounce', true)", email);
        jdbc.update("UPDATE memberships SET consent_basis = NULL WHERE membership_id = ?", role.get("noBasis"));
        jdbc.update("UPDATE memberships SET status = 'erase_pending' WHERE membership_id = ?", role.get("erasePending"));
        return role;
    }

    private static List<ExclusionReason> expectedExclusions(Map<String, UUID> role) {
        return List.of(
                new ExclusionReason(role.get("unsubscribed"), "marketing_unsubscribed"),
                new ExclusionReason(role.get("marketingSuppressed"), "marketing_suppressed"),
                new ExclusionReason(role.get("deliverabilitySuppressed"), "deliverability_suppressed"),
                new ExclusionReason(role.get("noBasis"), "no_lawful_basis"));
    }

    private UUID staticSegment(UUID orgId, List<UUID> ids) {
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Chunks " + UUID.randomUUID());
        seg.setKind("static");
        seg.setSnapshotIds(ids.stream().map(id -> "\"" + id + "\"").toList().toString());
        return segments.save(seg).getId();
    }

    private Campaign campaign(UUID orgId, UUID segmentId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Send gate chunks");
        c.setStatus("sending");
        c.setOrigin("manual");
        c.setSegmentId(segmentId);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }
}
