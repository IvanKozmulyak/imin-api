package com.imin.iminapi.audience;

import com.imin.iminapi.audience.service.AudienceBackfillJob;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/** A checkout email opt-in lost to a failed live projection is restored by the nightly backfill, from the order. */
@IminIntegrationTest
class CheckoutConsentBackfillTest {

    @Autowired AudienceOrderProjector projector;
    @Autowired AudienceBackfillJob backfillJob;
    @Autowired ConsentService consentService;
    @Autowired DsarService dsarService;
    @Autowired OrderRepository orders;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("taskExecutor") Executor asyncExecutor;

    private static final String PROOF = "Email me about this organizer's events. Unsubscribe anytime.";
    /** On the organizer-named allowlist in audienceplan/logic-v1.yaml. */
    private static final String ORG_NAMED_VERSION = "checkout-org-named-2026-09";

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();

    /** The backfill walks every org's orders, so own projection, ledger rows and orgs go. */
    @AfterEach
    void tearDown() {
        AsyncDrain.drain(asyncExecutor);
        try {
            for (UUID orgId : orgIds) jdbc.update("delete from memberships where org_id = ?", orgId);
            for (String email : emails) {
                jdbc.update("delete from consumers where normalized_email = ?", email);
                jdbc.update("delete from erased_addresses where email_normalized = ?", email);
            }
            jdbc.update("update shedlock set lock_until = locked_at where name = 'audience_backfill'");
        } finally {
            OrgRows.delete(jdbc, orgIds);
        }
    }

    enum Sentence { PLAIN, ORGANIZER_NAMED }

    @ParameterizedTest
    @EnumSource(Sentence.class)
    void failedProjectionCommit_backfillRestoresTheCheckoutConsent(Sentence sentence) {
        Organization org = org();
        String email = email("restore-" + sentence.name().toLowerCase());
        Order order = optedInOrder(org, email);
        String proof = PROOF;
        if (sentence == Sentence.ORGANIZER_NAMED) {
            proof = "Email me about " + org.getName() + "'s events. Unsubscribe anytime.";
            order.setMarketingOptInProof(proof);
            order.setMarketingOptInTextVersion(ORG_NAMED_VERSION);
            order = orders.save(order);
        }
        projectFailing(order);
        assertThat(checkoutGrants(order)).isEmpty();

        runBackfill();

        List<Map<String, Object>> grants = checkoutGrants(order);
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0)).containsEntry("status", "subscribed").containsEntry("lawful_basis", "explicit")
                .containsEntry("source", "checkout")
                .containsEntry("text_version", sentence == Sentence.ORGANIZER_NAMED ? ORG_NAMED_VERSION : null);
        assertThat((String) grants.get(0).get("proof_text"))
                .isEqualTo("Ticked the marketing opt-in at checkout next to: \"" + proof + "\", order " + order.getId());
        // Dated when the box was ticked, not when the backfill ran: it is the Art.7(1) proof date.
        assertThat(((Timestamp) grants.get(0).get("occurred_at")).toInstant()).isEqualTo(storedCreatedAt(order));
        assertThat(membership(org, email)).containsEntry("consent_status", "subscribed")
                .containsEntry("consent_basis", "explicit");
    }

    enum NoGrant { NOT_TICKED, NULL_PROOF, BLANK_PROOF }

    @ParameterizedTest
    @EnumSource(NoGrant.class)
    void orderWithoutATickedBoxAndItsSentence_backfillRecordsNothing(NoGrant shape) {
        Organization org = org();
        String email = email("nogrant-" + shape.name().toLowerCase());
        Order order = fx.order(fx.event(org, fx.owner(org), EventStatus.LIVE, Instant.now().plusSeconds(86_400)), email);
        // The buyer site sends the sentence even when the box is left unticked.
        order.setMarketingOptIn(shape != NoGrant.NOT_TICKED);
        order.setMarketingOptInProof(switch (shape) {
            case NOT_TICKED -> PROOF;
            case NULL_PROOF -> null;
            case BLANK_PROOF -> "   ";
        });
        orders.save(order);

        runBackfill();

        assertThat(checkoutGrants(order)).isEmpty();
    }

    enum LaterOptOut { UNSUBSCRIBED, UNSUBSCRIBED_THEN_RESUBSCRIBED, OBJECTED, ERASE_PENDING }

    @ParameterizedTest
    @EnumSource(LaterOptOut.class)
    void optOutAfterTheOrder_winsOverTheBackfill(LaterOptOut later) {
        Organization org = org();
        String email = email("later-" + later.name().toLowerCase());
        projector.upsertMembership(org.getId(), email, email);
        Order order = optedInOrder(org, email);
        projectFailing(order);
        UUID membershipId = (UUID) membership(org, email).get("membership_id");
        switch (later) {
            case UNSUBSCRIBED -> consentService.unsubscribe(org.getId(), membershipId, "one_click",
                    ConsentOrigin.DATA_SUBJECT, null);
            case UNSUBSCRIBED_THEN_RESUBSCRIBED -> {
                consentService.unsubscribe(org.getId(), membershipId, "one_click", ConsentOrigin.DATA_SUBJECT, null);
                resubscribe(org, membershipId);
            }
            // A spam complaint sets the objection alone (ResendWebhookProjector).
            case OBJECTED -> jdbc.update("update memberships set objected_profiling = true where membership_id = ?",
                    membershipId);
            // An erasure request waiting for the erasure job (DsarService.requestErase).
            case ERASE_PENDING -> jdbc.update("update memberships set status = 'erase_pending' where membership_id = ?",
                    membershipId);
        }
        Map<String, Object> before = membership(org, email);

        runBackfill();

        assertThat(checkoutGrants(order)).isEmpty();
        Map<String, Object> after = membership(org, email);
        assertThat(after.get("consent_status")).isEqualTo(before.get("consent_status"));
        assertThat(after.get("consent_basis")).isEqualTo(before.get("consent_basis"));
        assertThat(after.get("objected_profiling")).isEqualTo(before.get("objected_profiling"));
    }

    enum BeforeOrder { UNSUBSCRIBED, UNSUBSCRIBED_THEN_UNCONFIRMED_SIGNUP }

    @ParameterizedTest
    @EnumSource(BeforeOrder.class)
    void unsubscribedWhenTheOrderCameIn_backfillKeepsTheLiveRefusalAfterAReconsent(BeforeOrder before) {
        Organization org = org();
        String email = email("before-order-" + before.name().toLowerCase());
        projector.upsertMembership(org.getId(), email, email);
        UUID membershipId = (UUID) membership(org, email).get("membership_id");
        consentService.unsubscribe(org.getId(), membershipId, "one_click", ConsentOrigin.DATA_SUBJECT, null);
        if (before == BeforeOrder.UNSUBSCRIBED_THEN_UNCONFIRMED_SIGNUP) {
            // A door sign-up whose address is never confirmed grants nothing, so the member stays unsubscribed.
            consentService.capture(org.getId(), membershipId, "explicit", "door_qr", "Signed up at the door",
                    "email", null, null, ConsentOrigin.DATA_SUBJECT, null);
        }
        Order order = optedInOrder(org, email);
        // The live projection sees an unsubscribed member and records no grant, on purpose.
        projector.onTicketsIssued(new TicketsIssuedEvent(order.getId()));
        AsyncDrain.drain(asyncExecutor);
        assertThat(checkoutGrants(order)).isEmpty();
        resubscribe(org, membershipId);

        runBackfill();

        assertThat(checkoutGrants(order)).isEmpty();
    }

    enum EarlierGrant { EARLIER_BACKFILL_RUN, RECORDED_BEFORE_ORDER_ID_COLUMN }

    @ParameterizedTest
    @EnumSource(EarlierGrant.class)
    void grantAlreadyRecordedForTheOrder_backfillWritesNoSecond(EarlierGrant earlier) {
        Organization org = org();
        String email = email("dedup-" + earlier.name().toLowerCase());
        Order order = optedInOrder(org, email);
        projectFailing(order);
        switch (earlier) {
            case EARLIER_BACKFILL_RUN -> runBackfill();
            // Rows before V132 carry the order id only at the end of proof_text.
            case RECORDED_BEFORE_ORDER_ID_COLUMN -> {
                projector.upsertMembership(org.getId(), email, email);
                UUID membershipId = (UUID) membership(org, email).get("membership_id");
                jdbc.update("insert into consent_records (id, membership_id, channel, status, lawful_basis, source,"
                                + " proof_text, confirmation_required, occurred_at) values (?, ?, 'email', 'subscribed',"
                                + " 'soft_opt_in', 'checkout', ?, false, now())",
                        UUID.randomUUID(), membershipId, "Left the box ticked at checkout, order " + order.getId());
                jdbc.update("update memberships set consent_status = 'subscribed', consent_basis = 'soft_opt_in'"
                        + " where membership_id = ?", membershipId);
            }
        }

        runBackfill();

        assertThat(checkoutRecordCount(org, email)).isEqualTo(1);
    }

    enum Ordering { LIVE_FIRST, BACKFILL_FIRST }

    @ParameterizedTest
    @EnumSource(Ordering.class)
    void liveProjectionAndBackfillOfOneOrder_eitherOrder_recordOneGrant(Ordering ordering) {
        Organization org = org();
        String email = email("both-" + ordering.name().toLowerCase());
        Order order = optedInOrder(org, email);
        if (ordering == Ordering.LIVE_FIRST) {
            projectLive(order);
            runBackfill();
        } else {
            // The backfill reached the order before its delayed live projection did.
            runBackfill();
            projectLive(order);
        }

        assertThat(checkoutRecordCount(org, email)).isEqualTo(1);
    }

    @Test
    void erasureCommittedAfterTheJobsLedgerSnapshot_backfillRowWritesNothing() {
        Organization org = org();
        String email = email("erased-late");
        Order order = optedInOrder(org, email);
        projectFailing(order);
        dsarService.recordErasure(org.getId(), email);

        // The row as the job reaches it with a ledger snapshot taken before that erasure.
        projector.backfillMembership(org.getId(), email, email);

        assertThat(checkoutGrants(order)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from memberships m join consumers c"
                + " on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?",
                Integer.class, org.getId(), email)).isZero();
    }

    private Organization org() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        return org;
    }

    private String email(String tag) {
        String email = fx.email(tag);
        emails.add(email);
        return email;
    }

    private Order optedInOrder(Organization org, String email) {
        Order order = fx.order(fx.event(org, fx.owner(org), EventStatus.LIVE, Instant.now().plusSeconds(86_400)), email);
        order.setMarketingOptIn(true);
        order.setMarketingOptInProof(PROOF);
        return orders.save(order);
    }

    /** The person's own re-consent, which also lifts the objection their unsubscribe set. */
    private void resubscribe(Organization org, UUID membershipId) {
        consentService.capture(org.getId(), membershipId, "explicit", "preference_centre_row",
                "Turned emails back on", "email", null, null, ConsentOrigin.DATA_SUBJECT, null);
    }

    private void projectLive(Order order) {
        projector.onTicketsIssued(new TicketsIssuedEvent(order.getId()));
        AsyncDrain.drain(asyncExecutor);
    }

    /** The live projection, through the real async listener, with its commit rejected at the consent row. */
    private void projectFailing(Order order) {
        try (PgFaults.Fault fault = PgFaults.failWrites(jdbc, "consent_records", "order_id", order.getId())) {
            projectLive(order);
        }
    }

    /** {@code run()} is {@code @SchedulerLock}ed: expire the row first, or the call is a silent no-op. */
    private void runBackfill() {
        jdbc.update("update shedlock set lock_until = locked_at where name = 'audience_backfill'");
        backfillJob.run();
    }

    private Instant storedCreatedAt(Order order) {
        return jdbc.queryForObject("select created_at from orders where id = ?", Timestamp.class, order.getId())
                .toInstant();
    }

    private List<Map<String, Object>> checkoutGrants(Order order) {
        return jdbc.queryForList("select status, lawful_basis, source, proof_text, text_version, occurred_at"
                + " from consent_records where order_id = ? and channel = 'email'", order.getId());
    }

    private int checkoutRecordCount(Organization org, String email) {
        UUID membershipId = (UUID) membership(org, email).get("membership_id");
        return jdbc.queryForObject("select count(*) from consent_records where membership_id = ?"
                + " and channel = 'email' and source = 'checkout'", Integer.class, membershipId);
    }

    private Map<String, Object> membership(Organization org, String email) {
        return jdbc.queryForMap("select m.membership_id, m.consent_status, m.consent_basis, m.objected_profiling"
                + " from memberships m join consumers c on c.consumer_id = m.consumer_id"
                + " where m.org_id = ? and c.normalized_email = ?", org.getId(), email);
    }
}
