package com.imin.iminapi.refund;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V127 at the schema level: the platform-funded flag and its recovery marker persist, the
 * initiating user is now nullable (a Stripe-Dashboard refund has no imin actor), and the open
 * org-level debt lists only SUCCEEDED, platform-funded, not-yet-recovered rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RefundPlatformFundedTest {

    @Autowired RefundRepository refunds;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    Organization org;
    Event event;

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.setName("Debt Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("org@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Great Event");
        e.setSlug("event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setCreatedBy(owner.getId());
        event = events.save(e);
    }

    private Order order() {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString());
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(5000);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        return orders.save(o);
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
