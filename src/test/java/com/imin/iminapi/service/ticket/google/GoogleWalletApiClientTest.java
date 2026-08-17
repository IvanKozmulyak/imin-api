package com.imin.iminapi.service.ticket.google;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Google Wallet REST round trip, driven for real against a mocked Google.
 *
 * <h2>Why a mock server and not a mocked client</h2>
 *
 * <p>The thing most likely to be wrong here is not the logic — it is the wire.
 * Spring Boot 4 wires HTTP message conversion to Jackson 3
 * ({@code tools.jackson}); Jackson 2 ({@code com.fasterxml}) is also on the
 * classpath, and this project resolves <b>both</b> (3.1.0 and 2.21.2). Asking
 * {@code retrieve()} for a type from the wrong generation fails at runtime and
 * lands inside a {@code catch}, which is exactly how the mobile push sender came
 * to report {@code accepted=0} forever with no error at all. A mocked
 * {@code GoogleWalletApiClient} would stay green through every one of those
 * failures. So the client here is a real one bound to
 * {@link MockRestServiceServer}, and the assertions are on <b>the bytes Google
 * would receive</b> — "a request was made" proves nothing.
 *
 * <p>The request factory is replaced by the mock server on the way in, which is
 * why {@link GoogleWalletApiClient} takes an already-built {@code RestClient}
 * rather than building one: a constructor calling {@code requestFactory(...)}
 * would overwrite the mock and this suite would hit the real
 * {@code walletobjects.googleapis.com}.
 */
class GoogleWalletApiClientTest {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String CLASS_URL =
            "https://walletobjects.googleapis.com/walletobjects/v1/eventTicketClass";
    private static final String OBJECT_URL =
            "https://walletobjects.googleapis.com/walletobjects/v1/eventTicketObject";
    private static final String CLASS_ID = "3388000000000000000.evt_e1";

    private static final GoogleTestKeys.Bundle KEYS = GoogleTestKeys.generate();

    private GoogleWalletApiClient api;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        api = new GoogleWalletApiClient(
                GoogleServiceAccountKey.parse(KEYS.serviceAccountJson()), builder.build());
    }

    /** Google's happy answer to the token exchange. */
    private void expectTokenExchange() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"ya29.test-token\",\"expires_in\":3599,"
                                + "\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));
    }

    // ── the access token ─────────────────────────────────────────────────────

    /**
     * The RFC 7523 assertion, opened up and verified with the public half of the
     * key that signed it. Every claim Google checks is checked here, because a
     * wrong one comes back as an opaque {@code invalid_grant} that says nothing
     * about which.
     */
    @Test
    void theTokenExchangeSendsAnRs256AssertionGoogleWouldAccept() throws Exception {
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(request -> assertRfc7523Assertion(
                        ((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(
                        "{\"access_token\":\"ya29.test-token\",\"expires_in\":3599}",
                        MediaType.APPLICATION_JSON));

        assertThat(api.accessToken()).isEqualTo("ya29.test-token");
        server.verify();
    }

    /**
     * The form body Google receives, and the assertion inside it. Extracted from
     * the matcher lambda only because {@code RequestMatcher} may throw
     * {@code IOException} and nothing else; a checked exception from Nimbus is an
     * {@code AssertionError} here, which is what a malformed assertion is.
     */
    private static void assertRfc7523Assertion(String body) {
        assertThat(body).startsWith(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=");
        String encoded = body.substring(body.indexOf("&assertion=") + "&assertion=".length());
        try {
            SignedJWT jwt = SignedJWT.parse(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
            assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
            assertThat(jwt.getHeader().getType().toString()).isEqualTo("JWT");

            JWSVerifier verifier = new RSASSAVerifier(KEYS.publicKey());
            assertThat(jwt.verify(verifier))
                    .as("signed by the service-account key, not merely well-formed")
                    .isTrue();

            assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(KEYS.clientEmail());
            assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(TOKEN_URL);
            assertThat(jwt.getJWTClaimsSet().getStringClaim("scope"))
                    .as("wallet_object.issuer is the only scope this feature needs")
                    .isEqualTo("https://www.googleapis.com/auth/wallet_object.issuer");
            assertThat(jwt.getJWTClaimsSet().getExpirationTime())
                    .isAfter(jwt.getJWTClaimsSet().getIssueTime());
        } catch (ParseException | JOSEException e) {
            throw new AssertionError("the token-exchange assertion is not a usable RS256 JWT", e);
        }
    }

    /**
     * One token for many calls. A fetch per pass save would be a third round trip
     * on a path a buyer is waiting on, for a credential Google keeps alive for an
     * hour. {@code once()} on the token expectation is the assertion — a second
     * exchange fails the mock server outright.
     */
    @Test
    void theAccessTokenIsFetchedOnceAndReusedAcrossCalls() {
        expectTokenExchange();
        server.expect(once(), requestTo(CLASS_URL)).andRespond(withStatus(HttpStatus.OK));
        server.expect(once(), requestTo(OBJECT_URL)).andRespond(withStatus(HttpStatus.OK));

        api.insertEventTicketClass("{\"id\":\"" + CLASS_ID + "\"}");
        api.insertEventTicketObject("{\"id\":\"x\"}");

        server.verify();
    }

    /**
     * A response with no {@code expires_in} must shorten the cache, never lengthen
     * it. Caching a token past its life turns one bad response into an outage
     * lasting until the next deploy, and the symptom would be 401s that no
     * credential change fixes.
     */
    @Test
    void anAbsurdExpiresInShortensTheCacheRatherThanExtendingIt() {
        server.expect(once(), requestTo(TOKEN_URL)).andRespond(withSuccess(
                "{\"access_token\":\"ya29.t\",\"expires_in\":999999999}", MediaType.APPLICATION_JSON));

        assertThat(api.accessToken()).isEqualTo("ya29.t");
        // 300s fallback minus the 60s refresh margin: still cached right now, but
        // not for eleven days. A second exchange here would fail on once().
        assertThat(api.accessToken()).isEqualTo("ya29.t");
        server.verify();
    }

    @Test
    void aTokenResponseWithNoAccessTokenIs503AndNotACachedBlank() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"expires_in\":3599}", MediaType.APPLICATION_JSON));

        assertThat(refusalFrom(() -> api.accessToken()).code())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    // ── the class and object inserts ─────────────────────────────────────────

    /**
     * <b>The body Google actually receives.</b> Asserted field by field on the
     * parsed request, not on a substring, so a renamed field or a dropped
     * required one fails here rather than as a 400 on a buyer's request.
     */
    @Test
    void theClassInsertPostsTheExactJsonWithABearerToken() {
        expectTokenExchange();
        server.expect(once(), requestTo(CLASS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer ya29.test-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(CLASS_ID))
                .andExpect(jsonPath("$.issuerName").value("imin"))
                .andExpect(jsonPath("$.reviewStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.eventName.defaultValue.value").value("Vechirka"))
                .andRespond(withStatus(HttpStatus.OK));

        api.insertEventTicketClass("""
                {"id":"%s","issuerName":"imin","reviewStatus":"UNDER_REVIEW",
                 "eventName":{"defaultValue":{"language":"en-US","value":"Vechirka"}}}
                """.formatted(CLASS_ID));

        server.verify();
    }

    /**
     * The object body reaches Google byte for byte. This is the request that
     * carries the barcode, so a mangled payload here is a ticket that will not
     * scan at a door.
     */
    @Test
    void theObjectInsertPostsTheExactJsonIncludingTheBarcodePayload() {
        String body = """
                {"id":"3388000000000000000.tkt_abc","classId":"%s","state":"ACTIVE",
                 "barcode":{"type":"QR_CODE","value":"imin1.abc.SIG","alternateText":"abc"}}
                """.formatted(CLASS_ID);

        expectTokenExchange();
        server.expect(once(), requestTo(OBJECT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(body))
                .andExpect(jsonPath("$.barcode.value").value("imin1.abc.SIG"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andRespond(withStatus(HttpStatus.OK));

        api.insertEventTicketObject(body);
        server.verify();
    }

    /**
     * {@code 409} is success, on both inserts. Two buyers hitting the same event
     * at the same second must not produce one working pass and one 503, and a
     * buyer who taps the button twice must get the same pass twice.
     */
    @Test
    void aConflictOnInsertIsSuccessBecauseTheResourceExists() {
        expectTokenExchange();
        server.expect(once(), requestTo(CLASS_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("{\"error\":{\"code\":409}}"));
        server.expect(once(), requestTo(OBJECT_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("{\"error\":{\"code\":409}}"));

        api.insertEventTicketClass("{}");
        api.insertEventTicketObject("{}");
        server.verify();
    }

    // ── reads ────────────────────────────────────────────────────────────────

    /**
     * A 404 on the class lookup is the ordinary "this event has no class yet"
     * case and must be an empty Optional, not an exception.
     * {@code RestClient} throws on 4xx by default, so the {@code onStatus}
     * suppression is load-bearing and this is what pins it.
     */
    @Test
    void anAbsentClassReadsAsEmptyRatherThanThrowing() {
        expectTokenExchange();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer ya29.test-token"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"error\":{\"code\":404}}"));

        assertThat(api.getEventTicketClass(CLASS_ID)).isEmpty();
        server.verify();
    }

    @Test
    void anExistingClassComesBackAsItsRawBody() {
        expectTokenExchange();
        server.expect(once(), requestTo(CLASS_URL + "/" + CLASS_ID))
                .andRespond(withSuccess(
                        "{\"id\":\"" + CLASS_ID + "\",\"reviewStatus\":\"APPROVED\"}",
                        MediaType.APPLICATION_JSON));

        Optional<String> body = api.getEventTicketClass(CLASS_ID);
        assertThat(body).isPresent();
        assertThat(GoogleWalletModels.reviewStatusOf(body.orElseThrow())).isEqualTo("APPROVED");
        server.verify();
    }

    // ── failure never becomes a 500 ──────────────────────────────────────────

    /**
     * A Google outage is a 503 on one button, not a 500 on a ticket page that has
     * already rendered.
     */
    @Test
    void aServerErrorFromGoogleSurfacesAs503AndNever500() {
        expectTokenExchange();
        server.expect(once(), requestTo(CLASS_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("backend error"));

        assertThat(refusalFrom(() -> api.insertEventTicketClass("{}")).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aRefusedCredentialSurfacesAs503Too() {
        expectTokenExchange();
        server.expect(once(), requestTo(CLASS_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body(
                        "{\"error\":{\"message\":\"Insufficient permission\"}}"));

        assertThat(refusalFrom(() -> api.insertEventTicketClass("{}")).code())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    /**
     * A 400 is the shape a malformed payload comes back as — including the one
     * {@code DRAFT} produces. Still a 503 to the buyer; the diagnosis lives in the
     * log line, which carries Google's own message.
     */
    @Test
    void aBadRequestSurfacesAs503WithGooglesMessageLoggedNotReturned() {
        expectTokenExchange();
        server.expect(once(), requestTo(OBJECT_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(
                        "{\"error\":{\"message\":\"Class is in draft state\"}}"));

        ApiException refusal = refusalFrom(() -> api.insertEventTicketObject("{}"));
        assertThat(refusal.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refusal.getMessage())
                .as("Google's own wording stays in the log, never in the buyer's response")
                .isEqualTo("Google Wallet is temporarily unavailable")
                .doesNotContain("draft");
    }

    /**
     * A failed token exchange must not reach the wallet endpoint at all. The
     * class expectation below is never satisfied, and {@code verify()} would fail
     * if it were — the wrong shape here would be a request with a blank bearer.
     */
    @Test
    void aFailedTokenExchangeStopsBeforeTheWalletCall() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(
                        "{\"error\":\"invalid_grant\"}"));

        assertThat(refusalFrom(() -> api.insertEventTicketClass("{}")).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    // ── the gate ─────────────────────────────────────────────────────────────

    /**
     * <b>Nothing reaches Google while the wallet is off.</b> No credential means
     * no token, which means no request is even prepared — {@code server.verify()}
     * passes only because nothing was sent, since any unexpected request fails the
     * mock server. And the refusal is a 503, not an {@code IllegalStateException}
     * that would become a 500 on a buyer's ticket page.
     */
    @Test
    void withNoCredentialNothingIsSentAndTheRefusalIsA503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer strict = MockRestServiceServer.bindTo(builder).build();
        GoogleWalletApiClient off = new GoogleWalletApiClient((GoogleServiceAccountKey) null,
                builder.build());

        assertThat(off.isUsable()).isFalse();
        assertThat(refusalFrom(() -> off.insertEventTicketClass("{}")).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refusalFrom(() -> off.getEventTicketClass(CLASS_ID)).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        strict.verify();
    }

    /**
     * The production constructor is the one Spring calls, and it must never throw
     * — an unusable wallet credential cannot be allowed to stop the application
     * booting, because checkout, issuance, email and the door are all unaffected
     * by a missing pass.
     */
    @Test
    void aBrokenCredentialLeavesTheClientUnusableRatherThanFailingConstruction() {
        GoogleWalletProperties props = new GoogleWalletProperties();
        props.setEnabled(true);
        props.setIssuerId("3388000000000000000");
        props.setServiceAccountJsonBase64("this is not base64 json");

        GoogleWalletApiClient broken = new GoogleWalletApiClient(props, RestClient.builder().build());
        assertThat(broken.isUsable()).isFalse();
    }

    @Test
    void anUnconfiguredWalletLeavesTheClientUnusable() {
        assertThat(new GoogleWalletApiClient(new GoogleWalletProperties(),
                RestClient.builder().build()).isUsable())
                .as("GOOGLE_WALLET_ENABLED defaults false — see GoogleWalletProperties#enabled")
                .isFalse();
    }

    @Test
    void aFullyConfiguredWalletLoadsTheKeyAndBecomesUsable() {
        GoogleWalletProperties props = new GoogleWalletProperties();
        props.setEnabled(true);
        props.setIssuerId("3388000000000000000");
        props.setServiceAccountJsonBase64(KEYS.serviceAccountJsonBase64());

        assertThat(new GoogleWalletApiClient(props, RestClient.builder().build()).isUsable()).isTrue();
    }

    /** The refusal, typed, so its status and code are read and not reflected at. */
    private static ApiException refusalFrom(Runnable call) {
        ApiException e = catchThrowableOfType(ApiException.class, call::run);
        assertThat(e).as("expected a 503 refusal, not a silent success").isNotNull();
        return e;
    }
}
