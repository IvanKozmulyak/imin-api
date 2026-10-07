package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The org sent count (the unsubscribe and complaint rates' denominator) over the one sent-status set. */
@IminIntegrationTest
class RecipientStatusesTest {

    private static final List<String> SENT =
            List.of("sent", "delivered", "opened", "clicked", "complained", "unsubscribed");
    private static final List<String> NOT_SENT = List.of("pending", "skipped", "failed", "bounced");

    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;

    private UUID orgId;

    @AfterEach
    void tearDown() {
        if (orgId != null) CampaignRows.delete(jdbc, List.of(orgId));
    }

    @Test
    void countSentRecipientsByOrgId_countsEverySentStatus_andNoOther() {
        orgId = fx.org().getId();
        UUID campaignId = campaign(orgId);
        for (String status : SENT) recipient(campaignId, status);
        for (String status : NOT_SENT) recipient(campaignId, status);

        assertThat(recipients.countSentRecipientsByOrgId(orgId)).isEqualTo(SENT.size());
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
        r.setEmail(fx.email(status));
        r.setStatus(status);
        recipients.save(r);
    }
}
