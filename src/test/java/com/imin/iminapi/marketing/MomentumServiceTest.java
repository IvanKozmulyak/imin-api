package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.MomentumEngineStateDto;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.marketing.service.MomentumService;
import com.imin.iminapi.marketing.service.MomentumThresholds;
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
    @Autowired MomentumThresholds thresholds;
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

    /** Seed a terminal (acted-on) suggestion with an explicit status + actedAt for state tests. */
    private MomentumSuggestion seedActed(UUID orgId, UUID eventId, String trigger,
                                         String status, Instant actedAt) {
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(orgId);
        s.setEventId(eventId);
        s.setTriggerType(trigger);
        s.setStatus(status);
        s.setMetricsSnapshot("{}");
        s.setDraftPayload("{}");
        s.setSuggestedAt(actedAt);
        s.setActedAt(actedAt);
        return suggestions.save(s);
    }

    @Test
    void stateReturnsRealCountersAndThresholds() {
        UUID event = support.seedLiveEvent(5, 100, Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(864000));
        UUID org = support.orgIdOf(event);
        // 1 live 'suggested' → waiting=1, and this org's seeded live event is a Momentum
        // candidate → watching=1.
        seedSuggestion(org, event);
        // acted within 30d
        seedActed(org, event, "slump", "approved", Instant.now().minusSeconds(2L * 86400));
        seedActed(org, event, "urgency_72h", "dismissed", Instant.now().minusSeconds(5L * 86400));
        // acted 40d ago → outside the 30d window, excluded from the counters
        seedActed(org, event, "sold_out", "approved", Instant.now().minusSeconds(40L * 86400));

        AuthPrincipal principal = support.principalFor(org);
        MomentumEngineStateDto state = service.state(principal);

        assertThat(state.watching()).isEqualTo(1);
        assertThat(state.waiting()).isEqualTo(1);
        assertThat(state.approved30d()).isEqualTo(1);   // the 40d-old approved is excluded
        assertThat(state.dismissed30d()).isEqualTo(1);
        assertThat(state.attributedMinor()).isEqualTo(0L); // never faked — no source today
        assertThat(state.minAudienceFloor()).isEqualTo(thresholds.getMinAudienceFloor());
        assertThat(state.cooldownDays()).isEqualTo(thresholds.getCooldownDays());
    }

    @Test
    void stateLogIsNewestFirstAndMapsIconToneText() {
        UUID event = support.seedLiveEvent(5, 100, Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(864000));
        UUID org = support.orgIdOf(event);
        String eventName = support.eventNameOf(event);

        // A live 'suggested' row must NOT appear in the log.
        seedSuggestion(org, event);
        // Three terminal rows at distinct ages; newest = approved 1d ago.
        seedActed(org, event, "urgency_72h", "expired", Instant.now().minusSeconds(9L * 86400));
        seedActed(org, event, "slump", "dismissed", Instant.now().minusSeconds(5L * 86400));
        seedActed(org, event, "launch_push", "approved", Instant.now().minusSeconds(1L * 86400));

        MomentumEngineStateDto state = service.state(support.principalFor(org));

        assertThat(state.log()).hasSize(3);
        // newest first
        var first = state.log().get(0);
        assertThat(first.icon()).isEqualTo("check");
        assertThat(first.tone()).isEqualTo("green");
        assertThat(first.text()).isEqualTo("Approved — " + eventName + " launch push");
        assertThat(first.sub()).isEqualTo("1 day ago");

        var second = state.log().get(1);
        assertThat(second.icon()).isEqualTo("x");
        assertThat(second.tone()).isEqualTo("muted");
        assertThat(second.text()).isEqualTo("Dismissed — " + eventName + " slump");

        var third = state.log().get(2);
        assertThat(third.icon()).isEqualTo("clock");
        assertThat(third.tone()).isEqualTo("amber");
        assertThat(third.text()).isEqualTo("Expired — " + eventName + " urgency 72h");
        assertThat(third.sub()).isEqualTo("1 week ago");
    }

    @Test
    void stateDoesNotLeakAcrossOrgs() {
        UUID event = support.seedLiveEvent(5, 100, Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(864000));
        UUID org = support.orgIdOf(event);
        seedSuggestion(org, event);
        seedActed(org, event, "slump", "approved", Instant.now().minusSeconds(86400));

        // A caller in a different org sees an empty engine state, not this org's rows.
        MomentumEngineStateDto other = service.state(support.principalFor(UUID.randomUUID()));
        assertThat(other.watching()).isZero();
        assertThat(other.waiting()).isZero();
        assertThat(other.approved30d()).isZero();
        assertThat(other.dismissed30d()).isZero();
        assertThat(other.log()).isEmpty();
    }

    @Test
    void stateLogFallsBackToEventIdPrefixWhenEventGone() {
        UUID org = UUID.randomUUID();
        UUID missingEvent = UUID.randomUUID();
        seedActed(org, missingEvent, "slump", "dismissed", Instant.now().minusSeconds(86400));

        MomentumEngineStateDto state = service.state(support.principalFor(org));
        assertThat(state.log()).hasSize(1);
        assertThat(state.log().get(0).text())
                .isEqualTo("Dismissed — " + missingEvent.toString().substring(0, 8) + " slump");
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
