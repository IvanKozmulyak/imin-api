package com.imin.iminapi.marketing;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.MembershipProjector;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.service.event.FreeCheckoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checkout email-marketing opt-in: the buy-page checkbox flows to
 * orders.marketing_opt_in (V61, free path here; the paid path is metadata-mapped like
 * ads_consent) and the AudienceOrderProjector turns it into a channel='email' consent
 * proof that makes the membership SendGate-sendable.
 *
 * <p>A tick with the sentence the buyer read is {@code explicit}; without it nothing is recorded.
 * The free path also persists {@code orders.ads_consent}, without which the CAPI outbox never fires.
 */
@IminIntegrationTest
class MarketingOptInWriteTest {

    @Autowired FreeCheckoutService freeCheckout;
    @Autowired AudienceOrderProjector projector;
    @Autowired ConsentService consentService;
    @Autowired SendGateService sendGate;
    @Autowired OrderRepository orders;
    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanLogic planLogic;
    @Autowired PlatformTransactionManager txManager;
    @Autowired com.imin.iminapi.audience.repository.ErasedAddressRepository erasedAddresses;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired MembershipProjector membershipProjector;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired @Qualifier("taskExecutor") Executor asyncExecutor;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;
    @Autowired @Qualifier("ticketEmailExecutor") Executor ticketEmailExecutor;

    private static final String PROOF = "Email me about similar events. Unsubscribe anytime.";

    private Event event;
    private TicketTier freeTier;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        // The consent-label rows below name this org.
        org.setName("OptIn Org");
        org = orgs.save(org);
        orgId = org.getId();
        User owner = fx.owner(org);

        event = new Event();
        event.setOrgId(orgId);
        event.setName("OptIn Fest");
        event.setSlug("optin-event-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setPublishedAt(Instant.now().minusSeconds(3600));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        freeTier = new TicketTier();
        freeTier.setEventId(event.getId());
        freeTier.setName("Free GA");
        freeTier.setPriceMinor(0);
        freeTier.setQuantity(100);
        freeTier.setReserved(0);
        freeTier.setSold(0);
        freeTier.setEnabled(true);
        freeTier = tiers.save(freeTier);
    }

    @AfterEach
    void tearDown() {
        // The AFTER_COMMIT async projectors and ticket email can still be reading the order; finish them first.
        drainAsync();
        OrgRows.delete(jdbc, List.of(orgId));
    }

    private void drainAsync() {
        AsyncDrain.drain(asyncExecutor);
        // The membership commit on that pool queues a fan-feature recompute on its own pool.
        AsyncDrain.drain(fanFeatureExecutor);
        AsyncDrain.drain(ticketEmailExecutor);
    }

    // Consumers are keyed by email across orgs, so each test's addresses are unique.
    private final java.util.Map<String, String> addresses = new java.util.HashMap<>();

    private String addr(String tag) {
        return addresses.computeIfAbsent(tag, fx::email);
    }

    private com.imin.iminapi.audience.model.Membership membershipFor(String email) {
        var consumer = consumers.findByNormalizedEmail(email).orElseThrow();
        return memberships.findByOrgIdAndConsumerId(orgId, consumer.getConsumerId()).orElseThrow();
    }

    @Test
    void freeCheckout_persistsMarketingOptInFlag() {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, addr("optin-buyer"), null, false,
                /* marketingOptIn */ true, CheckoutAttribution.NONE, null);
        Order persisted = orders.findByToken(created.getToken()).orElseThrow();
        assertThat(persisted.isMarketingOptIn()).isTrue();
    }

    /**
     * V62: the free path stamps the landing utm_* + anon_id inline (the paid read-back is PaidCheckoutServiceTest's).
     * Untagged stays null, never empty; buyer input is untrusted: blank collapses to null, over-long is capped.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("attributions")
    void freeCheckout_persistsUtmAttribution(String label, CheckoutAttribution in, String source, String medium,
                                             String campaign, String anonId) {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, fx.email("utm"), null, false, true, in, null);

        Order persisted = orders.findByToken(created.getToken()).orElseThrow();
        assertThat(persisted.getUtmSource()).isEqualTo(source);
        assertThat(persisted.getUtmMedium()).isEqualTo(medium);
        assertThat(persisted.getUtmCampaign()).isEqualTo(campaign);
        assertThat(persisted.getAnonId()).isEqualTo(anonId);
    }

    static Stream<Arguments> attributions() {
        String campaignId = "8b0f3c1e-2a7d-4c55-9e3a-6f1d2b4c8a90";
        return Stream.of(
                Arguments.of("tagged", new CheckoutAttribution("imin", "email", campaignId, "anon-free-1"),
                        "imin", "email", campaignId, "anon-free-1"),
                Arguments.of("organic", CheckoutAttribution.NONE, null, null, null, null),
                Arguments.of("hostile", new CheckoutAttribution("   ", "  email  ", "x".repeat(500), "y".repeat(200)),
                        null, "email", "x".repeat(128), "y".repeat(64)));
    }

    /**
     * W1.G/V78: the free path is the only place the buyer's language is stamped, normalized at the write site.
     * Unsupported or absent ⇒ null, i.e. "no preference" ⇒ English emails.
     */
    @ParameterizedTest(name = "[{0}] -> {1}")
    @CsvSource(nullValues = "NULL", value = {"'  ES  ', es", "klingon, NULL", "NULL, NULL"})
    void freeCheckout_persistsBuyerLocale_normalized(String locale, String expected) {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, fx.email("locale"), null, false, true,
                CheckoutAttribution.NONE, locale);

        assertThat(orders.findByToken(created.getToken()).orElseThrow().getBuyerLocale()).isEqualTo(expected);
    }

    /** V60: the ads-consent flag from the buyer's cookie state is persisted; DEFAULT false holds when absent. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void freeCheckout_persistsAdsConsentFlag(boolean adsConsent) {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, fx.email("ads"), null, adsConsent, /* marketingOptIn */ false,
                CheckoutAttribution.NONE, null);

        assertThat(orders.findByToken(created.getToken()).orElseThrow().isAdsConsent()).isEqualTo(adsConsent);
    }

    @Test
    void projector_withoutOptIn_leavesConsentUntouched() {
        projector.upsertMembership(orgId, addr("no-optin"), addr("no-optin"),
                null, false, false, null);

        var m = membershipFor(addr("no-optin"));
        assertThat(m.getConsentStatus()).isNotEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isNull();
    }

    /**
     * UNSUBSCRIBED BEATS A LATER OPT-IN, ALWAYS. A buyer who opted out and later buys another
     * ticket is not resubscribed by the purchase, even with the box ticked and proof sent.
     */
    @Test
    void projector_neverResubscribesAnUnsubscribedMember_evenWithCheckoutOptIn() {
        // Seed a member and unsubscribe them.
        projector.upsertMembership(orgId, addr("gone"), addr("gone"),
                null, false, true, null, PROOF);
        var m = membershipFor(addr("gone"));
        consentService.unsubscribe(orgId, m.getMembershipId(), "user-request",
                ConsentOrigin.DATA_SUBJECT, null);
        assertThat(membershipFor(addr("gone")).getConsentStatus()).isEqualTo("unsubscribed");
        long proofsAfterUnsub = consentRecords
                .findByMembershipId(m.getMembershipId()).size();

        // They buy again and tick the box, with the proof sentence.
        projector.upsertMembership(orgId, addr("gone"), addr("gone"),
                null, false, /* emailOptIn */ true, UUID.randomUUID(), PROOF);

        // Still unsubscribed, still no lawful basis, and no new consent proof was written.
        var after = membershipFor(addr("gone"));
        assertThat(after.getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(after.getConsentBasis()).isNull();
        assertThat(consentRecords.findByMembershipId(m.getMembershipId()))
                .hasSize((int) proofsAfterUnsub);

        // ...and the Send Gate keeps excluding them.
        var gate = sendGate.evaluate(orgId, List.of(after.getMembershipId()));
        assertThat(gate.sendable()).isEmpty();
        assertThat(gate.excluded().get(0).reason()).isEqualTo("marketing_unsubscribed");
    }

    /** A checkout opt-in with proof is an explicit basis, which the email Send Gate admits. */
    @Test
    void checkoutOptIn_passesTheEmailSendGate() {
        projector.upsertMembership(orgId, addr("sendable"), addr("sendable"),
                null, false, true, null, PROOF);

        var m = membershipFor(addr("sendable"));
        assertThat(m.getConsentBasis()).isEqualTo("explicit");

        var gate = sendGate.evaluate(orgId, List.of(m.getMembershipId()));
        assertThat(gate.sendable()).containsExactly(m.getMembershipId());
        assertThat(gate.excluded()).isEmpty();
    }

    /** ...but it is EMAIL-only: the SMS side lives on separate sms_consent_* columns. */
    @Test
    void checkoutEmailOptIn_doesNotMakeTheMemberSmsSendable() {
        projector.upsertMembership(orgId, addr("email-only"), addr("email-only"),
                null, false, /* emailOptIn */ true, null, PROOF);

        var m = membershipFor(addr("email-only"));
        // Email side: subscribed on an explicit basis.
        assertThat(m.getConsentBasis()).isEqualTo("explicit");
        // SMS side: completely untouched — never subscribed, no basis, no phone.
        assertThat(m.getSmsConsentStatus()).isEqualTo("never");
        assertThat(m.getSmsConsentBasis()).isNull();
        assertThat(m.getPhoneE164()).isNull();
        // The SMS-sendable read-model (phone + sms subscribed) does not count them.
        assertThat(memberships.countSmsSubscribedByOrgId(orgId)).isZero();
    }

    /**
     * The SMS opt-in path is unchanged by the email opt-in: an SMS opt-in still
     * records 'explicit'. Pins that the two channels' bases don't converge.
     */
    @Test
    void smsOptIn_stillRecordsExplicitBasis_notSoftOptIn() {
        projector.upsertMembership(orgId, addr("sms"), addr("sms"),
                "+38067" + (1_000_000 + (int) (Math.random() * 8_999_999)), /* smsOptIn */ true, false, null);

        var m = membershipFor(addr("sms"));
        assertThat(m.getSmsConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getSmsConsentBasis()).isEqualTo("explicit");
        // Email side stays untouched by an SMS-only opt-in.
        assertThat(m.getConsentBasis()).isNull();
    }

    /** A ticked box with the sentence the buyer read is recorded as explicit consent, verbatim. */
    @Test
    void projector_withOptInAndProofText_recordsExplicitConsentWithVerbatimSentence() {
        UUID orderId = UUID.randomUUID();
        String sentence = "Email me about similar events. Unsubscribe anytime.";
        projector.upsertMembership(orgId, addr("proof-buyer"), addr("proof-buyer"),
                null, false, true, orderId, sentence);

        var m = membershipFor(addr("proof-buyer"));
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");

        var records = consentRecords.findByMembershipId(m.getMembershipId());
        assertThat(records).hasSize(1);
        var proof = records.get(0);
        assertThat(proof.getChannel()).isEqualTo("email");
        assertThat(proof.getLawfulBasis()).isEqualTo("explicit");
        assertThat(proof.getSource()).isEqualTo("checkout");
        assertThat(proof.getStatus()).isEqualTo("subscribed");
        assertThat(proof.getProofText())
                .startsWith("Ticked the marketing opt-in at checkout next to:");
        assertThat(proof.getProofText()).contains(sentence);
        assertThat(proof.getProofText()).contains(orderId.toString());
        assertThat(proof.getProofText()).doesNotContain("pre-ticked");
        assertThat(proof.getOrderId()).isEqualTo(orderId);
        assertThat(proof.getTextVersion()).isNull();
    }

    /** The label's version id lands on the consent record beside the sentence and the order. */
    @Test
    void projector_withOptInProofAndTextVersion_recordsTheVersion() {
        UUID orderId = UUID.randomUUID();
        projector.upsertMembership(orgId, addr("versioned"), addr("versioned"),
                null, false, true, orderId, "Email me about Arty Farty's events.", "checkout-org-named-2026-09");

        var records = consentRecords.findByMembershipId(membershipFor(addr("versioned")).getMembershipId());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getTextVersion()).isEqualTo("checkout-org-named-2026-09");
        assertThat(records.get(0).getOrderId()).isEqualTo(orderId);
        assertThat(records.get(0).getLawfulBasis()).isEqualTo("explicit");
    }

    /** Free checkout with this label and version, projected synchronously; returns the recorded versions. */
    private List<String> projectedVersions(String email, String label, String version) {
        Order order = freeCheckout.issueFreeOrder(
                event, freeTier, 1, email, null, false, true,
                CheckoutAttribution.NONE, null, null, new CheckoutConsent(true, label, version));
        assertThat(orders.findById(order.getId()).orElseThrow().getMarketingOptInTextVersion()).isEqualTo(version);

        new AudienceOrderProjector(orders, consumers, memberships, membershipProjector, consentService, e -> { }, orgs, planLogic, txManager, erasedAddresses)
                .onTicketsIssued(new TicketsIssuedEvent(order.getId()));

        // The context's own async projector may record the same order a second time.
        var records = consentRecords.findByMembershipId(membershipFor(email).getMembershipId());
        assertThat(records).isNotEmpty().allSatisfy(r -> assertThat(r.getOrderId()).isEqualTo(order.getId()));
        return records.stream().map(r -> r.getTextVersion()).toList();
    }

    /**
     * Free checkout → order column → projected consent record. A listed version survives only when the sentence
     * names the org (case and compatibility forms fold on both sides); otherwise it is a client claim and dropped.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", value = {
            "named,    Email me about events by OptIn Org.,           checkout-org-named-2026-09, checkout-org-named-2026-09",
            "folded,   Email me about events by \uFF2F\uFF30\uFF34\uFF29\uFF2E \uFF2F\uFF32\uFF27., checkout-org-named-2026-09, checkout-org-named-2026-09",
            "unnamed,  Email me about events by Arty Farty.,          checkout-org-named-2026-09, NULL",
            "unlisted, Email me about events by OptIn Org.,           checkout-other-v1,          NULL",
    })
    void freeOrder_textVersionSurvivesOnlyWhenListedAndNamingTheOrg(String tag, String label, String version,
                                                                    String expected) {
        assertThat(projectedVersions(fx.email(tag), label, version)).containsOnly(expected);
    }

    /** An opt-in flag with no proof sentence is not evidence of consent: nothing is recorded. */
    @Test
    void projector_withOptInButNoProofText_recordsNoConsent() {
        projector.upsertMembership(orgId, addr("no-proof"), addr("no-proof"),
                null, false, true, UUID.randomUUID());

        var m = membershipFor(addr("no-proof"));
        assertThat(m.getConsentStatus()).isNotEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isNull();
        assertThat(consentRecords.findByMembershipId(m.getMembershipId())).isEmpty();
    }

    /** A whitespace-only proof sentence is no proof either. */
    @Test
    void projector_withOptInButBlankProofText_recordsNoConsent() {
        projector.upsertMembership(orgId, addr("blank-proof"), addr("blank-proof"),
                null, false, true, UUID.randomUUID(), "   ");

        var m = membershipFor(addr("blank-proof"));
        assertThat(m.getConsentStatus()).isNotEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isNull();
        assertThat(consentRecords.findByMembershipId(m.getMembershipId())).isEmpty();
    }

    /** A zero-total order involves no sale, so it can never ground a soft opt-in. */
    @Test
    void freeOrderWithOptIn_neverYieldsSoftOptIn() {
        Order order = freeCheckout.issueFreeOrder(
                event, freeTier, 1, addr("free-optin"), null, false, true,
                CheckoutAttribution.NONE, null, null,
                new CheckoutConsent(true, "Email me about similar events. Unsubscribe anytime."));

        // Plain instance so the projection runs synchronously on this thread.
        new AudienceOrderProjector(orders, consumers, memberships, membershipProjector, consentService, e -> { }, orgs, planLogic, txManager, erasedAddresses)
                .onTicketsIssued(new TicketsIssuedEvent(order.getId()));

        var m = membershipFor(addr("free-optin"));
        assertThat(m.getConsentBasis()).isEqualTo("explicit");
        assertThat(consentRecords.findByMembershipId(m.getMembershipId()))
                .noneMatch(r -> "soft_opt_in".equals(r.getLawfulBasis()));
    }
}
