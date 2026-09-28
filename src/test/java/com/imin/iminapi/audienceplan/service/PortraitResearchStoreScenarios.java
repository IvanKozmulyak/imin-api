package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Pair;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Row;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.Spend;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.StoredGroup;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.WebSource;
import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The portrait research table and the weekly refresh over it, on H2 and on Postgres 17. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
abstract class PortraitResearchStoreScenarios {

    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    private static final String HOUSE = "house & techno";
    private static final StoredGroup GROUP = new StoredGroup("Techno regulars", "They follow the BAM nights.",
            "regulars", List.of("metz"), List.of(new WebSource("https://www.bam-metz.fr/programme", "BAM Metz")),
            "cited");
    private static final Spend SPEND = new Spend("anthropic/claude-haiku-4.5", 2000, 500, new BigDecimal("0.0361"));

    @Autowired PortraitResearchStore store;
    @Autowired JdbcTemplate jdbc;

    private final AudiencePlanProperties props = new AudiencePlanProperties();
    private final PortraitResearchService research = mock(PortraitResearchService.class);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audience_portraits");
    }

    // ── store ──────────────────────────────────────────────────────────────

    @Test
    void firstTouch_createsAPendingRow_laterTouchesMoveRequestedAtAtMostHourly() {
        Row first = store.touch(HOUSE, "metz", NOW);
        assertThat(first.status()).isEqualTo("pending");
        assertThat(first.requestedAt()).isEqualTo(NOW);
        assertThat(first.version()).isZero();
        assertThat(first.groups()).isEmpty();

        assertThat(store.touch(HOUSE, "metz", NOW.plus(Duration.ofMinutes(30))).requestedAt()).isEqualTo(NOW);
        assertThat(store.touch(HOUSE, "metz", NOW.plus(Duration.ofMinutes(61))).requestedAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(61)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audience_portraits", Integer.class)).isEqualTo(1);
    }

    @Test
    void saveReady_storesTheGroups_bumpsTheVersion_andClearsTheReview() {
        store.touch(HOUSE, "metz", NOW);
        jdbc.update("UPDATE audience_portraits SET reviewed_by = 'ivan', reviewed_at = ?", Timestamp.from(NOW));

        store.saveReady(new Pair(HOUSE, "metz"), List.of(GROUP), NOW, NOW.plus(Duration.ofDays(90)), SPEND);

        Row r = store.find(HOUSE, "metz").orElseThrow();
        assertThat(r.status()).isEqualTo("ready");
        assertThat(r.groups()).containsExactly(GROUP);
        assertThat(r.version()).isEqualTo(1);
        assertThat(r.generatedAt()).isEqualTo(NOW);
        assertThat(r.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
        assertThat(r.reviewedBy()).isNull();
        assertThat(jdbc.queryForMap("SELECT model_id, tokens_in, tokens_out, cost_usd, reviewed_at FROM audience_portraits"))
                .containsEntry("model_id", "anthropic/claude-haiku-4.5").containsEntry("tokens_in", 2000)
                .containsEntry("tokens_out", 500).containsEntry("reviewed_at", null)
                .hasEntrySatisfying("cost_usd", v -> assertThat((BigDecimal) v).isEqualByComparingTo("0.0361"));
    }

    @Test
    void saveEmpty_marksAPendingRow_butNeverWipesReadyResearch() {
        store.touch(HOUSE, "metz", NOW);
        store.saveEmpty(new Pair(HOUSE, "metz"), NOW, NOW.plus(Duration.ofDays(1)), new Spend("m", 0, 0, null));
        Row empty = store.find(HOUSE, "metz").orElseThrow();
        assertThat(empty.status()).isEqualTo("empty");
        assertThat(empty.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));

        store.saveReady(new Pair(HOUSE, "metz"), List.of(GROUP), NOW, NOW.plus(Duration.ofDays(90)), SPEND);
        store.saveEmpty(new Pair(HOUSE, "metz"), NOW.plusSeconds(5), NOW.plus(Duration.ofDays(1)),
                new Spend("m", 100, 50, new BigDecimal("0.0010")));
        Row kept = store.find(HOUSE, "metz").orElseThrow();
        assertThat(kept.status()).isEqualTo("ready");
        assertThat(kept.groups()).containsExactly(GROUP);
        assertThat(kept.generatedAt()).isEqualTo(NOW);
        assertThat(kept.version()).isEqualTo(2);
    }

    @Test
    void aFailedRefreshOfReadyResearch_addsItsSpend_aRunWithoutCallsAddsNothing() {
        store.touch(HOUSE, "metz", NOW);
        store.saveReady(new Pair(HOUSE, "metz"), List.of(GROUP), NOW, NOW.plus(Duration.ofDays(90)), SPEND);

        store.saveEmpty(new Pair(HOUSE, "metz"), NOW.plusSeconds(5), NOW.plus(Duration.ofDays(1)),
                new Spend("other/model", 100, 50, new BigDecimal("0.0010")));
        store.saveEmpty(new Pair(HOUSE, "metz"), NOW.plusSeconds(6), NOW.plus(Duration.ofDays(1)),
                new Spend("other/model", 0, 0, null));

        assertThat(jdbc.queryForMap("SELECT model_id, tokens_in, tokens_out, cost_usd FROM audience_portraits"))
                .containsEntry("model_id", "anthropic/claude-haiku-4.5").containsEntry("tokens_in", 2100)
                .containsEntry("tokens_out", 550)
                .hasEntrySatisfying("cost_usd", v -> assertThat((BigDecimal) v).isEqualByComparingTo("0.0371"));
    }

    // ── refresh job ────────────────────────────────────────────────────────

    private void generated(String city, Duration generatedAgo, Duration requestedAgo, String status) {
        store.touch(HOUSE, city, NOW.minus(requestedAgo));
        jdbc.update("UPDATE audience_portraits SET status = ?, generated_at = ?, requested_at = ? WHERE city_key = ?",
                status, Timestamp.from(NOW.minus(generatedAgo)), Timestamp.from(NOW.minus(requestedAgo)), city);
    }

    private PortraitRefreshJob job() {
        return new PortraitRefreshJob(store, research, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void refresh_picksA91DayOldRequestedPortrait_skips89DayOldAndUnrequested() {
        generated("metz", Duration.ofDays(91), Duration.ofDays(3), "ready");
        generated("nancy", Duration.ofDays(89), Duration.ofDays(3), "ready");
        generated("thionville", Duration.ofDays(120), Duration.ofDays(91), "ready");
        generated("lyon", Duration.ofDays(91), Duration.ofDays(89), "empty");
        store.touch(HOUSE, "paris", NOW.minus(Duration.ofDays(200)));
        when(research.refresh(any(), any())).thenReturn(PortraitResearchService.Outcome.READY);

        job().run();

        verify(research).refresh(eq(HOUSE), eq("metz"));
        verify(research).refresh(eq(HOUSE), eq("lyon"));
        verify(research, times(2)).refresh(any(), any());
    }

    @Test
    void refresh_neverTouchesAPendingPairItNeverGenerated() {
        store.touch(HOUSE, "metz", NOW.minus(Duration.ofDays(1)));

        job().run();

        verifyNoInteractions(research);
    }

    @Test
    void refresh_runsOneBatch_oldestFirst() {
        props.setPortraitRefreshBatch(2);
        generated("metz", Duration.ofDays(91), Duration.ofDays(1), "ready");
        generated("nancy", Duration.ofDays(150), Duration.ofDays(1), "ready");
        generated("thionville", Duration.ofDays(100), Duration.ofDays(1), "ready");
        when(research.refresh(any(), any())).thenReturn(PortraitResearchService.Outcome.READY);

        job().run();

        verify(research).refresh(eq(HOUSE), eq("nancy"));
        verify(research).refresh(eq(HOUSE), eq("thionville"));
        verify(research, never()).refresh(eq(HOUSE), eq("metz"));
    }

    @Test
    void refresh_aStuckReadyRow_doesNotBlockAHealthyOneInTheNextBatch() {
        props.setPortraitRefreshBatch(1);
        generated("metz", Duration.ofDays(150), Duration.ofDays(1), "ready");
        generated("nancy", Duration.ofDays(100), Duration.ofDays(1), "ready");
        when(research.refresh(eq(HOUSE), eq("metz"))).thenReturn(PortraitResearchService.Outcome.EMPTY);
        when(research.refresh(eq(HOUSE), eq("nancy"))).thenReturn(PortraitResearchService.Outcome.READY);

        job().run();
        verify(research).refresh(eq(HOUSE), eq("metz"));
        assertThat(store.find(HOUSE, "metz").orElseThrow().status()).isEqualTo("ready");

        job().run();
        verify(research).refresh(eq(HOUSE), eq("nancy"));
        verify(research, times(1)).refresh(eq(HOUSE), eq("metz"));
    }

    @Test
    void refresh_everyOutcomeButTheCap_stampsTheAttempt_andAWeekLaterTheRowIsDueAgain() {
        generated("metz", Duration.ofDays(150), Duration.ofDays(1), "ready");
        generated("nancy", Duration.ofDays(140), Duration.ofDays(1), "ready");
        generated("thionville", Duration.ofDays(130), Duration.ofDays(1), "ready");
        when(research.refresh(eq(HOUSE), eq("metz"))).thenReturn(PortraitResearchService.Outcome.BUSY);
        when(research.refresh(eq(HOUSE), eq("nancy"))).thenThrow(new IllegalStateException("boom"));
        when(research.refresh(eq(HOUSE), eq("thionville"))).thenReturn(PortraitResearchService.Outcome.CAPPED);

        job().run();

        assertThat(attempted("metz")).isEqualTo(NOW);
        assertThat(attempted("nancy")).isEqualTo(NOW);
        assertThat(attempted("thionville")).isNull();
        assertThat(store.dueForRefresh(NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(90)),
                NOW.minus(Duration.ofDays(7)), 10)).extracting(Pair::cityKey).containsExactly("thionville");
        assertThat(store.dueForRefresh(NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(90)),
                NOW.plusSeconds(1), 10)).extracting(Pair::cityKey).containsExactly("metz", "nancy", "thionville");
    }

    private Instant attempted(String city) {
        Timestamp t = jdbc.queryForObject("SELECT refresh_attempted_at FROM audience_portraits WHERE city_key = ?",
                Timestamp.class, city);
        return t == null ? null : t.toInstant();
    }

    @Test
    void refresh_capReached_skipsTheRest() {
        generated("metz", Duration.ofDays(150), Duration.ofDays(1), "ready");
        generated("nancy", Duration.ofDays(100), Duration.ofDays(1), "ready");
        when(research.refresh(any(), any())).thenReturn(PortraitResearchService.Outcome.CAPPED);

        job().run();

        verify(research).refresh(eq(HOUSE), eq("metz"));
        verify(research, never()).refresh(eq(HOUSE), eq("nancy"));
    }

    @Test
    void refresh_oneFailure_doesNotStopTheBatch() {
        generated("metz", Duration.ofDays(150), Duration.ofDays(1), "ready");
        generated("nancy", Duration.ofDays(100), Duration.ofDays(1), "ready");
        when(research.refresh(eq(HOUSE), eq("metz"))).thenThrow(new IllegalStateException("boom"));
        when(research.refresh(eq(HOUSE), eq("nancy"))).thenReturn(PortraitResearchService.Outcome.READY);

        job().run();

        verify(research).refresh(eq(HOUSE), eq("nancy"));
    }

    @Test
    void refresh_killSwitchOff_doesNothing() {
        props.setEnabled(false);
        generated("metz", Duration.ofDays(150), Duration.ofDays(1), "ready");

        job().run();

        verifyNoInteractions(research);
    }
}
