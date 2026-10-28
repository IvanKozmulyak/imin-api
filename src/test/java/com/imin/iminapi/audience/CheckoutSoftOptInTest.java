package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.audience.service.SuppressionService;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.CheckoutConsent;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.event.FreeCheckoutService;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paid checkout under the notice + refuse box: a soft opt-in is written only for a paid order whose allowlisted
 * notice named the organizer and was not refused, and a refusal is recorded as the buyer's own objection.
 */
@IminIntegrationTest
class CheckoutSoftOptInTest {

    /** On the soft opt-in notice allowlist in audienceplan/logic-v1.yaml. */
    private static final String NOTICE_VERSION = "checkout-soft-notice-2026-10";
    /** On the organizer-named checkout label allowlist. */
    private static final String ORG_NAMED_VERSION = "checkout-org-named-2026-09";

    @Autowired AudienceOrderProjector projector;
    @Autowired ConsentService consentService;
    @Autowired SuppressionService suppressionService;
    @Autowired DsarService dsarService;
    @Autowired MarketingOptOutRepository optOuts;
    @Autowired ConsentGate gate;
    @Autowired AudiencePlanProperties planProps;
    @Autowired PropertyFlips flips;
    @Autowired OrderRepository orders;
    @Autowired FreeCheckoutService freeCheckout;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("taskExecutor") Executor asyncExecutor;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;

    private Organization org;
    private User owner;
    private Event event;
    private final List<String> emails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        org = fx.org();
        owner = fx.owner(org);
        event = fx.event(org, owner, EventStatus.LIVE, Instant.now().plusSeconds(86_400));
    }

    @AfterEach
    void tearDown() {
        AsyncDrain.drain(asyncExecutor);
        AsyncDrain.drain(fanFeatureExecutor);
        try {
            jdbc.update("delete from memberships where org_id = ?", org.getId());
            jdbc.update("delete from marketing_optouts where org_id = ?", org.getId());
            for (String email : emails) {
                jdbc.update("delete from consumers where normalized_email = ?", email);
                jdbc.update("delete from erased_addresses where email_normalized = ?", email);
            }
        } finally {
            OrgRows.delete(jdbc, List.of(org.getId()));
        }
    }

    enum Buyer { NEW_BUYER, HISTORICAL_PRE_TICKED_SOFT_ROW }

    @ParameterizedTest
    @EnumSource(Buyer.class)
    void paidOrder_noticeShownNotRefused_recordsSoftOptIn(Buyer buyer) {
        String email = email("soft-" + buyer.name().toLowerCase());
        if (buyer == Buyer.HISTORICAL_PRE_TICKED_SOFT_ROW) {
            // The pre-notice shape: no order id, no version; the member is subscribed on it.
            UUID mid = member(email);
            jdbc.update("insert into consent_records (id, membership_id, channel, status, lawful_basis, source,"
                            + " proof_text, confirmation_required, occurred_at) values (?, ?, 'email', 'subscribed',"
                            + " 'soft_opt_in', 'checkout', ?, false, now() - interval '30 days')",
                    UUID.randomUUID(), mid, "Left the pre-ticked marketing box ticked at checkout, order "
                            + UUID.randomUUID());
            jdbc.update("update memberships set consent_status = 'subscribed', consent_basis = 'soft_opt_in'"
                    + " where membership_id = ?", mid);
        }
        Order order = paidOrder(email, o -> notice(o, notice()));

        projectLive(order);

        List<Map<String, Object>> records = records(order);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)).containsEntry("status", "subscribed").containsEntry("lawful_basis", "soft_opt_in")
                .containsEntry("source", "checkout").containsEntry("text_version", NOTICE_VERSION)
                .containsEntry("proof_text", "Paid order; shown at checkout and left the refuse box unticked: \""
                        + notice() + "\", order " + order.getId());
        assertThat(membership(email)).containsEntry("consent_status", "subscribed")
                .containsEntry("consent_basis", "soft_opt_in").containsEntry("objected_profiling", false);
    }

    enum NotShown { NO_NOTICE_FIELDS, VERSION_ABSENT, VERSION_NOT_ON_ALLOWLIST, BLANK_PROOF,
        PROOF_NOT_NAMING_THE_ORGANIZER, FREE_ORDER }

    @ParameterizedTest
    @EnumSource(NotShown.class)
    void noticeNotShown_recordsNothing(NotShown shape) {
        String email = email("not-shown-" + shape.name().toLowerCase());
        Order order = paidOrder(email, o -> {
            switch (shape) {
                // A stale tab, or the fan-app: no notice fields reach the order.
                case NO_NOTICE_FIELDS -> { }
                // A notice that arrives without its version id.
                case VERSION_ABSENT -> o.setMarketingNoticeProof(notice());
                case VERSION_NOT_ON_ALLOWLIST -> {
                    o.setMarketingNoticeProof(notice());
                    o.setMarketingNoticeTextVersion("checkout-soft-notice-2099-01");
                }
                case BLANK_PROOF -> {
                    o.setMarketingNoticeProof("   ");
                    o.setMarketingNoticeTextVersion(NOTICE_VERSION);
                }
                case PROOF_NOT_NAMING_THE_ORGANIZER -> notice(o, "This organiser may email you about its events.");
                case FREE_ORDER -> {
                    notice(o, notice());
                    o.setPaymentMethod("free");
                    o.setTotalMinor(0L);
                }
            }
        });

        projectLive(order);

        assertThat(records(order)).isEmpty();
        assertThat(softRecordCount(email)).isZero();
        assertThat(membership(email).get("consent_basis")).isNull();
    }

    enum OptedOut { OPERATOR_UNSUBSCRIBED, PERMANENT_OPT_OUT_ONLY, MARKETING_SUPPRESSION, OBJECTED_ONLY,
        ERASED_FOR_THE_ORG }

    @ParameterizedTest
    @EnumSource(OptedOut.class)
    void optedOutMember_getsNoSoftOptIn(OptedOut optedOut) {
        String email = email("opted-out-" + optedOut.name().toLowerCase());
        UUID mid = member(email);
        switch (optedOut) {
            case OPERATOR_UNSUBSCRIBED -> consentService.unsubscribe(org.getId(), mid, "organizer_tidy",
                    ConsentOrigin.OPERATOR, fx.principal(owner));
            // The membership is untouched; only the address-level row says no.
            case PERMANENT_OPT_OUT_ONLY -> optOuts.save(MarketingOptOut.of(email, org.getId(), "email", "one_click"));
            case MARKETING_SUPPRESSION -> suppressionService.addMarketing(org.getId(), mid,
                    SuppressionEntry.REASON_MANUAL, fx.principal(owner));
            // A spam complaint sets the objection alone.
            case OBJECTED_ONLY -> jdbc.update("update memberships set objected_profiling = true"
                    + " where membership_id = ?", mid);
            case ERASED_FOR_THE_ORG -> dsarService.recordErasure(org.getId(), email);
        }
        Map<String, Object> before = membership(email);
        Order order = paidOrder(email, o -> notice(o, notice()));

        projectLive(order);

        assertThat(records(order)).isEmpty();
        assertThat(softRecordCount(email)).isZero();
        Map<String, Object> after = membership(email);
        assertThat(after.get("consent_basis")).isEqualTo(before.get("consent_basis"));
        assertThat(after.get("objected_profiling")).isEqualTo(before.get("objected_profiling"));
    }

    /** A newer soft record would outrank the proven explicit one, so the member would vanish from every send. */
    @Test
    void explicitSubscriber_isNotShadowedBySoftOptIn() {
        flips.set(planProps, "softOptInEnabled", false);
        String email = email("explicit");
        Order ticked = paidOrder(email, o -> tickedBox(o));
        projectLive(ticked);
        UUID mid = (UUID) membership(email).get("membership_id");
        assertThat(gate.canMarket(org.getId(), mid)).isTrue();
        Order later = paidOrder(email, o -> notice(o, notice()));

        projectLive(later);

        assertThat(records(later)).isEmpty();
        assertThat(softRecordCount(email)).isZero();
        assertThat(membership(email)).containsEntry("consent_basis", "explicit");
        assertThat(gate.canMarket(org.getId(), mid)).isTrue();
    }

    enum Refuser { NEW_BUYER, EXPLICIT_SUBSCRIBER, REFUSED_AND_TICKED_ON_ONE_ORDER,
        OPERATOR_UNSUBSCRIBED_AND_OBJECTED }

    @ParameterizedTest
    @EnumSource(Refuser.class)
    void refused_recordsAnObjectionAndNoConsent(Refuser refuser) {
        String email = email("refused-" + refuser.name().toLowerCase());
        if (refuser == Refuser.EXPLICIT_SUBSCRIBER) {
            projectLive(paidOrder(email, o -> tickedBox(o)));
            assertThat(membership(email)).containsEntry("consent_basis", "explicit");
        }
        if (refuser == Refuser.OPERATOR_UNSUBSCRIBED_AND_OBJECTED) {
            // Already unsubscribed and objected, but with no permanent opt-out: an operator tidy plus a complaint.
            UUID mid = member(email);
            consentService.unsubscribe(org.getId(), mid, "organizer_tidy", ConsentOrigin.OPERATOR, fx.principal(owner));
            jdbc.update("update memberships set objected_profiling = true where membership_id = ?", mid);
        }
        Order order = paidOrder(email, o -> {
            notice(o, notice());
            o.setMarketingRefused(true);
            if (refuser == Refuser.REFUSED_AND_TICKED_ON_ONE_ORDER) tickedBox(o);
        });

        projectLive(order);

        assertThat(records(order)).isEmpty();
        assertThat(softRecordCount(email)).isZero();
        Map<String, Object> m = membership(email);
        assertThat(jdbc.queryForList("select status, coalesce(lawful_basis, 'none') as basis from consent_records"
                + " where membership_id = ? and channel = 'email' and source = 'checkout_refusal'",
                m.get("membership_id"))).containsExactly(Map.of("status", "unsubscribed", "basis", "none"));
        assertThat(m).containsEntry("consent_status", "unsubscribed").containsEntry("objected_profiling", true);
        assertThat(m.get("consent_basis")).isNull();
        assertThat(jdbc.queryForList("select channel, source from marketing_optouts where email_normalized = ?"
                + " and org_id = ?", email, org.getId()))
                .containsExactly(Map.of("channel", "email", "source", "checkout_refusal"));
    }

    /** The free checkout stamps the refusal before the order's first insert; the refusal is honoured there too. */
    @Test
    void freeOrder_refused_recordsAnObjection() {
        String email = email("free-refused");
        TicketTier free = fx.tier(event, 0, 50);

        Order order = freeCheckout.issueFreeOrder(event, free, 1, email, null, false, false, CheckoutAttribution.NONE,
                null, null, new CheckoutConsent(true, null, null, notice(), NOTICE_VERSION, true));
        AsyncDrain.drain(asyncExecutor);

        assertThat(jdbc.queryForObject("select marketing_refused from orders where id = ?", Boolean.class,
                order.getId())).isTrue();
        assertThat(records(order)).isEmpty();
        Map<String, Object> m = membership(email);
        assertThat(m).containsEntry("consent_status", "unsubscribed").containsEntry("objected_profiling", true);
        assertThat(jdbc.queryForObject("select count(*) from consent_records where membership_id = ?"
                + " and status = 'unsubscribed' and source = 'checkout_refusal'", Integer.class,
                m.get("membership_id"))).isEqualTo(1);
        assertThat(jdbc.queryForList("select channel, source from marketing_optouts where email_normalized = ?"
                + " and org_id = ?", email, org.getId()))
                .containsExactly(Map.of("channel", "email", "source", "checkout_refusal"));
    }

    @Test
    void sameOrderProjectedTwice_recordsOneSoftOptIn() {
        String email = email("twice-soft");
        Order order = paidOrder(email, o -> notice(o, notice()));

        projectLive(order);
        projectLive(order);

        assertThat(records(order)).hasSize(1);
    }

    @Test
    void refusalProjectedTwice_recordsOneObjection() {
        String email = email("twice-refused");
        Order order = paidOrder(email, o -> {
            notice(o, notice());
            o.setMarketingRefused(true);
        });

        projectLive(order);
        projectLive(order);

        assertThat(jdbc.queryForObject("select count(*) from consent_records where membership_id = ?"
                + " and source = 'checkout_refusal'", Integer.class, membership(email).get("membership_id")))
                .isEqualTo(1);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    /** The notice and refuse-box label as the buyer site sends them, naming this organizer. */
    private String notice() {
        return org.getName() + " may email you about its events because you're buying a ticket to one."
                + " Every email has an unsubscribe link. Don't email me about events by " + org.getName() + ".";
    }

    private static void notice(Order o, String proof) {
        o.setMarketingNoticeProof(proof);
        o.setMarketingNoticeTextVersion(NOTICE_VERSION);
    }

    private void tickedBox(Order o) {
        o.setMarketingOptIn(true);
        o.setMarketingOptInProof("Email me about events by " + org.getName() + ". Unsubscribe anytime.");
        o.setMarketingOptInTextVersion(ORG_NAMED_VERSION);
    }

    private String email(String tag) {
        String email = fx.email(tag);
        emails.add(email);
        return email;
    }

    private UUID member(String email) {
        projector.upsertMembership(org.getId(), email, email);
        return (UUID) membership(email).get("membership_id");
    }

    /** A paid card order of this org with one live ticket; the notice columns are set before the first insert. */
    private Order paidOrder(String email, Consumer<Order> shape) {
        Order o = new Order();
        o.setToken("SOFT_" + UUID.randomUUID());
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail(email);
        o.setTotalMinor(1500L);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        shape.accept(o);
        o = orders.save(o);
        fx.ticket(o, Ticket.STATE_ISSUED);
        return o;
    }

    private void projectLive(Order order) {
        projector.onTicketsIssued(new TicketsIssuedEvent(order.getId()));
        AsyncDrain.drain(asyncExecutor);
    }

    private List<Map<String, Object>> records(Order order) {
        return jdbc.queryForList("select status, lawful_basis, source, text_version, proof_text"
                + " from consent_records where order_id = ? and channel = 'email'", order.getId());
    }

    private int softRecordCount(String email) {
        return jdbc.queryForObject("select count(*) from consent_records r join memberships m"
                + " on m.membership_id = r.membership_id join consumers c on c.consumer_id = m.consumer_id"
                + " where m.org_id = ? and c.normalized_email = ? and r.lawful_basis = 'soft_opt_in'"
                + " and r.order_id is not null", Integer.class, org.getId(), email);
    }

    private Map<String, Object> membership(String email) {
        return jdbc.queryForMap("select m.membership_id, m.consent_status, m.consent_basis, m.objected_profiling"
                + " from memberships m join consumers c on c.consumer_id = m.consumer_id"
                + " where m.org_id = ? and c.normalized_email = ?", org.getId(), email);
    }
}
