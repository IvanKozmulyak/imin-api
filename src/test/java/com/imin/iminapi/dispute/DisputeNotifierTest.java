package com.imin.iminapi.dispute;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private DisputeRepository disputes;
    private EventRepository events;
    private OrganizationRepository orgs;
    private NotificationRepository notifications;
    private UserRepository users;
    private TicketRepository tickets;
    private EmailService email;
    private DisputeNotifier notifier;

    @BeforeEach
    void setUp() {
        disputes = mock(DisputeRepository.class);
        events = mock(EventRepository.class);
        orgs = mock(OrganizationRepository.class);
        notifications = mock(NotificationRepository.class);
        users = mock(UserRepository.class);
        tickets = mock(TicketRepository.class);
        email = mock(EmailService.class);

        EmailProperties props = new EmailProperties();
        props.setAppBaseUrl("https://dashboard.imin.wtf");

        notifier = new DisputeNotifier(disputes, events, orgs, notifications, users, tickets,
                email, new EmailTemplateRenderer(), props);

        when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute(EVENT_ID, ORDER_ID)));
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(
                List.of(ticket(Ticket.STATE_REVOKED), ticket(Ticket.STATE_REVOKED)));
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
        when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute(null, null)));

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
        values.put("consequenceLine", "__CONSEQUENCE__");
        EmailTemplateRenderer.Rendered english =
                new EmailTemplateRenderer().render("dispute-opened", null, values);
        assertThat(text.getValue()).isNotEqualTo(english.text());
        assertThat(subject.getValue())
                .isNotEqualTo("Action needed: a chargeback was opened on Warehouse 7");
    }

    /**
     * Branch (b): tickets really were revoked, so the email may say so — with the count that
     * came off {@code tickets}, not an assumed one.
     */
    @Test
    void anAttributedDisputeNamesTheTicketsItRevoked() {
        String text = sendAndCaptureText();

        assertThat(text).contains("The 2 tickets on that order are revoked and will not open the door.");
        assertThat(text).doesNotContain("{{");
    }

    /**
     * Branch (a): the dispute beat the order (or never matched one). The old copy claimed a
     * revocation that had not happened to an order we had not found.
     */
    @Test
    void anUnattributedDisputeSaysItIsNotMatchedYet() {
        when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute(null, null)));

        String text = sendAndCaptureText();

        assertThat(text).contains("We have not matched this payment to one of your orders yet, "
                + "so no ticket has been revoked");
        assertThat(text).contains("revoked automatically");
    }

    /** Branch (c): the order was refunded before the chargeback, so there was nothing to revoke. */
    @Test
    void anAlreadyRefundedOrderSaysThereWasNothingLeftToRevoke() {
        when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(ticket(Ticket.STATE_REFUNDED)));

        String text = sendAndCaptureText();

        assertThat(text).contains(
                "Nothing was left to revoke on that order - its tickets had already been refunded.");
    }

    /** The in-app row made the same unconditional claim the email did; it takes the same sentence. */
    @Test
    void theInAppRowCarriesTheSameConsequenceSentence() {
        notifier.notify(DISPUTE_ID);

        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(n.capture());
        assertThat(n.getValue().getBody())
                .contains("The 2 tickets on that order are revoked and will not open the door.");
    }

    /**
     * Every branch in every language: the sentence is chosen in Java via {@code EmailLocale},
     * so a localized template alone would still have shipped the English consequence.
     */
    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("consequenceCopy")
    void eachConsequenceBranchIsLocalized(String branch, String locale, String expected) {
        if ("unattributed".equals(branch)) {
            when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute(EVENT_ID, null)));
        } else if ("refunded".equals(branch)) {
            when(tickets.findByOrderId(ORDER_ID)).thenReturn(List.of(ticket(Ticket.STATE_REFUNDED)));
        }
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(locale)));

        String text = sendAndCaptureText();

        assertThat(text).contains(expected);
        assertThat(text).doesNotContain("{{");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> consequenceCopy() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("revoked", "en",
                        "The 2 tickets on that order are revoked"),
                org.junit.jupiter.params.provider.Arguments.of("revoked", "es",
                        "Las 2 entradas de ese pedido están revocadas"),
                org.junit.jupiter.params.provider.Arguments.of("revoked", "fr",
                        "Les 2 billets de cette commande sont révoqués"),
                org.junit.jupiter.params.provider.Arguments.of("revoked", "uk",
                        "Квитки того замовлення (2) анульовано"),
                org.junit.jupiter.params.provider.Arguments.of("unattributed", "en",
                        "so no ticket has been revoked"),
                org.junit.jupiter.params.provider.Arguments.of("unattributed", "es",
                        "en cuanto lo asociemos"),
                org.junit.jupiter.params.provider.Arguments.of("unattributed", "fr",
                        "aucun billet n'a été révoqué"),
                org.junit.jupiter.params.provider.Arguments.of("unattributed", "uk",
                        "жодного квитка не анульовано"),
                org.junit.jupiter.params.provider.Arguments.of("refunded", "en",
                        "Nothing was left to revoke on that order"),
                org.junit.jupiter.params.provider.Arguments.of("refunded", "es",
                        "ya estaban reembolsadas"),
                org.junit.jupiter.params.provider.Arguments.of("refunded", "fr",
                        "avaient déjà été remboursés"),
                org.junit.jupiter.params.provider.Arguments.of("refunded", "uk",
                        "вже зроблено повернення"));
    }

    /**
     * The renderer throws on a placeholder with no value, so a path that forgot to supply
     * {@code consequenceLine} would fail the send rather than degrade. All four locales, both
     * parts, every branch.
     */
    @ParameterizedTest(name = "no missing placeholder in {0}")
    @ValueSource(strings = {"en", "es", "fr", "uk"})
    void noBranchLeavesAPlaceholderUnfilled(String locale) {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(locale)));

        assertThatCode(() -> notifier.notify(DISPUTE_ID)).doesNotThrowAnyException();

        when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute(EVENT_ID, null)));
        assertThatCode(() -> notifier.notify(DISPUTE_ID)).doesNotThrowAnyException();

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).send(anyString(), anyString(), html.capture(), text.capture());
        assertThat(html.getAllValues()).allSatisfy(h -> assertThat(h).doesNotContain("{{"));
        assertThat(text.getAllValues()).allSatisfy(t -> assertThat(t).doesNotContain("{{"));
    }

    private String sendAndCaptureText() {
        notifier.notify(DISPUTE_ID);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(anyString(), anyString(), anyString(), text.capture());
        return text.getValue();
    }

    private static Ticket ticket(String state) {
        Ticket t = new Ticket();
        t.setOrderId(ORDER_ID);
        t.setState(state);
        return t;
    }

    private static Dispute dispute(UUID eventId, UUID orderId) {
        Dispute d = new Dispute();
        d.setId(DISPUTE_ID);
        d.setStripeDisputeId("du_1");
        d.setOrgId(ORG_ID);
        d.setEventId(eventId);
        d.setOrderId(orderId);
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
