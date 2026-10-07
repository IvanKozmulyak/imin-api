package com.imin.iminapi.refund;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V127 at the schema level: the platform-funded flag and its recovery marker persist, the
 * initiating user is now nullable (a Stripe-Dashboard refund has no imin actor), and the open
 * org-level debt lists only SUCCEEDED, platform-funded, not-yet-recovered rows.
 */
@IminIntegrationTest
class RefundPlatformFundedTest {

    @Autowired RefundRepository refunds;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    Organization org;
    Event event;

    @BeforeEach
    void setUp() {
        org = fx.org();
        User owner = fx.owner(org);
        event = fx.event(org, owner, EventStatus.LIVE, null);
    }

    /** Unrecovered platform-funded rows make the org "owing" to the payout sweep, so they never outlive the test. */
    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, List.of(org.getId()));
    }

    private Order order() {
        return fx.order(event, fx.email("buyer"));
    }

    private Refund refund(long amountMinor, RefundStatus status, boolean platformFunded) {
        Refund r = new Refund();
        r.setOrderId(order().getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        r.setStripeRefundId("re_" + UUID.randomUUID());
        r.setAmountMinor(amountMinor);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(0);
        r.setReason(RefundReason.OTHER);
        r.setStatus(status);
        r.setPlatformFunded(platformFunded);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        return refunds.saveAndFlush(r);
    }

    @Test
    void a_dashboard_refund_persists_without_an_initiating_user() {
        Refund saved = refund(1000, RefundStatus.SUCCEEDED, false);

        assertThat(saved.getInitiatedByUserId())
                .as("initiated_by_user_id must be nullable — no fabricated system actor")
                .isNull();
        assertThat(refunds.findById(saved.getId()).orElseThrow().isPlatformFunded()).isFalse();
    }

    @Test
    void the_open_debt_lists_only_succeeded_platform_funded_rows() {
        Refund fronted = refund(1000, RefundStatus.SUCCEEDED, true);
        refund(500, RefundStatus.SUCCEEDED, false);    // not platform-funded
        refund(700, RefundStatus.PENDING, true);       // money has not moved yet
        refund(900, RefundStatus.FAILED, true);        // no money moved

        assertThat(refunds.findUnrecoveredPlatformFundedByOrgId(org.getId()))
                .extracting(Refund::getId)
                .containsExactly(fronted.getId());
    }

    @Test
    void a_recovered_refund_drops_out_of_the_open_debt() {
        Refund fronted = refund(1000, RefundStatus.SUCCEEDED, true);
        fronted.setRecoveredAt(java.time.Instant.now());
        fronted.setRecoveryReversalId("trr_test_1");
        refunds.saveAndFlush(fronted);

        assertThat(refunds.findUnrecoveredPlatformFundedByOrgId(org.getId()))
                .as("money already pulled back must never be reversed a second time")
                .isEmpty();
        assertThat(refunds.findById(fronted.getId()).orElseThrow().getRecoveryReversalId())
                .isEqualTo("trr_test_1");
    }
}
