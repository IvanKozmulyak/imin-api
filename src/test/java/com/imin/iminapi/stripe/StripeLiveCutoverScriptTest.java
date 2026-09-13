package com.imin.iminapi.stripe;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.ProcessedWebhookEvent;
import com.imin.iminapi.model.ReservationStatus;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketReservation;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.payout.PayoutRun;
import com.imin.iminapi.payout.PayoutRunRepository;
import com.imin.iminapi.payout.PayoutRunStatus;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.ProcessedWebhookEventRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketReservationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code scripts/stripe-live-cutover.sql} — the real artefact, executed through
 * {@link ScriptUtils}, not a stripped copy of it — does exactly what the runbook says.
 *
 * <p><b>Isolated database on purpose.</b> The script's UPDATEs are table-wide, so on the
 * shared {@code jdbc:h2:mem:imin} context it would rewrite rows other test classes left
 * behind and its assertions would depend on execution order. A distinct
 * {@code spring.datasource.url} is a distinct context-cache key, so Flyway builds a fresh
 * schema for this class alone. Not {@code @Transactional}: the script runs on a real
 * connection and has to see committed rows.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:cutover;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@Import(TestRateLimitConfig.class)
class StripeLiveCutoverScriptTest {

    private static final Path RESET = Path.of("scripts/stripe-live-cutover.sql");
    private static final Path POSTCHECK = Path.of("scripts/stripe-live-cutover-postcheck.sql");
    private static final String CUTOVER = "TEST_MODE_CUTOVER";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired TicketReservationRepository reservations;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired RefundRepository refunds;
    @Autowired DisputeRepository disputes;
    @Autowired PayoutRunRepository payoutRuns;
    @Autowired ProcessedWebhookEventRepository webhookEvents;
    @Autowired BuyerAccountRepository buyerAccounts;

    /** The connected org whose columns the reset clears. */
    private Organization connectedOrg;
    /** Control: never connected, every column already at its reset value. */
    private Organization cleanOrg;
    private Event event;
    private TicketTier tierWithStripeIds;
    private TicketTier tierWithHeldSeats;
    private TicketTier tierWithDriftedReserved;
    /** Control: counter drift with no reservation row at all — out of the cutover's scope. */
    private TicketTier tierWithUnrelatedDrift;
    private TicketReservation cardHold;
    private TicketReservation asyncHold;
    private TicketReservation confirmedControl;
    private Order order;
    private Ticket ticket;
    private Refund frontedRefund;
    private Refund alreadyRecoveredRefund;
    private Refund pendingRefund;
    private Dispute openDispute;
    private Dispute lostDispute;
    private ProcessedWebhookEvent webhookEvent;
    private BuyerAccount buyerAccount;
    private Instant eventUpdatedAtBefore;

    @BeforeEach
    void seed() {
        wipe();

        connectedOrg = org("connected", o -> {
            o.setStripeAccountId("acct_test_cutover_1");
            o.setStripeConnectState(StripeConnectState.ACTIVE);
            o.setStripePayoutsEnabled(true);
            o.setStripeDetailsSubmitted(true);
            o.setStripePayoutScheduleManual(true);
            o.setStripeRequirementsCurrentlyDue(List.of("individual.verification.document"));
            o.setStripeRequirementsPastDue(List.of("individual.id_number"));
            o.setStripeDisabledReason("requirements.past_due");
            o.setStripeConnectStatusUpdatedAt(Instant.parse("2026-09-01T10:00:00Z"));
            o.setStripeLivemode(false);
        });
        cleanOrg = org("clean", o -> { });

        event = endedEvent(connectedOrg);
        eventUpdatedAtBefore = event.getUpdatedAt();

        tierWithStripeIds = tier(event, 0, t -> {
            t.setStripeProductId("prod_test_1");
            t.setStripePriceId("price_test_1");
        });
        tierWithHeldSeats = tier(event, 5, t -> { });
        tierWithDriftedReserved = tier(event, 1, t -> { });
        tierWithUnrelatedDrift = tier(event, 4, t -> { });

        cardHold = hold(tierWithHeldSeats, 3, ReservationStatus.HELD, null);
        asyncHold = hold(tierWithDriftedReserved, 3, ReservationStatus.HELD,
                Instant.now().minus(1, ChronoUnit.DAYS));
        confirmedControl = hold(tierWithStripeIds, 2, ReservationStatus.CONFIRMED, null);

        order = order(event);
        ticket = ticket(order, tierWithStripeIds);

        frontedRefund = refund(order, r -> {
            r.setStatus(RefundStatus.SUCCEEDED);
            r.setPlatformFunded(true);
        });
        alreadyRecoveredRefund = refund(order, r -> {
            r.setStatus(RefundStatus.SUCCEEDED);
            r.setPlatformFunded(true);
            r.setRecoveredAt(Instant.parse("2026-08-01T09:00:00Z"));
            r.setRecoveryReversalId("trr_already_done");
        });
        pendingRefund = refund(order, r -> r.setStatus(RefundStatus.PENDING));

        openDispute = dispute(DisputeStatus.OPEN);
        lostDispute = dispute(DisputeStatus.LOST);

        // One row per non-terminal status, and one per terminal status as the negative control.
        // UNIQUE(event_id, attempt) forces a distinct attempt per row.
        run(1, PayoutRunStatus.PLANNED);
        run(2, PayoutRunStatus.SUBMITTED);
        run(3, PayoutRunStatus.RETRYING);
        run(4, PayoutRunStatus.PAID);
        run(5, PayoutRunStatus.PARTIAL);
        run(6, PayoutRunStatus.FAILED);
        run(7, PayoutRunStatus.BLOCKED);

        webhookEvent = webhookEvent();
        buyerAccount = buyerAccounts.save(new BuyerAccount());
    }

    // ── §1  ticket_tiers Stripe ids ────────────────────────────────────────────

    @Test
    void clearsTierProductAndPriceIds() {
        runReset();

        TicketTier after = tiers.findById(tierWithStripeIds.getId()).orElseThrow();
        assertThat(after.getStripeProductId()).isNull();
        assertThat(after.getStripePriceId()).isNull();
    }

    // ── §2  organizations ──────────────────────────────────────────────────────

    @Test
    void resetsAllNineOrganizationStripeColumns() {
        runReset();

        Organization after = orgs.findById(connectedOrg.getId()).orElseThrow();
        assertThat(after.getStripeAccountId()).isNull();
        assertThat(after.getStripeConnectState()).isEqualTo(StripeConnectState.NOT_STARTED);
        assertThat(after.isStripePayoutsEnabled()).isFalse();
        assertThat(after.isStripeDetailsSubmitted()).isFalse();
        assertThat(after.isStripePayoutScheduleManual()).isFalse();
        assertThat(after.getStripeRequirementsCurrentlyDue()).isEmpty();
        assertThat(after.getStripeRequirementsPastDue()).isEmpty();
        assertThat(after.getStripeDisabledReason()).isNull();
        assertThat(after.getStripeConnectStatusUpdatedAt()).isNull();
        // The V129 guard column too: no account left for a recorded mode to describe.
        assertThat(after.getStripeLivemode()).isNull();
    }

    // ── §3  ticket_reservations ────────────────────────────────────────────────

    @Test
    void releasesHeldReservationsWithTheCutoverReason() {
        runReset();

        TicketReservation after = reservations.findById(cardHold.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(after.getReleasedAt()).isNotNull();
        assertThat(after.getReleaseReason()).isEqualTo(CUTOVER);
    }

    @Test
    void leavesAsyncProcessingHoldsReleasedToo() {
        // A V125 async hold expires seven days out — the sweeper would not have collected it
        // for a week, so only this script frees the seat.
        assertThat(asyncHold.getAsyncProcessingAt()).isNotNull();
        assertThat(asyncHold.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(6)));

        runReset();

        TicketReservation after = reservations.findById(asyncHold.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(after.getReleaseReason()).isEqualTo(CUTOVER);
    }

    @Test
    void leavesConfirmedReservationsAlone() {
        runReset();

        TicketReservation after = reservations.findById(confirmedControl.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(after.getReleaseReason()).isNull();
        assertThat(after.getReleasedAt()).isNull();
    }

    // ── §4  ticket_tiers.reserved ──────────────────────────────────────────────

    @Test
    void reDerivesReservedFromTheRemainingHoldsAfterReleasingThem() {
        runReset();

        assertThat(tiers.findById(tierWithHeldSeats.getId()).orElseThrow().getReserved())
                .as("nothing is HELD once §3 has run, so the derived counter is 0 — not "
                        + "reserved(5) minus the released qty(3), which would leave the tier "
                        + "short of stock for every seat a subtraction missed")
                .isZero();
        assertThat(reservations.findById(cardHold.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void reDerivingCannotDriveReservedNegative() {
        runReset();

        assertThat(tiers.findById(tierWithDriftedReserved.getId()).orElseThrow().getReserved())
                .as("reserved 1 against a HELD qty of 3 — re-derived to 0, never negative")
                .isZero();
    }

    @Test
    void leavesTiersThisCutoverDidNotReleaseAlone() {
        runReset();

        assertThat(tiers.findById(tierWithUnrelatedDrift.getId()).orElseThrow().getReserved())
                .as("no hold of this tier was released here, so its pre-existing drift is a "
                        + "human's call, not the cutover's")
                .isEqualTo(4);
    }

    // ── §5  payout_runs ────────────────────────────────────────────────────────

    @Test
    void parksEveryNonTerminalPayoutRunBlocked() {
        runReset();

        for (int attempt : new int[]{1, 2, 3}) {
            PayoutRun after = runByAttempt(attempt);
            assertThat(after.getStatus()).as("attempt " + attempt).isEqualTo(PayoutRunStatus.BLOCKED);
            assertThat(after.getFailureReason()).as("attempt " + attempt).isEqualTo(CUTOVER);
        }
    }

    @Test
    void leavesTerminalPayoutRunsAlone() {
        Map<Integer, PayoutRunStatus> terminal = Map.of(
                4, PayoutRunStatus.PAID,
                5, PayoutRunStatus.PARTIAL,
                6, PayoutRunStatus.FAILED,
                7, PayoutRunStatus.BLOCKED);
        Map<Integer, Instant> updatedBefore = terminal.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(a -> a, a -> runByAttempt(a).getUpdatedAt()));

        runReset();

        terminal.forEach((attempt, status) -> {
            PayoutRun after = runByAttempt(attempt);
            assertThat(after.getStatus()).as("attempt " + attempt).isEqualTo(status);
            assertThat(after.getFailureReason()).as("attempt " + attempt).isNull();
            assertThat(after.getUpdatedAt()).as("attempt " + attempt)
                    .isEqualTo(updatedBefore.get(attempt));
        });
    }

    // ── §6  refunds ────────────────────────────────────────────────────────────

    @Test
    void closesTheUnrecoveredPlatformFundedDebt() {
        runReset();

        Refund after = refunds.findById(frontedRefund.getId()).orElseThrow();
        assertThat(after.getRecoveredAt()).isNotNull();
        assertThat(after.getRecoveryReversalId()).isEqualTo(CUTOVER);

        Refund untouched = refunds.findById(alreadyRecoveredRefund.getId()).orElseThrow();
        assertThat(untouched.getRecoveryReversalId())
                .as("an already-recovered row keeps the real reversal id")
                .isEqualTo("trr_already_done");
        assertThat(untouched.getRecoveredAt()).isEqualTo(Instant.parse("2026-08-01T09:00:00Z"));
    }

    @Test
    void leavesPendingRefundsAlone() {
        runReset();

        Refund after = refunds.findById(pendingRefund.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(after.getRecoveredAt()).isNull();
        assertThat(after.getRecoveryReversalId()).isNull();
        assertThat(after.getUpdatedAt()).isEqualTo(pendingRefund.getUpdatedAt());
    }

    // ── §7  disputes ───────────────────────────────────────────────────────────

    @Test
    void closesOpenDisputesAsWithdrawnReinstated() {
        runReset();

        Dispute after = disputes.findById(openDispute.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DisputeStatus.WITHDRAWN_REINSTATED);
        assertThat(after.getClosedAt()).isNotNull();

        Dispute lost = disputes.findById(lostDispute.getId()).orElseThrow();
        assertThat(lost.getStatus()).as("a lost dispute is history, not in-flight")
                .isEqualTo(DisputeStatus.LOST);
        assertThat(lost.getClosedAt()).isNull();
    }

    // ── §8  test_mode flags ────────────────────────────────────────────────────

    @Test
    void flagsEveryExistingOrderAsTestMode() {
        assertThat(order.isTestMode()).isFalse();

        runReset();

        assertThat(orders.findById(order.getId()).orElseThrow().isTestMode())
                .as("test-era money must never feed a live payout")
                .isTrue();
    }

    @Test
    void flagsEveryExistingPayoutRunAsTestMode() {
        runReset();

        assertThat(payoutRuns.findAll())
                .as("a test-era payout moved nothing out of a live balance, so it must not "
                        + "count as already triggered against a live net")
                .isNotEmpty()
                .allMatch(PayoutRun::isTestMode);
    }

    @Test
    void flagsEveryExistingDisputeAsTestMode() {
        runReset();

        assertThat(disputes.findAll())
                .as("including the LOST one the reset leaves closed — it withheld nothing real")
                .isNotEmpty()
                .allMatch(Dispute::isTestMode);
    }

    // ── controls + idempotency ─────────────────────────────────────────────────

    @Test
    void leavesNonStripeRowsUntouched() {
        runReset();

        Organization after = orgs.findById(cleanOrg.getId()).orElseThrow();
        assertThat(after.getStripeAccountId()).isNull();
        assertThat(after.getStripeConnectState()).isEqualTo(StripeConnectState.NOT_STARTED);
        assertThat(after.getStripeRequirementsCurrentlyDue()).isEmpty();

        assertThat(events.findById(event.getId()).orElseThrow().getUpdatedAt())
                .as("the reset is not an organizer edit; updated_at is an ETag")
                .isEqualTo(eventUpdatedAtBefore);

        Order orderAfter = orders.findById(order.getId()).orElseThrow();
        assertThat(orderAfter.getStripePaymentIntentId())
                .isEqualTo(order.getStripePaymentIntentId());
        assertThat(orderAfter.getToken()).isEqualTo(order.getToken());
        assertThat(orderAfter.getTotalMinor()).isEqualTo(order.getTotalMinor());

        Ticket ticketAfter = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(ticketAfter.getToken()).isEqualTo(ticket.getToken());
        assertThat(ticketAfter.getState()).isEqualTo(ticket.getState());

        assertThat(webhookEvents.findById(webhookEvent.getStripeEventId()).orElseThrow().getEventType())
                .as("the dedup key is untouched — a live event id cannot collide with a test one")
                .isEqualTo(webhookEvent.getEventType());
        assertThat(buyerAccounts.findById(buyerAccount.getId())).isPresent();
    }

    @Test
    void isIdempotent() {
        runReset();

        int reservedAfterFirst = tiers.findById(tierWithHeldSeats.getId()).orElseThrow().getReserved();
        Instant releasedAtAfterFirst =
                reservations.findById(cardHold.getId()).orElseThrow().getReleasedAt();
        Instant recoveredAtAfterFirst =
                refunds.findById(frontedRefund.getId()).orElseThrow().getRecoveredAt();

        runReset();

        assertThat(tiers.findById(tierWithHeldSeats.getId()).orElseThrow().getReserved())
                .as("the seats are given back once, not once per run")
                .isEqualTo(reservedAfterFirst);
        assertThat(reservations.findById(cardHold.getId()).orElseThrow().getReleasedAt())
                .isEqualTo(releasedAtAfterFirst);
        assertThat(refunds.findById(frontedRefund.getId()).orElseThrow().getRecoveredAt())
                .isEqualTo(recoveredAtAfterFirst);
        assertThat(runByAttempt(1).getStatus()).isEqualTo(PayoutRunStatus.BLOCKED);
    }

    @Test
    void postCheckQueriesAllReturnZero() {
        runReset();

        List<Map<String, Object>> rows = jdbc.queryForList(readPostCheckQuery());

        assertThat(rows).as("a truncated post-check file must not pass vacuously").isNotEmpty();
        for (Map<String, Object> row : rows) {
            Object name = row.get("check_name");
            Number n = (Number) row.get("n");
            assertThat(n).as("post-check %s returned no count", name).isNotNull();
            assertThat(n.longValue()).as("post-check %s", name).isZero();
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────────

    /** Executes the shipped artefact itself, so the test can never drift from the file. */
    private void runReset() {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource(RESET));
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + RESET, e);
        }
    }

    /**
     * The post-check file is a single SELECT; JdbcTemplate needs it without the leading
     * comment block or the trailing semicolon.
     */
    private String readPostCheckQuery() {
        String raw;
        try {
            raw = Files.readString(POSTCHECK, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + POSTCHECK, e);
        }
        String sql = Arrays.stream(raw.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
    }

    private PayoutRun runByAttempt(int attempt) {
        return payoutRuns.findAll().stream()
                .filter(r -> r.getAttempt() == attempt)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no payout run with attempt " + attempt));
    }

    private void wipe() {
        payoutRuns.deleteAll();
        disputes.deleteAll();
        refunds.deleteAll();
        tickets.deleteAll();
        orders.deleteAll();
        reservations.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
        webhookEvents.deleteAll();
        buyerAccounts.deleteAll();
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private Organization org(String label, java.util.function.Consumer<Organization> tweak) {
        Organization o = new Organization();
        o.setName("Org " + label);
        o.setSlug(label + "-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail(label + "@test.example");
        o.setCountry("DE");
        tweak.accept(o);
        return orgs.save(o);
    }

    private Event endedEvent(Organization owner) {
        User u = new User();
        u.setOrgId(owner.getId());
        u.setEmail("u-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        UUID userId = users.save(u).getId();

        Event e = new Event();
        e.setOrgId(owner.getId());
        e.setName("Cutover night");
        e.setSlug("cutover-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setCurrency("EUR");
        e.setEndsAt(Instant.now().minus(10, ChronoUnit.DAYS));
        e.setCreatedBy(userId);
        return events.save(e);
    }

    private TicketTier tier(Event e, int reserved, java.util.function.Consumer<TicketTier> tweak) {
        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("Tier " + reserved + "-" + UUID.randomUUID().toString().substring(0, 4));
        t.setPriceMinor(2_000);
        t.setQuantity(100);
        t.setReserved(reserved);
        tweak.accept(t);
        return tiers.save(t);
    }

    private TicketReservation hold(TicketTier t, int qty, ReservationStatus status, Instant asyncAt) {
        TicketReservation r = new TicketReservation();
        r.setTierId(t.getId());
        r.setQty(qty);
        r.setStatus(status);
        r.setAsyncProcessingAt(asyncAt);
        // An async hold's expiry is pushed out to the async-payment window (7 days); a card
        // hold keeps the 30-minute checkout-session window.
        r.setExpiresAt(asyncAt == null
                ? Instant.now().plus(Duration.ofMinutes(30))
                : Instant.now().plus(Duration.ofDays(7)));
        if (status == ReservationStatus.CONFIRMED) {
            r.setConfirmedAt(Instant.now());
        }
        return reservations.save(r);
    }

    private Order order(Event e) {
        Order o = new Order();
        o.setToken(token());
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(4_000);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(400);
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_test_cutover_1");
        o.setStripeSessionId("cs_test_cutover_1");
        return orders.save(o);
    }

    private Ticket ticket(Order o, TicketTier t) {
        Ticket tk = new Ticket();
        tk.setToken(token());
        tk.setOrderId(o.getId());
        tk.setEventId(o.getEventId());
        tk.setTierId(t.getId());
        tk.setTierName(t.getName());
        tk.setPriceMinor(t.getPriceMinor());
        return tickets.save(tk);
    }

    private Refund refund(Order o, java.util.function.Consumer<Refund> tweak) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_test_cutover_1");
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setStripeChargeId("ch_test_cutover_1");
        r.setAmountMinor(2_000);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(200);
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        tweak.accept(r);
        return refunds.save(r);
    }

    private Dispute dispute(DisputeStatus status) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        d.setOrgId(connectedOrg.getId());
        d.setEventId(event.getId());
        d.setOrderId(order.getId());
        d.setStripeChargeId("ch_test_cutover_1");
        d.setAmountMinor(2_000);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setOpenedAt(Instant.now().minus(5, ChronoUnit.DAYS));
        return disputes.save(d);
    }

    private PayoutRun run(int attempt, PayoutRunStatus status) {
        PayoutRun r = new PayoutRun();
        r.setOrgId(connectedOrg.getId());
        r.setEventId(event.getId());
        r.setStripeAccountId("acct_test_cutover_1");
        r.setAmountMinor(1_000);
        r.setCurrency("eur");
        r.setStatus(status);
        r.setAttempt(attempt);
        r.setIdempotencyKey("evt:" + event.getId() + ":attempt:" + attempt);
        return payoutRuns.save(r);
    }

    private ProcessedWebhookEvent webhookEvent() {
        ProcessedWebhookEvent w = new ProcessedWebhookEvent();
        w.setStripeEventId("evt_test_cutover_1");
        w.setEventType("payment_intent.succeeded");
        return webhookEvents.save(w);
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
