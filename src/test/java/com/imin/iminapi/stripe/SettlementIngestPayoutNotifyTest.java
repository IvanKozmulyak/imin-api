package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.payout.PayoutArrivedEvent;
import com.imin.iminapi.payout.PayoutRun;
import com.imin.iminapi.payout.PayoutRunRepository;
import com.imin.iminapi.payout.PayoutRunStatus;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.settlement.SettlementRepository;
import com.stripe.StripeClient;
import com.stripe.model.Payout;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code payout.paid} is the only moment imin knows the organizer's money is actually moving,
 * and until now it told them nothing — the {@code payout_arrived} preference switched a
 * notification that was never sent. This pins the publish and, just as importantly, the
 * transition guard: Stripe delivers at least once, and a second delivery must not email twice.
 */
class SettlementIngestPayoutNotifyTest {

    private static final String PAYOUT_ID = "po_test_arrived";
    private static final String ACCT = "acct_test_1";

    private PayoutRunRepository payoutRuns;
    private ApplicationEventPublisher publisher;
    private SettlementIngestService ingest;
    private UUID runId;

    @BeforeEach
    void setUp() {
        SettlementRepository settlements = mock(SettlementRepository.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        payoutRuns = mock(PayoutRunRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        ingest = new SettlementIngestService(settlements, orgs, payoutRuns,
                mock(StripeClient.class), publisher);

        when(settlements.findByStripeObjectId(anyString())).thenReturn(Optional.empty());
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setStripeAccountId(ACCT);
        when(orgs.findByStripeAccountId(ACCT)).thenReturn(Optional.of(org));
        runId = UUID.randomUUID();
    }

    @Test
    void payoutPaidTellsTheOrganizerTheMoneyIsOnItsWay() {
        when(payoutRuns.findByStripePayoutId(PAYOUT_ID))
                .thenReturn(Optional.of(run(PayoutRunStatus.SUBMITTED)));

        ingest.ingestPayout(payout("paid"), ACCT, Instant.now());

        verify(publisher).publishEvent(new PayoutArrivedEvent(runId));
    }

    @Test
    void aRedeliveryOfPayoutPaidDoesNotNotifyAgain() {
        // The run is already settled — this is Stripe's at-least-once delivery, not new news.
        when(payoutRuns.findByStripePayoutId(PAYOUT_ID))
                .thenReturn(Optional.of(run(PayoutRunStatus.PAID)));

        ingest.ingestPayout(payout("paid"), ACCT, Instant.now());

        verify(publisher, never()).publishEvent(any(PayoutArrivedEvent.class));
    }

    @Test
    void aFailedPayoutIsNotAnArrival() {
        when(payoutRuns.findByStripePayoutId(PAYOUT_ID))
                .thenReturn(Optional.of(run(PayoutRunStatus.SUBMITTED)));

        ingest.ingestPayout(payout("failed"), ACCT, Instant.now());

        verify(publisher, never()).publishEvent(any(PayoutArrivedEvent.class));
    }

    private PayoutRun run(PayoutRunStatus status) {
        PayoutRun r = new PayoutRun();
        r.setId(runId);
        r.setOrgId(UUID.randomUUID());
        r.setEventId(UUID.randomUUID());
        r.setStripeAccountId(ACCT);
        r.setAmountMinor(9_000);
        r.setCurrency("eur");
        r.setStatus(status);
        r.setStripePayoutId(PAYOUT_ID);
        r.setAttempt(1);
        r.setIdempotencyKey("evt:x:attempt:1");
        return r;
    }

    private static Payout payout(String status) {
        String json = """
                { "object": "payout", "id": "%s", "amount": 9000, "currency": "eur",
                  "status": "%s", "arrival_date": %d }
                """.formatted(PAYOUT_ID, status, Instant.now().getEpochSecond());
        return ApiResource.GSON.fromJson(json, Payout.class);
    }
}
