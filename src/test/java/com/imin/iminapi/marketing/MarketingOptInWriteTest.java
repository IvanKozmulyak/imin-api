package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.service.event.FreeCheckoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checkout email-marketing opt-in: the buy-page checkbox flows to
 * orders.marketing_opt_in (V61, free path here; the paid path is metadata-mapped like
 * ads_consent) and the AudienceOrderProjector turns it into a channel='email' consent
 * proof that makes the membership SendGate-sendable.
 *
 * <p>The checkbox is PRE-TICKED (default ON) and worded as an opt-out, so the basis
 * recorded is {@code soft_opt_in} (ePrivacy Art.13(2)) and never {@code explicit} — a
 * pre-ticked box cannot evidence explicit consent (GDPR Recital 32). These tests pin
 * that basis, the honesty of the stored proof text, and the two rules that protect the
 * buyer: unsubscribed always beats default-on, and a soft opt-in is EMAIL-only.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class MarketingOptInWriteTest {

    @Autowired FreeCheckoutService freeCheckout;
    @Autowired AudienceOrderProjector projector;
    @Autowired ConsentService consentService;
    @Autowired SendGateService sendGate;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired DataSource dataSource;

    private Event event;
    private TicketTier freeTier;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        cleanUp();

        Organization org = new Organization();
        org.setName("OptIn Org");
        org.setSlug("optin-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("optin@example.com");
        org.setCountry("DE");
        org = orgs.save(org);
        orgId = org.getId();

        User owner = new User();
        owner.setEmail("optin-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

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
        cleanUp();
    }

    private void cleanUp() {
        // Audience rows use marker repositories without deleteAll — JDBC teardown (audience convention).
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.execute("delete from consent_records");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        tickets.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private com.imin.iminapi.audience.model.Membership membershipFor(String email) {
        var consumer = consumers.findByNormalizedEmail(email).orElseThrow();
        return memberships.findByOrgIdAndConsumerId(orgId, consumer.getConsumerId()).orElseThrow();
    }

    @Test
    void freeCheckout_persistsMarketingOptInFlag() {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, "optin-buyer@example.com", null, false,
                /* marketingOptIn */ true, CheckoutAttribution.NONE);
        Order persisted = orders.findByToken(created.getToken()).orElseThrow();
        assertThat(persisted.isMarketingOptIn()).isTrue();
    }

    /**
     * V62: the free path stamps the landing utm_* + anon_id inline (it never round-trips
     * through Stripe metadata — the paid path's read-back is covered in
     * PaidCheckoutServiceTest).
     */
    @Test
    void freeCheckout_persistsUtmAttribution() {
        String campaignId = UUID.randomUUID().toString();
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, "utm-buyer@example.com", null, false, true,
                new CheckoutAttribution("imin", "email", campaignId, "anon-free-1"));

        Order persisted = orders.findByToken(created.getToken()).orElseThrow();
        assertThat(persisted.getUtmSource()).isEqualTo("imin");
        assertThat(persisted.getUtmMedium()).isEqualTo("email");
        assertThat(persisted.getUtmCampaign()).isEqualTo(campaignId);
        assertThat(persisted.getAnonId()).isEqualTo("anon-free-1");
    }

    /** Untagged (organic) free order → null columns, never empty strings. */
    @Test
    void freeCheckout_withNoAttribution_leavesUtmNull() {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, "organic@example.com", null, false, true,
                CheckoutAttribution.NONE);

        Order persisted = orders.findByToken(created.getToken()).orElseThrow();
        assertThat(persisted.getUtmSource()).isNull();
        assertThat(persisted.getUtmCampaign()).isNull();
        assertThat(persisted.getAnonId()).isNull();
    }

    /**
     * Buyer-supplied attribution is untrusted: blank collapses to null (so it can't
     * masquerade as a real tag) and over-long values are capped to the column widths
     * rather than failing the order insert.
     */
    @Test
    void freeCheckout_normalizesHostileAttributionInput() {
        Order created = freeCheckout.issueFreeOrder(
                event, freeTier, 1, "hostile@example.com", null, false, true,
                new CheckoutAttribution("   ", "  email  ", "x".repeat(500), "y".repeat(200)));

        Order persisted = orders.findByToken(created.getToken()).orElseThrow();
        assertThat(persisted.getUtmSource()).isNull();            // blank → null
        assertThat(persisted.getUtmMedium()).isEqualTo("email");  // trimmed
        assertThat(persisted.getUtmCampaign()).hasSize(128);      // capped to column width
        assertThat(persisted.getAnonId()).hasSize(64);
    }

    /**
     * The core of the soft-opt-in change: a checkout opt-in records basis='soft_opt_in',
     * NOT 'explicit'. The box is pre-ticked, so 'explicit' would be a false audit record.
     */
    @Test
    void projector_withOptIn_capturesSoftOptInEmailConsent() {
        projector.upsertMembership(orgId, "optin-buyer@example.com", "optin-buyer@example.com",
                null, false, /* emailOptIn */ true, UUID.randomUUID());

        var m = membershipFor("optin-buyer@example.com");
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("soft_opt_in");
        assertThat(m.getConsentBasis()).isNotEqualTo("explicit");
    }

    /**
     * The stored proof must describe what actually happened — a pre-ticked box the buyer
     * left ticked — and must never claim the buyer took an affirmative action. This is the
     * record a regulator would read.
     */
    @Test
    void projector_withOptIn_proofTextDescribesPreTickedBox_notAnAffirmativeAction() {
        UUID orderId = UUID.randomUUID();
        projector.upsertMembership(orgId, "proof-buyer@example.com", "proof-buyer@example.com",
                null, false, true, orderId);

        var m = membershipFor("proof-buyer@example.com");
        var records = consentRecords.findByMembershipId(m.getMembershipId());
        assertThat(records).hasSize(1);
        var proof = records.get(0);

        assertThat(proof.getChannel()).isEqualTo("email");
        assertThat(proof.getLawfulBasis()).isEqualTo("soft_opt_in");
        assertThat(proof.getSource()).isEqualTo("checkout");
        // Says what happened...
        assertThat(proof.getProofText()).contains("pre-ticked");
        assertThat(proof.getProofText()).contains(orderId.toString());
        // ...and does not claim the buyer actively opted in.
        assertThat(proof.getProofText()).doesNotContain("Checked '");
    }

    @Test
    void projector_withoutOptIn_leavesConsentUntouched() {
        projector.upsertMembership(orgId, "no-optin@example.com", "no-optin@example.com",
                null, false, false, null);

        var m = membershipFor("no-optin@example.com");
        assertThat(m.getConsentStatus()).isNotEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isNull();
    }

    /**
     * UNSUBSCRIBED BEATS DEFAULT-ON, ALWAYS. A buyer who opted out and later buys another
     * ticket must not be silently resurrected by a checkbox that defaults to ticked. This
     * is the single most important guard on a default-on control.
     */
    @Test
    void projector_neverResubscribesAnUnsubscribedMember_evenWithDefaultOnOptIn() {
        // Seed a member and unsubscribe them.
        projector.upsertMembership(orgId, "gone@example.com", "gone@example.com",
                null, false, true, null);
        var m = membershipFor("gone@example.com");
        consentService.unsubscribe(orgId, m.getMembershipId(), "user-request", null);
        assertThat(membershipFor("gone@example.com").getConsentStatus()).isEqualTo("unsubscribed");
        long proofsAfterUnsub = consentRecords
                .findByMembershipId(m.getMembershipId()).size();

        // They buy again with the pre-ticked box left ticked.
        projector.upsertMembership(orgId, "gone@example.com", "gone@example.com",
                null, false, /* emailOptIn */ true, UUID.randomUUID());

        // Still unsubscribed, still no lawful basis, and no new consent proof was written.
        var after = membershipFor("gone@example.com");
        assertThat(after.getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(after.getConsentBasis()).isNull();
        assertThat(consentRecords.findByMembershipId(m.getMembershipId()))
                .hasSize((int) proofsAfterUnsub);

        // ...and the Send Gate keeps excluding them.
        var gate = sendGate.evaluate(orgId, List.of(after.getMembershipId()));
        assertThat(gate.sendable()).isEmpty();
        assertThat(gate.excluded().get(0).reason()).isEqualTo("marketing_unsubscribed");
    }

    /**
     * soft_opt_in is a lawful basis for EMAIL — SendGate clause 4 admits any non-null
     * consent_basis, so a checkout opt-in makes the member sendable.
     */
    @Test
    void softOptIn_passesTheEmailSendGate() {
        projector.upsertMembership(orgId, "sendable@example.com", "sendable@example.com",
                null, false, true, null);

        var m = membershipFor("sendable@example.com");
        assertThat(m.getConsentBasis()).isEqualTo("soft_opt_in");

        var gate = sendGate.evaluate(orgId, List.of(m.getMembershipId()));
        assertThat(gate.sendable()).containsExactly(m.getMembershipId());
        assertThat(gate.excluded()).isEmpty();
    }

    /**
     * ...but it is EMAIL-only. An email soft opt-in must NOT make anyone SMS-sendable:
     * SMS keeps requiring an explicit, unticked-by-default opt-in and lives on the
     * separate sms_consent_* columns. Guards against the soft opt-in leaking channels.
     */
    @Test
    void softOptIn_doesNotMakeTheMemberSmsSendable() {
        projector.upsertMembership(orgId, "email-only@example.com", "email-only@example.com",
                null, false, /* emailOptIn */ true, null);

        var m = membershipFor("email-only@example.com");
        // Email side: subscribed on a soft basis.
        assertThat(m.getConsentBasis()).isEqualTo("soft_opt_in");
        // SMS side: completely untouched — never subscribed, no basis, no phone.
        assertThat(m.getSmsConsentStatus()).isEqualTo("never");
        assertThat(m.getSmsConsentBasis()).isNull();
        assertThat(m.getPhoneE164()).isNull();
        // The SMS-sendable read-model (phone + sms subscribed) does not count them.
        assertThat(memberships.countSmsSubscribedByOrgId(orgId)).isZero();
    }

    /**
     * The SMS opt-in path is unchanged by the email soft opt-in: an SMS opt-in still
     * records 'explicit'. Pins that the two channels' bases don't converge.
     */
    @Test
    void smsOptIn_stillRecordsExplicitBasis_notSoftOptIn() {
        projector.upsertMembership(orgId, "sms@example.com", "sms@example.com",
                "+380671234567", /* smsOptIn */ true, false, null);

        var m = membershipFor("sms@example.com");
        assertThat(m.getSmsConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getSmsConsentBasis()).isEqualTo("explicit");
        // Email side stays untouched by an SMS-only opt-in.
        assertThat(m.getConsentBasis()).isNull();
    }
}
