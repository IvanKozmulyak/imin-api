package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.sms.SmsStopService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sticky {@code marketing_optouts} write (buyer-accounts epic §6.3, resolved in
 * §16).
 *
 * <p>What this table is for: without it, the Release-2 account-level "organizer
 * updates" toggle is a consent-laundering device — flipping it ON would re-subscribe
 * a buyer to an organizer they had explicitly left, without anyone ever asking them.
 * The record has to be written by the unsubscribe itself, before the toggle exists,
 * or the first buyer to flip that toggle ON resurrects everyone they left earlier.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class StickyMarketingOptOutTest {

    @Autowired ConsentService consentService;
    @Autowired DsarService dsarService;
    @Autowired SmsStopService smsStopService;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired MarketingOptOutRepository optOuts;

    // ConsentService/DsarService write an organizer audit row when a principal is
    // present; those org/user ids are synthetic here.
    @MockitoBean AuditLogger auditLogger;

    // ── The whole point: origin decides, `source` does not ───────────────────

    /**
     * The pair that matters. Identical {@code source} string, opposite origins:
     * only the data subject's decision becomes sticky. {@code AudienceController}
     * takes {@code source} straight from an organizer-supplied request body, so if
     * stickiness were inferred from that string an organizer could POST
     * {@code "one_click"} and permanently bar a buyer from ever opting back in —
     * on a record keyed by address, which outlives the account.
     */
    @Test
    void dataSubjectWritesAStickyRow_operatorWritesNone_forTheSameSourceString() {
        String buyer = seedEmail("origin-buyer");
        String tidied = seedEmail("origin-tidied");
        UUID orgId = UUID.randomUUID();
        UUID buyerMembership = seedMembership(orgId, buyer);
        UUID tidiedMembership = seedMembership(orgId, tidied);

        consentService.unsubscribe(orgId, buyerMembership, "one_click",
                ConsentOrigin.DATA_SUBJECT, null);
        consentService.unsubscribe(orgId, tidiedMembership, "one_click",
                ConsentOrigin.OPERATOR, organizerOf(orgId));

        assertThat(optOuts.findByEmailNormalized(buyer))
                .as("the data subject's own unsubscribe is sticky")
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getOrgId()).isEqualTo(orgId);
                    assertThat(row.getChannel()).isEqualTo("email");
                    assertThat(row.getSource()).isEqualTo("one_click");
                });

        assertThat(optOuts.findByEmailNormalized(tidied))
                .as("an organizer tidying their list must not be able to write a sticky "
                        + "row, whatever they put in `source`")
                .isEmpty();

        // Both are genuinely unsubscribed either way — origin changes only stickiness.
        assertThat(consentStatus(orgId, buyerMembership)).isEqualTo("unsubscribed");
        assertThat(consentStatus(orgId, tidiedMembership)).isEqualTo("unsubscribed");
    }

    // ── Idempotency ─────────────────────────────────────────────────────────

    /**
     * Unsubscribing twice from one organizer is ordinary, not exceptional: the public
     * GET handler mutates, so link prefetchers and mail-client image proxies reach it
     * unprompted. One row, and the FIRST one — the date the person first objected is
     * the one that matters if anyone ever has to answer for it.
     */
    @Test
    void repeatUnsubscribe_keepsOneRow_withTheEarliestCreatedAtAndFirstSource() {
        String email = seedEmail("repeat");
        UUID orgId = UUID.randomUUID();
        UUID membershipId = seedMembership(orgId, email);

        consentService.unsubscribe(orgId, membershipId, "one_click",
                ConsentOrigin.DATA_SUBJECT, null);
        MarketingOptOut first = only(optOuts.findByEmailNormalized(email));
        Instant firstSeenAt = first.getCreatedAt();

        // Second click, deliberately a different surface: if anything refreshed the
        // row, `source` would move to footer_link and created_at would jump.
        consentService.unsubscribe(orgId, membershipId, "footer_link",
                ConsentOrigin.DATA_SUBJECT, null);

        MarketingOptOut after = only(optOuts.findByEmailNormalized(email));
        assertThat(after.getCreatedAt()).isEqualTo(firstSeenAt);
        assertThat(after.getSource()).isEqualTo("one_click");
    }

    /**
     * The PK is {@code (email, org, channel)}: leaving one organizer says nothing
     * about another, and an email opt-out is not an SMS opt-out.
     */
    @Test
    void stickinessIsScopedToOneOrganizerAndOneChannel() {
        String email = seedEmail("scope");
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID inA = seedMembership(orgA, email);
        UUID inB = seedMembership(orgB, email);

        consentService.unsubscribe(orgA, inA, "footer_link", "email", ConsentOrigin.DATA_SUBJECT, null);
        consentService.unsubscribe(orgA, inA, "one_click", "sms", ConsentOrigin.DATA_SUBJECT, null);

        assertThat(optOuts.findByEmailNormalized(email))
                .extracting(MarketingOptOut::getOrgId, MarketingOptOut::getChannel)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(orgA, "email"),
                        org.assertj.core.groups.Tuple.tuple(orgA, "sms"));
        assertThat(optOuts.findByEmailNormalizedAndOrgId(email, orgB)).isEmpty();
        assertThat(inB).isNotNull(); // membership in org B exists and is untouched
        assertThat(consentStatus(orgB, inB)).isEqualTo("subscribed");
    }

    // ── The five call sites pass the origin §16 assigns them ─────────────────
    //
    // The two PublicUnsubscribeController handlers and AudienceController are
    // verified against the real HTTP surface in PublicUnsubscribeControllerTest and
    // AudienceControllerWebTest. The two service-level call sites are here, where
    // the observable effect is the row itself rather than a mock interaction.

    /**
     * {@code DsarService.object} — an Art. 21 objection is the buyer's decision even
     * though an operator keyed it in, and it is exactly the record that must never be
     * reversed by a toggle. Note the principal here is an organizer and the row is
     * still written: the origin, not the actor, decides.
     */
    @Test
    void dsarObjection_isDataSubject_andWritesAStickyRow() {
        String email = seedEmail("dsar");
        UUID orgId = UUID.randomUUID();
        UUID membershipId = seedMembership(orgId, email);

        dsarService.object(orgId, membershipId, organizerOf(orgId));

        assertThat(optOuts.findByEmailNormalized(email))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getChannel()).isEqualTo("email");
                    assertThat(row.getSource()).isEqualTo("dsar_object");
                });
    }

    /**
     * {@code SmsStopService} — the legally mandated STOP path, and the reason an
     * allow-list over {@code source} was rejected: the source string here is passed
     * through from the inbound SMS webhook, so no list of known-good strings could
     * have covered it reliably.
     */
    @Test
    void smsStop_isDataSubject_andWritesAStickyRowOnTheSmsChannel() {
        String email = seedEmail("stop");
        UUID orgId = UUID.randomUUID();
        String phone = "+3538" + (1_000_000 + (int) (Math.random() * 8_999_999));
        UUID membershipId = seedMembership(orgId, email);
        Membership m = memberships.findByIdAndOrgId(membershipId, orgId).orElseThrow();
        m.setPhoneE164(phone);
        m.setSmsConsentStatus("subscribed");
        memberships.save(m);

        assertThat(smsStopService.suppressPhone(phone, "sms_stop_reply")).isEqualTo(1);

        assertThat(optOuts.findByEmailNormalized(email))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getChannel()).isEqualTo("sms");
                    assertThat(row.getSource()).isEqualTo("sms_stop_reply");
                });
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String seedEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private UUID seedMembership(UUID orgId, String normalizedEmail) {
        Consumer c = consumers.findByNormalizedEmail(normalizedEmail).orElseGet(() -> {
            Consumer fresh = new Consumer();
            fresh.setNormalizedEmail(normalizedEmail);
            return consumers.save(fresh);
        });
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
    }

    private String consentStatus(UUID orgId, UUID membershipId) {
        return memberships.findByIdAndOrgId(membershipId, orgId).orElseThrow().getConsentStatus();
    }

    private AuthPrincipal organizerOf(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private static MarketingOptOut only(List<MarketingOptOut> rows) {
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
