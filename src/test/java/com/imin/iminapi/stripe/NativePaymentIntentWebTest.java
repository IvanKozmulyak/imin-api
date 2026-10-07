package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutConsent;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.stripe.StripeClient;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.PaymentIntentService;
import com.stripe.service.checkout.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both public checkout endpoints over the real services: validation stops a request before any hold or Stripe call,
 * the consent evidence reaches the Stripe metadata, and an Idempotency-Key retry takes no second hold.
 */
@IminIntegrationTest
class NativePaymentIntentWebTest {

    private static final String LABEL = "Email me about Arty Farty's events.";
    private static final String VERSION = "checkout-org-named-2026-09";

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired StripeClient stripeClient;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private UUID orgId;
    private UUID eventId;
    private UUID tierId;
    private String accountId;
    private PaymentIntentService paymentIntents;
    private SessionService sessions;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgId = org.getId();
        User owner = fx.owner(org);
        accountId = "acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        // A fresh ACTIVE mirror, stamped with JVM time (not DB now()) so DB clock drift cannot make it stale.
        jdbc.update("UPDATE organizations SET stripe_account_id = ?, stripe_connect_state = 'ACTIVE', "
                + "stripe_payouts_enabled = true, stripe_details_submitted = true, "
                + "stripe_connect_status_updated_at = ? WHERE id = ?",
                accountId, java.sql.Timestamp.from(clock.instant()), orgId);
        Event event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(30)));
        eventId = event.getId();
        jdbc.update("UPDATE events SET published_at = now() WHERE id = ?", eventId);
        tierId = fx.tier(event, 2500, 50).getId();
        // updatable=false columns, written the way the sync writes them.
        jdbc.update("UPDATE ticket_tiers SET stripe_product_id = ?, stripe_price_id = ? WHERE id = ?",
                "prod_" + tierId, "price_" + tierId, tierId);

        paymentIntents = mock(PaymentIntentService.class);
        sessions = mock(SessionService.class);
        CheckoutService checkout = mock(CheckoutService.class);
        when(stripeClient.paymentIntents()).thenReturn(paymentIntents);
        when(stripeClient.checkout()).thenReturn(checkout);
        when(checkout.sessions()).thenReturn(sessions);
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, List.of(orgId));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("payment-intent without tierId", "payment-intent", "{\"quantity\":1}"),
                Arguments.of("payment-intent quantity 11", "payment-intent", "{\"tierId\":\"%s\",\"quantity\":11}"),
                Arguments.of("payment-intent textVersion 33 chars", "payment-intent", consentBody("v".repeat(33), false)),
                Arguments.of("hosted textVersion 33 chars", "checkout", consentBody("v".repeat(33), false)));
    }

    /** Validation runs before the service: an out-of-policy body takes no hold and never reaches Stripe. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidBody_is400_beforeAnyHoldOrStripeCall(String name, String path, String body) throws Exception {
        mvc.perform(post(path(path)).contentType(MediaType.APPLICATION_JSON).content(body.formatted(tierId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").exists());

        verifyNoInteractions(stripeClient);
        assertThat(reserved()).isZero();
        assertThat(holds()).isZero();
    }

    @Test
    void paymentIntent_returnsTheClientSecretShape_andNeverCachesIt() throws Exception {
        PaymentIntent pi = intent();
        when(paymentIntents.create(any(PaymentIntentCreateParams.class))).thenReturn(pi);

        // 2 x 2500 = 5000; fee 5% of 5000 = 250 plus 2 x 99 = 448; charged 5448.
        mvc.perform(paymentIntent("{\"tierId\":\"" + tierId + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").value(pi.getClientSecret()))
                .andExpect(jsonPath("$.paymentIntentId").value(pi.getId()))
                .andExpect(jsonPath("$.amountMinor").value(5448))
                .andExpect(jsonPath("$.feeMinor").value(448))
                .andExpect(jsonPath("$.currency").value("eur"))
                // A client secret is a bearer credential for one payment; never in a shared cache.
                .andExpect(header().string("Cache-Control", "private, no-store"));

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(paymentIntents).create(params.capture());
        assertThat(params.getValue().getAmount()).isEqualTo(5448L);
        assertThat(params.getValue().getApplicationFeeAmount()).isEqualTo(448L);
        assertThat(params.getValue().getTransferData().getDestination()).isEqualTo(accountId);
    }

    static Stream<Arguments> consentCases() {
        return Stream.of(
                Arguments.of("hosted with version", "checkout", VERSION, false),
                Arguments.of("hosted without version", "checkout", null, false),
                Arguments.of("native with version and accepted terms", "payment-intent", VERSION, true));
    }

    /** The Art. 7 proof, its text version and terms acceptance travel from the body into the Stripe metadata. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("consentCases")
    void consentEvidence_reachesTheStripeMetadata(String name, String path, String version, boolean acceptedTerms)
            throws Exception {
        when(paymentIntents.create(any(PaymentIntentCreateParams.class))).thenReturn(intent());
        Session session = new Session();
        session.setId("cs_" + UUID.randomUUID());
        session.setUrl("https://checkout.stripe.test/" + session.getId());
        when(sessions.create(any(SessionCreateParams.class))).thenReturn(session);

        var result = mvc.perform(post(path(path)).contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody(version, acceptedTerms).formatted(tierId)))
                .andExpect(status().isOk());

        Map<String, String> metadata;
        if ("checkout".equals(path)) {
            // The hosted response shape the buyer site branches on.
            result.andExpect(jsonPath("$.kind").value("stripe"))
                    .andExpect(jsonPath("$.sessionId").value(session.getId()))
                    .andExpect(jsonPath("$.url").value(session.getUrl()))
                    .andExpect(jsonPath("$.orderToken").doesNotExist());
            ArgumentCaptor<SessionCreateParams> params = ArgumentCaptor.forClass(SessionCreateParams.class);
            verify(sessions).create(params.capture());
            metadata = params.getValue().getMetadata();
        } else {
            ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
            verify(paymentIntents).create(params.capture());
            metadata = params.getValue().getMetadata();
        }
        assertThat(metadata).containsEntry(CheckoutConsent.META_MARKETING_PROOF, LABEL);
        if (version == null) {
            assertThat(metadata).doesNotContainKey(CheckoutConsent.META_MARKETING_TEXT_VERSION);
        } else {
            assertThat(metadata).containsEntry(CheckoutConsent.META_MARKETING_TEXT_VERSION, version);
        }
        if (acceptedTerms) {
            assertThat(metadata).containsEntry(CheckoutConsent.META_ACCEPTED_TERMS, "true");
        } else {
            assertThat(metadata).doesNotContainKey(CheckoutConsent.META_ACCEPTED_TERMS);
        }
    }

    /** Bound but dropped is the failure that matters: every native retry would take a second hold and intent. */
    @Test
    void sameIdempotencyKeyTwice_createsOnePaymentIntent_andOneHold() throws Exception {
        PaymentIntent pi = intent();
        when(paymentIntents.create(any(PaymentIntentCreateParams.class))).thenReturn(pi);
        when(paymentIntents.retrieve(pi.getId())).thenReturn(pi);
        String key = "retry-" + UUID.randomUUID();
        String body = "{\"tierId\":\"" + tierId + "\",\"quantity\":2}";

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(paymentIntent(body).header("Idempotency-Key", key))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentIntentId").value(pi.getId()))
                    .andExpect(jsonPath("$.amountMinor").value(5448));
        }

        verify(paymentIntents, times(1)).create(any(PaymentIntentCreateParams.class));
        assertThat(holds()).isEqualTo(1);
        assertThat(reserved()).isEqualTo(2);
    }

    /** The web sends no header and must keep working unchanged. */
    @Test
    void noIdempotencyKey_isStillAValidRequest() throws Exception {
        when(paymentIntents.create(any(PaymentIntentCreateParams.class))).thenReturn(intent());

        mvc.perform(paymentIntent("{\"tierId\":\"" + tierId + "\",\"quantity\":2}"))
                .andExpect(status().isOk());

        verify(paymentIntents, times(1)).create(any(PaymentIntentCreateParams.class));
        assertThat(holds()).isEqualTo(1);
    }

    private static String consentBody(String version, boolean acceptedTerms) {
        return "{\"tierId\":\"%s\",\"quantity\":1,\"marketingOptIn\":true,"
                + "\"marketingOptInProofText\":\"" + LABEL + "\""
                + (acceptedTerms ? ",\"acceptedTerms\":true" : "")
                + (version == null ? "" : ",\"marketingOptInTextVersion\":\"" + version + "\"") + "}";
    }

    private String path(String endpoint) {
        return "/api/v1/public/events/" + eventId + "/" + endpoint;
    }

    private MockHttpServletRequestBuilder paymentIntent(String body) {
        return post(path("payment-intent")).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static PaymentIntent intent() {
        PaymentIntent pi = new PaymentIntent();
        pi.setId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        pi.setClientSecret(pi.getId() + "_secret_test");
        pi.setAmount(5448L);
        pi.setApplicationFeeAmount(448L);
        pi.setCurrency("eur");
        return pi;
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT reserved FROM ticket_tiers WHERE id = ?", Integer.class, tierId);
    }

    private int holds() {
        return jdbc.queryForObject("SELECT count(*) FROM ticket_reservations WHERE tier_id = ?", Integer.class, tierId);
    }
}
