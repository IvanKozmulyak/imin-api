package com.imin.iminapi.service.event;

import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.push.ExpoPushSender;
import com.imin.iminapi.push.PushProperties;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link NotifyReleaseSender} — the sweeper that turns "we'll email you if tickets
 * release" into an actual email, exactly once per subscription.
 *
 * <p>Real JPA (H2) + real template renderer, mocked {@link EmailService}. The sender is
 * constructed by hand rather than autowired so the call skips the {@code @SchedulerLock}
 * proxy — ShedLock's {@code lockAtLeastFor} would otherwise make the second sweep in a
 * test (and every sweep in a later test) a no-op. The scheduling annotations are
 * declarative config, not behaviour under test.
 *
 * <p>{@code @Transactional} rolls every fixture back so the shared H2 database isn't
 * polluted for sibling tests (there is no delete API for deliverability suppressions).
 */
@SpringBootTest
@Transactional
@Import(TestRateLimitConfig.class)
class NotifyReleaseSenderTest {

    /** Fixed "now": 2026-06-01T12:00:00Z. */
    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired SuppressionRepository suppressions;
    @Autowired EmailTemplateRenderer renderer;
    @Autowired EmailProperties emailProps;
    @Autowired BuyerPushDeviceRepository pushDevices;
    @Autowired BuyerAccountEmailRepository buyerEmails;
    @Autowired BuyerNotificationPreferenceRepository pushPrefs;

    EmailService emailService;
    ExpoPushSender push;
    PushProperties pushProps;
    NotifyReleaseSender sender;

    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        // Push is dark in every case in this file. These tests exist to protect
        // the EMAIL promise, and the whole point of the fan-out's placement is
        // that push can neither enable nor suppress a single one of them.
        push = mock(ExpoPushSender.class);
        pushProps = new PushProperties(); // enabled defaults to false
        sender = new NotifyReleaseSender(subscriptions, events, tiers, suppressions,
                emailService, renderer, emailProps, CLOCK,
                pushProps, push, pushDevices, buyerEmails, pushPrefs);

        org = new Organization();
        org.setName("Release Org");
        org.setSlug("release-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("release-org@example.com");
        org.setCountry("DE");
        org = organizations.save(org);

        owner = new User();
        owner.setEmail("release-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------
    private Event liveEvent() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Release Night");
        e.setSlug("release-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setStartsAt(NOW.plusSeconds(86400));
        e.setTimezone("Europe/Berlin");
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        e.setVenueName("Funkhaus");
        e.setVenueCity("Berlin");
        e.setVenueCountry("DE");
        return events.save(e);
    }

    private TicketTier tier(UUID eventId, int quantity, int sold) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(2500);
        t.setQuantity(quantity);
        t.setSold(sold);
        t.setEnabled(true);
        t.setSortOrder(0);
        return tiers.save(t);
    }

    private NotifySubscription subscribe(UUID eventId, String email) {
        return subscribe(eventId, email, null);
    }

    private NotifySubscription subscribe(UUID eventId, String email, String locale) {
        NotifySubscription s = new NotifySubscription();
        s.setEventId(eventId);
        s.setEmail(email);
        s.setLocale(locale);
        return subscriptions.save(s);
    }

    private Instant notifiedAtOf(NotifySubscription sub) {
        return subscriptions.findById(sub.getId()).orElseThrow().getNotifiedAt();
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------
    @Test
    void sendsOnce_andMarksNotified_whenATierIsPurchasable() {
        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        NotifySubscription sub = subscribe(e.getId(), "ada@example.com");

        sender.sweep();

        verify(emailService, times(1)).send(
                eq("ada@example.com"),
                contains("Release Night"),
                contains("one-time notification"),
                contains("one-time notification"));
        assertThat(notifiedAtOf(sub)).isEqualTo(NOW);

        // Push must be dark unless explicitly enabled, and must never be a
        // precondition for the email these tests exist to protect.
        verify(push, never()).send(anyList());
    }

    /**
     * W1.G: the release email follows the language each subscriber signed up in (V77).
     * Subscribers on the SAME event can disagree, so the sweep must render per locale
     * rather than once per event — this is the test that would catch a regression to a
     * single shared body.
     */
    @Test
    void mailsEachSubscriberInTheLocaleTheySignedUpWith() {
        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        subscribe(e.getId(), "es@example.com", "es");
        subscribe(e.getId(), "uk@example.com", "uk");
        subscribe(e.getId(), "none@example.com", null);

        sender.sweep();

        verify(emailService).send(
                eq("es@example.com"),
                eq("Ya hay entradas disponibles para Release Night"),
                contains("<html lang=\"es\">"),
                contains("ENTRADAS A LA VENTA"));
        verify(emailService).send(
                eq("uk@example.com"),
                eq("Квитки на Release Night уже доступні"),
                contains("<html lang=\"uk\">"),
                contains("КВИТКИ У ПРОДАЖУ"));
        verify(emailService).send(
                eq("none@example.com"),
                eq("Tickets are available for Release Night"),
                contains("<html lang=\"en\">"),
                contains("one-time notification"));
    }

    /** An unsupported tag stored on the row must degrade to English, not blow up the sweep. */
    @Test
    void unsupportedSubscriptionLocale_fallsBackToEnglish() {
        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        NotifySubscription sub = subscribe(e.getId(), "de@example.com", "de");

        sender.sweep();

        verify(emailService).send(
                eq("de@example.com"),
                eq("Tickets are available for Release Night"),
                contains("<html lang=\"en\">"),
                anyString());
        assertThat(notifiedAtOf(sub)).isEqualTo(NOW);
    }

    @Test
    void secondSweep_doesNotResend() {
        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        subscribe(e.getId(), "ada@example.com");

        sender.sweep();
        sender.sweep();

        verify(emailService, times(1)).send(eq("ada@example.com"), anyString(), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // Nothing to announce
    // -----------------------------------------------------------------------
    @Test
    void leavesRowPending_whenNoTierIsPurchasable() {
        Event e = liveEvent();
        tier(e.getId(), 50, 50); // sold out — the exact state that shows the notify form
        NotifySubscription sub = subscribe(e.getId(), "ada@example.com");

        sender.sweep();

        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyString());
        assertThat(notifiedAtOf(sub)).isNull();
    }

    @Test
    void leavesRowPending_whenEventIsCancelled() {
        Event e = liveEvent();
        e.setStatus(EventStatus.CANCELLED);
        events.save(e);
        tier(e.getId(), 100, 0); // stock exists, but the event is off

        NotifySubscription sub = subscribe(e.getId(), "ada@example.com");

        sender.sweep();

        verify(emailService, never()).send(anyString(), anyString(), anyString(), anyString());
        assertThat(notifiedAtOf(sub)).isNull();
    }

    // -----------------------------------------------------------------------
    // Deliverability suppression
    // -----------------------------------------------------------------------
    @Test
    void suppressedEmail_isMarkedWithoutSending() {
        SuppressionEntry entry = new SuppressionEntry();
        entry.setScope(SuppressionEntry.SCOPE_DELIVERABILITY);
        entry.setNormalizedEmail("bounced@example.com");
        entry.setReason(SuppressionEntry.REASON_HARD_BOUNCE);
        suppressions.save(entry);

        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        NotifySubscription suppressed = subscribe(e.getId(), "bounced@example.com");
        NotifySubscription fine = subscribe(e.getId(), "ada@example.com");

        sender.sweep();

        verify(emailService, never()).send(eq("bounced@example.com"), anyString(), anyString(), anyString());
        verify(emailService, times(1)).send(eq("ada@example.com"), anyString(), anyString(), anyString());
        // Marked so the pending scan stops returning it, but never mailed.
        assertThat(notifiedAtOf(suppressed)).isEqualTo(NOW);
        assertThat(notifiedAtOf(fine)).isEqualTo(NOW);
    }

    // -----------------------------------------------------------------------
    // Failure handling — at-least-once
    // -----------------------------------------------------------------------
    @Test
    void leavesRowPending_whenSendThrows() {
        doThrow(new IllegalStateException("resend down"))
                .when(emailService).send(anyString(), anyString(), anyString(), anyString());

        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        NotifySubscription sub = subscribe(e.getId(), "ada@example.com");

        sender.sweep();

        verify(emailService, times(1)).send(eq("ada@example.com"), anyString(), anyString(), anyString());
        assertThat(notifiedAtOf(sub)).isNull();
    }
}
