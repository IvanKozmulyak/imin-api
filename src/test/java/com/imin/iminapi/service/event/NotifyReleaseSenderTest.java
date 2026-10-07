package com.imin.iminapi.service.event;

import org.mockito.ArgumentCaptor;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
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
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>Real JPA + real template renderer, mocked {@link EmailService}. The sender is
 * constructed by hand rather than autowired so the call skips the {@code @SchedulerLock}
 * proxy — ShedLock's {@code lockAtLeastFor} would otherwise make the second sweep in a
 * test (and every sweep in a later test) a no-op. The scheduling annotations are
 * declarative config, not behaviour under test.
 *
 * <p>{@code @Transactional} rolls every fixture back so the shared database isn't
 * polluted for sibling tests (there is no delete API for deliverability suppressions).
 */
@IminIntegrationTest
@Transactional
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
    /** The sweep reads every pending subscription, so each test mails only addresses it owns. */
    String uid;

    private String addr(String local) {
        return local + "-" + uid + "@example.com";
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService unsubscribeTokens;

    @BeforeEach
    void setUp() {
        uid = UUID.randomUUID().toString().substring(0, 8);
        emailService = mock(EmailService.class);
        // Push is dark in every case in this file. These tests exist to protect
        // the EMAIL promise, and the whole point of the fan-out's placement is
        // that push can neither enable nor suppress a single one of them.
        push = mock(ExpoPushSender.class);
        pushProps = new PushProperties(); // enabled defaults to false
        sender = new NotifyReleaseSender(subscriptions, events, tiers, suppressions,
                emailService, renderer, emailProps, CLOCK,
                pushProps, push, pushDevices, buyerEmails, pushPrefs, unsubscribeTokens);

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

    /**
     * A guest subscriber has no account, so the link in this email is their only
     * way to stop the mail (CPCE L34-5). It points at the buyer site, carries a
     * signed token scoped to THIS subscription, and differs per recipient even
     * though the body is rendered once per locale.
     */
    @Test
    void the_release_email_carries_a_per_subscriber_optout_link() {
        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        NotifySubscription a = subscribe(e.getId(), addr("ada"));
        NotifySubscription b = subscribe(e.getId(), addr("grace"));

        sender.sweep();

        String expectedA = "/notify/unsubscribe/" + unsubscribeTokens.signNotify(a.getId());
        String expectedB = "/notify/unsubscribe/" + unsubscribeTokens.signNotify(b.getId());
        for (var sub : java.util.List.of(java.util.Map.entry(a, expectedA), java.util.Map.entry(b, expectedB))) {
            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(emailService, times(1))
                    .send(eq(sub.getKey().getEmail()), anyString(), html.capture(), text.capture());
            assertThat(html.getValue()).contains(sub.getValue());
            assertThat(text.getValue()).contains("/notify/unsubscribe/");
            // The sentinel is an implementation detail and must never reach an inbox.
            assertThat(html.getValue()).doesNotContain("PLACEHOLDER");
            assertThat(text.getValue()).doesNotContain("PLACEHOLDER");
        }
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
        NotifySubscription sub = subscribe(e.getId(), addr("ada"));

        sender.sweep();

        verify(emailService, times(1)).send(
                eq(addr("ada")),
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
        subscribe(e.getId(), addr("es"), "es");
        subscribe(e.getId(), addr("uk"), "uk");
        subscribe(e.getId(), addr("none"), null);

        sender.sweep();

        verify(emailService).send(
                eq(addr("es")),
                eq("Ya hay entradas disponibles para Release Night"),
                contains("<html lang=\"es\">"),
                contains("ENTRADAS A LA VENTA"));
        verify(emailService).send(
                eq(addr("uk")),
                eq("Квитки на Release Night уже доступні"),
                contains("<html lang=\"uk\">"),
                contains("КВИТКИ У ПРОДАЖУ"));
        verify(emailService).send(
                eq(addr("none")),
                eq("Tickets are available for Release Night"),
                contains("<html lang=\"en\">"),
                contains("one-time notification"));
    }

    /** An unsupported tag stored on the row must degrade to English, not blow up the sweep. */
    @Test
    void unsupportedSubscriptionLocale_fallsBackToEnglish() {
        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        NotifySubscription sub = subscribe(e.getId(), addr("de"), "de");

        sender.sweep();

        verify(emailService).send(
                eq(addr("de")),
                eq("Tickets are available for Release Night"),
                contains("<html lang=\"en\">"),
                anyString());
        assertThat(notifiedAtOf(sub)).isEqualTo(NOW);
    }

    @Test
    void secondSweep_doesNotResend() {
        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        subscribe(e.getId(), addr("ada"));

        sender.sweep();
        sender.sweep();

        verify(emailService, times(1)).send(eq(addr("ada")), anyString(), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // Nothing to announce
    // -----------------------------------------------------------------------
    @Test
    void leavesRowPending_whenNoTierIsPurchasable() {
        Event e = liveEvent();
        tier(e.getId(), 50, 50); // sold out — the exact state that shows the notify form
        NotifySubscription sub = subscribe(e.getId(), addr("ada"));

        sender.sweep();

        verify(emailService, never()).send(eq(sub.getEmail()), anyString(), anyString(), anyString());
        assertThat(notifiedAtOf(sub)).isNull();
    }

    @Test
    void leavesRowPending_whenEventIsCancelled() {
        Event e = liveEvent();
        e.setStatus(EventStatus.CANCELLED);
        events.save(e);
        tier(e.getId(), 100, 0); // stock exists, but the event is off

        NotifySubscription sub = subscribe(e.getId(), addr("ada"));

        sender.sweep();

        verify(emailService, never()).send(eq(sub.getEmail()), anyString(), anyString(), anyString());
        assertThat(notifiedAtOf(sub)).isNull();
    }

    // -----------------------------------------------------------------------
    // Deliverability suppression
    // -----------------------------------------------------------------------
    @Test
    void suppressedEmail_isMarkedWithoutSending() {
        SuppressionEntry entry = new SuppressionEntry();
        entry.setScope(SuppressionEntry.SCOPE_DELIVERABILITY);
        entry.setNormalizedEmail(addr("bounced"));
        entry.setReason(SuppressionEntry.REASON_HARD_BOUNCE);
        suppressions.save(entry);

        Event e = liveEvent();
        tier(e.getId(), 100, 0);
        NotifySubscription suppressed = subscribe(e.getId(), addr("bounced"));
        NotifySubscription fine = subscribe(e.getId(), addr("ada"));

        sender.sweep();

        verify(emailService, never()).send(eq(addr("bounced")), anyString(), anyString(), anyString());
        verify(emailService, times(1)).send(eq(addr("ada")), anyString(), anyString(), anyString());
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
        NotifySubscription sub = subscribe(e.getId(), addr("ada"));

        sender.sweep();

        verify(emailService, times(1)).send(eq(addr("ada")), anyString(), anyString(), anyString());
        assertThat(notifiedAtOf(sub)).isNull();
    }
}
