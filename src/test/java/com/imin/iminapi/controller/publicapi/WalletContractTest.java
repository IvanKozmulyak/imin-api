package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.service.audience.SmsConsentService;
import com.imin.iminapi.service.event.PublicEventService;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.AppleWalletProperties;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.service.ticket.TicketProperties;
import com.imin.iminapi.service.ticket.WalletOffers;
import com.imin.iminapi.service.ticket.WalletTestCerts;
import com.imin.iminapi.service.ticket.google.GoogleTestKeys;
import com.imin.iminapi.service.ticket.google.GoogleWalletJwtSigner;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import com.imin.iminapi.service.ticket.google.GoogleWalletProperties;
import com.imin.iminapi.service.ticket.google.GoogleWalletProvisioner;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two-wallet buyer contract on the wire, with real credentials: wallet configuration is captured
 * at construction, so this builds the configured services and drives the real controller standalone.
 */
@IminIntegrationTest
class WalletContractTest {

    /** Minted once per JVM; opening a PKCS#12 is not free and nothing here mutates it. */
    static final WalletTestCerts.Bundle APPLE = WalletTestCerts.generate("");
    static final GoogleTestKeys.Bundle GOOGLE = GoogleTestKeys.generate();

    static final String API_BASE = "http://localhost:8080";

    @Autowired IminFixtures fx;
    @Autowired TicketRepository tickets;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired QrPayloadSigner qrSigner;
    @Autowired TicketProperties ticketProps;
    @Autowired EmailProperties emailProps;
    @Autowired GoogleWalletProvisioner provisioner;
    @Autowired SmsConsentService smsConsentService;
    @Autowired PublicEventService publicEventService;
    /** The context's own Apple service: unconfigured, since the test yaml has no imin.apple-wallet block. */
    @Autowired AppleWalletPassService unconfiguredApple;
    @Autowired @Qualifier("requestMappingHandlerAdapter") RequestMappingHandlerAdapter handlerAdapter;

    MockMvc mvc;

    @BeforeEach
    void bothWalletsOn() {
        assertThat(ticketProps.getApiPublicBaseUrl()).isEqualTo(API_BASE);
        mvc = standalone(new WalletOffers(configuredApple(), configuredGoogle(), ticketProps));
    }

    private AppleWalletPassService configuredApple() {
        AppleWalletProperties props = new AppleWalletProperties();
        props.setEnabled(true);
        props.setPassTypeId("pass.test.imin");
        props.setTeamId("TESTTEAMID");
        props.setCertP12Base64(APPLE.p12Base64());
        props.setCertPassword(APPLE.password());
        props.setWwdrPemBase64(APPLE.wwdrPemBase64());
        AppleWalletPassService apple = new AppleWalletPassService(
                props, tickets, orders, events, orgs, qrSigner, emailProps);
        assertThat(apple.isConfigured()).as("real certificate, production gate").isTrue();
        return apple;
    }

    private GoogleWalletPassService configuredGoogle() {
        // enabled=true is the demo-mode hold released. In production it is the
        // last switch flipped, after Google grants publishing access.
        GoogleWalletProperties props = new GoogleWalletProperties();
        props.setEnabled(true);
        props.setIssuerId("3388000000000000000");
        props.setServiceAccountJsonBase64(GOOGLE.serviceAccountJsonBase64());
        GoogleWalletPassService google = new GoogleWalletPassService(
                props, provisioner, new GoogleWalletJwtSigner(props), tickets, events, orgs, qrSigner);
        assertThat(google.isConfigured()).as("real service account, production gate").isTrue();
        return google;
    }

    /** The real controller, serialised by the context's own message converters. */
    private MockMvc standalone(WalletOffers offers) {
        return MockMvcBuilders
                .standaloneSetup(new PublicOrderController(orders, tickets, events, qrSigner, offers,
                        ticketProps, smsConsentService, publicEventService))
                .setMessageConverters(handlerAdapter.getMessageConverters().toArray(HttpMessageConverter[]::new))
                .build();
    }

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
     * <p>So this one case runs with the context's own unconfigured Apple service and a
     * configured Google one.
     */
    @Test
    void walletAvailableFollowsAppleAndNotGoogleWhenTheTwoDisagree() throws Exception {
        assertThat(unconfiguredApple.isConfigured()).isFalse();
        MockMvc asymmetric = standalone(new WalletOffers(unconfiguredApple, configuredGoogle(), ticketProps));
        Ticket t = persist(Ticket.STATE_ISSUED);

        MvcResult result = asymmetric.perform(get("/api/v1/public/tickets/" + t.getToken()))
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
        Organization org = fx.org();
        Event ev = fx.event(org, fx.owner(org), EventStatus.LIVE, null);
        Order order = fx.order(ev, fx.email("buyer"));
        return fx.ticket(order, state);
    }
}
