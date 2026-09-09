package com.imin.iminapi.marketing.unsubscribe;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * mkt-core-3 (P2): the campaign "unsubscribed" stat was structurally always 0 — nothing
 * wrote that recipient status. The opt-out token carries the campaign id, so the row the
 * opt-out came from is addressable and the count can be real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class CampaignUnsubscribeProjectionTest {

    @Autowired MockMvc mvc;
    @Autowired UnsubscribeTokenService tokenService;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    /** The consent write is exercised elsewhere; this test is about the campaign projection. */
    @MockitoBean ConsentService consentService;

    private record Fixture(UUID orgId, UUID membershipId, UUID campaignId, UUID recipientId) {}

    private Fixture seed(String recipientStatus) {
        UUID orgId = UUID.randomUUID();
        Consumer cn = new Consumer();
        cn.setNormalizedEmail("unsub-" + UUID.randomUUID() + "@example.com");
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setStatus("active");
        m = memberships.save(m);

        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Blast");
        c.setStatus("sent");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(cn.getNormalizedEmail());
        r.setStatus(recipientStatus);
        recipients.save(r);
        return new Fixture(orgId, m.getMembershipId(), c.getId(), r.getId());
    }

    @Test
    void oneClickMarksTheRecipientRowUnsubscribed() throws Exception {
        Fixture f = seed("delivered");
        String token = tokenService.sign(f.orgId(), f.membershipId(), f.campaignId(), "email");

        mvc.perform(post("/api/v1/public/unsubscribe/{token}", token))
                .andExpect(status().isOk());

        CampaignRecipient after = recipients.findById(f.recipientId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("unsubscribed");
        assertThat(after.getLastEventAt()).isNotNull();
        assertThat(recipients.countByCampaignIdAndStatus(f.campaignId(), "unsubscribed")).isEqualTo(1L);
    }

    /** A complaint outranks an opt-out: the projection must not walk a terminal status back. */
    @Test
    void oneClickDoesNotOverwriteAComplaint() throws Exception {
        Fixture f = seed("complained");
        String token = tokenService.sign(f.orgId(), f.membershipId(), f.campaignId(), "email");

        mvc.perform(post("/api/v1/public/unsubscribe/{token}", token))
                .andExpect(status().isOk());

        assertThat(recipients.findById(f.recipientId()).orElseThrow().getStatus())
                .isEqualTo("complained");
    }
}
