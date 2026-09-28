package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.model.RecipientStatuses;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The one sent-status set, and the org sent count that reads it. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class RecipientStatusesTest {

    private static final List<String> SENT =
            List.of("sent", "delivered", "opened", "clicked", "complained", "unsubscribed");
    private static final List<String> NOT_SENT = List.of("pending", "skipped", "failed", "bounced");

    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired JdbcTemplate jdbc;

    private UUID orgId;

    @AfterEach
    void tearDown() {
        if (orgId == null) return;
        jdbc.update("delete from campaign_recipients where campaign_id in (select id from campaigns where org_id = ?)",
                orgId);
        jdbc.update("delete from campaigns where org_id = ?", orgId);
    }

    @Test
    void sentSet_isTheSixStatusesThatLeftTheProvider() {
        assertThat(RecipientStatuses.SENT_SQL)
                .isEqualTo("('sent', 'delivered', 'opened', 'clicked', 'complained', 'unsubscribed')");
    }

    @Test
    void countSentRecipientsByOrgId_countsEverySentStatus_andNoOther() {
        orgId = org();
        UUID campaignId = campaign(orgId);
        for (String status : SENT) recipient(campaignId, status);
        for (String status : NOT_SENT) recipient(campaignId, status);

        assertThat(recipients.countSentRecipientsByOrgId(orgId)).isEqualTo(SENT.size());
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("Status Org");
        o.setSlug("status-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("status@example.com");
        o.setCountry("FR");
        return orgs.save(o).getId();
    }

    private UUID campaign(UUID org) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org);
        c.setChannel("email");
        c.setName("Statuses");
        c.setStatus("sent");
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return campaigns.save(c).getId();
    }

    private void recipient(UUID campaignId, String status) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(campaignId);
        r.setMembershipId(null);
        r.setEmail(status + "-" + UUID.randomUUID() + "@example.com");
        r.setStatus(status);
        recipients.save(r);
    }
}
