package com.imin.iminapi.dispute;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The organizer-facing half of a chargeback: an in-app row plus a localized email, neither of
 * which may ever throw back into the webhook thread.
 *
 * <p>The locale cases are here rather than in a shared organizer-email test because
 * {@link EmailTemplateRenderer} falls back to English SILENTLY when a variant file is missing
 * — "it rendered" proves nothing, so each variant is asserted to differ from the English one.
 */
class DisputeNotifierTest {

    private static final UUID DISPUTE_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private DisputeRepository disputes;
    private EventRepository events;
    private OrganizationRepository orgs;
    private NotificationRepository notifications;
    private UserRepository users;
    private EmailService email;
    private DisputeNotifier notifier;

    @BeforeEach
    void setUp() {
        disputes = mock(DisputeRepository.class);
        events = mock(EventRepository.class);
        orgs = mock(OrganizationRepository.class);
        notifications = mock(NotificationRepository.class);
        users = mock(UserRepository.class);
        email = mock(EmailService.class);

        EmailProperties props = new EmailProperties();
        props.setAppBaseUrl("https://dashboard.imin.wtf");

        notifier = new DisputeNotifier(disputes, events, orgs, notifications, users,
                email, new EmailTemplateRenderer(), props);

        when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute(EVENT_ID)));
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(orgs.findById(ORG_ID)).thenReturn(Optional.of(org()));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(null)));
    }

    @Test
    void disputeOpenedWritesInAppAndEmail() {
        notifier.notify(DISPUTE_ID);

        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(n.capture());
        assertThat(n.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(n.getValue().getKind()).isEqualTo("dispute.opened");
        assertThat(n.getValue().getTitle()).contains("Warehouse 7");
        assertThat(n.getValue().getBody()).contains("42.00 EUR");
        assertThat(n.getValue().getLink()).isEqualTo("/events/" + EVENT_ID);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(org.mockito.ArgumentMatchers.eq("contact@org.example"),
                subject.capture(), html.capture(), text.capture());
        assertThat(subject.getValue()).isEqualTo(
                "Action needed: a chargeback was opened on Warehouse 7");
        assertThat(html.getValue()).contains("42.00 EUR").doesNotContain("{{");
        assertThat(html.getValue()).contains("https://dashboard.imin.wtf/events/" + EVENT_ID);
        assertThat(text.getValue()).contains("42.00 EUR").doesNotContain("{{");
    }

    @Test
    void unattributedDisputeEmailsWithoutAnInAppRow() {
        // No event means no organizer user, and a Notification row needs one.
        when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute(null)));

        notifier.notify(DISPUTE_ID);

        verify(notifications, never()).save(any(Notification.class));
        verify(email).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emailFailureDoesNotThrow() {
        doThrow(new IllegalStateException("resend down"))
                .when(email).send(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> notifier.onDisputeOpened(new DisputeOpenedEvent(DISPUTE_ID)))
                .as("the webhook already answered Stripe 200 — an email failure must not escape")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "dispute-opened.{0}")
    @ValueSource(strings = {"es", "fr", "uk"})
    void eachLocaleRendersItsOwnDisputeEmail(String locale) {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(locale)));

        notifier.notify(DISPUTE_ID);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(anyString(), subject.capture(), html.capture(), text.capture());

        assertThat(html.getValue()).doesNotContain("{{");
        assertThat(text.getValue()).doesNotContain("{{");
        assertThat(html.getValue())
                .as("a missing %s variant falls back to English silently", locale)
                .contains("<html lang=\"" + locale + "\">");

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", "Warehouse 7");
        values.put("amountFormatted", "42.00 EUR");
        values.put("dashboardUrl", "https://dashboard.imin.wtf/events/" + EVENT_ID);
        EmailTemplateRenderer.Rendered english =
                new EmailTemplateRenderer().render("dispute-opened", null, values);
        assertThat(text.getValue()).isNotEqualTo(english.text());
        assertThat(subject.getValue())
                .isNotEqualTo("Action needed: a chargeback was opened on Warehouse 7");
    }

    private static Dispute dispute(UUID eventId) {
        Dispute d = new Dispute();
        d.setId(DISPUTE_ID);
        d.setStripeDisputeId("du_1");
        d.setOrgId(ORG_ID);
        d.setEventId(eventId);
        d.setAmountMinor(4200);
        d.setCurrency("eur");
        d.setStatus(DisputeStatus.OPEN);
        return d;
    }

    private static Event event() {
        Event e = new Event();
        e.setId(EVENT_ID);
        e.setOrgId(ORG_ID);
        e.setName("Warehouse 7");
        e.setCreatedBy(USER_ID);
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
}
