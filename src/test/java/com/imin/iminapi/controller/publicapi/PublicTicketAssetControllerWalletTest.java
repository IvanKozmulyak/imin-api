package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.GlobalExceptionHandler;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.WalletSigningException;
import com.imin.iminapi.service.ticket.AppleWalletProperties;
import com.imin.iminapi.service.ticket.QrImageRenderer;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.service.ticket.TicketProperties;
import com.imin.iminapi.service.ticket.WalletTestCerts;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pkpass endpoint with the wallet actually CONFIGURED.
 *
 * <p>{@link PublicTicketAssetControllerTest} can only ever exercise the
 * unconfigured branch: it is a {@code @SpringBootTest}, and
 * {@code src/test/resources/application.yaml} replaces the main YAML and
 * carries no {@code imin.apple-wallet} block, so the properties always bind
 * blank. Standalone MockMvc over the real controller and the real
 * {@link AppleWalletPassService} is how the 200 path — and the defect-1
 * consequence, a signed pass from a <b>passwordless</b> .p12 — gets asserted at
 * the HTTP layer without a second Spring context.
 */
class PublicTicketAssetControllerWalletTest {

    private static final String TOKEN = "abc-DEF_123ghiJKL456mnoPQR";
    private static final String REFUNDED_TOKEN = "refunded-DEF_123ghiJKL456mno";
    private static final String REVOKED_TOKEN = "revoked-DEF_123ghiJKL456mnoP";
    private static final String REDEEMED_TOKEN = "redeemed-DEF_123ghiJKL456mn";

    private final List<String> bucketsConsumed = new ArrayList<>();

    /**
     * <b>Defect 1 at the level it bites.</b> Four env vars set correctly, a
     * certificate whose PKCS#12 export password is empty — legal, and what
     * {@code openssl pkcs12 -export -passout pass:} produces. Before the gate
     * fix this endpoint answered 503 forever with no log line telling anyone
     * apart from "nobody has set this up yet".
     */
    @Test
    void passwordlessCertificateServesASignedPassRatherThan503() throws Exception {
        MockMvc mvc = mvcWith(propsFrom(WalletTestCerts.generate("")));

        byte[] body = mvc.perform(get("/api/v1/public/tickets/" + TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.apple.pkpass"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                // Without this, Android Chrome and every desktop browser save the
                // file as "apple-wallet.pkpass" with no relation to the ticket.
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"imin-ticket-" + TOKEN + ".pkpass\""))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).as("a real signed archive, not an empty 200").isNotEmpty();
    }

    /** The same endpoint with a normal password, so the fix did not trade one case for the other. */
    @Test
    void passwordProtectedCertificateStillServesASignedPass() throws Exception {
        MockMvc mvc = mvcWith(propsFrom(WalletTestCerts.generate("s3cret")));

        mvc.perform(get("/api/v1/public/tickets/" + TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isOk());
    }

    /**
     * <b>A certificate that expires under a running process must not 500.</b>
     *
     * <p>{@code isConfigured()} is memoised from construction. That is sound for
     * a credential swap — a redeploy, so a new instance — and unsound for the one
     * thing a certificate does on its own clock. An Apple Pass Type ID
     * certificate lasts a year; a process that boots before {@code notAfter} and
     * outlives it keeps answering "configured" while every signature throws.
     * Found by minting an expired certificate and driving the real service:
     * {@code isConfigured() == true}, {@code generatePass} threw, nothing caught
     * it, and {@code GlobalExceptionHandler.handleAny} turned it into a
     * <b>500 on an endpoint whose only credential is a URL a buyer taps</b>.
     *
     * <p>The double stages the failure; it is not the oracle. What is asserted is
     * the HTTP contract three documents on this branch promise — {@code CLAUDE.md}
     * ("never a 500"), ADR-0004's Consequences, and {@code isConfigured()}'s own
     * javadoc, which describes this exact 500 as the defect it was written to fix
     * and fixed only at boot.
     *
     * <p>Delete the {@code catch (WalletSigningException)} in the controller and
     * this goes red on the status line, which is how it was checked.
     */
    @Test
    void aSigningFailureAtRequestTimeIs503AndNeverA500() throws Exception {
        AppleWalletPassService expired = mock(AppleWalletPassService.class);
        // Exactly the state an expired certificate leaves behind: the gate is
        // open, and the signature is what fails.
        when(expired.isConfigured()).thenReturn(true);
        when(expired.generatePass(TOKEN))
                .thenThrow(new WalletSigningException(new java.security.cert.CertificateExpiredException()));

        MockMvc mvc = mvcWith(new AppleWalletProperties(), expired);

        mvc.perform(get("/api/v1/public/tickets/" + TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isServiceUnavailable())
                // The envelope too, not only the status: imin-public reads
                // $.error.code, and this is the branch a buyer actually hits.
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));
    }

    /**
     * The other half of the same rule: a fault that is NOT a signing failure must
     * still surface as a 500, so a genuine bug inside pass construction cannot be
     * quietly reported as an upstream outage. This is why the controller catches
     * {@code WalletSigningException} and not {@code IllegalStateException} —
     * widen that catch and this case goes green while a real defect goes silent.
     */
    @Test
    void aProgrammingErrorIsNotDisguisedAsAnUpstreamOutage() throws Exception {
        AppleWalletPassService broken = mock(AppleWalletPassService.class);
        when(broken.isConfigured()).thenReturn(true);
        when(broken.generatePass(TOKEN)).thenThrow(new NullPointerException("a real bug"));

        MockMvc mvc = mvcWith(new AppleWalletProperties(), broken);

        mvc.perform(get("/api/v1/public/tickets/" + TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isInternalServerError());
    }

    /**
     * The envelope, asserted over the real advice rather than only over the
     * status line — {@code imin-public} reads {@code $.error.code} and an empty
     * body read there as a parse failure.
     */
    @Test
    void unconfiguredWalletReturnsTheErrorEnvelope() throws Exception {
        MockMvc mvc = mvcWith(new AppleWalletProperties());

        mvc.perform(get("/api/v1/public/tickets/" + TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));
    }

    /**
     * The bucket name is the contract between the controller and
     * {@code RateLimitConfig}: an unregistered name throws there and the global
     * handler turns it into a 500. {@code RateLimitBucketCoverageTest} checks the
     * YAML side; this pins that the controller asks for that exact name at all.
     */
    @Test
    void everyPkpassRequestConsumesTheWalletPassBucket() throws Exception {
        MockMvc mvc = mvcWith(propsFrom(WalletTestCerts.generate("")));

        mvc.perform(get("/api/v1/public/tickets/" + TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isOk());

        assertThat(bucketsConsumed).containsExactly("wallet-pass");
    }

    /**
     * Metering happens before the lookup, so token enumeration cannot spin the
     * DB for free either — an unknown token still costs a token from the bucket.
     */
    @Test
    void anUnknownTokenIsMeteredBeforeItIs404d() throws Exception {
        MockMvc mvc = mvcWith(new AppleWalletProperties());

        mvc.perform(get("/api/v1/public/tickets/no-such-token/apple-wallet.pkpass"))
                .andExpect(status().isNotFound());

        assertThat(bucketsConsumed).containsExactly("wallet-pass");
    }

    /**
     * <b>The defect this task exists to close, at the level a buyer meets it.</b>
     *
     * <p>{@code generatePass} never looked at {@code ticket.state}, so a fully
     * configured deployment would sign and serve a real, valid-looking pkpass for
     * a ticket that had already been refunded — a buyer at a door holding
     * something that looks official on their phone, which the scanner then
     * correctly refuses. The refusal is a handled 409 in the standard envelope,
     * not a 500 and not an empty body: {@code imin-public} reads
     * {@code $.error.code}.
     */
    @Test
    void aRefundedTicketIsRefusedAtTheEndpointRatherThanSigned() throws Exception {
        MockMvc mvc = mvcWith(propsFrom(WalletTestCerts.generate("")));

        mvc.perform(get("/api/v1/public/tickets/" + REFUNDED_TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_ALREADY_REFUNDED"));
    }

    /** The other state the gate paints red. Distinct code so the FE can tell them apart. */
    @Test
    void aRevokedTicketIsRefusedAtTheEndpoint() throws Exception {
        MockMvc mvc = mvcWith(propsFrom(WalletTestCerts.generate("")));

        mvc.perform(get("/api/v1/public/tickets/" + REVOKED_TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    /**
     * Redeemed is amber at the door, not red, and re-adding the pass after entry
     * is harmless — a buyer whose phone died in the queue must not be locked out
     * of their own ticket record.
     */
    @Test
    void aRedeemedTicketStillGetsItsPass() throws Exception {
        MockMvc mvc = mvcWith(propsFrom(WalletTestCerts.generate("")));

        mvc.perform(get("/api/v1/public/tickets/" + REDEEMED_TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isOk());
    }

    // ── wiring ───────────────────────────────────────────────────────────────

    private static AppleWalletProperties propsFrom(WalletTestCerts.Bundle bundle) {
        AppleWalletProperties props = new AppleWalletProperties();
        props.setPassTypeId("pass.test.imin");
        props.setTeamId("TESTTEAMID");
        props.setCertP12Base64(bundle.p12Base64());
        props.setCertPassword(bundle.password());
        props.setWwdrPemBase64(bundle.wwdrPemBase64());
        return props;
    }

    private MockMvc mvcWith(AppleWalletProperties props) {
        return mvcWith(props, null);
    }

    /**
     * @param apple when non-null, replaces the real service — the only way to
     *              stage a request-time signing failure without minting an
     *              expired certificate, which is a change to a shared helper.
     */
    private MockMvc mvcWith(AppleWalletProperties props, AppleWalletPassService apple) {
        TicketRepository tickets = mock(TicketRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        EventRepository events = mock(EventRepository.class);

        Ticket t = new Ticket();
        t.setToken(TOKEN);
        t.setOrderId(UUID.randomUUID());
        t.setEventId(UUID.randomUUID());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setState(Ticket.STATE_ISSUED);
        when(tickets.findByToken(TOKEN)).thenReturn(Optional.of(t));
        when(tickets.findByToken("no-such-token")).thenReturn(Optional.empty());
        when(tickets.findByToken(REFUNDED_TOKEN))
                .thenReturn(Optional.of(sameTicketIn(t, REFUNDED_TOKEN, Ticket.STATE_REFUNDED)));
        when(tickets.findByToken(REVOKED_TOKEN))
                .thenReturn(Optional.of(sameTicketIn(t, REVOKED_TOKEN, Ticket.STATE_REVOKED)));
        when(tickets.findByToken(REDEEMED_TOKEN))
                .thenReturn(Optional.of(sameTicketIn(t, REDEEMED_TOKEN, Ticket.STATE_REDEEMED)));

        Order o = new Order();
        o.setId(t.getOrderId());
        o.setEventId(t.getEventId());
        o.setOrgId(UUID.randomUUID());
        when(orders.findById(t.getOrderId())).thenReturn(Optional.of(o));

        Event e = new Event();
        e.setId(t.getEventId());
        e.setName("Saturn Night");
        e.setStartsAt(OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant());
        e.setTimezone("Europe/Paris");
        e.setVenueName("Le Petit Bain");
        e.setVenueCity("Paris");
        when(events.findById(t.getEventId())).thenReturn(Optional.of(e));

        TicketProperties tp = new TicketProperties();
        tp.setSigningSecret("wallet-endpoint-test-signing-secret");
        QrPayloadSigner qr = new QrPayloadSigner(tp);

        // Records rather than invents: a double that silently answers "fine" to
        // any bucket name is how an unregistered bucket ships green.
        RateLimiter recording = (bucket, key) -> bucketsConsumed.add(bucket);

        PublicTicketAssetController controller = new PublicTicketAssetController(
                tickets, qr, new QrImageRenderer(),
                apple != null ? apple : new AppleWalletPassService(props, tickets, orders, events,
                        mock(OrganizationRepository.class), qr, new EmailProperties()),
                // Apple's routes are the subject here; the Google collaborator only
                // has to exist. A Mockito double answers isConfigured() => false,
                // which is the closed direction — nothing in this file can reach
                // the Google endpoint and accidentally get a save link out of it.
                // The Google endpoint has its own file, GoogleWalletEndpointTest,
                // which uses no doubles at all below the HTTP transport.
                mock(GoogleWalletPassService.class),
                recording);

        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** The same order/event/tier as the live fixture, differing only in token and state. */
    private static Ticket sameTicketIn(Ticket live, String token, String state) {
        Ticket t = new Ticket();
        t.setToken(token);
        t.setOrderId(live.getOrderId());
        t.setEventId(live.getEventId());
        t.setTierId(live.getTierId());
        t.setTierName(live.getTierName());
        t.setState(state);
        return t;
    }
}
