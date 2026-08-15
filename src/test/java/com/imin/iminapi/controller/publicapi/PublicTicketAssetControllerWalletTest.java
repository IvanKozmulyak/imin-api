package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.GlobalExceptionHandler;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.AppleWalletProperties;
import com.imin.iminapi.service.ticket.QrImageRenderer;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.service.ticket.TicketProperties;
import com.imin.iminapi.service.ticket.WalletTestCerts;
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
                new AppleWalletPassService(props, tickets, orders, events, qr),
                recording);

        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
