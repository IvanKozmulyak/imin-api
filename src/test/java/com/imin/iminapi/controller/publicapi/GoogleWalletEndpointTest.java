package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.GlobalExceptionHandler;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.AppleWalletProperties;
import com.imin.iminapi.service.ticket.QrImageRenderer;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.service.ticket.TicketProperties;
import com.imin.iminapi.service.ticket.WalletTestCerts;
import com.imin.iminapi.service.ticket.google.GoogleServiceAccountKey;
import com.imin.iminapi.service.ticket.google.GoogleTestKeys;
import com.imin.iminapi.service.ticket.google.GoogleWalletApiClient;
import com.imin.iminapi.service.ticket.google.GoogleWalletJwtSigner;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import com.imin.iminapi.service.ticket.google.GoogleWalletProperties;
import com.imin.iminapi.service.ticket.google.GoogleWalletProvisioner;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/public/tickets/{token}/google-wallet}, end to end, with
 * nothing doubled below the HTTP transport.
 *
 * <h2>Why not the shape the plan asked for</h2>
 *
 * <p>The plan specified "a {@code @SpringBootTest} … plus a
 * {@code @MockitoBean GoogleWalletPassService} for the wired cases". A mocked
 * pass service is the one component that must not be mocked here: it is where
 * the ticket rows, the QR payload, the provisioner and the signer meet, and it
 * is the only place the two obligations Task 6 handed forward can be observed at
 * all. Mocked, this file would assert that a stub returns the string the stub
 * was told to return.
 *
 * <p>So it is standalone MockMvc over the real controller, the real
 * {@link GoogleWalletPassService}, the real {@link GoogleWalletProvisioner}, the
 * real {@link GoogleWalletApiClient} and the real {@link GoogleWalletJwtSigner}
 * over a real 2048-bit RSA key — the same construction
 * {@code PublicTicketAssetControllerWalletTest} uses for the Apple side, for the
 * same reason: {@code src/test/resources/application.yaml} replaces the main
 * YAML and carries no {@code imin.google-wallet} block, so a
 * {@code @SpringBootTest} can only ever exercise the unconfigured branch.
 * {@code PublicTicketAssetControllerTest} keeps that branch, over the real
 * security chain, where it belongs.
 *
 * <p>The only double is the transport: {@link MockRestServiceServer} bound to
 * the {@code RestClient}. Every "nothing was sent" assertion below is
 * {@code server.verify()} against a mock server with <b>no expectations at
 * all</b>, which fails on any request — not an absence of assertions.
 */
class GoogleWalletEndpointTest {

    private static final String ISSUER = "3388000000000000000";

    /** Base64url without padding, the shape {@code PaidCheckoutService.randomToken()} produces. */
    private static final String TOKEN = "abc-DEF_123ghiJKL456mnoPQR";
    private static final String REFUNDED_TOKEN = "refunded-DEF_123ghiJKL456mno";
    private static final String REVOKED_TOKEN = "revoked-DEF_123ghiJKL456mnoP";
    private static final String REDEEMED_TOKEN = "redeemed-DEF_123ghiJKL456mn";

    private static final UUID EVENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ORG_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final UUID ORDER_ID = UUID.fromString("12121212-3434-5656-7878-909090909090");

    private static final String CLASS_ID = ISSUER + ".evt_" + EVENT_ID;
    private static final String OBJECT_ID = ISSUER + ".tkt_" + TOKEN;

    private static final String SAVE_PREFIX = "https://pay.google.com/gp/v/save/";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String CLASS_URL =
            "https://walletobjects.googleapis.com/walletobjects/v1/eventTicketClass";
    private static final String OBJECT_URL =
            "https://walletobjects.googleapis.com/walletobjects/v1/eventTicketObject";

    private static final GoogleTestKeys.Bundle KEYS = GoogleTestKeys.generate();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> bucketsConsumed = new ArrayList<>();
    private final QrPayloadSigner qr = qrSigner();

    private MockRestServiceServer server;
    /** The exact body Google would have received for the object insert. */
    private final AtomicReference<String> objectBody = new AtomicReference<>();

    // ── the closed states ────────────────────────────────────────────────────

    /**
     * The gate, in the state every developer machine and every CI run is in.
     * {@code $.error.code} and not {@code $.code} — {@code ApiError} wraps the
     * body, and {@code imin-public} reads the wrapped form.
     */
    @Test
    void anUnconfiguredWalletIs503WithTheErrorEnvelopeAndSendsNothing() throws Exception {
        MockMvc mvc = mvcWith(new GoogleWalletProperties());

        mvc.perform(get(url(TOKEN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));

        server.verify();
    }

    /**
     * Credentials complete, {@code GOOGLE_WALLET_ENABLED} still false — the state
     * the whole development period sits in, because Google's publishing review
     * cannot even be requested until a class exists in production. It must behave
     * exactly like unconfigured: closed, quiet, and nothing on the wire.
     */
    @Test
    void completeCredentialsWithTheSwitchOffStillReachNoBuyerAndNoGoogle() throws Exception {
        GoogleWalletProperties off = liveProperties();
        off.setEnabled(false);
        MockMvc mvc = mvcWith(off);

        mvc.perform(get(url(TOKEN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));

        server.verify();
    }

    /**
     * <b>The 404 comes before the config check, and the pinning matters.</b> If
     * the gate answered first, an unconfigured deployment would return 503 for a
     * real token and 404 for a fake one — handing anyone enumerating tokens a
     * free oracle for "is this a real ticket", with no rate limit fast enough to
     * matter. Both servers answer 404 to the same unknown token; that identity is
     * the assertion.
     */
    @Test
    void anUnknownTokenIs404OnBothAConfiguredAndAnUnconfiguredServer() throws Exception {
        mvcWith(new GoogleWalletProperties())
                .perform(get(url("no-such-token")))
                .andExpect(status().isNotFound());
        server.verify();

        mvcWith(liveProperties())
                .perform(get(url("no-such-token")))
                .andExpect(status().isNotFound());
        server.verify();
    }

    // ── dead tickets ─────────────────────────────────────────────────────────

    /**
     * A refunded ticket gets no fresh, official-looking artifact on a phone —
     * and it gets the true answer, not the convenient one. 409 and not 503 even
     * with the wallet switched off: "temporarily unavailable" would be false, and
     * it would invite a retry that can never succeed. What a buyer learns about
     * their own ticket must not depend on our deployment state.
     */
    @Test
    void aRefundedTicketIs409EvenWhenTheWalletIsOff() throws Exception {
        mvcWith(new GoogleWalletProperties())
                .perform(get(url(REFUNDED_TOKEN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_ALREADY_REFUNDED"));
        server.verify();
    }

    /** And with it on, so the ordering is not the only thing keeping it 409. */
    @Test
    void aRefundedTicketIs409OnALiveWalletAndNothingIsSentToGoogle() throws Exception {
        mvcWith(liveProperties())
                .perform(get(url(REFUNDED_TOKEN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_ALREADY_REFUNDED"));
        server.verify();
    }

    /** The organizer's action, with its own code so the frontend can tell them apart. */
    @Test
    void aRevokedTicketIs409WithItsOwnCode() throws Exception {
        mvcWith(liveProperties())
                .perform(get(url(REVOKED_TOKEN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        server.verify();
    }

    /**
     * Redeemed is amber at the door, not red. A buyer whose phone died in the
     * queue must not be locked out of their own ticket record.
     */
    @Test
    void aRedeemedTicketStillGetsASaveLink() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectFullProvisioning();

        mvc.perform(get(url(REDEEMED_TOKEN)))
                .andExpect(status().isFound());
        server.verify();
    }

    // ── the redirect ─────────────────────────────────────────────────────────

    @Test
    void aLiveTicketRedirectsToGooglesSavePageWithNoStoreCaching() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectFullProvisioning();

        String location = mvc.perform(get(url(TOKEN)))
                .andExpect(status().isFound())
                // A bearer artifact in a header: whoever holds this JWT can add
                // the pass. No shared cache may keep it.
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).startsWith(SAVE_PREFIX);
        server.verify();
    }

    /**
     * The JWT Google is about to be handed, opened and verified with the public
     * half of the key that signed it. If the algorithm, the key or the claim set
     * were wrong, this fails here exactly as it would fail there.
     */
    @Test
    void theJwtInTheLocationIsOneGoogleWouldAccept() throws Exception {
        SignedJWT jwt = SignedJWT.parse(saveLinkJwt());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getType().toString())
                .as("Google's reference sample sets the JOSE header typ to JWT")
                .isEqualTo("JWT");
        assertThat(jwt.verify(new RSASSAVerifier(KEYS.publicKey())))
                .as("Google verifies this signature; if it fails here it fails there")
                .isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(KEYS.clientEmail());
        assertThat(claims.getStringClaim("typ")).isEqualTo("savetowallet");
        assertThat(claims.getIssueTime()).isNotNull();
        assertThat(claims.getStringListClaim("origins")).containsExactly("https://app.imin.wtf");
    }

    /**
     * <b>The payload is an id and nothing else.</b> Google caps a save URL at 1800
     * characters and answers "my JWT link exceeds the limit" with "pre-create the
     * resources over REST and put only the id in the JWT" — which is the entire
     * reason this feature has a provisioner. An inline class or object creeping
     * back into the payload is how that cap gets breached, and it would be
     * breached in production and nowhere else.
     */
    @Test
    void theJwtCarriesTheObjectIdAndNothingElse() throws Exception {
        var payload = SignedJWT.parse(saveLinkJwt()).getJWTClaimsSet().getJSONObjectClaim("payload");

        assertThat(payload).containsOnlyKeys("eventTicketObjects");
        @SuppressWarnings("unchecked")
        List<Object> objects = (List<Object>) payload.get("eventTicketObjects");
        assertThat(objects).hasSize(1);

        JsonNode only = JSON.readTree(JSON.writeValueAsString(objects.get(0)));
        assertThat(only.properties())
                .as("an inline object here is the 1800-character regression")
                .hasSize(1);
        assertThat(only.get("id").asText()).isEqualTo(OBJECT_ID);
    }

    /** The one assertion that catches that regression whatever shape it arrives in. */
    @Test
    void theWholeSaveUrlIsUnderGooglesEighteenHundredCharacterCap() throws Exception {
        assertThat(SAVE_PREFIX + saveLinkJwt())
                .hasSizeLessThan(1800);
    }

    // ── one canonical payload ────────────────────────────────────────────────

    /**
     * <b>Obligation carried forward from Task 6, discharged here.</b>
     *
     * <p>Task 6 asserted that the Google barcode carries the canonical
     * {@code imin1.…} payload — but {@link com.imin.iminapi.service.ticket.google.GoogleWalletModels}
     * takes {@code qrPayload} as a parameter, so its test fed in the literal it
     * then asserted. A tautology: the models will faithfully carry whatever they
     * are handed, including the wrong thing.
     *
     * <p>The invariant only exists at the seam where the payload is produced, and
     * that seam is this endpoint. So all three transports are driven through
     * their real code here — the object body Google would receive, the PNG
     * {@code /qr.png} serves (decoded, not compared as bytes), and the barcode
     * inside a genuinely signed {@code .pkpass} — and the three are asserted
     * equal to each other and to {@code QrPayloadSigner.sign(token)}.
     *
     * <p>What it catches: any future change that gives one wallet a different
     * payload from the other — a token instead of the signed string, a
     * re-signature with a different secret, an id where the payload belongs.
     * Today a door scanner accepts all three because they are the same bytes;
     * this is the test that keeps that sentence true.
     */
    @Test
    void allThreeTransportsCarryTheSameQrPayload() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectFullProvisioning();

        // 1. What Google is sent.
        mvc.perform(get(url(TOKEN))).andExpect(status().isFound());
        String google = JSON.readTree(objectBody.get()).at("/barcode/value").asText();

        // 2. What the buyer's ticket page renders, decoded back out of the pixels.
        byte[] png = mvc.perform(get("/api/v1/public/tickets/" + TOKEN + "/qr.png"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String web = decodeQr(png);

        // 3. What the signed Apple archive carries.
        byte[] pkpass = mvc.perform(get("/api/v1/public/tickets/" + TOKEN + "/apple-wallet.pkpass"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String apple = JSON.readTree(readZipEntry(pkpass, "pass.json")).at("/barcodes/0/message").asText();

        assertThat(google)
                .as("the payload is produced by QrPayloadSigner on this path, not handed in by a test")
                .isEqualTo(qr.sign(TOKEN))
                .startsWith("imin1." + TOKEN + ".");
        assertThat(apple).isEqualTo(google);
        assertThat(web).isEqualTo(google);

        server.verify();
    }

    /**
     * The object Google stores, checked past the barcode: the id, the class it
     * hangs off, and the state that decides whether the pass renders as live.
     */
    @Test
    void theObjectGoogleReceivesNamesTheRightTicketClassAndState() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectFullProvisioning();
        mvc.perform(get(url(TOKEN))).andExpect(status().isFound());

        JsonNode object = JSON.readTree(objectBody.get());
        assertThat(object.get("id").asText()).isEqualTo(OBJECT_ID);
        assertThat(object.get("classId").asText()).isEqualTo(CLASS_ID);
        assertThat(object.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(object.at("/barcode/alternateText").asText())
                .as("the human-readable fallback is the bare token, as at the door")
                .isEqualTo(TOKEN);
    }

    // ── Google saying no ─────────────────────────────────────────────────────

    /**
     * A 5xx from Google degrades to a 503 on this one button and never a 500.
     * The distinction is the whole error contract: 503 says "try again", and the
     * frontend has copy for it.
     */
    @Test
    void a5xxFromGoogleIs503AndNotA500() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        mvc.perform(get(url(TOKEN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));
        server.verify();
    }

    /**
     * <b>The failure this feature will actually have in production, and the plan
     * never named it.</b> A service account that has not been added to the issuer
     * account answers 401/403, and a {@code DRAFT} class answers 400 — neither is
     * a 5xx, and the plan's error rule covered only 5xx. Built to the letter, all
     * three would throw {@code HttpClientErrorException} and reach the buyer as a
     * 500. They are 503 here, and the operator fault is named in the log.
     */
    @Test
    void a403FromGoogleIs503AndNotA500() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        mvc.perform(get(url(TOKEN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));
        server.verify();
    }

    /**
     * A class a human left in {@code DRAFT} cannot have objects attached, so the
     * object insert is never attempted — a partially-usable pass is worse than
     * none, and the log names the console fix rather than reporting the object.
     */
    @Test
    void aDraftClassIs503AndTheObjectIsNeverInserted() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withSuccess("{\"id\":\"" + CLASS_ID + "\",\"reviewStatus\":\"DRAFT\"}",
                        MediaType.APPLICATION_JSON));

        mvc.perform(get(url(TOKEN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));
        // No POST expectation exists, so an attempted object insert fails here.
        server.verify();
    }

    // ── metering ─────────────────────────────────────────────────────────────

    /**
     * The bucket name is the contract between this controller and
     * {@code RateLimitConfig}, where an unregistered name throws and the global
     * handler turns it into a 500. The save link is more expensive than the
     * pkpass — same DB reads, an RSA signature, plus up to three calls to Google
     * — and shares the pkpass's bucket deliberately.
     */
    @Test
    void everySaveLinkRequestConsumesTheWalletPassBucket() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectFullProvisioning();

        mvc.perform(get(url(TOKEN))).andExpect(status().isFound());

        assertThat(bucketsConsumed).containsExactly("wallet-pass");
    }

    /** Metered before the lookup, so token enumeration cannot spin the DB for free. */
    @Test
    void anUnknownTokenIsMeteredBeforeItIs404d() throws Exception {
        mvcWith(new GoogleWalletProperties())
                .perform(get(url("no-such-token")))
                .andExpect(status().isNotFound());

        assertThat(bucketsConsumed).containsExactly("wallet-pass");
    }

    // ── the transaction constraint, on the live path ─────────────────────────

    /**
     * <b>The other obligation carried forward from Task 6.</b>
     *
     * <p>{@link GoogleWalletProvisioner} refuses to open a socket while a database
     * transaction is active, because up to three 5s-timeout calls to Google would
     * otherwise hold a pooled JDBC connection for their whole duration on an
     * unauthenticated endpoint — a pool-exhaustion outage of the entire API,
     * caused by Google being slow, visible only under concurrency.
     * {@code GoogleWalletProvisionerTest} proves the guard bites; this proves the
     * guard is on <em>this</em> path and not bypassed by it.
     *
     * <p>The transaction is faked at the thread-local, which is exactly what
     * {@code @Transactional(readOnly = true)} on the pass service would produce.
     * Nothing reaches Google, and the buyer gets an honest 500 rather than a
     * quietly degraded API.
     *
     * <p><b>The resolved exception is asserted, not just the status.</b> First
     * draft of this test checked only for a 500 against an expectation-free mock
     * server — and stayed green when the guard was deleted, because the request
     * then went on to hit a mock server with no expectations and 500ed on
     * <em>that</em>. Same status code, entirely different reason, and the test
     * certified a guard that was no longer there. Naming the exception is what
     * makes it fail when it should.
     */
    @Test
    void theSaveLinkPathGoesThroughTheNoTransactionGuard() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var result = mvc.perform(get(url(TOKEN)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL"))
                    .andReturn();

            assertThat(result.getResolvedException())
                    .as("a 500 from anywhere else would look identical from the status line")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not run inside a database transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        server.verify();
    }

    /**
     * And the positive half: with no transaction open — which is what the endpoint
     * actually runs in, since neither the controller method nor
     * {@link GoogleWalletPassService} is annotated — the same request succeeds.
     */
    @Test
    void theSaveLinkPathRunsWithNoDatabaseTransactionOpen() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectFullProvisioning();

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        mvc.perform(get(url(TOKEN))).andExpect(status().isFound());
        server.verify();
    }

    // ── wiring ───────────────────────────────────────────────────────────────

    private static String url(String token) {
        return "/api/v1/public/tickets/" + token + "/google-wallet";
    }

    /** The redirect target's JWT, off a full happy-path run. */
    private String saveLinkJwt() throws Exception {
        MockMvc mvc = mvcWith(liveProperties());
        expectFullProvisioning();

        String location = mvc.perform(get(url(TOKEN)))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        server.verify();

        assertThat(location).startsWith(SAVE_PREFIX);
        return location.substring(SAVE_PREFIX.length());
    }

    private static GoogleWalletProperties liveProperties() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setEnabled(true);
        p.setIssuerId(ISSUER);
        p.setServiceAccountJsonBase64(KEYS.serviceAccountJsonBase64());
        p.setOrigins(List.of("https://app.imin.wtf"));
        return p;
    }

    private void expectToken() {
        server.expect(once(), requestTo(TOKEN_URL)).andRespond(withSuccess(
                "{\"access_token\":\"ya29.t\",\"expires_in\":3599}", MediaType.APPLICATION_JSON));
    }

    /** Token exchange, a class that does not exist yet, then both inserts. */
    private void expectFullProvisioning() {
        expectToken();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(CLASS_URL)).andRespond(withStatus(HttpStatus.OK));
        server.expect(once(), requestTo(OBJECT_URL))
                .andExpect(request ->
                        objectBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withStatus(HttpStatus.OK));
    }

    private MockMvc mvcWith(GoogleWalletProperties props) {
        TicketRepository tickets = mock(TicketRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        EventRepository events = mock(EventRepository.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);

        when(tickets.findByToken(TOKEN)).thenReturn(Optional.of(ticket(TOKEN, Ticket.STATE_ISSUED)));
        when(tickets.findByToken("no-such-token")).thenReturn(Optional.empty());
        when(tickets.findByToken(REFUNDED_TOKEN))
                .thenReturn(Optional.of(ticket(REFUNDED_TOKEN, Ticket.STATE_REFUNDED)));
        when(tickets.findByToken(REVOKED_TOKEN))
                .thenReturn(Optional.of(ticket(REVOKED_TOKEN, Ticket.STATE_REVOKED)));
        when(tickets.findByToken(REDEEMED_TOKEN))
                .thenReturn(Optional.of(ticket(REDEEMED_TOKEN, Ticket.STATE_REDEEMED)));

        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(orgs.findById(ORG_ID)).thenReturn(Optional.of(organization()));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order()));

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        GoogleWalletApiClient api = new GoogleWalletApiClient(props, builder.build());
        GoogleWalletProvisioner provisioner =
                new GoogleWalletProvisioner(props, api, new EmailProperties());
        // The production constructor, parsing the same base64 credential the
        // properties carry — not a hand-fed key. A credential that binds but does
        // not load is a real deployment state, and this is the constructor that
        // has to survive it.
        GoogleWalletJwtSigner signer = new GoogleWalletJwtSigner(props);

        GoogleWalletPassService google = new GoogleWalletPassService(
                props, provisioner, signer, tickets, events, orgs, qr);

        // Records rather than invents: a double that answers "fine" to any bucket
        // name is how an unregistered bucket ships green and 500s in production.
        RateLimiter recording = (bucket, key) -> bucketsConsumed.add(bucket);

        PublicTicketAssetController controller = new PublicTicketAssetController(
                tickets, qr, new QrImageRenderer(),
                // Real, and really signing: the pkpass barcode is one of the three
                // payloads the canonical-payload test compares.
                new AppleWalletPassService(appleProperties(), tickets, orders, events, orgs,
                        qr, new EmailProperties()),
                google, recording);

        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static QrPayloadSigner qrSigner() {
        TicketProperties p = new TicketProperties();
        p.setSigningSecret("google-endpoint-test-signing-secret");
        return new QrPayloadSigner(p);
    }

    private static AppleWalletProperties appleProperties() {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate("");
        AppleWalletProperties props = new AppleWalletProperties();
        props.setPassTypeId("pass.test.imin");
        props.setTeamId("TESTTEAMID");
        props.setCertP12Base64(bundle.p12Base64());
        props.setCertPassword(bundle.password());
        props.setWwdrPemBase64(bundle.wwdrPemBase64());
        return props;
    }

    private static Ticket ticket(String token, String state) {
        Ticket t = new Ticket();
        t.setToken(token);
        t.setOrderId(ORDER_ID);
        t.setEventId(EVENT_ID);
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setState(state);
        return t;
    }

    private static Event event() {
        Event e = new Event();
        e.setId(EVENT_ID);
        e.setOrgId(ORG_ID);
        e.setName("Vechirka");
        e.setStartsAt(OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant());
        e.setTimezone("Europe/Paris");
        e.setVenueName("Club Zenith");
        e.setVenueStreet("12 Rue de la Nuit");
        e.setVenuePostalCode("75011");
        e.setVenueCity("Paris");
        e.setVenueCountry("FR");
        return e;
    }

    private static Organization organization() {
        Organization o = new Organization();
        o.setId(ORG_ID);
        o.setName("Zenith Collective");
        return o;
    }

    private static Order order() {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setEventId(EVENT_ID);
        o.setOrgId(ORG_ID);
        return o;
    }

    // ── readers ──────────────────────────────────────────────────────────────

    /** Decoded out of the pixels, so the comparison is of payloads and not of PNG bytes. */
    private static String decodeQr(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        return new MultiFormatReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))))
                .getText();
    }

    private static String readZipEntry(byte[] zipBytes, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("no " + name + " in the archive");
    }
}
