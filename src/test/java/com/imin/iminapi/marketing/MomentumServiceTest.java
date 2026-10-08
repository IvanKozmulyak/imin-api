package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.MomentumEngineStateDto;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.marketing.service.MomentumService;
import com.imin.iminapi.marketing.service.MomentumThresholds;
import com.imin.iminapi.marketing.repository.CampaignRepository; // Phase 2
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IminIntegrationTest
class MomentumServiceTest {

    @Autowired MomentumService service;
    @Autowired MomentumSuggestionRepository suggestions;
    @Autowired CampaignRepository campaigns;
    @Autowired MomentumTestSupport support; // reused seeder from Task 6
    @Autowired MomentumThresholds thresholds;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    /** Seeded events are live and on sale, so the evaluator's pass would otherwise keep visiting them. */
    @AfterEach
    void deleteOwnRows() {
        for (UUID orgId : orgIds) jdbc.update("delete from momentum_suggestions where org_id = ?", orgId);
        CampaignRows.delete(jdbc, orgIds);
        OrgRows.delete(jdbc, orgIds);
    }

    private UUID liveEvent(int sold, int capacity, Instant onSaleAt, Instant startsAt) {
        UUID event = support.seedLiveEvent(sold, capacity, onSaleAt, startsAt);
        orgIds.add(support.orgIdOf(event));
        return event;
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
        UUID event = liveEvent(5, 100, Instant.now().minusSeconds(3600),
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
        UUID event = liveEvent(5, 100, Instant.now().minusSeconds(3600),
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
        UUID event = liveEvent(5, 100, Instant.now().minusSeconds(3600),
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
        orgIds.add(org);
        UUID missingEvent = UUID.randomUUID();
        seedActed(org, missingEvent, "slump", "dismissed", Instant.now().minusSeconds(86400));

        MomentumEngineStateDto state = service.state(support.principalFor(org));
        assertThat(state.log()).hasSize(1);
        assertThat(state.log().get(0).text())
                .isEqualTo("Dismissed — " + missingEvent.toString().substring(0, 8) + " slump");
    }

    @Test
    void listReturnsOrgSuggestions() {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));
        List<MomentumSuggestionDto> out = service.list(principal, "suggested");
        assertThat(out).isNotEmpty();
        assertThat(out.get(0).triggerType()).isEqualTo("launch_push");
    }

    @Test
    void approveCreatesMomentumCampaignAndReturnsIt() {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
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

    /** Only the parts the model actually wrote are marked AI-generated, on the DTO and the stored campaign. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "subject and body | '{\"subject\":\"Announcing\",\"preheader\":\"p\",\"bodyMd\":\"b\",\"segmentId\":null,\"posterUrl\":null,\"why\":\"low sales\"}' | true  | true",
            "empty draft      | '{}'                                                                                                                                | false | false",
            "preheader only   | '{\"preheader\":\"p\"}'                                                                                                          | false | true",
    })
    void approve_marksOnlyTheModelWrittenParts(String label, String draft, boolean subjectAi, boolean bodyAi) {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        s.setDraftPayload(draft);
        suggestions.save(s);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));

        var campaign = service.approve(principal, s.getId());

        assertThat(campaign.subjectAiGenerated()).isEqualTo(subjectAi);
        assertThat(campaign.bodyAiGenerated()).isEqualTo(bodyAi);
        var saved = campaigns.findById(campaign.id()).orElseThrow();
        assertThat(saved.isSubjectAiGenerated()).isEqualTo(subjectAi);
        assertThat(saved.isBodyAiGenerated()).isEqualTo(bodyAi);
    }

    @Test
    void dismissMarksDismissed() {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));
        service.dismiss(principal, s.getId());
        assertThat(suggestions.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("dismissed");
    }

    /**
     * mkt-core-9(a): the draft payload is model output and V52 declares
     * name VARCHAR(120) / subject VARCHAR(200) / preheader VARCHAR(200). The prompt only ASKS
     * for 60/90 chars, so an over-long subject was a varchar overflow — a 500 on the
     * organizer's Approve click. CampaignService.create already clamps the same column.
     */
    @Test
    void approveClampsOverlongModelCopyToTheColumnWidths() {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        s.setDraftPayload("{\"subject\":\"" + "S".repeat(400) + "\",\"preheader\":\""
                + "P".repeat(400) + "\",\"bodyMd\":\"b\",\"segmentId\":null}");
        suggestions.save(s);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));

        var campaign = service.approve(principal, s.getId());

        var saved = campaigns.findById(campaign.id()).orElseThrow();
        assertThat(saved.getName().length()).isLessThanOrEqualTo(120);
        assertThat(saved.getSubject().length()).isLessThanOrEqualTo(200);
        assertThat(saved.getPreheader().length()).isLessThanOrEqualTo(200);
    }

    /**
     * mkt-core-9(b): approve requires status 'suggested'; dismiss had no status guard, so an
     * already-approved suggestion (campaign row created, campaignId set) could be flipped to
     * 'dismissed' — orphaning the link and skewing state()'s approved30d/dismissed30d.
     */
    @Test
    void dismissRefusesASuggestionThatWasAlreadyApproved() {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));
        service.approve(principal, s.getId());

        assertThatThrownBy(() -> service.dismiss(principal, s.getId()))
                .isInstanceOf(ApiException.class);

        assertThat(suggestions.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("approved");
    }

    @Test
    void approveFromAnotherOrgIs404() {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal otherOrg = support.principalFor(UUID.randomUUID());
        assertThatThrownBy(() -> service.approve(otherOrg, s.getId()))
                .isInstanceOf(ApiException.class);
    }
}
