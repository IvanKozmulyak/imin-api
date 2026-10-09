package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A null email or skip reason in the snapshot arrays lands as SQL NULL, never the text "null". */
@IminIntegrationTest
class CampaignRecipientBulkInsertTest {

    @Autowired CampaignRecipientBulkInsert bulkInsert;
    @Autowired CampaignRepository campaigns;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private UUID orgId;

    @AfterEach
    void deleteOwnCampaigns() {
        if (orgId != null) CampaignRows.delete(jdbc, List.of(orgId));
    }

    @Test
    void aMemberWithNoAddress_isStoredWithANullEmailBesideTheOthers() {
        orgId = UUID.randomUUID();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Bulk insert");
        c.setStatus("sending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        UUID noAddress = member();
        UUID pending = member();
        UUID suppressed = member();
        String pendingEmail = fx.email("bulk-pending");
        String suppressedEmail = fx.email("bulk-suppressed");
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);

        bulkInsert.insert(c.getId(), at, List.of(
                new CampaignRecipientBulkInsert.Row(pending, pendingEmail, "pending", null),
                new CampaignRecipientBulkInsert.Row(noAddress, null, "skipped", "no_email"),
                new CampaignRecipientBulkInsert.Row(suppressed, suppressedEmail, "skipped", "marketing_suppressed")));

        Map<UUID, Map<String, Object>> rows = new HashMap<>();
        jdbc.queryForList("SELECT membership_id, email, email IS NULL AS email_null, status, skip_reason, "
                        + "skip_reason IS NULL AS reason_null, last_event_at FROM campaign_recipients WHERE campaign_id = ?",
                c.getId()).forEach(r -> rows.put((UUID) r.get("membership_id"), r));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(noAddress)).containsEntry("email_null", true)
                .containsEntry("status", "skipped").containsEntry("skip_reason", "no_email");
        assertThat(rows.get(pending)).containsEntry("email", pendingEmail)
                .containsEntry("status", "pending").containsEntry("reason_null", true);
        assertThat(rows.get(suppressed)).containsEntry("email", suppressedEmail)
                .containsEntry("status", "skipped").containsEntry("skip_reason", "marketing_suppressed");
        rows.values().forEach(r -> assertThat(((Timestamp) r.get("last_event_at")).toInstant()).isEqualTo(at));
    }

    private UUID member() {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(fx.email("bulk-insert"));
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        return memberships.save(m).getMembershipId();
    }
}
