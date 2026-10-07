package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketIssuanceEmailerTest {

    @Test
    void renders_email_with_per_ticket_qr_link_order_link_and_recover_link() {
        EmailService email = mock(EmailService.class);
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();
        OrderRepository orders = mock(OrderRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        EventRepository events = mock(EventRepository.class);
        EmailProperties emailProps = new EmailProperties();
        emailProps.setBuyerSiteBaseUrl("https://app.imin.wtf");
        WalletOffers wallet = offers(true, true);

        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setToken("ORDER_TOK");
        order.setEventId(eventId);
        order.setEmail("buyer@example.com");

        Event event = new Event();
        event.setId(eventId);
        event.setName("Saturn Night");
        event.setStartsAt(OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant());
        event.setTimezone("Europe/Paris");
        event.setVenueName("Le Petit Bain");
        event.setVenueCity("Paris");

        Ticket t1 = new Ticket();
        t1.setToken("TKT_A");
        t1.setTierName("GA");
        Ticket t2 = new Ticket();
        t2.setToken("TKT_B");
        t2.setTierName("GA");

        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(t1, t2));

        TicketProperties ticketProps = new TicketProperties();
        ticketProps.setSigningSecret("x".repeat(32));
        ticketProps.setApiPublicBaseUrl("https://api.imin.test");
        TicketIssuanceEmailer emailer = new TicketIssuanceEmailer(
                orders, tickets, events, email, renderer, emailProps, ticketProps, wallet);
        emailer.send(orderId);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("buyer@example.com"), subject.capture(), html.capture(), text.capture());

        assertThat(subject.getValue()).isEqualTo("Your tickets for Saturn Night");
        assertThat(html.getValue())
                .contains("https://api.imin.test/api/v1/public/tickets/TKT_A/qr.png")
                .contains("https://api.imin.test/api/v1/public/tickets/TKT_B/qr.png")
                .contains("https://api.imin.test/api/v1/public/tickets/TKT_A/apple-wallet.pkpass")
                .contains("https://api.imin.test/api/v1/public/tickets/TKT_A/google-wallet")
                .contains("https://app.imin.wtf/tickets/TKT_A")
                .contains("https://app.imin.wtf/order/ORDER_TOK")
                .contains("https://app.imin.wtf/recover");
        assertThat(text.getValue())
                .contains("https://app.imin.wtf/tickets/TKT_A")
                .contains("https://app.imin.wtf/tickets/TKT_B")
                .contains("https://app.imin.wtf/order/ORDER_TOK")
                .contains("https://app.imin.wtf/recover");
    }

    @Test
    void single_ticket_uses_singular_subject_and_omits_wallet_when_unconfigured() {
        EmailService email = mock(EmailService.class);
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();
        OrderRepository orders = mock(OrderRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        EventRepository events = mock(EventRepository.class);
        EmailProperties emailProps = new EmailProperties();
        emailProps.setBuyerSiteBaseUrl("https://app.imin.wtf");
        WalletOffers wallet = offers(false, false);

        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setToken("ORDER_X");
        order.setEventId(eventId);
        order.setEmail("solo@example.com");

        Event event = new Event();
        event.setId(eventId);
        event.setName("Helios");

        Ticket t = new Ticket();
        t.setToken("TKT_SOLO");
        t.setTierName("GA");

        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(t));

        TicketProperties ticketProps = new TicketProperties();
        ticketProps.setSigningSecret("x".repeat(32));
        ticketProps.setApiPublicBaseUrl("https://api.imin.test");
        TicketIssuanceEmailer emailer = new TicketIssuanceEmailer(
                orders, tickets, events, email, renderer, emailProps, ticketProps, wallet);
        emailer.send(orderId);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("solo@example.com"), subject.capture(), html.capture(), any());

        assertThat(subject.getValue()).isEqualTo("Your ticket for Helios");
        // No wallet → no wallet link, on either side
        assertThat(html.getValue())
                .doesNotContain("apple-wallet.pkpass")
                .doesNotContain("google-wallet");
    }

    /** An order with no tickets left gets no ticket email: a mail with no QR would be worse than none. */
    @Test
    void an_order_with_no_tickets_sends_nothing() {
        EmailService email = mock(EmailService.class);
        OrderRepository orders = mock(OrderRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        EventRepository events = mock(EventRepository.class);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setEventId(eventId);
        order.setEmail("empty@example.com");
        Event event = new Event();
        event.setId(eventId);
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of());

        new TicketIssuanceEmailer(orders, tickets, events, email, new EmailTemplateRenderer(),
                new EmailProperties(), new TicketProperties(), offers(true, true)).send(orderId);

        org.mockito.Mockito.verifyNoInteractions(email);
    }

    /**
     * The order's stored language picks the body AND subject from the real localized file; none falls back to
     * English. Ukrainian is the locale most likely to break on encoding.
     */
    @ParameterizedTest(name = "locale {0}")
    @MethodSource("localizedTemplates")
    void uses_the_buyers_locale_for_body_and_subject(String locale, String subject, String lang, String textMarker) {
        SentEmail sent = sendWithLocale(locale, "buyer-" + locale + "@example.com");

        assertThat(sent.subject()).isEqualTo(subject);
        assertThat(sent.html()).contains("<html lang=\"" + lang + "\">");
        assertThat(sent.text()).contains(textMarker);
        // Placeholders still resolved in the localized file.
        assertThat(sent.html()).doesNotContain("{{");
        assertThat(sent.text()).doesNotContain("{{");
        // The per-ticket blocks are injected post-render; the localized file must still
        // carry the {{ticketBlocks}} slot or the QR codes silently vanish.
        assertThat(sent.html()).contains("https://api.imin.test/api/v1/public/tickets/TKT_SOLO/qr.png");
    }

    static Stream<Arguments> localizedTemplates() {
        return Stream.of(
                Arguments.of("es", "Tu entrada para Helios", "es", "YA ESTÁS DENTRO"),
                Arguments.of(null, "Your ticket for Helios", "en", "YOU'RE IN"),
                Arguments.of("uk", "Ваш квиток на Helios", "uk", "ВИ У СПИСКУ"));
    }

    // ── Price breakdown (Code conso. L112-1 / CRD Art.6(1)(e)) ────────────────
    //
    // Until this landed the itemised price existed only as a "Service fee" line on
    // the Stripe page the buyer had already left — the receipt they keep showed no
    // fee at all. These assert the numbers are the ones actually charged (derived
    // from the order, never recomputed) and that every locale carries the block.

    @Test
    void receipt_itemises_tickets_booking_fee_and_total() {
        SentEmail sent = sendPriced(null, "priced@example.com", 5198L, 199L);

        assertThat(sent.text())
                .contains("WHAT YOU PAID")
                .contains("Tickets: 49.99 EUR")
                .contains("Booking fee: 1.99 EUR")
                .contains("Total: 51.98 EUR");
        assertThat(sent.html())
                .contains("WHAT YOU PAID")
                .contains("49.99 EUR")
                .contains("1.99 EUR")
                .contains("51.98 EUR");
    }

    @Test
    void receipt_price_breakdown_is_localised() {
        assertThat(sendPriced("fr", "fr@example.com", 5198L, 199L).text())
                .contains("CE QUE VOUS AVEZ PAYÉ")
                .contains("Frais de réservation : 1.99 EUR");
        assertThat(sendPriced("es", "es@example.com", 5198L, 199L).text())
                .contains("LO QUE PAGASTE")
                .contains("Gastos de gestión: 1.99 EUR");
        assertThat(sendPriced("uk", "uk@example.com", 5198L, 199L).text())
                .contains("СКІЛЬКИ ВИ СПЛАТИЛИ")
                .contains("Сервісний збір: 1.99 EUR");
    }

    /** A free order really is free — no invented fee line. */
    @Test
    void receipt_shows_zero_for_a_free_order() {
        SentEmail sent = sendPriced(null, "free@example.com", 0L, 0L);
        assertThat(sent.text())
                .contains("Tickets: 0.00 EUR")
                .contains("Booking fee: 0.00 EUR")
                .contains("Total: 0.00 EUR");
    }

    /** One-ticket issuance for an order with real money on it. */
    private SentEmail sendPriced(String locale, String buyerEmail, long totalMinor, long feeMinor) {
        EmailService email = mock(EmailService.class);
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();
        OrderRepository orders = mock(OrderRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        EventRepository events = mock(EventRepository.class);
        EmailProperties emailProps = new EmailProperties();
        emailProps.setBuyerSiteBaseUrl("https://app.imin.wtf");

        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setToken("ORDER_PRICE");
        order.setEventId(eventId);
        order.setEmail(buyerEmail);
        order.setBuyerLocale(locale);
        order.setTotalMinor(totalMinor);
        order.setApplicationFeeMinor(feeMinor);
        order.setCurrency("EUR");

        Event event = new Event();
        event.setId(eventId);
        event.setName("Helios");

        Ticket t = new Ticket();
        t.setToken("TKT_SOLO");
        t.setTierName("GA");

        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(t));

        TicketProperties ticketProps = new TicketProperties();
        ticketProps.setSigningSecret("x".repeat(32));
        ticketProps.setApiPublicBaseUrl("https://api.imin.test");
        new TicketIssuanceEmailer(orders, tickets, events, email, renderer, emailProps,
                ticketProps, offers(false, false)).send(orderId);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq(buyerEmail), subject.capture(), html.capture(), text.capture());
        return new SentEmail(subject.getValue(), html.getValue(), text.getValue());
    }

    private record SentEmail(String subject, String html, String text) {}

    /** One-ticket issuance for an order carrying {@code locale}. */
    private SentEmail sendWithLocale(String locale, String buyerEmail) {
        EmailService email = mock(EmailService.class);
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();
        OrderRepository orders = mock(OrderRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        EventRepository events = mock(EventRepository.class);
        EmailProperties emailProps = new EmailProperties();
        emailProps.setBuyerSiteBaseUrl("https://app.imin.wtf");
        WalletOffers wallet = offers(false, false);

        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setToken("ORDER_L10N");
        order.setEventId(eventId);
        order.setEmail(buyerEmail);
        order.setBuyerLocale(locale);

        Event event = new Event();
        event.setId(eventId);
        event.setName("Helios");

        Ticket t = new Ticket();
        t.setToken("TKT_SOLO");
        t.setTierName("GA");

        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(t));

        TicketProperties ticketProps = new TicketProperties();
        ticketProps.setSigningSecret("x".repeat(32));
        ticketProps.setApiPublicBaseUrl("https://api.imin.test");
        new TicketIssuanceEmailer(orders, tickets, events, email, renderer, emailProps,
                ticketProps, wallet).send(orderId);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq(buyerEmail), subject.capture(), html.capture(), text.capture());
        return new SentEmail(subject.getValue(), html.getValue(), text.getValue());
    }

    /**
     * A real {@link WalletOffers} over doubled wallet services.
     *
     * <p>The doubles supply only the two inputs this test is parameterised on —
     * "are the Apple certs loaded" and "is the Google issuer live". Everything
     * the assertions actually read (which rows appear, and the exact URL in
     * each) is produced by the real {@code WalletOffers} from the real
     * {@code TicketProperties}. A {@code mock(WalletOffers.class)} would have to
     * be told the answer the test then checks.
     */
    private static WalletOffers offers(boolean appleOn, boolean googleOn) {
        AppleWalletPassService apple = mock(AppleWalletPassService.class);
        when(apple.isConfigured()).thenReturn(appleOn);
        GoogleWalletPassService google = mock(GoogleWalletPassService.class);
        when(google.isConfigured()).thenReturn(googleOn);

        TicketProperties props = new TicketProperties();
        props.setApiPublicBaseUrl("https://api.imin.test");
        return new WalletOffers(apple, google, props);
    }

    /** One-ticket issuance with an arbitrary wallet configuration and ticket state. */
    private SentEmail sendWith(WalletOffers wallet, String ticketState) {
        EmailService email = mock(EmailService.class);
        OrderRepository orders = mock(OrderRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        EventRepository events = mock(EventRepository.class);
        EmailProperties emailProps = new EmailProperties();
        emailProps.setBuyerSiteBaseUrl("https://app.imin.wtf");

        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setToken("ORDER_W");
        order.setEventId(eventId);
        order.setEmail("wallet@example.com");

        Event event = new Event();
        event.setId(eventId);
        event.setName("Helios");

        Ticket t = new Ticket();
        t.setToken("TKT_SOLO");
        t.setTierName("GA");
        t.setState(ticketState);

        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(t));

        TicketProperties ticketProps = new TicketProperties();
        ticketProps.setSigningSecret("x".repeat(32));
        ticketProps.setApiPublicBaseUrl("https://api.imin.test");
        new TicketIssuanceEmailer(orders, tickets, events, email, new EmailTemplateRenderer(),
                emailProps, ticketProps, wallet).send(orderId);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("wallet@example.com"), any(), html.capture(), text.capture());
        return new SentEmail("", html.getValue(), text.getValue());
    }

    // ── the two-wallet email ─────────────────────────────────────────────────

    /**
     * A wallet row appears only when {@link WalletOffers} offers it for that ticket: a refunded re-send must not
     * mail two buttons that 409, while a redeemed ticket keeps them (the door paints it amber, not red).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("walletRows")
    void wallet_rows_follow_what_the_offers_allow_for_the_ticket(String name, boolean appleOn, boolean googleOn,
                                                                 String state, boolean apple, boolean google) {
        SentEmail sent = sendWith(offers(appleOn, googleOn), state);

        String appleUrl = "https://api.imin.test/api/v1/public/tickets/TKT_SOLO/apple-wallet.pkpass";
        String googleUrl = "https://api.imin.test/api/v1/public/tickets/TKT_SOLO/google-wallet";
        if (apple) {
            assertThat(sent.html()).contains(appleUrl);
            assertThat(sent.text()).contains("Apple Wallet: " + appleUrl);
        } else {
            assertThat(sent.html()).doesNotContain("apple-wallet.pkpass").doesNotContain("Apple Wallet");
            assertThat(sent.text()).doesNotContain("Apple Wallet");
        }
        if (google) {
            assertThat(sent.html()).contains(googleUrl).contains("Save to Google Wallet");
            assertThat(sent.text()).contains("Google Wallet: " + googleUrl);
        } else {
            assertThat(sent.html()).doesNotContain("google-wallet").doesNotContain("Google Wallet");
            assertThat(sent.text()).doesNotContain("Google Wallet");
        }
        // The ticket itself is always in the email; only the wallet CTA can go.
        assertThat(sent.html()).contains("https://api.imin.test/api/v1/public/tickets/TKT_SOLO/qr.png");
    }

    static Stream<Arguments> walletRows() {
        return Stream.of(
                Arguments.of("google alone renders only the google row", false, true, Ticket.STATE_ISSUED, false, true),
                Arguments.of("both wallets render both rows", true, true, Ticket.STATE_ISSUED, true, true),
                Arguments.of("a refunded ticket gets no wallet row", true, true, Ticket.STATE_REFUNDED, false, false),
                Arguments.of("a redeemed ticket keeps its wallet rows", true, true, Ticket.STATE_REDEEMED, true, true));
    }
}
