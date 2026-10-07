package com.imin.iminapi.dispute;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.payout.DisputeRecoveryMarker;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.util.Times;
import com.stripe.StripeClient;
import com.stripe.net.ApiRequest;
import com.stripe.net.ApiResource;
import com.stripe.net.StripeResponseGetter;
import com.stripe.service.ChargeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The V172 recovery columns against the other writer of a {@code disputes} row: dispute ingest's
 * full-entity save, in both orders, plus the conditional bulk updates that are their only writer.
 */
@IminIntegrationTest
class DisputeRecoveryColumnsTest {

    @Autowired DisputeIngestService ingest;
    @Autowired DisputeRecoveryMarker marker;
    @Autowired DisputeRepository disputes;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StripeClient stripeClient;

    private final List<UUID> orgIds = new ArrayList<>();

    private Order order;
    private String pi;
    private String chargeId;

    @BeforeEach
    void setUp() {
        pi = "pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        chargeId = "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Organization o = new Organization();
        o.setName("Org");
        o.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("o@test.example");
        o.setCountry("FR");
        o = orgs.save(o);
        orgIds.add(o.getId());
        User u = new User();
        u.setOrgId(o.getId());
        u.setEmail("u-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        u = users.save(u);
        Event e = new Event();
        e.setOrgId(o.getId());
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setCurrency("EUR");
        e.setCreatedBy(u.getId());
        e = events.save(e);
        order = new Order();
        order.setToken(UUID.randomUUID().toString().replace("-", ""));
        order.setEventId(e.getId());
        order.setOrgId(o.getId());
        order.setEmail("b@test.example");
        order.setTotalMinor(1_149);
        order.setApplicationFeeMinor(149);
        order.setCurrency("eur");
        order.setPaymentMethod("card");
        order.setStripePaymentIntentId(pi);
        order = orders.save(order);

        stubChargeRetrieve();
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void an_ingest_after_the_marker_keeps_the_recovery() {
        Dispute d = lostRow();
        marker.markRecovered(d.getId(), 0L, 1_000L, "trr_1", true);

        tx.executeWithoutResult(s -> deliverLostAgain(d));

        assertThat(columns(d)).containsEntry("RECOVERED_MINOR", 1_000L)
                .containsEntry("RECOVERY_REVERSAL_ID", "trr_1");
    }

    @Test
    void an_ingest_that_loaded_the_row_before_the_marker_committed_does_not_revert_it() {
        Dispute d = lostRow();

        tx.executeWithoutResult(s -> {
            // The ingest's entity is now in this persistence context without the marker.
            assertThat(disputes.findByStripeDisputeId(d.getStripeDisputeId()).orElseThrow().getRecoveredAt()).isNull();
            marker.markRecovered(d.getId(), 0L, 1_000L, "trr_1", true);
            deliverLostAgain(d);
        });

        Map<String, Object> c = columns(d);
        assertThat(c.get("RECOVERED_AT")).isNotNull();
        assertThat(c).containsEntry("RECOVERED_MINOR", 1_000L).containsEntry("RECOVERY_REVERSAL_ID", "trr_1");
        assertThat(c.get("LAST_EVENT_AT")).as("the ingest's own write did land").isNotNull();
    }

    @Test
    void a_second_recovery_marker_changes_nothing() {
        Dispute d = lostRow();
        Integer first = tx.execute(s -> disputes.markRecovered(d.getId(), 0L, 1_000L, "trr_1", Times.nowMicros()));
        Integer second = tx.execute(s -> disputes.markRecovered(d.getId(), 0L, 999L, "trr_2", Times.nowMicros()));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(columns(d)).containsEntry("RECOVERED_MINOR", 1_000L).containsEntry("RECOVERY_REVERSAL_ID", "trr_1");
    }

    @Test
    void a_return_cannot_be_stamped_on_a_dispute_never_recovered() {
        Dispute d = lostRow();

        Integer updated = tx.execute(s -> disputes.markReturned(d.getId(), "tr_back", 0L, 100L, Times.nowMicros()));

        assertThat(updated).isZero();
        assertThat(columns(d).get("RETURNED_AT")).isNull();
    }

    @Test
    void a_recovered_dispute_is_no_longer_listed_as_owed() {
        Dispute d = lostRow();
        assertThat(disputes.findUnrecoveredLostByOrgId(d.getOrgId(), DisputeStatus.LOST, false))
                .extracting(Dispute::getId).containsExactly(d.getId());

        marker.markRecovered(d.getId(), 0L, 1_000L, "trr_1", true);

        assertThat(disputes.findUnrecoveredLostByOrgId(d.getOrgId(), DisputeStatus.LOST, false)).isEmpty();
    }

    private Dispute lostRow() {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        d.setOrgId(order.getOrgId());
        d.setEventId(order.getEventId());
        d.setOrderId(order.getId());
        d.setStripeChargeId(chargeId);
        d.setAmountMinor(1_149);
        d.setCurrency("eur");
        d.setStatus(DisputeStatus.LOST);
        d = disputes.save(d);
        return d;
    }

    /** A resent {@code charge.dispute.closed}: same status, a later event, so ingest saves the row. */
    private void deliverLostAgain(Dispute d) {
        com.stripe.model.Dispute du = ApiResource.GSON.fromJson("""
                { "id": "%s", "object": "dispute", "amount": 1149, "currency": "eur",
                  "status": "lost", "charge": "%s" }
                """.formatted(d.getStripeDisputeId(), chargeId), com.stripe.model.Dispute.class);
        ingest.ingest(du, "acct_1", "charge.dispute.closed", Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** The real ingest reads the disputed charge from Stripe; this answers it with the order's PaymentIntent. */
    private void stubChargeRetrieve() {
        StripeResponseGetter rg = mock(StripeResponseGetter.class);
        try {
            when(rg.request(any(ApiRequest.class), any(Type.class))).thenAnswer(inv -> ApiResource.GSON.fromJson("""
                    { "id": "%s", "object": "charge", "payment_intent": "%s" }
                    """.formatted(chargeId, pi), com.stripe.model.Charge.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(stripeClient.charges()).thenReturn(new ChargeService(rg));
    }

    private Map<String, Object> columns(Dispute d) {
        return jdbc.queryForMap("select recovered_at, recovered_minor, recovery_reversal_id, returned_at, "
                + "last_event_at from disputes where id = ?", d.getId());
    }
}
