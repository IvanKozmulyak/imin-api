package com.imin.iminapi.service.event;

import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.dto.publicapi.NotifySubscriptionRequest;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.push.ExpoPushSender;
import com.imin.iminapi.push.PushProperties;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.NotifyReleaseRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The two writers of one {@code notify_subscriptions} row, a re-subscribe and the release sweep, in both orders.
 * Each test runs the other writer, committing on its own thread, between one writer's read of the row and its write.
 *
 * <p>Both writers are built by hand around the real repository, which is the only seam between a read and its write;
 * the re-subscribe runs in a {@link TransactionTemplate}, the boundary its {@code @Transactional} draws in production.
 */
@IminIntegrationTest
class NotifyResubscribeRaceTest {

    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired NotifyReleaseRepository releases;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired ModerationHoldRepository moderationHolds;
    @Autowired SuppressionRepository suppressions;
    @Autowired EmailTemplateRenderer renderer;
    @Autowired EmailProperties emailProps;
    @Autowired BuyerPushDeviceRepository pushDevices;
    @Autowired BuyerAccountEmailRepository buyerEmails;
    @Autowired BuyerNotificationPreferenceRepository pushPrefs;
    @Autowired UnsubscribeTokenService unsubscribeTokens;
    @Autowired PlatformTransactionManager txManager;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    final MutableClock clock = new MutableClock();
    final List<UUID> orgIds = new ArrayList<>();
    final ExecutorService otherWriter = Executors.newSingleThreadExecutor();
    EmailService email;
    Event event;
    TicketTier tier;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);
        email = mock(EmailService.class);
        Organization org = fx.org();
        orgIds.add(org.getId());
        Event e = fx.event(org, fx.owner(org), EventStatus.LIVE, NOW.plus(Duration.ofDays(10)));
        e.setPublishedAt(NOW.minus(Duration.ofDays(5)));
        event = events.save(e);
        tier = fx.tier(event, 2500, 10);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        otherWriter.shutdownNow();
        otherWriter.awaitTermination(30, TimeUnit.SECONDS);
        OrgRows.delete(jdbc, orgIds);
    }

    enum AtRead { PENDING, EMAILED_SEVEN_HOURS_AGO }

    /**
     * The sweep mails and marks the row after the re-subscribe read it. The re-subscribe's commit keeps that mark,
     * so the next tick sends nothing inside the 6 h cap, and its own provenance still lands.
     */
    @ParameterizedTest(name = "row {0} when the re-subscribe reads it")
    @EnumSource(AtRead.class)
    void theSweepMarksBeforeTheResubscribeCommits_theRowStaysMarked(AtRead atRead) throws Exception {
        setSold(10);
        NotifySubscription row = row(atRead == AtRead.PENDING ? null : NOW.minus(Duration.ofHours(7)));
        NotifyReleaseSender sweep = sender(subscriptions);
        // Sold out, the sweep re-arms a row emailed 7 h ago; one seat back, it mails and marks the row.
        NotifySubscriptionService resubscribe = service(hookAfterFirst("findByEventIdAndEmail", () -> {
            sweep.sweep();
            setSold(9);
            clock.advance(Duration.ofMinutes(1));
            sweep.sweep();
        }));

        inTransaction(() -> resubscribe.subscribe(event.getId(), request(row), "203.0.113.9", "Again/2.0"));
        clock.advance(Duration.ofMinutes(1));
        sweep.sweep();

        verify(email, times(1)).send(eq(row.getEmail()), anyString(), anyString(), anyString());
        NotifySubscription after = reload(row);
        assertThat(after.getNotifiedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(after.getUserAgent()).isEqualTo("Again/2.0");
    }

    /**
     * The re-subscribe commits after the sweep read the pending row: the sweep's mark keeps that signup's provenance
     * and one email goes out. A second signup a minute later, inside the 6 h gap, re-arms nothing.
     */
    @Test
    void theResubscribeCommitsBeforeTheSweepMarks_oneEmail_andTheSignupKept() throws Exception {
        setSold(9);
        NotifySubscription row = row(null);
        NotifySubscriptionService resubscribe = service(subscriptions);
        NotifyReleaseSender sweep = sender(hookAfterFirst("findPendingByEventId", () ->
                inTransaction(() -> resubscribe.subscribe(event.getId(), request(row), "203.0.113.9", "Again/2.0"))));

        sweep.sweep();

        NotifySubscription marked = reload(row);
        assertThat(marked.getNotifiedAt()).isEqualTo(NOW);
        assertThat(marked.getUserAgent()).isEqualTo("Again/2.0");
        assertThat(marked.getSourceIp()).isEqualTo("203.0.113.9");

        clock.advance(Duration.ofMinutes(1));
        inTransaction(() -> resubscribe.subscribe(event.getId(), request(row), "203.0.113.10", "Third/3.0"));
        sweep.sweep();

        verify(email, times(1)).send(eq(row.getEmail()), anyString(), anyString(), anyString());
        NotifySubscription after = reload(row);
        assertThat(after.getNotifiedAt()).isEqualTo(NOW);
        assertThat(after.getUserAgent()).isEqualTo("Third/3.0");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private NotifySubscriptionService service(NotifySubscriptionRepository repo) {
        return new NotifySubscriptionService(events, repo, new NotifyProperties(), clock);
    }

    private NotifyReleaseSender sender(NotifySubscriptionRepository repo) {
        return new NotifyReleaseSender(repo, events, tiers, suppressions, email, renderer, emailProps, clock,
                new PushProperties(), mock(ExpoPushSender.class), pushDevices, buyerEmails, pushPrefs,
                unsubscribeTokens, moderationHolds, releases, new NotifyProperties());
    }

    /**
     * The real repository; after its first {@code method} call for this test's event, {@code writer} runs to completion
     * on another thread, so it commits on its own before the caller writes.
     */
    private NotifySubscriptionRepository hookAfterFirst(String method, Runnable writer) {
        AtomicBoolean fired = new AtomicBoolean();
        return (NotifySubscriptionRepository) Proxy.newProxyInstance(NotifySubscriptionRepository.class.getClassLoader(),
                new Class<?>[] {NotifySubscriptionRepository.class}, (proxy, m, args) -> {
                    Object result;
                    try {
                        result = m.invoke(subscriptions, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (m.getName().equals(method) && event.getId().equals(args[0]) && fired.compareAndSet(false, true)) {
                        try {
                            otherWriter.submit(writer).get(30, TimeUnit.SECONDS);
                        } catch (ExecutionException e) {
                            throw e.getCause();
                        }
                    }
                    return result;
                });
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }

    private NotifySubscription row(Instant notifiedAt) {
        NotifySubscription s = new NotifySubscription();
        s.setEventId(event.getId());
        s.setEmail(fx.email("fan"));
        s.setEveryRelease(true);
        s.setConsentText(NotifySubscriptionService.EVERY_RELEASE_CONSENT_TEXT);
        s.setNotifiedAt(notifiedAt);
        return subscriptions.saveAndFlush(s);
    }

    private static NotifySubscriptionRequest request(NotifySubscription row) {
        return new NotifySubscriptionRequest(row.getEmail(), null, true);
    }

    private NotifySubscription reload(NotifySubscription row) {
        return subscriptions.findById(row.getId()).orElseThrow();
    }

    private void setSold(int sold) {
        jdbc.update("UPDATE ticket_tiers SET sold = ? WHERE id = ?", sold, tier.getId());
    }
}
