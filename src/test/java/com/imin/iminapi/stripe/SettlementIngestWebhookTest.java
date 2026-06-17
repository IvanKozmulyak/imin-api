package com.imin.iminapi.stripe;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.payout.PayoutRun;
import com.imin.iminapi.payout.PayoutRunRepository;
import com.imin.iminapi.payout.PayoutRunStatus;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.service.event.InventoryService;
import com.imin.iminapi.service.ticket.PaidCheckoutService;
import com.imin.iminapi.settlement.Settlement;
import com.imin.iminapi.settlement.SettlementObjectType;
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.settlement.SettlementStatus;
import com.stripe.StripeClient;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Track A settlements ingestion through the V1 webhook path. Unlike the
 * pure-unit {@link StripeWebhookServiceTest} (which mocks the dedup service), this test wires the
 * REAL {@link SettlementIngestService}, {@link SettlementRepository}, {@link OrganizationRepository}
 * and {@link WebhookEventDedupService} against the H2 schema so it can assert the actual
 * {@code settlements} row that gets written — and prove a redelivery is a real no-op via the real
 * {@code processed_webhook_events} dedup table.
 *
 * <p>Only the money-flow collaborators ({@link StripeClient}, {@link PaidCheckoutService},
 * {@link InventoryService}) are mocked — the transfer-ingest path never touches them, but mocking
 * keeps the context free of live Stripe/email side effects. Events are built as real Stripe-shaped
 * V1 JSON + HMAC signature so the service's own {@code Webhook.constructEvent} runs end-to-end.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class SettlementIngestWebhookTest {

    private static final String SECRET = "whsec_test_settlement_secret";

    @Autowired StripeWebhookService webhook;
    @Autowired StripeProperties props;
    @Autowired SettlementRepository settlements;
    @Autowired OrganizationRepository orgs;
    @Autowired PayoutRunRepository payoutRuns;
    @Autowired JdbcTemplate jdbc;

    // Mocked so the context loads without live Stripe/email; the transfer-ingest path doesn't use them.
    @MockitoBean StripeClient stripeClient;
    @MockitoBean PaidCheckoutService paidCheckoutService;
    @MockitoBean InventoryService inventoryService;

    private Organization org;
    private String acctId;

    @BeforeEach
    void setUp() {
        wipe();
        props.setWebhookSecretV1(SECRET);

        acctId = "acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        org = new Organization();
        org.setName("Settlement Org");
        org.setSlug("settlement-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("payouts@test.example");
        org.setCountry("DE");
        org.setStripeAccountId(acctId);
        org = orgs.save(org);
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private void wipe() {
        payoutRuns.deleteAll();
        settlements.deleteAll();
        jdbc.update("DELETE FROM processed_webhook_events");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM users");
        orgs.deleteAll();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String sign(String body) throws Exception {
        long ts = Instant.now().getEpochSecond();
        String signedPayload = ts + "." + body;
        String sig = Webhook.Util.computeHmacSha256(SECRET, signedPayload);
        return "t=" + ts + ",v1=" + sig;
    }

    /**
     * A real {@code transfer.created} V1 envelope. The event carries a top-level {@code account}
     * (the connected-account fallback) and the transfer's {@code destination} is the org acct id.
     */
    private String transferCreatedEvent(String eventId, String transferId, String destination,
                                        long amount, String currency) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "transfer.created",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "account": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "transfer",
                  "amount": %d,
                  "amount_reversed": 0,
                  "currency": "%s",
                  "destination": "%s",
                  "reversed": false,
                  "metadata": {}
                }
              }
            }
            """.formatted(eventId, Instant.now().getEpochSecond(), destination,
                transferId, amount, currency, destination);
    }

    /**
     * A real {@code charge.refunded} V1 envelope for a DESTINATION charge: the connected account
     * lives on {@code transfer_data.destination}, and the backing transfer on {@code source_transfer}.
     * {@code refunded} toggles full vs partial.
     */
    private String chargeRefundedEvent(String eventId, String chargeId, String sourceTransfer,
                                       String destination, long amount, boolean fullyRefunded) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "charge.refunded",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "account": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "charge",
                  "amount": %d,
                  "currency": "eur",
                  "refunded": %b,
                  "source_transfer": "%s",
                  "transfer_data": { "destination": "%s" },
                  "metadata": {}
                }
              }
            }
            """.formatted(eventId, Instant.now().getEpochSecond(), destination,
                chargeId, amount, fullyRefunded, sourceTransfer, destination);
    }

    /**
     * A real {@code charge.dispute.created} V1 envelope. The dispute carries an EXPANDED
     * {@code charge} object so the ingest can reach {@code charge.source_transfer}.
     */
    private String disputeEvent(String eventId, String disputeId, String chargeId,
                                String sourceTransfer, long amount) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "charge.dispute.created",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "account": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "dispute",
                  "amount": %d,
                  "currency": "eur",
                  "reason": "fraudulent",
                  "status": "needs_response",
                  "charge": {
                    "id": "%s",
                    "object": "charge",
                    "source_transfer": "%s"
                  }
                }
              }
            }
            """.formatted(eventId, Instant.now().getEpochSecond(), acctId,
                disputeId, amount, chargeId, sourceTransfer);
    }

    /**
     * A real {@code payout.paid} / {@code payout.failed} V1 envelope, settled ON the connected
     * account ({@code event.account = acctId}). Carries an {@code arrival_date} and, for the
     * failed case, a {@code failure_message}/{@code failure_code}.
     */
    private String payoutEvent(String eventId, String payoutId, String type,
                               long amount, String status, long arrivalDate, String failureMessage) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "%s",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "account": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "payout",
                  "amount": %d,
                  "currency": "eur",
                  "status": "%s",
                  "arrival_date": %d,
                  "failure_message": %s
                }
              }
            }
            """.formatted(eventId, type, Instant.now().getEpochSecond(), acctId,
                payoutId, amount, status, arrivalDate,
                failureMessage == null ? "null" : "\"" + failureMessage + "\"");
    }

    /**
     * Insert a minimal real event row (FK target for payout_runs.event_id) plus the user it
     * references, via JDBC so we don't have to wire EventRepository/UserRepository here.
     */
    private UUID insertEvent() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO users (id, org_id, email, email_lower, first_name, last_name,
                                   role, avatar_initials, created_at)
                VALUES (?, ?, ?, ?, '', '', 'OWNER', '', ?)
                """, userId, org.getId(),
                "creator-" + userId + "@test.example",
                "creator-" + userId + "@test.example", java.sql.Timestamp.from(now));

        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, org_id, name, slug, visibility, status, genre, type,
                                    timezone, venue_street, venue_city, venue_postal_code,
                                    description, sold, revenue_minor, currency, created_by,
                                    created_at, updated_at)
                VALUES (?, ?, 'Recon Event', ?, 'PUBLIC', 'PAST', '', '', 'UTC', '', '', '',
                        '', 0, 0, 'EUR', ?, ?, ?)
                """, eventId, org.getId(), "recon-" + eventId.toString().substring(0, 8),
                userId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return eventId;
    }

    /** Seed a SUBMITTED payout_runs row (as the post-event job would leave it) for a po_ id. */
    private PayoutRun seedSubmittedRun(UUID eventId, String payoutId, long amount) {
        PayoutRun r = new PayoutRun();
        r.setOrgId(org.getId());
        r.setEventId(eventId);
        r.setStripeAccountId(acctId);
        r.setAmountMinor(amount);
        r.setCurrency("eur");
        r.setStatus(PayoutRunStatus.SUBMITTED);
        r.setAttempt(1);
        r.setIdempotencyKey("evt:" + eventId + ":attempt:1");
        r.setStripePayoutId(payoutId);
        return payoutRuns.save(r);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void payoutPaid_reconcilesRunToPaid_andCopiesEventIdOntoSettlement() throws Exception {
        UUID eventId = insertEvent();
        String payoutId = "po_" + UUID.randomUUID().toString().substring(0, 12);
        seedSubmittedRun(eventId, payoutId, 7_200);

        long arrival = Instant.now().getEpochSecond();
        String body = payoutEvent("evt_payout_paid_1", payoutId, "payout.paid", 7_200, "paid", arrival, null);
        webhook.handleV1Endpoint(body, sign(body));

        // The run flipped to PAID, with paid_at derived from Stripe's arrival_date — the
        // SAME instant the settlement row mirrors as its arrival (both go through the same
        // H2 timestamp round-trip, so compare them to each other rather than to a literal).
        PayoutRun run = payoutRuns.findByStripePayoutId(payoutId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(PayoutRunStatus.PAID);
        assertThat(run.getPaidAt()).isNotNull();

        // The settlement row carries the run's event attribution (payouts have no metadata of their own).
        Settlement s = settlements.findByStripeObjectId(payoutId).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(s.getEventIds()).isEqualTo(eventId.toString());
        assertThat(run.getPaidAt()).isEqualTo(s.getArrivalAt());
    }

    @Test
    void payoutFailed_reconcilesRunToFailed_withReason() throws Exception {
        UUID eventId = insertEvent();
        String payoutId = "po_" + UUID.randomUUID().toString().substring(0, 12);
        seedSubmittedRun(eventId, payoutId, 5_000);

        String body = payoutEvent("evt_payout_failed_1", payoutId, "payout.failed", 5_000,
                "failed", Instant.now().getEpochSecond(), "account_closed");
        webhook.handleV1Endpoint(body, sign(body));

        PayoutRun run = payoutRuns.findByStripePayoutId(payoutId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(PayoutRunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo("account_closed");
    }

    @Test
    void payoutWithNoMatchingRun_justUpsertsSettlement_noCrash() throws Exception {
        // A Stripe-auto payout (not imin-triggered) has no payout_runs row — must still upsert cleanly.
        String payoutId = "po_" + UUID.randomUUID().toString().substring(0, 12);
        String body = payoutEvent("evt_payout_orphan_1", payoutId, "payout.paid", 3_000,
                "paid", Instant.now().getEpochSecond(), null);

        webhook.handleV1Endpoint(body, sign(body));

        assertThat(payoutRuns.findByStripePayoutId(payoutId)).isEmpty();
        Settlement s = settlements.findByStripeObjectId(payoutId).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(s.getEventIds()).isNull();
    }

    @Test
    void transferCreated_createsSettlementRowTiedToOrg() throws Exception {
        String transferId = "tr_" + UUID.randomUUID().toString().substring(0, 12);
        String body = transferCreatedEvent("evt_transfer_create_1", transferId, acctId, 4200, "eur");

        webhook.handleV1Endpoint(body, sign(body));

        Optional<Settlement> found = settlements.findByStripeObjectId(transferId);
        assertThat(found).isPresent();
        Settlement s = found.get();
        assertThat(s.getOrgId()).isEqualTo(org.getId());
        assertThat(s.getObjectType()).isEqualTo(SettlementObjectType.TRANSFER);
        assertThat(s.getAmountMinor()).isEqualTo(4200L);
        assertThat(s.getCurrency()).isEqualTo("eur");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    void transferCreated_redelivery_isDedupNoOp_singleRow() throws Exception {
        String transferId = "tr_" + UUID.randomUUID().toString().substring(0, 12);
        String body = transferCreatedEvent("evt_transfer_dedup_1", transferId, acctId, 4200, "eur");

        webhook.handleV1Endpoint(body, sign(body));   // first delivery
        webhook.handleV1Endpoint(body, sign(body));   // exact replay — same event id

        // Exactly one row for the org, and exactly one for this transfer id.
        assertThat(settlements.findByOrgIdOrderByCreatedAtDesc(org.getId())).hasSize(1);
        assertThat(settlements.findByStripeObjectId(transferId)).isPresent();
    }

    @Test
    void transferReversed_distinctEventId_upsertsSameRowToReversed() throws Exception {
        // A second, DISTINCT event id (transfer.reversed) for the SAME transfer must update the
        // existing row in place (upsert keyed on stripe_object_id), not create a duplicate.
        String transferId = "tr_" + UUID.randomUUID().toString().substring(0, 12);
        String created = transferCreatedEvent("evt_tr_c", transferId, acctId, 4200, "eur");
        webhook.handleV1Endpoint(created, sign(created));

        String reversedBody = """
            {
              "id": "evt_tr_r",
              "object": "event",
              "type": "transfer.reversed",
              "api_version": "2026-04-22.dahlia",
              "created": %d,
              "account": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "transfer",
                  "amount": 4200,
                  "amount_reversed": 4200,
                  "currency": "eur",
                  "destination": "%s",
                  "reversed": true,
                  "metadata": {}
                }
              }
            }
            """.formatted(Instant.now().getEpochSecond(), acctId, transferId, acctId);
        webhook.handleV1Endpoint(reversedBody, sign(reversedBody));

        assertThat(settlements.findByOrgIdOrderByCreatedAtDesc(org.getId())).hasSize(1);
        assertThat(settlements.findByStripeObjectId(transferId))
                .get()
                .extracting(Settlement::getStatus)
                .isEqualTo(SettlementStatus.REVERSED);
    }

    @Test
    void transferForUnknownAccount_skips_noRow() throws Exception {
        String transferId = "tr_" + UUID.randomUUID().toString().substring(0, 12);
        String body = transferCreatedEvent("evt_unknown_acct", transferId,
                "acct_does_not_exist", 4200, "eur");

        webhook.handleV1Endpoint(body, sign(body));

        assertThat(settlements.findByStripeObjectId(transferId)).isEmpty();
    }

    // ── charge.refunded ─────────────────────────────────────────────────────────

    @Test
    void partialRefund_existingTransferRow_keepsAmountAndStatusUnchanged() throws Exception {
        // Seed the backing transfer row first (PENDING, 4200).
        String transferId = "tr_" + UUID.randomUUID().toString().substring(0, 12);
        String created = transferCreatedEvent("evt_pr_seed", transferId, acctId, 4200, "eur");
        webhook.handleV1Endpoint(created, sign(created));

        // A PARTIAL refund (refunded=false) must NOT change amount and must NOT change status.
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String refund = chargeRefundedEvent("evt_pr_refund", chargeId, transferId, acctId, 1000, false);
        webhook.handleV1Endpoint(refund, sign(refund));

        Settlement s = settlements.findByStripeObjectId(transferId).orElseThrow();
        assertThat(s.getAmountMinor()).isEqualTo(4200L);                 // amount untouched
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PENDING);   // status untouched
        // Still exactly one row for the org (no new row minted by the refund).
        assertThat(settlements.findByOrgIdOrderByCreatedAtDesc(org.getId())).hasSize(1);
    }

    @Test
    void refund_withNoExistingTransferRow_createsNothing() throws Exception {
        // No transfer row seeded. A refund (even a full one) must NEVER mint a row.
        String transferId = "tr_" + UUID.randomUUID().toString().substring(0, 12);
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String refund = chargeRefundedEvent("evt_refund_orphan", chargeId, transferId, acctId, 4200, true);

        webhook.handleV1Endpoint(refund, sign(refund));

        assertThat(settlements.findByStripeObjectId(transferId)).isEmpty();
        assertThat(settlements.findByOrgIdOrderByCreatedAtDesc(org.getId())).isEmpty();
    }

    @Test
    void fullRefund_existingTransferRow_flipsToReversed_amountUntouched() throws Exception {
        String transferId = "tr_" + UUID.randomUUID().toString().substring(0, 12);
        String created = transferCreatedEvent("evt_fr_seed", transferId, acctId, 4200, "eur");
        webhook.handleV1Endpoint(created, sign(created));

        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String refund = chargeRefundedEvent("evt_fr_refund", chargeId, transferId, acctId, 4200, true);
        webhook.handleV1Endpoint(refund, sign(refund));

        Settlement s = settlements.findByStripeObjectId(transferId).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.REVERSED);  // status flipped
        assertThat(s.getAmountMinor()).isEqualTo(4200L);                 // amount untouched
        assertThat(settlements.findByOrgIdOrderByCreatedAtDesc(org.getId())).hasSize(1);
    }

    // ── charge.dispute.* ────────────────────────────────────────────────────────

    @Test
    void dispute_withNoExistingTransferRow_createsNoPhantomRow() throws Exception {
        // A dispute must NEVER mint a settlement row — least of all a payout-looking one keyed
        // on the dispute id (du_...). With no backing transfer row present, it's a pure no-op.
        String disputeId = "du_" + UUID.randomUUID().toString().substring(0, 12);
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String transferId = "tr_" + UUID.randomUUID().toString().substring(0, 12);
        String body = disputeEvent("evt_dispute_orphan", disputeId, chargeId, transferId, 4200);

        webhook.handleV1Endpoint(body, sign(body));

        // No row keyed on the dispute id, none on the (unmirrored) transfer, none for the org.
        assertThat(settlements.findByStripeObjectId(disputeId)).isEmpty();
        assertThat(settlements.findByStripeObjectId(transferId)).isEmpty();
        assertThat(settlements.findByOrgIdOrderByCreatedAtDesc(org.getId())).isEmpty();
    }

    @Test
    void dispute_existingTransferRow_annotatesStatusOnly_amountUntouched() throws Exception {
        // Seed the backing transfer row, then a dispute annotates its status to FAILED
        // (funds at risk) WITHOUT changing the amount and WITHOUT creating a new row.
        String transferId = "tr_" + UUID.randomUUID().toString().substring(0, 12);
        String created = transferCreatedEvent("evt_disp_seed", transferId, acctId, 4200, "eur");
        webhook.handleV1Endpoint(created, sign(created));

        String disputeId = "du_" + UUID.randomUUID().toString().substring(0, 12);
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        String body = disputeEvent("evt_disp_annot", disputeId, chargeId, transferId, 4200);
        webhook.handleV1Endpoint(body, sign(body));

        Settlement s = settlements.findByStripeObjectId(transferId).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.FAILED);   // funds at risk
        assertThat(s.getAmountMinor()).isEqualTo(4200L);                // amount untouched
        assertThat(settlements.findByStripeObjectId(disputeId)).isEmpty();   // no phantom row
        assertThat(settlements.findByOrgIdOrderByCreatedAtDesc(org.getId())).hasSize(1);
    }
}
