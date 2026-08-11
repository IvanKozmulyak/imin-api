package com.imin.iminapi.refund;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.refund.email.RefundRequestEmailer;
import com.imin.iminapi.refund.event.RefundRequestRejectedEvent;
import com.imin.iminapi.refund.event.RefundRequestSubmittedEvent;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundRequestEmailerTest {

    RecordingEmailService emails = new RecordingEmailService();
    EmailTemplateRenderer renderer = mock(EmailTemplateRenderer.class);
    EmailProperties props = new EmailProperties();
    RefundRequestRepository requests = mock(RefundRequestRepository.class);
    EventRepository events = mock(EventRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    OrganizationRepository orgs = mock(OrganizationRepository.class);
    UserRepository users = mock(UserRepository.class);

    RefundRequestEmailer emailer;

    @BeforeEach
    void setUp() {
        props.setFromAddress("noreply@test");
        props.setAppBaseUrl("https://dashboard.test");
        props.setRefundRequestInbox("support+refunds@test");
        when(renderer.render(anyString(), any()))
            .thenReturn(new EmailTemplateRenderer.Rendered("<html/>", "txt"));
        // The organizer-notify email uses the locale-aware 3-arg render overload.
        when(renderer.render(anyString(), any(), any()))
            .thenReturn(new EmailTemplateRenderer.Rendered("<html/>", "txt"));
        emailer = new RefundRequestEmailer(emails, renderer, props, requests, events, orders, orgs, users);
    }

    private RefundRequest seedRequest() {
        RefundRequest rr = new RefundRequest();
        rr.setId(UUID.randomUUID());
        rr.setOrderId(UUID.randomUUID());
        rr.setOrgId(UUID.randomUUID());
        rr.setEventId(UUID.randomUUID());
        rr.setBuyerEmail("buyer@example.com");
        rr.setReason(RefundRequestReason.CANT_ATTEND);
        rr.setExplanation("text");
        rr.setStatus(RefundRequestStatus.PENDING);
        rr.setCreatedAt(Instant.now());
        return rr;
    }

    @Test
    void on_submitted_sends_three_emails() {
        RefundRequest rr = seedRequest();
        when(requests.findById(rr.getId())).thenReturn(Optional.of(rr));
        Event event = new Event();
        event.setName("Test Event");
        when(events.findById(rr.getEventId())).thenReturn(Optional.of(event));
        Organization org = new Organization();
        org.setContactEmail("organizer@example.com");
        when(orgs.findById(rr.getOrgId())).thenReturn(Optional.of(org));

        emailer.onSubmitted(new RefundRequestSubmittedEvent(rr.getId()));

        assertThat(emails.sent()).hasSize(3);
        assertThat(emails.sent().stream().map(RecordingEmailService.SentEmail::to))
            .containsExactlyInAnyOrder(
                "buyer@example.com",
                "organizer@example.com",
                "support+refunds@test");
    }

    /**
     * W1.G: the buyer acknowledgement follows the language on the order
     * (orders.buyer_locale, V78) — independently of the organizer notification, which
     * follows the ORGANIZER's language. A Spanish buyer and a French organizer each get
     * their own.
     */
    @Test
    void on_submitted_sends_buyer_ack_in_the_buyers_locale() {
        RefundRequest rr = seedRequest();
        when(requests.findById(rr.getId())).thenReturn(Optional.of(rr));
        Order order = new Order();
        order.setId(rr.getOrderId());
        order.setBuyerLocale("es");
        when(orders.findById(rr.getOrderId())).thenReturn(Optional.of(order));
        when(events.findById(rr.getEventId())).thenReturn(Optional.empty());
        when(orgs.findById(rr.getOrgId())).thenReturn(Optional.empty());

        emailer.onSubmitted(new RefundRequestSubmittedEvent(rr.getId()));

        verify(renderer).render(eq("refund-request-received-buyer"), eq("es"), any());
        assertThat(emails.sent().stream()
                .filter(s -> s.to().equals("buyer@example.com"))
                .map(RecordingEmailService.SentEmail::subject))
            .containsExactly("Hemos recibido tu solicitud de reembolso · imin");
    }

    /** Unknown order (or no stored preference) ⇒ null locale ⇒ the English ack. */
    @Test
    void on_submitted_buyer_ack_falls_back_to_english_without_an_order_locale() {
        RefundRequest rr = seedRequest();
        when(requests.findById(rr.getId())).thenReturn(Optional.of(rr));
        when(orders.findById(rr.getOrderId())).thenReturn(Optional.empty());
        when(events.findById(rr.getEventId())).thenReturn(Optional.empty());
        when(orgs.findById(rr.getOrgId())).thenReturn(Optional.empty());

        emailer.onSubmitted(new RefundRequestSubmittedEvent(rr.getId()));

        assertThat(emails.sent().stream()
                .filter(s -> s.to().equals("buyer@example.com"))
                .map(RecordingEmailService.SentEmail::subject))
            .containsExactly("We got your refund request · imin");
    }

    @Test
    void on_submitted_skips_organizer_email_when_org_missing() {
        RefundRequest rr = seedRequest();
        when(requests.findById(rr.getId())).thenReturn(Optional.of(rr));
        when(events.findById(rr.getEventId())).thenReturn(Optional.empty());
        when(orgs.findById(rr.getOrgId())).thenReturn(Optional.empty());

        emailer.onSubmitted(new RefundRequestSubmittedEvent(rr.getId()));

        // Buyer and imin inbox still go; organizer is skipped because contactEmail is unknown.
        assertThat(emails.sent()).hasSize(2);
        assertThat(emails.sent().stream().map(RecordingEmailService.SentEmail::to))
            .containsExactlyInAnyOrder("buyer@example.com", "support+refunds@test");
    }

    @Test
    void on_rejected_sends_buyer_email() {
        RefundRequest rr = seedRequest();
        rr.setStatus(RefundRequestStatus.REJECTED);
        rr.setDecisionNote("Past 48h");
        when(requests.findById(rr.getId())).thenReturn(Optional.of(rr));

        emailer.onRejected(new RefundRequestRejectedEvent(rr.getId()));

        assertThat(emails.sent()).hasSize(1);
        assertThat(emails.sent().get(0).to()).isEqualTo("buyer@example.com");
    }

    @Test
    void missing_request_is_a_noop() {
        UUID id = UUID.randomUUID();
        when(requests.findById(id)).thenReturn(Optional.empty());

        emailer.onSubmitted(new RefundRequestSubmittedEvent(id));
        emailer.onRejected(new RefundRequestRejectedEvent(id));

        assertThat(emails.sent()).isEmpty();
    }
}
