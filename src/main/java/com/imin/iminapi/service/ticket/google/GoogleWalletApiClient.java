package com.imin.iminapi.service.ticket.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The HTTP half of Google Wallet: an access token minted from the service
 * account, and the three calls this feature makes against
 * {@code walletobjects.googleapis.com}.
 *
 * <h2>No Google client library</h2>
 *
 * <p>{@code google-api-client} plus {@code google-auth-library} would add a
 * large transitive tree to do RS256 over a JSON body and issue three HTTPS
 * requests. Nimbus (already on the classpath via
 * {@code spring-security-oauth2-jose}, already used by
 * {@link GoogleWalletJwtSigner}) and {@link RestClient} already do both.
 *
 * <h2>Nothing typed crosses a message converter</h2>
 *
 * <p>Every request body leaves here as a {@code String} and every response
 * arrives as a {@code String} — including the token exchange, whose form body is
 * URL-encoded by hand rather than handed over as a {@code MultiValueMap}. So the
 * only converter that ever runs is {@code StringHttpMessageConverter}, which has
 * no Jackson in it at all. Boot 4 wires HTTP conversion to Jackson 3
 * ({@code tools.jackson}) while Jackson 2 ({@code com.fasterxml}) is also on the
 * classpath — {@code mvn dependency:build-classpath} resolves 3.1.0 and 2.21.2
 * side by side — and asking {@code retrieve()} for a type from the wrong
 * generation fails at <em>runtime</em>, inside a {@code catch}. That is the exact
 * defect that made the mobile push sender report {@code accepted=0} forever with
 * no error. {@code GoogleWalletApiClientTest} drives the whole round trip
 * through {@code MockRestServiceServer} so the property is proven and not
 * asserted in a comment.
 *
 * <h2>Failure policy</h2>
 *
 * <p>Every failure — transport, auth, 4xx, 5xx — leaves here as a
 * {@code 503 UPSTREAM_UNAVAILABLE}. Never a 500: a wallet button is a decoration
 * on a ticket page that has already rendered, and a Google outage must read as
 * "this button is temporarily unavailable", not as an imin fault. Google's own
 * message is logged; the access token and the save JWT never are.
 */
@Component
public class GoogleWalletApiClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleWalletApiClient.class);

    static final String BASE_URL = "https://walletobjects.googleapis.com/walletobjects/v1";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    /** The only scope this feature needs, and the only one the key should hold. */
    static final String SCOPE = "https://www.googleapis.com/auth/wallet_object.issuer";

    private static final String JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    /** How long the signed assertion is good for. Google's documented maximum is one hour. */
    private static final Duration ASSERTION_TTL = Duration.ofHours(1);

    /**
     * Refresh this far before the token actually expires, so a token that is
     * valid when we check is still valid when Google reads it.
     */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    /** What an absent or absurd {@code expires_in} is worth. Short, never long. */
    private static final long FALLBACK_TTL_SECONDS = 300L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;

    /** Null when Google Wallet is off, or when the configured credential will not load. */
    private final GoogleServiceAccountKey key;

    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;

    /**
     * The credential is parsed here as well as in {@link GoogleWalletJwtSigner}
     * — the two components need it independently and neither should own the
     * other. Deliberately silent about a parse failure: the signer already logs
     * that exact condition as an {@code ERROR} at boot, and a second identical
     * line for one fault is how operators learn to skim past both. Never throws;
     * an unusable wallet credential must not stop the application booting.
     *
     * <p><b>{@code @Autowired} is load-bearing.</b> This class has a second,
     * package-private constructor for tests, and Spring's implicit
     * single-constructor rule only applies when there is exactly one. Without the
     * annotation the container looks for a no-arg constructor, fails to find one,
     * and the <em>whole application context</em> stops starting — which is not a
     * wallet-shaped failure, it is every Spring test in the repository erroring
     * at once. {@code GoogleWalletContextTest} boots the real context so a future
     * third constructor cannot reintroduce that quietly.
     */
    @Autowired
    public GoogleWalletApiClient(GoogleWalletProperties props,
                                 @Qualifier("googleWalletRestClient") RestClient http) {
        this.http = http;
        GoogleServiceAccountKey parsed = null;
        if (props.fullyConfigured()) {
            try {
                parsed = GoogleServiceAccountKey.parseBase64(props.getServiceAccountJsonBase64());
            } catch (RuntimeException e) {
                parsed = null;
            }
        }
        this.key = parsed;
    }

    /** Direct construction from an already-parsed key, for tests. */
    GoogleWalletApiClient(GoogleServiceAccountKey key, RestClient http) {
        this.key = key;
        this.http = http;
    }

    /**
     * True when a call can be made at all. False covers every closed state:
     * disabled, unconfigured, and configured-but-broken.
     */
    public boolean isUsable() {
        return key != null;
    }

    // ── the three calls ──────────────────────────────────────────────────────

    /**
     * The class as Google holds it, or empty when it does not exist.
     *
     * <p>This is the only reason the class path does a read before a write: a
     * {@code 409} on insert would tell us the class exists but not what
     * {@code reviewStatus} it carries, and a {@code DRAFT} class is unusable in
     * a way that only shows up as an opaque failure on the <em>object</em>
     * insert. See {@link GoogleWalletProvisioner}.
     */
    public Optional<String> getEventTicketClass(String classId) {
        ResponseEntity<String> res = send(HttpMethod.GET, "/eventTicketClass/" + classId, null);
        if (res.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        require2xx(res, "read the event ticket class");
        return Optional.ofNullable(res.getBody());
    }

    /** Insert a class. A {@code 409} means another replica won the race, which is success. */
    public void insertEventTicketClass(String json) {
        insert("/eventTicketClass", json, "create the event ticket class");
    }

    /** Insert an object. A {@code 409} means the buyer already saved this ticket once. */
    public void insertEventTicketObject(String json) {
        insert("/eventTicketObject", json, "create the event ticket object");
    }

    private void insert(String path, String json, String what) {
        ResponseEntity<String> res = send(HttpMethod.POST, path, json);
        if (res.getStatusCode().value() == 409) {
            return;
        }
        require2xx(res, what);
    }

    // ── transport ────────────────────────────────────────────────────────────

    private ResponseEntity<String> send(HttpMethod method, String path, String body) {
        String token = accessToken();
        // URI, not a String: RestClient treats a String uri as a template and
        // expands it, and the class path carries a ticket token straight from
        // the database. Nothing in base64url can open a brace today; a URI does
        // not need that to keep being true.
        URI uri = URI.create(BASE_URL + path);
        try {
            RestClient.RequestBodySpec spec = http.method(method)
                    .uri(uri)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE);
            if (body != null) {
                return spec.contentType(MediaType.APPLICATION_JSON)
                        .body(body).retrieve().onStatus(any(), noThrow()).toEntity(String.class);
            }
            return spec.retrieve().onStatus(any(), noThrow()).toEntity(String.class);
        } catch (RestClientException e) {
            // Transport: connect refused, read timeout, DNS. Never carries a
            // status code, so it cannot go through require2xx.
            log.warn("[wallet] Google Wallet {} {} failed in transport — {}",
                    method, path, e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    /**
     * Turn anything that is not a success into a {@code 503}, loudly enough that
     * the cause is in the log even though the buyer only ever sees the 503.
     *
     * <p>{@code 401} and {@code 403} are called out separately because they mean
     * the credential or its scope is wrong — an operator fault that no amount of
     * retrying fixes, and one that would otherwise look identical to Google
     * being down.
     */
    private void require2xx(ResponseEntity<String> res, String what) {
        HttpStatusCode status = res.getStatusCode();
        if (status.is2xxSuccessful()) {
            return;
        }
        String detail = res.getBody() == null ? "" : res.getBody();
        if (status.value() == 401 || status.value() == 403) {
            log.error("[wallet] Google Wallet refused the service account ({}) trying to {} — check "
                            + "GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64, that the account is added to "
                            + "the issuer in the Google Pay & Wallet Console, and that it holds {}. {}",
                    status.value(), what, SCOPE, detail);
        } else {
            log.error("[wallet] Google Wallet returned {} trying to {}. {}",
                    status.value(), what, detail);
        }
        throw unavailable();
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                "Google Wallet is temporarily unavailable");
    }

    private static Predicate<HttpStatusCode> any() {
        return s -> true;
    }

    /**
     * Suppresses {@code RestClient}'s default 4xx/5xx exceptions so the status
     * can be read. Without this a {@code 404} on the class lookup — the entirely
     * normal "this event has no class yet" case — would arrive as an exception.
     */
    private static RestClient.ResponseSpec.ErrorHandler noThrow() {
        return (request, response) -> { };
    }

    // ── OAuth ────────────────────────────────────────────────────────────────

    /**
     * A cached bearer token for the service account, minted through the RFC 7523
     * JWT-bearer flow: sign an assertion with the service-account key, exchange
     * it at {@code oauth2.googleapis.com/token}.
     *
     * <p>Cached until shortly before expiry. A token fetch per pass save would
     * be a third round trip on a path a buyer is waiting on, for a token Google
     * keeps valid for an hour.
     */
    synchronized String accessToken() {
        GoogleServiceAccountKey k = key;
        if (k == null) {
            // Unreachable through the provisioner, which checks isUsable() before
            // it builds anything. It is still a 503 and not an IllegalState:
            // "nothing reaches Google while the wallet is off" must hold even if
            // a future caller forgets, and it must hold without ever turning a
            // decoration on a ticket page into a 500 the buyer sees.
            log.error("[wallet] a Google Wallet call was attempted with no usable credential — "
                    + "the caller skipped the gate; this is a bug, not an outage");
            throw unavailable();
        }
        String cached = accessToken;
        if (cached != null && Instant.now().isBefore(accessTokenExpiry.minus(REFRESH_MARGIN))) {
            return cached;
        }

        Instant now = Instant.now();
        SignedJWT assertion = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder()
                        .issuer(k.clientEmail())
                        .audience(TOKEN_URL)
                        .claim("scope", SCOPE)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(ASSERTION_TTL)))
                        .build());
        try {
            assertion.sign(new RSASSASigner(k.privateKey()));
        } catch (Exception e) {
            log.error("[wallet] could not sign the Google access-token assertion ({})",
                    e.getClass().getSimpleName());
            throw unavailable();
        }

        // Encoded by hand and sent as a String. A MultiValueMap would be handed
        // to whichever form converter the auto-configured converter list happens
        // to hold; see the class Javadoc.
        String form = "grant_type=" + urlEncode(JWT_BEARER)
                + "&assertion=" + urlEncode(assertion.serialize());

        ResponseEntity<String> res;
        try {
            res = http.post()
                    .uri(URI.create(TOKEN_URL))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .body(form)
                    .retrieve()
                    .onStatus(any(), noThrow())
                    .toEntity(String.class);
        } catch (RestClientException e) {
            log.warn("[wallet] Google token exchange failed in transport — {}",
                    e.getClass().getSimpleName());
            throw unavailable();
        }
        require2xx(res, "exchange the service-account assertion for an access token");

        String value;
        long expiresIn;
        try {
            JsonNode body = MAPPER.readTree(res.getBody() == null ? "" : res.getBody());
            value = body.path("access_token").asText(null);
            expiresIn = body.path("expires_in").asLong(0L);
        } catch (Exception e) {
            log.error("[wallet] Google token response was not readable JSON ({})",
                    e.getClass().getSimpleName());
            throw unavailable();
        }
        if (value == null || value.isBlank()) {
            // No echo of the body: it is the response that carries access tokens.
            log.error("[wallet] Google token response carried no access_token");
            throw unavailable();
        }
        // A missing or absurd expires_in must shorten the cache, never lengthen
        // it: caching a token past its life turns one bad response into an
        // outage that lasts until the next deploy.
        long ttl = (expiresIn > 0 && expiresIn <= 3600) ? expiresIn : FALLBACK_TTL_SECONDS;
        this.accessToken = value;
        this.accessTokenExpiry = now.plusSeconds(ttl);
        return value;
    }

    private static String urlEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
