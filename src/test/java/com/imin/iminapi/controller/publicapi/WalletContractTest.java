package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.audience.SmsConsentService;
import com.imin.iminapi.service.event.PublicEventService;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.service.ticket.TicketProperties;
import com.imin.iminapi.service.ticket.WalletOffers;
import com.imin.iminapi.service.ticket.WalletTestCerts;
import com.imin.iminapi.service.ticket.google.GoogleTestKeys;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two-wallet buyer contract, on the wire, in the real container.
 *
 * <h2>Why this class configures both wallets for real</h2>
 *
 * <p>Every other test in the suite sees a server with both wallets off, because
 * {@code src/test/resources/application.yaml} replaces the main YAML and carries
 * neither block. That server can only ever produce {@code available: false}, so
 * it cannot tell a contract that works from one that is broken in the same
 * direction as its own defaults. {@code @DynamicPropertySource} therefore hands
 * the real {@code AppleWalletProperties} a certificate {@link WalletTestCerts}
 * mints at runtime, and the real {@code GoogleWalletProperties} a service
 * account {@link GoogleTestKeys} mints at runtime — real RSA, parsed by the
 * production constructors, gated by the production {@code fullyConfigured()}
 * and {@code isUsable()}. Nothing is stubbed and nothing reaches Apple or
 * Google: {@code available} is a question about credentials, and answering it
 * opens no socket.
 *
 * <p>The combinatorial half of the contract — every wallet-config × ticket-state
 * pair, and the {@code url} ⟺ {@code available} biconditional — lives in
 * {@code WalletOffersTest}, where it is a pure function and can be exhaustive.
 * This class exists for the things only a container can answer: that the block
 * serialises, that the URLs point at this API, and that the deprecated flag
 * agrees with its replacement through the real Jackson.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class WalletContractTest {

    /** Minted once per JVM; opening a PKCS#12 is not free and nothing here mutates it. */
    static final WalletTestCerts.Bundle APPLE = WalletTestCerts.generate("");
    static final GoogleTestKeys.Bundle GOOGLE = GoogleTestKeys.generate();

    static final String API_BASE = "http://localhost:8080";

    @DynamicPropertySource
    static void bothWalletsOn(DynamicPropertyRegistry registry) {
        registry.add("imin.apple-wallet.enabled", () -> "true");
        registry.add("imin.apple-wallet.pass-type-id", () -> "pass.test.imin");
        registry.add("imin.apple-wallet.team-id", () -> "TESTTEAMID");
        registry.add("imin.apple-wallet.cert-p12-base64", APPLE::p12Base64);
        registry.add("imin.apple-wallet.cert-password", APPLE::password);
        registry.add("imin.apple-wallet.wwdr-pem-base64", APPLE::wwdrPemBase64);

        // enabled=true is the demo-mode hold released. In production it is the
        // last switch flipped, after Google grants publishing access.
        registry.add("imin.google-wallet.enabled", () -> "true");
        registry.add("imin.google-wallet.issuer-id", () -> "3388000000000000000");
        registry.add("imin.google-wallet.service-account-json-base64", GOOGLE::serviceAccountJsonBase64);
    }

    @Autowired MockMvc mvc;
    @Autowired TicketRepository tickets;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    final ObjectMapper json = new ObjectMapper();

    // ── the ticket response ──────────────────────────────────────────────────

    /**
     * The shape, once, in full — and the two URLs, which are the only part of
     * this contract a client cannot derive or default.
     */
    @Test
    void aLiveTicketOffersBothWalletsWithAbsoluteUrlsOnThisApi() throws Exception {
        Ticket t = persist(Ticket.STATE_ISSUED);

        JsonNode body = getJson("/api/v1/public/tickets/" + t.getToken());

        assertThat(body.path("wallet").path("apple").path("available").asBoolean()).isTrue();
        assertThat(body.path("wallet").path("google").path("available").asBoolean()).isTrue();
        assertThat(body.path("wallet").path("apple").path("url").asText())
                .as("absolute, on the API's own public base — not the buyer site, "
                    + "which has no such route, and not a relative path an email "
                    + "client or a native app cannot resolve")
                .isEqualTo(API_BASE + "/api/v1/public/tickets/" + t.getToken() + "/apple-wallet.pkpass");
        assertThat(body.path("wallet").path("google").path("url").asText())
                .as("our endpoint, NOT a pay.google.com save link. The save JWT "
                    + "carries an iat and is a bearer artifact; this response is "
                    + "cached by imin-public's service worker for the door, and a "
                    + "cached save link would be both stale and a credential in a "
                    + "cache. The mint happens on the far side of this URL.")
                .isEqualTo(API_BASE + "/api/v1/public/tickets/" + t.getToken() + "/google-wallet");
    }

    /**
     * THE DEPRECATION INVARIANT. {@code walletAvailable} is Apple's boolean and
     * nothing else, forever — a value earned by Google would light the Apple CTA
     * on Android, because the client gate on the other side is
     * {@code walletAvailable && isApplePlatform()}.
     */
    @Test
    void walletAvailableEqualsTheAppleHalfInEveryState() throws Exception {
        for (String state : new String[]{Ticket.STATE_ISSUED, Ticket.STATE_REDEEMED,
                Ticket.STATE_REFUNDED, Ticket.STATE_REVOKED}) {
            Ticket t = persist(state);
            JsonNode body = getJson("/api/v1/public/tickets/" + t.getToken());

            assertThat(body.path("walletAvailable").asBoolean())
                    .as("walletAvailable must equal wallet.apple.available for a %s ticket", state)
                    .isEqualTo(body.path("wallet").path("apple").path("available").asBoolean());
        }
    }

    /**
     * A refunded ticket is refused by both wallets <b>on a server where both are
     * configured</b> — which is the only server on which that sentence can be
     * tested at all. The 409 from the two endpoints is the enforcement; this is
     * the advertisement, and it has to agree, because the endpoints are directly
     * linkable from an email sent months earlier.
     */
    @Test
    void aRefundedTicketOffersNeitherWalletThoughBothAreConfigured() throws Exception {
        assertRefused(persist(Ticket.STATE_REFUNDED), "refunded");
        assertRefused(persist(Ticket.STATE_REVOKED), "revoked");
    }

    private void assertRefused(Ticket t, String state) throws Exception {
        JsonNode body = getJson("/api/v1/public/tickets/" + t.getToken());

        assertThat(body.path("walletAvailable").asBoolean()).isFalse();
        for (String vendor : new String[]{"apple", "google"}) {
            JsonNode pass = body.path("wallet").path(vendor);
            assertThat(pass.path("available").asBoolean())
                    .as("%s must not be offered for a %s ticket", vendor, state)
                    .isFalse();
            assertThat(pass.path("url").isNull())
                    .as("url must be null when available is false — two "
                        + "independently checkable encodings of one fact is how a "
                        + "client ends up gating on the wrong one, and the shape "
                        + "that produces is a lit CTA that 409s")
                    .isTrue();
        }
        // The response still says WHY, and it is not in the wallet block: state
        // is what separates "your ticket was refunded" from "the wallet is off".
        assertThat(body.path("state").asText()).isEqualTo(state);
    }

    /**
     * Redeemed is deliberately not refused. The door paints
     * {@code already_redeemed} amber, not red, and a buyer whose phone died in
     * the queue must not lose their own ticket record. This is the assertion a
     * future "tighten wallet eligibility" change has to argue with.
     */
    @Test
    void aRedeemedTicketStillOffersBothWallets() throws Exception {
        JsonNode body = getJson("/api/v1/public/tickets/" + persist(Ticket.STATE_REDEEMED).getToken());

        assertThat(body.path("wallet").path("apple").path("available").asBoolean()).isTrue();
        assertThat(body.path("wallet").path("google").path("available").asBoolean()).isTrue();
    }

    /**
     * Nothing about the deployment leaks. Google alone has three closed states
     * and {@code GoogleWalletProperties.gateReason()} names the env var that
     * closed each of them — for the log. This endpoint needs no authentication
     * beyond a token anyone who has ever bought a ticket holds.
     */
    @Test
    void theResponseNeverNamesAnEnvVarOrAGateReason() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/public/tickets/"
                        + persist(Ticket.STATE_ISSUED).getToken()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("APPLE_WALLET")
                .doesNotContain("GOOGLE_WALLET")
                .doesNotContain("demo-mode")
                .doesNotContain("issuer");
    }

    /**
     * THE ONE ASSERTION THIS CLASS'S OWN CONFIGURATION CANNOT MAKE.
     *
     * <p>Both wallets are on here, so {@code apple.available} and
     * {@code google.available} are equal in every case above and a
     * {@code walletAvailable} wired to the <b>wrong</b> one would pass all of
     * them. The whole reason {@code walletAvailable} is frozen to Apple is the
     * case where the two differ: {@code imin-public} gates on
     * {@code walletAvailable && isApplePlatform()}, so a value earned by Google
     * alone hands an Android buyer a {@code .pkpass} their device cannot open —
     * and Google is the wallet more likely to be live first, since its gate is
     * an account rather than a legal entity and a D-U-N-S number.
     *
     * <p>So this one case is driven over a standalone MockMvc with Apple off and
     * Google on: a second Spring context for one boolean is not worth it, and
     * the assembly being tested is the controller's, which standalone runs for
     * real.
     */
    @Test
    void walletAvailableFollowsAppleAndNotGoogleWhenTheTwoDisagree() throws Exception {
        Ticket t = new Ticket();
        t.setToken("TKT_ASYM");
        t.setState(Ticket.STATE_ISSUED);
        t.setTierName("GA");
        t.setOrderId(UUID.randomUUID());
        t.setEventId(UUID.randomUUID());

        Order order = new Order();
        order.setId(t.getOrderId());
        order.setToken("ORD_ASYM");
        order.setEventId(t.getEventId());
        order.setEmail("asym@example.com");

        Event ev = new Event();
        ev.setId(t.getEventId());
        ev.setName("Asymmetry");

        TicketRepository tr = mock(TicketRepository.class);
        OrderRepository or = mock(OrderRepository.class);
        EventRepository er = mock(EventRepository.class);
        when(tr.findByToken("TKT_ASYM")).thenReturn(Optional.of(t));
        when(or.findById(t.getOrderId())).thenReturn(Optional.of(order));
        when(er.findById(t.getEventId())).thenReturn(Optional.of(ev));

        AppleWalletPassService apple = mock(AppleWalletPassService.class);
        when(apple.isConfigured()).thenReturn(false);
        GoogleWalletPassService google = mock(GoogleWalletPassService.class);
        when(google.isConfigured()).thenReturn(true);

        TicketProperties tp = new TicketProperties();
        tp.setSigningSecret("wallet-contract-asymmetry-secret");
        tp.setApiPublicBaseUrl(API_BASE);

        MockMvc standalone = MockMvcBuilders
                .standaloneSetup(new PublicOrderController(or, tr, er,
                        new QrPayloadSigner(tp),
                        new WalletOffers(apple, google, tp),
                        tp,
                        mock(SmsConsentService.class),
                        mock(PublicEventService.class)))
                .build();

        MvcResult result = standalone.perform(get("/api/v1/public/tickets/TKT_ASYM"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());

        assertThat(body.path("wallet").path("google").path("available").asBoolean()).isTrue();
        assertThat(body.path("wallet").path("apple").path("available").asBoolean()).isFalse();
        assertThat(body.path("walletAvailable").asBoolean())
                .as("walletAvailable is Apple's boolean forever; Google being live "
                    + "must never light the Apple CTA")
                .isFalse();
    }

    // ── the order response ───────────────────────────────────────────────────

    /**
     * THE N+1 THIS DELETES, AND THE BUG THE OBVIOUS FIX WOULD HAVE.
     *
     * <p>{@code imin-public/components/buyer/order-view.tsx:53-63} fetches a
     * whole extra ticket through a {@code <Suspense>} boundary purely to learn
     * one boolean, then applies it to the order. Carrying the answer on the
     * order response removes that round trip — but only if it is resolved per
     * ticket. An order with one refunded ticket and two live ones is three
     * different answers, and an order-wide flag would show three buttons or
     * none.
     */
    @Test
    void eachTicketOnAnOrderCarriesItsOwnAnswer() throws Exception {
        Ticket live = persist(Ticket.STATE_ISSUED);
        Ticket dead = new Ticket();
        dead.setToken("TKT_" + UUID.randomUUID());
        dead.setOrderId(live.getOrderId());
        dead.setEventId(live.getEventId());
        dead.setTierId(live.getTierId());
        dead.setTierName("GA");
        dead.setState(Ticket.STATE_REFUNDED);
        dead = tickets.save(dead);

        Order order = orders.findById(live.getOrderId()).orElseThrow();
        JsonNode body = getJson("/api/v1/public/orders/" + order.getToken());

        assertThat(body.path("tickets")).hasSize(2);
        for (JsonNode node : body.path("tickets")) {
            boolean isLive = node.path("token").asText().equals(live.getToken());
            assertThat(node.path("wallet").path("apple").path("available").asBoolean())
                    .as("ticket %s", node.path("token").asText())
                    .isEqualTo(isLive);
            assertThat(node.path("wallet").path("google").path("available").asBoolean())
                    .isEqualTo(isLive);
            assertThat(node.path("wallet").path("apple").path("url").isNull()).isEqualTo(!isLive);
        }

        // Per-ticket URLs, not one token repeated — the failure mode of building
        // these from the order rather than the row.
        JsonNode first = body.path("tickets").get(0);
        JsonNode second = body.path("tickets").get(1);
        assertThat(first.path("token").asText()).isNotEqualTo(second.path("token").asText());
        assertThat(body.path("tickets").toString())
                .contains(live.getToken() + "/apple-wallet.pkpass")
                .doesNotContain(dead.getToken() + "/apple-wallet.pkpass");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Ticket persist(String state) {
        Organization org = new Organization();
        org.setName("Wallet Contract Org");
        org.setSlug("wallet-contract-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("wallet@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("wallet-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event ev = new Event();
        ev.setOrgId(org.getId());
        ev.setName("Wallet Contract Event");
        ev.setSlug("wallet-contract-event-" + UUID.randomUUID().toString().substring(0, 8));
        ev.setVisibility(EventVisibility.PUBLIC);
        ev.setStatus(EventStatus.LIVE);
        ev.setCurrency("EUR");
        ev.setCreatedBy(owner.getId());
        ev = events.save(ev);

        Order order = new Order();
        order.setToken("ORD_" + UUID.randomUUID());
        order.setEventId(ev.getId());
        order.setOrgId(org.getId());
        order.setEmail("buyer@example.com");
        order.setTotalMinor(1500L);
        order.setCurrency("EUR");
        order.setPaymentMethod("stripe");
        order = orders.save(order);

        Ticket t = new Ticket();
        t.setToken("TKT_" + UUID.randomUUID());
        t.setOrderId(order.getId());
        t.setEventId(ev.getId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setState(state);
        return tickets.save(t);
    }
}
