package com.imin.iminapi.payout;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The organizer-facing half of a payout — the two moments the money stops being imin's
 * internal business: it is parked, or it is on its way.
 *
 * <p>The locale cases live here rather than only in the shared template test because
 * {@link EmailTemplateRenderer} falls back to English SILENTLY on a missing variant, so
 * "it rendered" proves nothing; each variant is asserted to differ from the English one.
 */
class OrganizerPayoutNotifierTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private PayoutRunRepository payoutRuns;
    private EventRepository events;
    private OrganizationRepository orgs;
    private NotificationRepository notifications;
    private NotificationPreferencesRepository prefs;
    private UserRepository users;
    private EmailService email;
    private OrganizerPayoutNotifier notifier;

    @BeforeEach
    void setUp() {
        payoutRuns = mock(PayoutRunRepository.class);
        events = mock(EventRepository.class);
        orgs = mock(OrganizationRepository.class);
        notifications = mock(NotificationRepository.class);
        prefs = mock(NotificationPreferencesRepository.class);
        users = mock(UserRepository.class);
        email = mock(EmailService.class);

        EmailProperties props = new EmailProperties();
        props.setAppBaseUrl("https://dashboard.imin.wtf");

        notifier = new OrganizerPayoutNotifier(payoutRuns, events, orgs, notifications, prefs,
                users, email, new EmailTemplateRenderer(), props);

        when(payoutRuns.findById(RUN_ID)).thenReturn(Optional.of(run(PayoutBlockReason.NO_BANK_ACCOUNT, 0)));
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event(USER_ID)));
        when(orgs.findById(ORG_ID)).thenReturn(Optional.of(org()));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(null)));
        when(prefs.findById(USER_ID)).thenReturn(Optional.empty());   // default-on
    }

    // ── blocked ──────────────────────────────────────────────────────────────────

    @Test
    void noBankAccountBlockWritesInAppAndEmail() {
        notifier.notifyBlocked(RUN_ID);

        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(n.capture());
        assertThat(n.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(n.getValue().getKind()).isEqualTo("payout.blocked");
        assertThat(n.getValue().getTitle()).contains("Warehouse 7").contains("bank account");
        assertThat(n.getValue().getBody()).contains("90.00 EUR");
        assertThat(n.getValue().getLink()).isEqualTo("/events/" + EVENT_ID);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("contact@org.example"), subject.capture(), html.capture(), text.capture());
        assertThat(subject.getValue())
                .isEqualTo("Add a bank account to receive your Warehouse 7 payout");
        assertThat(html.getValue())
                .contains("90.00 EUR")
                .contains("no bank account attached")
                .contains("https://dashboard.imin.wtf/events/" + EVENT_ID)
                .doesNotContain("{{");
        assertThat(text.getValue()).contains("90.00 EUR").doesNotContain("{{");
    }

    @Test
    void attemptCapBlockNamesTheAttemptsAndTheLastStripeFailure() {
        when(payoutRuns.findById(RUN_ID)).thenReturn(Optional.of(run("balance_insufficient", 3)));

        notifier.notifyBlocked(RUN_ID);

        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(n.capture());
        assertThat(n.getValue().getBody())
                .as("the Stripe code is the only clue the organizer can act on")
                .contains("3 attempts")
                .contains("balance_insufficient");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).send(anyString(), subject.capture(), html.capture(), anyString());
        assertThat(subject.getValue())
                .isEqualTo("Action needed: we could not send your Warehouse 7 payout");
        assertThat(html.getValue()).contains("3 times").doesNotContain("{{");
    }

    @Test
    void blockedIgnoresThePayoutArrivedPreference() {
        when(prefs.findById(USER_ID)).thenReturn(Optional.of(preferences(false)));

        notifier.notifyBlocked(RUN_ID);

        verify(notifications).save(any(Notification.class));
        verify(email).send(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(prefs);   // a stuck payout is an alert, not a marketing notice
    }

    @Test
    void blockedWithNoOrganizerUserEmailsWithoutAnInAppRow() {
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event(null)));

        notifier.notifyBlocked(RUN_ID);

        verify(notifications, never()).save(any(Notification.class));
        verify(email).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void blockedWithNoContactEmailStaysInAppOnly() {
        Organization noInbox = org();
        noInbox.setContactEmail("  ");
        when(orgs.findById(ORG_ID)).thenReturn(Optional.of(noInbox));

        notifier.notifyBlocked(RUN_ID);

        verify(notifications).save(any(Notification.class));
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aRunThatNoLongerExistsNotifiesNobody() {
        when(payoutRuns.findById(RUN_ID)).thenReturn(Optional.empty());

        notifier.notifyBlocked(RUN_ID);

        verify(notifications, never()).save(any(Notification.class));
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emailFailureDoesNotThrow() {
        doThrow(new IllegalStateException("resend down"))
                .when(email).send(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> notifier.onPayoutBlocked(new PayoutBlockedEvent(RUN_ID)))
                .as("the payout transaction has already committed — an email failure must not escape")
                .doesNotThrowAnyException();
    }

    // ── arrived ──────────────────────────────────────────────────────────────────

    @Test
    void payoutArrivedWritesInAppAndEmail() {
        notifier.notifyArrived(RUN_ID);

        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(n.capture());
        assertThat(n.getValue().getKind()).isEqualTo("payout.arrived");
        assertThat(n.getValue().getTitle()).contains("Warehouse 7");
        assertThat(n.getValue().getBody()).contains("90.00 EUR");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("contact@org.example"), subject.capture(), html.capture(), anyString());
        assertThat(subject.getValue()).isEqualTo("Your payout for Warehouse 7 is on its way");
        assertThat(html.getValue()).contains("90.00 EUR").doesNotContain("{{");
    }

    @Test
    void payoutArrivedRespectsThePreference() {
        when(prefs.findById(USER_ID)).thenReturn(Optional.of(preferences(false)));

        notifier.notifyArrived(RUN_ID);

        verify(notifications, never()).save(any(Notification.class));
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void arrivalEmailFailureDoesNotThrow() {
        doThrow(new IllegalStateException("resend down"))
                .when(email).send(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> notifier.onPayoutArrived(new PayoutArrivedEvent(RUN_ID)))
                .doesNotThrowAnyException();
    }

    // ── locales ──────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "payout-blocked.{0}")
    @ValueSource(strings = {"es", "fr", "uk"})
    void eachLocaleRendersItsOwnBlockedEmail(String locale) {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(locale)));

        notifier.notifyBlocked(RUN_ID);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(anyString(), subject.capture(), html.capture(), text.capture());

        assertThat(html.getValue()).doesNotContain("{{");
        assertThat(text.getValue()).doesNotContain("{{");
        assertThat(html.getValue())
                .as("a missing %s variant falls back to English silently", locale)
                .contains("<html lang=\"" + locale + "\">");
        assertThat(subject.getValue())
                .isNotEqualTo("Add a bank account to receive your Warehouse 7 payout");
        assertThat(text.getValue()).isNotEqualTo(english("payout-blocked", blockedValues()).text());
    }

    @ParameterizedTest(name = "payout-arrived.{0}")
    @ValueSource(strings = {"es", "fr", "uk"})
    void eachLocaleRendersItsOwnArrivedEmail(String locale) {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(locale)));

        notifier.notifyArrived(RUN_ID);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(anyString(), subject.capture(), html.capture(), text.capture());

        assertThat(html.getValue()).doesNotContain("{{").contains("<html lang=\"" + locale + "\">");
        assertThat(text.getValue()).doesNotContain("{{");
        assertThat(subject.getValue()).isNotEqualTo("Your payout for Warehouse 7 is on its way");
        assertThat(text.getValue()).isNotEqualTo(english("payout-arrived", arrivedValues()).text());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private static EmailTemplateRenderer.Rendered english(String template, Map<String, String> values) {
        return new EmailTemplateRenderer().render(template, null, values);
    }

    private static Map<String, String> arrivedValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", "Warehouse 7");
        values.put("amountFormatted", "90.00 EUR");
        values.put("dashboardUrl", "https://dashboard.imin.wtf/events/" + EVENT_ID);
        return values;
    }

    private static Map<String, String> blockedValues() {
        Map<String, String> values = arrivedValues();
        values.put("blockedReason", "x");
        values.put("nextStep", "y");
        return values;
    }

    private static PayoutRun run(String failureReason, int attempt) {
        PayoutRun r = new PayoutRun();
        r.setId(RUN_ID);
        r.setOrgId(ORG_ID);
        r.setEventId(EVENT_ID);
        r.setStripeAccountId("acct_1");
        r.setAmountMinor(9_000);
        r.setCurrency("eur");
        r.setStatus(PayoutRunStatus.BLOCKED);
        r.setFailureReason(failureReason);
        r.setAttempt(attempt);
        r.setIdempotencyKey("evt:" + EVENT_ID + ":attempt:" + attempt);
        return r;
    }

    private static Event event(UUID createdBy) {
        Event e = new Event();
        e.setId(EVENT_ID);
        e.setOrgId(ORG_ID);
        e.setName("Warehouse 7");
        e.setCreatedBy(createdBy);
        return e;
    }

    private static Organization org() {
        Organization o = new Organization();
        o.setId(ORG_ID);
        o.setName("Sala");
        o.setContactEmail("contact@org.example");
        return o;
    }

    private static User user(String locale) {
        User u = new User();
        u.setId(USER_ID);
        u.setOrgId(ORG_ID);
        u.setEmail("organizer@org.example");
        u.setLocale(locale);
        return u;
    }

    private static NotificationPreferences preferences(boolean payoutArrived) {
        NotificationPreferences p = new NotificationPreferences();
        p.setUserId(USER_ID);
        p.setPayoutArrived(payoutArrived);
        return p;
    }
}
