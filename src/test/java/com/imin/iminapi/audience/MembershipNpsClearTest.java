package com.imin.iminapi.audience;

import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.webhook.ResendWebhookProjector;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The membership recommendation score: cleared on the person's own unsubscribe, never reverted by a stale save. */
@IminIntegrationTest
class MembershipNpsClearTest {

    @Autowired IminFixtures fx;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired MembershipRepository memberships;
    @Autowired ConsentService consentService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResendWebhookProjector projector;
    @Autowired CampaignRepository campaigns;

    private UUID orgId;
    private UUID mid;

    @BeforeEach
    void setUp() {
        orgId = fx.org().getId();
        String email = fx.email("nps");
        orderProjector.upsertMembership(orgId, email, null);
        mid = jdbc.queryForObject("select m.membership_id from memberships m join consumers c on c.consumer_id ="
                + " m.consumer_id where m.org_id = ? and c.normalized_email = ?", UUID.class, orgId, email);
    }

    @AfterEach
    void tearDown() {
        List<UUID> cids = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, orgId);
        jdbc.update("delete from consent_records where membership_id = ?", mid);
        jdbc.update("delete from fan_features where membership_id = ?", mid);
        jdbc.update("delete from marketing_optouts where org_id = ?", orgId);
        jdbc.update("delete from memberships where org_id = ?", orgId);
        for (UUID cid : cids) jdbc.update("delete from consumers where consumer_id = ?", cid);
        jdbc.update("delete from suppression_entries where org_id = ?", orgId);
        CampaignRows.delete(jdbc, List.of(orgId));
        OrgRows.delete(jdbc, List.of(orgId));
    }

    @Test
    void the_persons_own_unsubscribe_clears_the_score() {
        memberships.updateNps(mid, (short) 9);
        UUID held = holdScore();

        consentService.unsubscribe(orgId, mid, "unsubscribe_link", ConsentOrigin.DATA_SUBJECT, null);

        assertThat(nps()).as("an objection takes the person out of Promoters").isNull();
        assertThat(heldScore(held)).as("and drops a score still waiting for its confirmation click").isNull();
    }

    @Test
    void a_spam_complaint_clears_the_score() {
        memberships.updateNps(mid, (short) 9);
        UUID held = holdScore();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Sent");
        c.setStatus("sent");
        c.setOrigin("manual");
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        UUID campaignId = campaigns.save(c).getId();

        projector.project(campaignId, null, mid, null, ProviderEvent.TYPE_COMPLAINED, null, Instant.now());

        assertThat(nps()).as("a spam report is the person objecting").isNull();
        assertThat(heldScore(held)).isNull();
    }

    @Test
    void a_stale_full_entity_save_after_the_score_keeps_it() {
        Membership stale = memberships.findByIdAndOrgId(mid, orgId).orElseThrow();
        memberships.updateNps(mid, (short) 9);

        stale.setNotes("late");
        memberships.save(stale);

        assertThat(nps()).isEqualTo((short) 9);
    }

    @Test
    void a_save_before_the_score_lets_the_score_land() {
        Membership m = memberships.findByIdAndOrgId(mid, orgId).orElseThrow();
        m.setNotes("early");
        memberships.save(m);

        memberships.updateNps(mid, (short) 9);

        assertThat(nps()).isEqualTo((short) 9);
    }

    /** A survey sign-up's unclicked confirmation email holding a score of 7. */
    private UUID holdScore() {
        UUID record = consentService.capture(orgId, mid, "explicit", "survey", "Ticked the survey sign-up", "email",
                "survey-org-named-2026-09", null, null, ConsentOrigin.DATA_SUBJECT, null);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into consent_confirmation_tokens (id, org_id, membership_id, consent_record_id, locale,"
                + " sent_at, expires_at, nps_score) values (?, ?, ?, ?, 'en', now(), now() + interval '7 days', 7)",
                id, orgId, mid, record);
        assertThat(heldScore(id)).isEqualTo((short) 7);
        return id;
    }

    private Short heldScore(UUID tokenId) {
        return jdbc.queryForObject("select nps_score from consent_confirmation_tokens where id = ?", Short.class,
                tokenId);
    }

    private Short nps() {
        return jdbc.queryForObject("select nps from memberships where membership_id = ?", Short.class, mid);
    }
}
