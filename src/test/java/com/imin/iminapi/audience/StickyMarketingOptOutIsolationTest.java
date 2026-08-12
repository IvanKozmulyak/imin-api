package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.MarketingOptOutId;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * <b>The sticky write can never fail the unsubscribe.</b>
 *
 * <p>{@code ConsentService.unsubscribe} is the live RFC 8058 one-click path and the
 * legally mandated SMS STOP path. Recording stickiness is a bonus record, never a
 * precondition: a buyer who clicks unsubscribe must end up unsubscribed even if the
 * {@code marketing_optouts} insert blows up for any reason at all.
 *
 * <p>These tests exist because a try/catch alone does <b>not</b> buy that. A
 * constraint violation raised inside the caller's own transaction marks it
 * rollback-only; the caught exception is followed by an
 * {@code UnexpectedRollbackException} at commit and the unsubscribe is silently
 * undone. Isolation comes from {@code MarketingOptOutRecorder} being a separate bean
 * with {@code REQUIRES_NEW}, and from the catch sitting outside that boundary. Both
 * tests here assert against committed state — the test methods are deliberately
 * <b>not</b> {@code @Transactional}, so anything they can read back is something that
 * actually reached the database.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class StickyMarketingOptOutIsolationTest {

    @Autowired ConsentService consentService;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ConsentRecordRepository consentRecords;

    @MockitoSpyBean MarketingOptOutRepository optOuts;
    @MockitoBean AuditLogger auditLogger;

    /**
     * The real thing: a genuine duplicate-key violation from the database, raised
     * inside the recorder's flush.
     *
     * <p>The row is pre-committed and the existence check is then blinded, which is
     * exactly the race the read-then-insert idiom leaves open — two unsubscribes
     * arriving together both see "no row" before either inserts. The loser must be a
     * no-op, not a 500 on the buyer's unsubscribe link.
     */
    @Test
    void aRealDuplicateKeyViolation_doesNotRollBackTheUnsubscribe() {
        UUID orgId = UUID.randomUUID();
        String email = seedEmail("race");
        UUID membershipId = seedMembership(orgId, email);

        // The winner of the race, committed before we start.
        optOuts.saveAndFlush(MarketingOptOut.of(email, orgId, "email", "one_click"));

        // Blind the guard so the recorder takes the insert path against a row that is
        // already there — the losing side of the race, reproduced deterministically.
        doReturn(false).when(optOuts).existsById(any(MarketingOptOutId.class));

        assertThatCode(() -> consentService.unsubscribe(orgId, membershipId, "footer_link",
                ConsentOrigin.DATA_SUBJECT, null))
                .as("a duplicate sticky row must be success, not a 500 on the RFC 8058 path")
                .doesNotThrowAnyException();

        assertUnsubscribeCommitted(orgId, membershipId);

        // The winner's row stands untouched: still one row, still the earliest one.
        assertThat(optOuts.findByEmailNormalized(email))
                .singleElement()
                .satisfies(row -> assertThat(row.getSource()).isEqualTo("one_click"));
    }

    /**
     * And the general case, because §16's rule is "for any reason": the recorder
     * failing on something that is not a constraint violation at all — a broken
     * connection, a mapping error, anything — still leaves the unsubscribe standing.
     */
    @Test
    void anyOtherFailureInTheStickyWrite_doesNotRollBackTheUnsubscribe() {
        UUID orgId = UUID.randomUUID();
        String email = seedEmail("boom");
        UUID membershipId = seedMembership(orgId, email);

        doThrow(new IllegalStateException("sticky write exploded"))
                .when(optOuts).existsById(any(MarketingOptOutId.class));

        assertThatCode(() -> consentService.unsubscribe(orgId, membershipId, "one_click",
                ConsentOrigin.DATA_SUBJECT, null))
                .doesNotThrowAnyException();

        assertUnsubscribeCommitted(orgId, membershipId);
    }

    /**
     * The thing the buyer actually asked for, read back from the database. If the
     * sticky write had poisoned the surrounding transaction, none of this would be
     * here — the {@code unsubscribe} call above would have thrown at commit.
     */
    private void assertUnsubscribeCommitted(UUID orgId, UUID membershipId) {
        Membership after = memberships.findByIdAndOrgId(membershipId, orgId).orElseThrow();
        assertThat(after.getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(after.getConsentBasis()).isNull();
        assertThat(consentRecords.findByMembershipId(membershipId))
                .as("the immutable consent proof row is the legal record — it must commit")
                .anySatisfy(r -> {
                    assertThat(r.getChannel()).isEqualTo("email");
                    assertThat(r.getStatus()).isEqualTo("unsubscribed");
                });
    }

    private String seedEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private UUID seedMembership(UUID orgId, String normalizedEmail) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(normalizedEmail);
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
    }
}
