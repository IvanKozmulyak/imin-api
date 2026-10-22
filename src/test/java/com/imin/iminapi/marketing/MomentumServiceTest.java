package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.MomentumEngineStateDto;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumFloorSkipRepository;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.marketing.service.MomentumEvaluator;
import com.imin.iminapi.marketing.service.MomentumService;
import com.imin.iminapi.marketing.service.MomentumThresholds;
import com.imin.iminapi.marketing.repository.CampaignRepository; // Phase 2
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IminIntegrationTest
class MomentumServiceTest {

    @Autowired MomentumService service;
    @Autowired MomentumSuggestionRepository suggestions;
    @Autowired CampaignRepository campaigns;
    @Autowired MomentumTestSupport support; // reused seeder from Task 6
    @Autowired MomentumThresholds thresholds;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired MomentumEvaluator evaluator;
    @Autowired DataSource dataSource;
    @Autowired MomentumFloorSkipRepository floorSkips;

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

    private UUID campaign(UUID orgId, String origin, Instant createdAt) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Camp " + origin);
        c.setStatus("sent");
        c.setOrigin(origin);
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(createdAt);
        return campaigns.save(c).getId();
    }

    /** A live order carrying the campaign's utm tag, as UtmLinkRewriter writes it. */
    private void taggedOrder(UUID orgId, UUID eventId, UUID campaignId, long totalMinor) {
        Order o = new Order();
        o.setToken("tok-" + UUID.randomUUID());
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setEmail("buyer-" + UUID.randomUUID() + "@example.com");
        o.setTotalMinor(totalMinor);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setUtmCampaign(campaignId.toString());
        o.setCreatedAt(Instant.now());
        orders.save(o);
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
        // Ticket sales: only the Momentum campaign created in the last 30 days counts.
        taggedOrder(org, event, campaign(org, "momentum", Instant.now().minusSeconds(2L * 86400)), 5_000);
        taggedOrder(org, event, campaign(org, "manual", Instant.now().minusSeconds(2L * 86400)), 7_000);
        taggedOrder(org, event, campaign(org, "momentum", Instant.now().minusSeconds(40L * 86400)), 9_000);
        UUID otherEvent = liveEvent(5, 100, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(864000));
        UUID otherOrg = support.orgIdOf(otherEvent);
        taggedOrder(otherOrg, otherEvent,
                campaign(otherOrg, "momentum", Instant.now().minusSeconds(2L * 86400)), 11_000);

        AuthPrincipal principal = support.principalFor(org);
        MomentumEngineStateDto state = service.state(principal);

        assertThat(state.watching()).isEqualTo(1);
        assertThat(state.waiting()).isEqualTo(1);
        assertThat(state.approved30d()).isEqualTo(1);   // the 40d-old approved is excluded
        assertThat(state.dismissed30d()).isEqualTo(1);
        assertThat(state.attributedMinor()).isEqualTo(5_000L);
        assertThat(state.minAudienceFloor()).isEqualTo(thresholds.getMinAudienceFloor());
        assertThat(state.cooldownDays()).isEqualTo(thresholds.getCooldownDays());
    }

    /** Rows carry what happened, not English text: the dashboard words them; a vanished event has no name. */
    @Test
    void stateLogIsNewestFirstWithStatusTriggerAndTime() {
        UUID event = liveEvent(5, 100, Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(864000));
        UUID org = support.orgIdOf(event);
        String eventName = support.eventNameOf(event);
        UUID missingEvent = UUID.randomUUID(); // momentum_suggestions.event_id has no FK, so the row outlives it

        // A live 'suggested' row must NOT appear in the log.
        seedSuggestion(org, event);
        Instant expired = Instant.now().minus(Duration.ofDays(9));
        Instant dismissed = Instant.now().minus(Duration.ofDays(5));
        Instant gone = Instant.now().minus(Duration.ofDays(3));
        Instant approved = Instant.now().minus(Duration.ofDays(1));
        seedActed(org, event, "urgency_72h", "expired", expired);
        seedActed(org, event, "slump", "dismissed", dismissed);
        seedActed(org, missingEvent, "sold_out", "dismissed", gone);
        seedActed(org, event, "launch_push", "approved", approved);

        MomentumEngineStateDto state = service.state(support.principalFor(org));

        assertThat(state.log()).containsExactly(
                new MomentumEngineStateDto.LogEntry("approved", "launch_push", event, eventName, micros(approved)),
                new MomentumEngineStateDto.LogEntry("dismissed", "sold_out", missingEvent, null, micros(gone)),
                new MomentumEngineStateDto.LogEntry("dismissed", "slump", event, eventName, micros(dismissed)),
                new MomentumEngineStateDto.LogEntry("expired", "urgency_72h", event, eventName, micros(expired)));
    }

    private static Instant micros(Instant at) {
        return at.truncatedTo(ChronoUnit.MICROS);
    }

    enum Excluded { THREE_HOURS_OLD, EVENT_STARTED, ANOTHER_ORG }

    /** Only a fresh skip of a night this org still watches is the current reason Momentum is quiet. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Excluded.class)
    void belowFloorShowsOnlyFreshSkipsOfThisOrgsWatchedEvents(Excluded excluded) {
        UUID event = liveEvent(5, 100, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(864000));
        UUID org = support.orgIdOf(event);
        Instant now = Instant.now();
        // The excluded row is written first and, but for the stale one, checked later, so it would sort first.
        switch (excluded) {
            case THREE_HOURS_OLD -> floorSkips.upsert(
                    support.seedSiblingEvent(event, "Older Night", now.plus(Duration.ofDays(20))), org, "slump", 2, 10,
                    now.minus(Duration.ofHours(3)));
            case EVENT_STARTED -> {
                UUID started = support.seedSiblingEvent(event, "Started Night", now.plus(Duration.ofDays(20)));
                support.markStarted(started);
                floorSkips.upsert(started, org, "urgency_72h", 4, 10, now);
            }
            case ANOTHER_ORG -> {
                UUID other = liveEvent(5, 100, now.minusSeconds(3600), now.plusSeconds(864000));
                floorSkips.upsert(other, support.orgIdOf(other), "sold_out", 1, 10, now);
            }
        }
        Instant checked = now.minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MICROS);
        floorSkips.upsert(event, org, "launch_push", 3, 10, checked);

        MomentumEngineStateDto state = service.state(support.principalFor(org));

        assertThat(state.belowFloor()).containsExactly(new MomentumEngineStateDto.FloorSkipEntry(
                event, support.eventNameOf(event), "launch_push", 3, 10, checked));
    }

    /** A draft carrying the event poster renders with the poster template, as the card promised. */
    @ParameterizedTest(name = "posterUrl {0} -> {1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "'\"https://cdn.imin.wtf/ai-posters/night.png\"' | poster",
            "NULL                                              | classic",
            "'\"  \"'                                         | classic",
    })
    void approveDraftsWithThePosterTemplateWhenTheSuggestionCarriesAPoster(String posterJson, String template) {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        s.setDraftPayload("{\"subject\":\"Announcing\",\"bodyMd\":\"b\",\"segmentId\":null,\"posterUrl\":"
                + (posterJson == null ? "null" : posterJson) + "}");
        suggestions.save(s);

        var campaign = service.approve(support.principalFor(support.orgIdOf(event)), s.getId());

        assertThat(campaigns.findById(campaign.id()).orElseThrow().getTemplateKey()).isEqualTo(template);
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

    /** A double click or a second tab makes one draft; that draft leaves out people already holding a ticket. */
    @Test
    void approvingTheSameSuggestionTwice_makesOneDraft() {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));

        var first = service.approve(principal, s.getId());

        assertThatThrownBy(() -> service.approve(principal, s.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE);
                });
        assertThat(draftsOf(s.getId())).containsExactly(first.id());
        assertThat(first.skipTicketHolders()).isTrue();
        assertThat(campaigns.findById(first.id()).orElseThrow().isSkipTicketHolders()).isTrue();
        assertThat(suggestions.findById(s.getId()).orElseThrow().getCampaignId()).isEqualTo(first.id());
    }

    enum Ordering { EXPIRE_THEN_APPROVE, APPROVE_THEN_DISMISS, DISMISS_THEN_APPROVE }

    /** Whichever end state lands first stays: a later approve, dismiss or expiry answers 409 or does nothing. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Ordering.class)
    void anEndStateNeverFlips(Ordering ordering) {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));

        String expected = switch (ordering) {
            case EXPIRE_THEN_APPROVE -> {
                support.markStarted(event);
                evaluator.runOnce();
                assertThatThrownBy(() -> service.approve(principal, s.getId())).isInstanceOf(ApiException.class);
                yield "expired";
            }
            case APPROVE_THEN_DISMISS -> {
                service.approve(principal, s.getId());
                assertThatThrownBy(() -> service.dismiss(principal, s.getId())).isInstanceOf(ApiException.class);
                yield "approved";
            }
            case DISMISS_THEN_APPROVE -> {
                service.dismiss(principal, s.getId());
                assertThatThrownBy(() -> service.approve(principal, s.getId())).isInstanceOf(ApiException.class);
                yield "dismissed";
            }
        };

        assertThat(suggestions.findById(s.getId()).orElseThrow().getStatus()).isEqualTo(expected);
        assertThat(draftsOf(s.getId())).hasSize("approved".equals(expected) ? 1 : 0);
    }

    /** The expiry sweep read the row while an approval was still committing; its write must not turn it 'expired'. */
    @Test
    void anApprovalCommittedAfterTheSweepReadTheRow_staysApproved() throws Exception {
        UUID event = liveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        MomentumSuggestion s = seedSuggestion(support.orgIdOf(event), event);
        AuthPrincipal principal = support.principalFor(support.orgIdOf(event));
        support.markStarted(event);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "momentum_suggestions", "id", s.getId())) {
            Future<?> approving = pool.submit(() -> service.approve(principal, s.getId()));
            pause.awaitBlocked(Duration.ofSeconds(10));
            Future<?> sweeping = pool.submit(() -> evaluator.runOnce());
            awaitRowLockWait(Duration.ofSeconds(20));
            pause.release();
            approving.get(30, TimeUnit.SECONDS);
            sweeping.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(suggestions.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("approved");
        assertThat(draftsOf(s.getId())).hasSize(1);
    }

    /** Waits until another statement queues on a momentum_suggestions row lock (not on the advisory pause). */
    private void awaitRowLockWait(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                    + " AND wait_event IN ('transactionid', 'tuple') AND query ILIKE 'update momentum_suggestions%'",
                    Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("the expiry sweep never queued on the suggestion row within " + timeout);
    }

    private List<UUID> draftsOf(UUID suggestionId) {
        return jdbc.queryForList("SELECT id FROM campaigns WHERE momentum_suggestion_id = ?", UUID.class, suggestionId);
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
