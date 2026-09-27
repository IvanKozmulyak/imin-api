package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionJobUnitTest {

    private static final Instant NOW = Instant.parse("2026-09-27T02:00:00Z");
    private static final Instant FRESH_SINCE = NOW.minus(RetentionJob.FRESHNESS);
    private static final UUID ORG = UUID.randomUUID();

    private FanFeatureRepository features;
    private ConsentGate gate;
    private ConsentService consentService;
    private MembershipRepository memberships;
    private AudiencePlanProperties props;
    private RetentionJob proxied;
    private RetentionJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        features = mock(FanFeatureRepository.class);
        gate = mock(ConsentGate.class);
        consentService = mock(ConsentService.class);
        memberships = mock(MembershipRepository.class);
        props = new AudiencePlanProperties();
        props.setRetentionJobEnabled(true);
        proxied = mock(RetentionJob.class);
        ObjectProvider<RetentionJob> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(proxied);
        job = new RetentionJob(features, gate, consentService, memberships, new AudiencePlanAccess(props), props,
                mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC), self);
        when(features.findOrgIdsWithSubscribedMembers()).thenReturn(List.of(ORG));
    }

    @Test
    void schedule_isFourParisWithTheRetentionLock_andFreshnessIs48h() throws Exception {
        Scheduled scheduled = RetentionJob.class.getMethod("scheduled").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 0 4 * * *");
        assertThat(scheduled.zone()).isEqualTo("Europe/Paris");
        SchedulerLock lock = RetentionJob.class.getMethod("run").getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo("audience_retention");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT2H");
        assertThat(RetentionJob.FRESHNESS).hasHours(48);
        assertThat(RetentionJob.SOURCE).isEqualTo("retention_3y");
    }

    @Test
    void scheduled_runsThroughTheLockedProxy() {
        job.scheduled();
        verify(proxied).run();
    }

    @Test
    void scheduled_runFailure_isSwallowed() {
        doThrow(new IllegalStateException("boom")).when(proxied).run();
        assertThatCode(job::scheduled).doesNotThrowAnyException();
    }

    @Test
    void killSwitchOff_orgNeverQueried() {
        props.setEnabled(false);
        RetentionJob.Result r = job.run();
        verify(gate, never()).retentionScan(any(), any());
        assertThat(r).isEqualTo(new RetentionJob.Result(true, 0, 0, 0, 0, 0));
    }

    @Test
    void orgWithNoExpiredMember_notCounted() {
        when(gate.retentionScan(ORG, FRESH_SINCE)).thenReturn(new ConsentGate.RetentionScan(List.of(), 0));
        assertThat(job.run()).isEqualTo(new RetentionJob.Result(true, 0, 0, 0, 0, 0));
    }

    @Test
    void dryRun_countsOnly_neverLocksOrUnsubscribes() {
        props.setRetentionJobEnabled(false);
        UUID a = UUID.randomUUID();
        when(gate.retentionScan(ORG, FRESH_SINCE)).thenReturn(new ConsentGate.RetentionScan(List.of(a), 0));

        assertThat(job.run()).isEqualTo(new RetentionJob.Result(false, 1, 1, 0, 0, 0));
        verify(memberships, never()).lockByIdAndOrgId(any(), any());
        verify(consentService, never()).unsubscribe(any(), any(), anyString(), any(ConsentOrigin.class), any());
        verify(features, never()).clearProfiling(any());
    }

    @Test
    void enabled_unsubscribesAsOperatorAndClearsProfiling() {
        UUID a = UUID.randomUUID();
        when(gate.retentionScan(ORG, FRESH_SINCE)).thenReturn(new ConsentGate.RetentionScan(List.of(a), 0));
        when(memberships.lockByIdAndOrgId(a, ORG)).thenReturn(Optional.of(subscribed()));
        when(gate.isRetentionExpired(ORG, a, FRESH_SINCE)).thenReturn(true);

        assertThat(job.run()).isEqualTo(new RetentionJob.Result(true, 1, 1, 0, 1, 0));
        verify(gate).hasPaidOrderWithinRetention(ORG, a);
        verify(consentService).unsubscribe(ORG, a, "retention_3y", ConsentOrigin.OPERATOR, null);
        verify(features).clearProfiling(a);
    }

    @Test
    void membershipGoneAtWriteTime_skipped() {
        UUID a = UUID.randomUUID();
        when(gate.retentionScan(ORG, FRESH_SINCE)).thenReturn(new ConsentGate.RetentionScan(List.of(a), 0));
        when(memberships.lockByIdAndOrgId(a, ORG)).thenReturn(Optional.empty());

        assertThat(job.run().cleared()).isZero();
        verify(consentService, never()).unsubscribe(any(), any(), anyString(), any(ConsentOrigin.class), any());
    }

    @Test
    void alreadyUnsubscribedAtWriteTime_skipped() {
        UUID a = UUID.randomUUID();
        when(gate.retentionScan(ORG, FRESH_SINCE)).thenReturn(new ConsentGate.RetentionScan(List.of(a), 0));
        Membership m = subscribed();
        m.setConsentStatus("unsubscribed");
        when(memberships.lockByIdAndOrgId(a, ORG)).thenReturn(Optional.of(m));

        assertThat(job.run().cleared()).isZero();
        verify(gate, never()).isRetentionExpired(any(), any(), any());
        verify(consentService, never()).unsubscribe(any(), any(), anyString(), any(ConsentOrigin.class), any());
    }

    @Test
    void contactLandedAfterTheScan_recheckWins() {
        UUID a = UUID.randomUUID();
        when(gate.retentionScan(ORG, FRESH_SINCE)).thenReturn(new ConsentGate.RetentionScan(List.of(a), 0));
        when(memberships.lockByIdAndOrgId(a, ORG)).thenReturn(Optional.of(subscribed()));
        when(gate.isRetentionExpired(ORG, a, FRESH_SINCE)).thenReturn(false);

        assertThat(job.run()).isEqualTo(new RetentionJob.Result(true, 1, 1, 0, 0, 0));
        verify(consentService, never()).unsubscribe(any(), any(), anyString(), any(ConsentOrigin.class), any());
        verify(features, never()).clearProfiling(any());
    }

    @Test
    void paidOrderWithinRetentionAtWriteTime_skipped() {
        UUID a = UUID.randomUUID();
        when(gate.retentionScan(ORG, FRESH_SINCE)).thenReturn(new ConsentGate.RetentionScan(List.of(a), 0));
        when(memberships.lockByIdAndOrgId(a, ORG)).thenReturn(Optional.of(subscribed()));
        when(gate.isRetentionExpired(ORG, a, FRESH_SINCE)).thenReturn(true);
        when(gate.hasPaidOrderWithinRetention(ORG, a)).thenReturn(true);

        assertThat(job.run()).isEqualTo(new RetentionJob.Result(true, 1, 1, 0, 0, 0));
        verify(consentService, never()).unsubscribe(any(), any(), anyString(), any(ConsentOrigin.class), any());
        verify(features, never()).clearProfiling(any());
    }

    @Test
    void orgWithOnlyLegacyImports_countedSeparately_neverLocked() {
        when(gate.retentionScan(ORG, FRESH_SINCE)).thenReturn(new ConsentGate.RetentionScan(List.of(), 2));

        assertThat(job.run()).isEqualTo(new RetentionJob.Result(true, 1, 0, 2, 0, 0));
        verify(memberships, never()).lockByIdAndOrgId(any(), any());
        verify(consentService, never()).unsubscribe(any(), any(), anyString(), any(ConsentOrigin.class), any());
    }

    @Test
    void oneMemberFailing_othersStillCleared_failureCounted() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(gate.retentionScan(ORG, FRESH_SINCE)).thenReturn(new ConsentGate.RetentionScan(List.of(bad, good), 0));
        when(memberships.lockByIdAndOrgId(any(), eq(ORG))).thenReturn(Optional.of(subscribed()));
        when(gate.isRetentionExpired(eq(ORG), any(), eq(FRESH_SINCE))).thenReturn(true);
        doThrow(new IllegalStateException("db")).when(consentService)
                .unsubscribe(ORG, bad, "retention_3y", ConsentOrigin.OPERATOR, null);

        assertThat(job.run()).isEqualTo(new RetentionJob.Result(true, 1, 2, 0, 1, 1));
        verify(features).clearProfiling(good);
        verify(features, never()).clearProfiling(bad);
    }

    private static Membership subscribed() {
        Membership m = new Membership();
        m.setConsentStatus("subscribed");
        return m;
    }
}
