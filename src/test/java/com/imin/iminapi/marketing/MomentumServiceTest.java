package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.marketing.service.MomentumService;
import com.imin.iminapi.marketing.repository.CampaignRepository; // Phase 2
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class MomentumServiceTest {

    @Autowired MomentumService service;
    @Autowired MomentumSuggestionRepository suggestions;
    @Autowired CampaignRepository campaigns;
    @Autowired MomentumTestSupport support; // reused seeder from Task 6
    @Autowired DataSource dataSource;

    // Shared H2 context — wipe momentum_suggestions + campaigns + the seeded fixtures,
    // FK-safe, before and after each test (audience convention) so list/approve assertions
    // are not polluted by rows leaked from MomentumRepositoryTest/MomentumEvaluatorTest.
    @BeforeEach
    @AfterEach
    void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from momentum_suggestions");
            s.execute("delete from campaigns");
            s.execute("delete from orders");
            s.execute("delete from ticket_tiers");
            s.execute("delete from events");
            s.execute("delete from segments");
            s.execute("delete from users");
            s.execute("delete from organizations");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }

    private MomentumSuggestion seedSuggestion(UUID orgId, UUID eventId) {
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(orgId);
        s.setEventId(eventId);
        s.setTriggerType("launch_push");
        s.setStatus("suggested");
        s.setMetricsSnapshot("{\"sellThroughPct\":5}");
        s.setDraftPayload("{\"subject\":\"Announcing\",\"preheader\":\"p\",\"bodyMd\":\"b\","
                + "\"segmentId\":null,\"posterUrl\":null,\"why\":\"low sales\"}");
        s.setSuggestedAt(Instant.now());
        return suggestions.save(s);
    }

    @Test
    void listReturnsOrgSuggestions() {
        UUID event = support.seedLiveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));
        List<MomentumSuggestionDto> out = service.list(principal, "suggested");
        assertThat(out).isNotEmpty();
        assertThat(out.get(0).triggerType()).isEqualTo("launch_push");
    }

    @Test
    void approveCreatesMomentumCampaignAndReturnsIt() {
        UUID event = support.seedLiveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));

        var campaign = service.approve(principal, s.getId());
        assertThat(campaign.origin()).isEqualTo("momentum");
        assertThat(campaign.status()).isEqualTo("draft");
        assertThat(campaign.channel()).isEqualTo("email");

        MomentumSuggestion reloaded = suggestions.findById(s.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("approved");
        assertThat(reloaded.getCampaignId()).isNotNull();
        assertThat(campaigns.findById(reloaded.getCampaignId())).isPresent();
    }

    @Test
    void dismissMarksDismissed() {
        UUID event = support.seedLiveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));
        service.dismiss(principal, s.getId());
        assertThat(suggestions.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("dismissed");
    }

    @Test
    void approveFromAnotherOrgIs404() {
        UUID event = support.seedLiveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal otherOrg = support.principalFor(UUID.randomUUID());
        assertThatThrownBy(() -> service.approve(otherOrg, s.getId()))
                .isInstanceOf(ApiException.class);
    }
}
