# Stripe Connect Refunds — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship organizer-initiated Stripe refunds (full or partial, via Connect destination charges) that release ticket inventory on webhook confirmation, email the buyer, and surface from an Orders tab in the dashboard.

**Architecture:** Synchronous `POST /orders/{id}/refund` persists a `refunds` row and creates a Stripe Refund (+ Application Fee Refund) with deterministic idempotency keys, then returns 202. Inventory release, ticket-state flip to `refunded`, and the buyer email are all triggered exclusively by the `charge.refund.updated` webhook on the existing V1 endpoint. The webhook uses the same `WebhookEventDedupService` + status-conditional UPDATE pattern that fulfilment already uses. Two precursor schema additions (`orders.application_fee_minor`, `tickets.price_minor`) snapshot data needed for the proportional fee-refund formula and the refund amount computation.

**Tech Stack:** Java 17, Spring Boot 4, JPA + Flyway (V28), Stripe Java SDK (`StripeClient`), JUnit 5 + Mockito + H2 in PG-compat mode for tests. Webapp: Vite + React 19 + TypeScript + TanStack Query + Radix `ConfirmDialog` + Sonner toasts.

**Spec:** `docs/superpowers/specs/2026-05-25-stripe-refunds-design.md` — read first if any task is ambiguous.

---

## File map

### imin-api (new files)

```
src/main/resources/db/migration/
  V28__refunds.sql                                    NEW

src/main/java/com/imin/iminapi/
  refund/Refund.java                                  NEW
  refund/RefundTicket.java                            NEW
  refund/RefundTicketId.java                          NEW (composite PK)
  refund/RefundStatus.java                            NEW
  refund/RefundReason.java                            NEW
  refund/RefundRepository.java                        NEW
  refund/RefundTicketRepository.java                  NEW
  refund/RefundService.java                           NEW (chokepoint)
  refund/RefundController.java                        NEW (POST + GET)
  refund/dto/CreateRefundRequest.java                 NEW
  refund/dto/RefundResponse.java                      NEW
  refund/event/RefundConfirmedEvent.java              NEW
  refund/event/RefundFailedEvent.java                 NEW
  refund/email/RefundConfirmationEmailer.java         NEW (@Async listener)
  stripe/StripeRefundService.java                     NEW (SDK wrapper)
  controller/order/EventOrdersController.java         NEW (GET /events/{id}/orders)
  controller/order/dto/OrderRowResponse.java          NEW
```

### imin-api (modified files)

```
src/main/java/com/imin/iminapi/model/Order.java                  ADD applicationFeeMinor
src/main/java/com/imin/iminapi/model/Ticket.java                 ADD priceMinor + REFUNDED state constant
src/main/java/com/imin/iminapi/repository/OrderRepository.java   ADD findAllByEventIdOrderByCreatedAtDesc
src/main/java/com/imin/iminapi/repository/TicketRepository.java  ADD countByOrderIdAndStateNot, findAllByIdAndOrderId
src/main/java/com/imin/iminapi/security/ErrorCode.java           ADD MISSING_IDEMPOTENCY_KEY, ORDER_NOT_REFUNDABLE, TICKET_ALREADY_REFUNDED, TICKET_REDEEMED, STRIPE_REFUND_FAILED
src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java   set order.applicationFeeMinor + ticket.priceMinor at issuance
src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java  ADD onChargeRefundUpdated dispatch
src/main/java/com/imin/iminapi/stripe/StripeConfig.java          PIN api version (open question #1 — if approved)
```

### imin-webapp (new files)

```
src/features/events/orders/
  EventOrdersTab.tsx                                  NEW
  RefundOrderDialog.tsx                               NEW
  ordersApi.ts                                        NEW
```

### imin-webapp (modified files)

```
src/shared/api/types.ts                              ADD OrderRow, Refund, RefundReason
src/shared/api/client.ts                             ADD /orders/{id}/refund to IDEMPOTENT_ENDPOINTS
src/features/events/EventDetailPage.tsx              WIRE new Orders tab
src/shared/copy.ts                                   ADD copy.orders.*
```

---

## Task 1: V28 migration — add refunds table + snapshot columns

**Files:**
- Create: `imin-api/src/main/resources/db/migration/V28__refunds.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V28__refunds.sql
-- Refund domain: full or partial Stripe refunds with proportional application-fee reversal.
-- Pure webhook-driven inventory release. See spec 2026-05-25-stripe-refunds-design.md.

-- Snapshot the application fee Stripe deducted at checkout, so refund proration
-- can compute proportional fee refund without an extra Stripe round-trip.
ALTER TABLE orders
  ADD COLUMN application_fee_minor BIGINT NOT NULL DEFAULT 0;

-- Snapshot the price-at-purchase on each ticket so refund amount survives tier
-- price changes between purchase and refund.
ALTER TABLE tickets
  ADD COLUMN price_minor INTEGER NOT NULL DEFAULT 0;

-- Backfill existing tickets with the current tier price. Acceptable approximation
-- for pre-launch data (no real orders exist yet).
UPDATE tickets t
   SET price_minor = COALESCE((SELECT tt.price_minor FROM ticket_tiers tt WHERE tt.id = t.tier_id), 0);

CREATE TABLE refunds (
  id                            UUID PRIMARY KEY,
  order_id                      UUID NOT NULL REFERENCES orders(id),
  stripe_refund_id              VARCHAR(255),
  stripe_charge_id              VARCHAR(255),
  stripe_payment_intent_id      VARCHAR(255) NOT NULL,
  amount_minor                  BIGINT NOT NULL,
  currency                      VARCHAR(8) NOT NULL,
  application_fee_refund_minor  BIGINT NOT NULL DEFAULT 0,
  reason                        VARCHAR(32) NOT NULL,
  status                        VARCHAR(16) NOT NULL,
  failure_code                  VARCHAR(64),
  failure_message               VARCHAR(500),
  initiated_by_user_id          UUID NOT NULL REFERENCES users(id),
  idempotency_key               VARCHAR(128) NOT NULL,
  created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX refunds_stripe_refund_id_unique
  ON refunds (stripe_refund_id)
  WHERE stripe_refund_id IS NOT NULL;

CREATE UNIQUE INDEX refunds_order_idem_unique
  ON refunds (order_id, idempotency_key);

CREATE INDEX refunds_stripe_charge_id_idx ON refunds (stripe_charge_id);
CREATE INDEX refunds_order_id_idx ON refunds (order_id);

CREATE TABLE refund_tickets (
  refund_id  UUID NOT NULL REFERENCES refunds(id),
  ticket_id  UUID NOT NULL REFERENCES tickets(id),
  PRIMARY KEY (refund_id, ticket_id)
);

-- A ticket can be in at most one refund row. Enforces "no double refund of same ticket"
-- at the storage layer even under concurrent POSTs from two organizers.
CREATE UNIQUE INDEX refund_tickets_ticket_id_unique
  ON refund_tickets (ticket_id);
```

- [ ] **Step 2: Run the test suite to confirm Flyway accepts the migration**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=IminApiApplicationTests -q
```

Expected: PASS (Spring Boot context loads, which runs Flyway against H2).

- [ ] **Step 3: Commit**

```bash
cd /Users/ivan/imin/imin-api
git add src/main/resources/db/migration/V28__refunds.sql
git commit -m "feat(refunds): V28 migration — refunds table, refund_tickets, snapshot columns"
```

---

## Task 2: Extend ErrorCode + Order/Ticket entities

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/security/ErrorCode.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/model/Order.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/model/Ticket.java`

- [ ] **Step 1: Extend ErrorCode enum**

Append these constants at the bottom of the enum:

```java
    MISSING_IDEMPOTENCY_KEY,
    ORDER_NOT_REFUNDABLE,
    TICKET_ALREADY_REFUNDED,
    TICKET_REDEEMED,
    STRIPE_REFUND_FAILED
```

- [ ] **Step 2: Add `applicationFeeMinor` to `Order`**

After the `currency` field (line ~46), add:

```java
    /**
     * Application fee (platform cut) Stripe deducted from this order, in minor units.
     * Snapshotted at issuance from PaymentIntent.applicationFeeAmount so refund
     * proration can compute the proportional fee refund without an extra Stripe call.
     * 0 for free orders.
     */
    @Column(name = "application_fee_minor", nullable = false)
    private long applicationFeeMinor;
```

- [ ] **Step 3: Add `priceMinor` to `Ticket` and define the REFUNDED state constant**

After the `tierName` field, add:

```java
    /**
     * Price-at-purchase snapshot in minor units. Survives tier price changes
     * between purchase and refund.
     */
    @Column(name = "price_minor", nullable = false)
    private int priceMinor;
```

After the `state` field, add a `public static final String` for the new state value (kept inline rather than a full enum migration — matches existing code):

```java
    /** Ticket states. Stored as VARCHAR; keep in sync with the data column. */
    public static final String STATE_ISSUED = "issued";
    public static final String STATE_REDEEMED = "redeemed";
    public static final String STATE_REVOKED = "revoked";
    public static final String STATE_REFUNDED = "refunded";
```

- [ ] **Step 4: Run the build**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/security/ErrorCode.java \
        src/main/java/com/imin/iminapi/model/Order.java \
        src/main/java/com/imin/iminapi/model/Ticket.java
git commit -m "feat(refunds): extend entities + ErrorCode for refund support"
```

---

## Task 3: Populate snapshot fields in PaidCheckoutService

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java`
- Test: `imin-api/src/test/java/com/imin/iminapi/service/ticket/PaidCheckoutServiceTest.java`

- [ ] **Step 1: Add failing test asserting snapshot fields are populated**

In `PaidCheckoutServiceTest`, add a test verifying:
- After `issuePaidOrder(pi)` runs with a PaymentIntent that has `applicationFeeAmount=349`, the saved Order has `applicationFeeMinor=349`.
- Each issued Ticket has `priceMinor` equal to the current `tier.priceMinor`.

```java
@Test
void issuePaidOrder_snapshotsApplicationFeeAndTicketPrice() {
    // arrange: tier with priceMinor=2500, qty=2, fee=349 on PI
    UUID tierId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    TicketTier tier = new TicketTier();
    tier.setId(tierId);
    tier.setName("GA");
    tier.setPriceMinor(2500);
    when(tiers.findById(tierId)).thenReturn(Optional.of(tier));

    Event event = new Event();
    event.setId(eventId);
    event.setOrgId(UUID.randomUUID());
    event.setCurrency("eur");
    when(events.findById(eventId)).thenReturn(Optional.of(event));

    PaymentIntent pi = mock(PaymentIntent.class);
    when(pi.getId()).thenReturn("pi_test_123");
    when(pi.getAmount()).thenReturn(5000L);
    when(pi.getCurrency()).thenReturn("eur");
    when(pi.getApplicationFeeAmount()).thenReturn(349L);
    when(pi.getLatestCharge()).thenReturn(null);
    when(pi.getMetadata()).thenReturn(Map.of(
        "tier_id", tierId.toString(),
        "event_id", eventId.toString(),
        "qty", "2"
    ));
    when(orders.findByStripePaymentIntentId("pi_test_123")).thenReturn(Optional.empty());

    // stubbed Stripe session list returns empty (buyer email comes from charge fallback)
    when(stripeClient.checkout()).thenReturn(checkoutClient);
    // ... arrange session lookup returns empty + buyer email is supplied via test injection

    ArgumentCaptor<Order> orderCap = ArgumentCaptor.forClass(Order.class);
    ArgumentCaptor<Ticket> ticketCap = ArgumentCaptor.forClass(Ticket.class);
    when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // act
    service.issuePaidOrder(pi);

    // assert
    verify(orders).save(orderCap.capture());
    assertEquals(349L, orderCap.getValue().getApplicationFeeMinor());
    verify(tickets, times(2)).save(ticketCap.capture());
    assertTrue(ticketCap.getAllValues().stream().allMatch(t -> t.getPriceMinor() == 2500));
}
```

Note: the existing test setup likely already wires `stripeClient` mocks — reuse the existing happy-path arrangement.

- [ ] **Step 2: Run the test, confirm it fails**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=PaidCheckoutServiceTest#issuePaidOrder_snapshotsApplicationFeeAndTicketPrice -q
```

Expected: FAIL (fields not populated).

- [ ] **Step 3: Patch `PaidCheckoutService.issuePaidOrder`**

In the section that builds the Order (around line 123–134), add:

```java
        order.setApplicationFeeMinor(pi.getApplicationFeeAmount() == null ? 0L : pi.getApplicationFeeAmount());
```

In the ticket-issuance loop (around line 155–164), add to each Ticket:

```java
            t.setPriceMinor(tier.getPriceMinor());
```

- [ ] **Step 4: Re-run test**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=PaidCheckoutServiceTest -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java \
        src/test/java/com/imin/iminapi/service/ticket/PaidCheckoutServiceTest.java
git commit -m "feat(refunds): snapshot applicationFee + ticket priceMinor at issuance"
```

---

## Task 4: Refund domain — entities, enums, repositories

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/Refund.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/RefundTicket.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/RefundTicketId.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/RefundStatus.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/RefundReason.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/RefundRepository.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/RefundTicketRepository.java`

- [ ] **Step 1: `RefundStatus.java`**

```java
package com.imin.iminapi.refund;

public enum RefundStatus {
    REQUESTED, PENDING, SUCCEEDED, FAILED, CANCELED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }

    public static RefundStatus fromStripe(String stripeStatus) {
        if (stripeStatus == null) return PENDING;
        return switch (stripeStatus) {
            case "pending" -> PENDING;
            case "succeeded" -> SUCCEEDED;
            case "failed" -> FAILED;
            case "canceled" -> CANCELED;
            default -> PENDING;
        };
    }
}
```

- [ ] **Step 2: `RefundReason.java`**

```java
package com.imin.iminapi.refund;

public enum RefundReason {
    REQUESTED_BY_CUSTOMER, DUPLICATE, FRAUDULENT, OTHER;

    public String toStripe() {
        return switch (this) {
            case REQUESTED_BY_CUSTOMER -> "requested_by_customer";
            case DUPLICATE -> "duplicate";
            case FRAUDULENT -> "fraudulent";
            case OTHER -> null;  // Stripe accepts null reason
        };
    }

    public String toWire() { return name().toLowerCase(); }
}
```

- [ ] **Step 3: `Refund.java`**

```java
package com.imin.iminapi.refund;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "refunds")
@Getter
@Setter
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "stripe_refund_id", length = 255)
    private String stripeRefundId;

    @Column(name = "stripe_charge_id", length = 255)
    private String stripeChargeId;

    @Column(name = "stripe_payment_intent_id", nullable = false, length = 255)
    private String stripePaymentIntentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "application_fee_refund_minor", nullable = false)
    private long applicationFeeRefundMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RefundStatus status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "initiated_by_user_id", nullable = false)
    private UUID initiatedByUserId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Times.nowMicros();

    @PrePersist
    @PreUpdate
    void touch() {
        Instant now = Times.nowMicros();
        if (createdAt == null) createdAt = now;
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
```

- [ ] **Step 4: `RefundTicketId.java` + `RefundTicket.java`**

```java
// RefundTicketId.java
package com.imin.iminapi.refund;

import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter @Setter @NoArgsConstructor @EqualsAndHashCode
public class RefundTicketId implements Serializable {
    private UUID refundId;
    private UUID ticketId;

    public RefundTicketId(UUID refundId, UUID ticketId) {
        this.refundId = refundId;
        this.ticketId = ticketId;
    }
}
```

```java
// RefundTicket.java
package com.imin.iminapi.refund;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "refund_tickets")
@Getter @Setter @NoArgsConstructor
@IdClass(RefundTicketId.class)
public class RefundTicket {
    @Id
    @Column(name = "refund_id", nullable = false)
    private UUID refundId;

    @Id
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    public RefundTicket(UUID refundId, UUID ticketId) {
        this.refundId = refundId;
        this.ticketId = ticketId;
    }
}
```

- [ ] **Step 5: `RefundRepository.java`**

```java
package com.imin.iminapi.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);
    Optional<Refund> findByStripeRefundId(String stripeRefundId);
    List<Refund> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    /**
     * Race-safe status transition. Returns 1 if this caller won the transition, 0 if
     * another transaction already advanced the row.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update Refund r
               set r.status = :next
             where r.id = :id
               and r.status = :expected
            """)
    int updateStatusIfCurrent(@Param("id") UUID id,
                              @Param("expected") RefundStatus expected,
                              @Param("next") RefundStatus next);
}
```

- [ ] **Step 6: `RefundTicketRepository.java`**

```java
package com.imin.iminapi.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface RefundTicketRepository extends JpaRepository<RefundTicket, RefundTicketId> {

    @Query("select rt.ticketId from RefundTicket rt where rt.ticketId in :ticketIds")
    Set<UUID> findRefundedTicketIds(@Param("ticketIds") Collection<UUID> ticketIds);

    @Query("select rt.ticketId from RefundTicket rt where rt.refundId = :refundId")
    List<UUID> findTicketIdsByRefundId(@Param("refundId") UUID refundId);
}
```

- [ ] **Step 7: Compile**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/
git commit -m "feat(refunds): domain entities, enums, repositories"
```

---

## Task 5: StripeRefundService — Stripe SDK wrapper

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/stripe/StripeRefundService.java`
- Test: `imin-api/src/test/java/com/imin/iminapi/stripe/StripeRefundServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.imin.iminapi.stripe;

import com.imin.iminapi.refund.RefundReason;
import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.service.ApplicationFeeService;
import com.stripe.service.ChargeService;
import com.stripe.service.RefundService;
import com.stripe.service.FeeRefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StripeRefundServiceTest {

    private StripeClient stripeClient;
    private RefundService refundSvc;          // Stripe SDK service
    private ChargeService chargeSvc;
    private ApplicationFeeService appFeeSvc;
    private FeeRefundService feeRefundSvc;
    private com.imin.iminapi.stripe.StripeRefundService service;

    @BeforeEach
    void setUp() {
        stripeClient = mock(StripeClient.class);
        refundSvc = mock(RefundService.class);
        chargeSvc = mock(ChargeService.class);
        appFeeSvc = mock(ApplicationFeeService.class);
        feeRefundSvc = mock(FeeRefundService.class);
        when(stripeClient.refunds()).thenReturn(refundSvc);
        when(stripeClient.charges()).thenReturn(chargeSvc);
        when(stripeClient.applicationFees()).thenReturn(appFeeSvc);
        when(appFeeSvc.refunds()).thenReturn(feeRefundSvc);
        service = new com.imin.iminapi.stripe.StripeRefundService(stripeClient);
    }

    @Test
    void create_passesAmountReasonReverseTransferAndIdempotencyKey() throws Exception {
        Refund stub = new Refund();
        stub.setId("re_test_123");
        stub.setCharge("ch_test_abc");
        stub.setStatus("pending");
        when(refundSvc.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stub);

        Refund out = service.create(
            "pi_test_1", 5000L, "eur",
            RefundReason.REQUESTED_BY_CUSTOMER, 0L, "refund_xyz");

        assertEquals("re_test_123", out.getId());

        ArgumentCaptor<RefundCreateParams> paramsCap = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> optsCap = ArgumentCaptor.forClass(RequestOptions.class);
        verify(refundSvc).create(paramsCap.capture(), optsCap.capture());

        RefundCreateParams p = paramsCap.getValue();
        assertEquals("pi_test_1", p.getPaymentIntent());
        assertEquals(5000L, p.getAmount());
        assertEquals(Boolean.TRUE, p.getReverseTransfer());
        assertEquals(Boolean.FALSE, p.getRefundApplicationFee());
        assertEquals("requested_by_customer", p.getReason().getValue());
        assertEquals("refund_xyz", optsCap.getValue().getIdempotencyKey());

        // appFeeRefundMinor == 0 → no separate fee refund call
        verifyNoInteractions(chargeSvc);
        verifyNoInteractions(feeRefundSvc);
    }

    @Test
    void create_withAppFeeRefund_alsoCallsApplicationFeeRefund() throws Exception {
        Refund stubRefund = new Refund();
        stubRefund.setId("re_test_2");
        stubRefund.setCharge("ch_test_xyz");
        stubRefund.setStatus("pending");
        when(refundSvc.create(any(), any(RequestOptions.class))).thenReturn(stubRefund);

        Charge stubCharge = new Charge();
        stubCharge.setApplicationFee("fee_test_1");
        when(chargeSvc.retrieve(eq("ch_test_xyz"))).thenReturn(stubCharge);

        service.create("pi_test_2", 2500L, "eur",
                       RefundReason.REQUESTED_BY_CUSTOMER, 200L, "refund_pqr");

        verify(feeRefundSvc).create(eq("fee_test_1"), any(), any(RequestOptions.class));
    }
}
```

- [ ] **Step 2: Confirm test fails**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=StripeRefundServiceTest -q
```

Expected: COMPILATION FAILURE (class doesn't exist).

- [ ] **Step 3: Implement `StripeRefundService.java`**

```java
package com.imin.iminapi.stripe;

import com.imin.iminapi.refund.RefundReason;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.FeeRefundCreateOnApplicationFeeParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around {@link StripeClient}'s refund + application-fee-refund
 * APIs. Issues TWO calls per refund: one for the charge (with reverse_transfer
 * so funds come back from the connected account), then a second for the
 * application fee proration. Idempotency keys are deterministic and derived
 * from our own refund id; replays of the same refund return the same Stripe
 * objects.
 */
@Service
public class StripeRefundService {

    private static final Logger log = LoggerFactory.getLogger(StripeRefundService.class);

    private final StripeClient stripeClient;

    public StripeRefundService(StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    /**
     * Create a Stripe Refund and (if appFeeRefundMinor > 0) a proportional
     * ApplicationFee refund.
     *
     * @throws StripeException unwrapped — caller maps to ApiException.
     */
    public Refund create(String paymentIntentId, long amountMinor, String currency,
                         RefundReason reason, long appFeeRefundMinor,
                         String idempotencyKey) throws StripeException {

        RefundCreateParams.Builder pb = RefundCreateParams.builder()
            .setPaymentIntent(paymentIntentId)
            .setAmount(amountMinor)
            .setReverseTransfer(true)
            .setRefundApplicationFee(false);
        String stripeReason = reason.toStripe();
        if (stripeReason != null) {
            pb.setReason(RefundCreateParams.Reason.valueOf(stripeReason.toUpperCase()));
        }
        RefundCreateParams params = pb.build();

        RequestOptions opts = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        Refund refund = stripeClient.refunds().create(params, opts);
        log.info("[stripe-refund] created id={} status={} amount={} {} idemp={}",
                refund.getId(), refund.getStatus(), amountMinor, currency, idempotencyKey);

        if (appFeeRefundMinor > 0) {
            String chargeId = refund.getCharge();
            Charge charge = stripeClient.charges().retrieve(chargeId);
            String appFeeId = charge.getApplicationFee();
            if (appFeeId == null) {
                log.warn("[stripe-refund] charge {} has no application_fee — skipping fee refund", chargeId);
            } else {
                FeeRefundCreateOnApplicationFeeParams feeParams = FeeRefundCreateOnApplicationFeeParams.builder()
                    .setAmount(appFeeRefundMinor).build();
                RequestOptions feeOpts = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey + "_fee").build();
                stripeClient.applicationFees().refunds().create(appFeeId, feeParams, feeOpts);
                log.info("[stripe-refund] created app-fee refund on fee={} amount={}", appFeeId, appFeeRefundMinor);
            }
        }
        return refund;
    }
}
```

- [ ] **Step 4: Run test, confirm pass**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=StripeRefundServiceTest -q
```

Expected: PASS. If the Stripe SDK class/method names differ from the placeholders (e.g., `FeeRefundCreateOnApplicationFeeParams` may be `FeeRefundCreateParams` depending on SDK version), correct them per actual SDK and re-run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripeRefundService.java \
        src/test/java/com/imin/iminapi/stripe/StripeRefundServiceTest.java
git commit -m "feat(refunds): StripeRefundService wrapper for refunds + app fee refund"
```

---

## Task 6: RefundService — the business chokepoint

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/RefundService.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/repository/TicketRepository.java`
- Test: `imin-api/src/test/java/com/imin/iminapi/refund/RefundServiceTest.java`

- [ ] **Step 1: Add finder methods to `TicketRepository`**

```java
    List<Ticket> findByIdInAndOrderId(Collection<UUID> ids, UUID orderId);

    long countByOrderIdAndStateNot(UUID orderId, String state);
```

- [ ] **Step 2: Write `RefundServiceTest` covering idempotency, validation, math**

```java
package com.imin.iminapi.refund;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeRefundService;
import com.stripe.model.Refund;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class RefundServiceTest {

    OrderRepository orders = mock(OrderRepository.class);
    TicketRepository tickets = mock(TicketRepository.class);
    RefundRepository refunds = mock(RefundRepository.class);
    RefundTicketRepository refundTickets = mock(RefundTicketRepository.class);
    StripeRefundService stripeRefunds = mock(StripeRefundService.class);
    com.imin.iminapi.refund.RefundService service = new com.imin.iminapi.refund.RefundService(
        orders, tickets, refunds, refundTickets, stripeRefunds);

    UUID orgId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    AuthPrincipal principal = new AuthPrincipal(userId, orgId, null, UUID.randomUUID());
    UUID orderId = UUID.randomUUID();

    Order paidOrder() {
        Order o = new Order();
        o.setId(orderId);
        o.setOrgId(orgId);
        o.setStripePaymentIntentId("pi_x");
        o.setTotalMinor(10000);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(599);   // 5% + 99 fixed = 599 on 10000
        return o;
    }

    Ticket ticket(UUID tierId, int price) {
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setOrderId(orderId);
        t.setTierId(tierId);
        t.setPriceMinor(price);
        t.setState(Ticket.STATE_ISSUED);
        return t;
    }

    @Test
    void idempotencyKey_returnsExistingRefund_withoutCallingStripe() {
        Refund existing = new Refund();
        when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-1"))
            .thenReturn(Optional.of(new com.imin.iminapi.refund.Refund() {{ setId(UUID.randomUUID()); }}));
        var out = service.createRefund(orderId, principal, "idem-1", List.of(UUID.randomUUID()), RefundReason.OTHER);
        assertNotNull(out);
        verifyNoInteractions(stripeRefunds);
    }

    @Test
    void crossOrgOrder_returns404() {
        Order o = paidOrder();
        o.setOrgId(UUID.randomUUID());   // different org
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class,
            () -> service.createRefund(orderId, principal, "k", List.of(UUID.randomUUID()), RefundReason.OTHER));
        assertEquals(ErrorCode.NOT_FOUND, ex.code());
    }

    @Test
    void freeOrder_returns409_ORDER_NOT_REFUNDABLE() {
        Order o = paidOrder();
        o.setStripePaymentIntentId(null);
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class,
            () -> service.createRefund(orderId, principal, "k", List.of(UUID.randomUUID()), RefundReason.OTHER));
        assertEquals(ErrorCode.ORDER_NOT_REFUNDABLE, ex.code());
    }

    @Test
    void appFeeRefund_isProportionalToRefundAmount() throws Exception {
        Order o = paidOrder();   // total 10000, app fee 599
        Ticket t1 = ticket(UUID.randomUUID(), 2500);
        Ticket t2 = ticket(UUID.randomUUID(), 2500);

        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of(t1, t2));
        when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
        when(tickets.countByOrderIdAndStateNot(eq(orderId), eq(Ticket.STATE_REFUNDED))).thenReturn(4L);

        Refund stripeRefund = new Refund();
        stripeRefund.setId("re_1");
        stripeRefund.setCharge("ch_1");
        stripeRefund.setStatus("pending");
        when(stripeRefunds.create(eq("pi_x"), eq(5000L), eq("eur"), eq(RefundReason.OTHER), eq(300L), any()))
            .thenReturn(stripeRefund);
        when(refunds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var out = service.createRefund(orderId, principal, "k",
            List.of(t1.getId(), t2.getId()), RefundReason.OTHER);

        // 599 * 5000 / 10000 = 299.5 → rounds HALF_UP to 300
        assertEquals(300L, out.getApplicationFeeRefundMinor());
        assertEquals(5000L, out.getAmountMinor());
    }
}
```

- [ ] **Step 3: Confirm test fails**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=RefundServiceTest -q
```

Expected: COMPILATION FAILURE.

- [ ] **Step 4: Implement `RefundService.java`**

```java
package com.imin.iminapi.refund;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeRefundService;
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static java.util.stream.Collectors.toSet;

/**
 * Refund chokepoint. All refund creation flows through {@link #createRefund} —
 * validation, authorization, persistence, and the Stripe API call live here.
 * Inventory release does NOT happen here; the {@code charge.refund.updated}
 * webhook is the sole authority for that.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final RefundRepository refunds;
    private final RefundTicketRepository refundTickets;
    private final StripeRefundService stripeRefundService;

    public RefundService(OrderRepository orders, TicketRepository tickets,
                         RefundRepository refunds, RefundTicketRepository refundTickets,
                         StripeRefundService stripeRefundService) {
        this.orders = orders;
        this.tickets = tickets;
        this.refunds = refunds;
        this.refundTickets = refundTickets;
        this.stripeRefundService = stripeRefundService;
    }

    @Transactional
    public Refund createRefund(UUID orderId, AuthPrincipal principal,
                               String idempotencyKey, List<UUID> ticketIds,
                               RefundReason reason) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_IDEMPOTENCY_KEY,
                "Idempotency-Key header is required");
        }

        Optional<Refund> existing = refunds.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey);
        if (existing.isPresent()) {
            log.info("[refund] idempotent replay orderId={} key={} → returning existing {}",
                orderId, idempotencyKey, existing.get().getId());
            return existing.get();
        }

        if (ticketIds == null || ticketIds.isEmpty() || new HashSet<>(ticketIds).size() != ticketIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "ticketIds must be a non-empty unique list");
        }

        Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getOrgId().equals(principal.orgId())) {
            throw ApiException.notFound("Order");   // 404 — no cross-org leak
        }
        if (order.getStripePaymentIntentId() == null || order.getStripePaymentIntentId().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "Order has no Stripe payment to refund");
        }

        List<Ticket> selectedTickets = tickets.findByIdInAndOrderId(ticketIds, orderId);
        if (selectedTickets.size() != ticketIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "One or more ticketIds do not belong to this order");
        }

        Set<UUID> alreadyRefunded = refundTickets.findRefundedTicketIds(ticketIds);
        if (!alreadyRefunded.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_ALREADY_REFUNDED,
                "One or more selected tickets have already been refunded",
                Map.of("ticketIds", alreadyRefunded.stream().map(UUID::toString).collect(toSet()).toString()));
        }
        for (Ticket t : selectedTickets) {
            if (Ticket.STATE_REDEEMED.equals(t.getState())) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_REDEEMED,
                    "Redeemed tickets cannot be refunded",
                    Map.of("ticketId", t.getId().toString()));
            }
        }

        long refundAmountMinor = selectedTickets.stream().mapToLong(Ticket::getPriceMinor).sum();
        if (refundAmountMinor <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "Refund amount must be positive");
        }
        long appFeeRefundMinor = 0;
        if (order.getTotalMinor() > 0 && order.getApplicationFeeMinor() > 0) {
            appFeeRefundMinor = Math.round(
                (double) order.getApplicationFeeMinor() * refundAmountMinor / order.getTotalMinor());
        }

        Refund r = new Refund();
        r.setOrderId(orderId);
        r.setStripePaymentIntentId(order.getStripePaymentIntentId());
        r.setAmountMinor(refundAmountMinor);
        r.setCurrency(order.getCurrency());
        r.setApplicationFeeRefundMinor(appFeeRefundMinor);
        r.setReason(reason == null ? RefundReason.OTHER : reason);
        r.setStatus(RefundStatus.REQUESTED);
        r.setInitiatedByUserId(principal.userId());
        r.setIdempotencyKey(idempotencyKey);
        r = refunds.save(r);

        try {
            List<RefundTicket> rows = new ArrayList<>();
            for (Ticket t : selectedTickets) rows.add(new RefundTicket(r.getId(), t.getId()));
            refundTickets.saveAll(rows);
        } catch (DataIntegrityViolationException race) {
            // UNIQUE on refund_tickets.ticket_id raced. Some concurrent refund grabbed
            // a ticket between our pre-check and INSERT. Surface as TICKET_ALREADY_REFUNDED.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_ALREADY_REFUNDED,
                "One or more selected tickets were just refunded by another request");
        }

        com.stripe.model.Refund stripeRefund;
        try {
            stripeRefund = stripeRefundService.create(
                order.getStripePaymentIntentId(), refundAmountMinor, order.getCurrency(),
                r.getReason(), appFeeRefundMinor, "refund_" + r.getId());
        } catch (StripeException e) {
            log.warn("[refund] Stripe refund failed orderId={} refundId={} — {}",
                orderId, r.getId(), e.getMessage());
            // Row stays at REQUESTED; client can safely retry with same idempotency key.
            HttpStatus status = e.getStatusCode() >= 500
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.UNPROCESSABLE_ENTITY;
            ErrorCode code = e.getStatusCode() >= 500
                ? ErrorCode.UPSTREAM_UNAVAILABLE
                : ErrorCode.STRIPE_REFUND_FAILED;
            throw new ApiException(status, code, e.getMessage(),
                Map.of("stripeCode", String.valueOf(e.getCode())));
        }

        r.setStripeRefundId(stripeRefund.getId());
        r.setStripeChargeId(stripeRefund.getCharge());
        r.setStatus(RefundStatus.fromStripe(stripeRefund.getStatus()));
        r = refunds.save(r);

        log.info("[refund] created id={} orderId={} amount={} {} appFeeRefund={} status={}",
            r.getId(), orderId, refundAmountMinor, order.getCurrency(), appFeeRefundMinor, r.getStatus());
        return r;
    }

    @Transactional(readOnly = true)
    public List<Refund> listForOrder(UUID orderId, AuthPrincipal principal) {
        Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Order");
        return refunds.findByOrderIdOrderByCreatedAtDesc(orderId);
    }
}
```

- [ ] **Step 5: Re-run tests**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=RefundServiceTest -q
```

Expected: PASS. If a test asserts something not yet implemented, fix the implementation; do NOT loosen the test.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundService.java \
        src/main/java/com/imin/iminapi/repository/TicketRepository.java \
        src/test/java/com/imin/iminapi/refund/RefundServiceTest.java
git commit -m "feat(refunds): RefundService chokepoint with idempotency + validation + Stripe call"
```

---

## Task 7: RefundController + DTOs

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/dto/CreateRefundRequest.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/dto/RefundResponse.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/RefundController.java`
- Test: `imin-api/src/test/java/com/imin/iminapi/refund/RefundControllerTest.java`

- [ ] **Step 1: DTOs**

```java
// CreateRefundRequest.java
package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.RefundReason;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateRefundRequest(
    @NotEmpty List<UUID> ticketIds,
    @NotNull RefundReason reason
) {}
```

```java
// RefundResponse.java
package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.refund.RefundTicketRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundResponse(
    UUID id,
    UUID orderId,
    String stripeRefundId,
    long amountMinor,
    String currency,
    long applicationFeeRefundMinor,
    String status,
    String reason,
    List<UUID> ticketIds,
    String failureMessage,
    Instant createdAt
) {
    public static RefundResponse from(Refund r, List<UUID> ticketIds) {
        return new RefundResponse(
            r.getId(), r.getOrderId(), r.getStripeRefundId(),
            r.getAmountMinor(), r.getCurrency(), r.getApplicationFeeRefundMinor(),
            r.getStatus().name().toLowerCase(),
            r.getReason().toWire(),
            ticketIds,
            r.getFailureMessage(),
            r.getCreatedAt()
        );
    }
}
```

- [ ] **Step 2: Controller**

```java
package com.imin.iminapi.refund;

import com.imin.iminapi.refund.dto.CreateRefundRequest;
import com.imin.iminapi.refund.dto.RefundResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders/{orderId}")
public class RefundController {

    private final RefundService refundService;
    private final RefundTicketRepository refundTicketRepository;

    public RefundController(RefundService refundService, RefundTicketRepository refundTicketRepository) {
        this.refundService = refundService;
        this.refundTicketRepository = refundTicketRepository;
    }

    @PostMapping("/refund")
    public ResponseEntity<RefundResponse> refund(
        @PathVariable UUID orderId,
        @CurrentUser AuthPrincipal principal,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody CreateRefundRequest body
    ) {
        Refund refund = refundService.createRefund(orderId, principal, idempotencyKey, body.ticketIds(), body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(refund));
    }

    @GetMapping("/refunds")
    public List<RefundResponse> list(@PathVariable UUID orderId, @CurrentUser AuthPrincipal principal) {
        return refundService.listForOrder(orderId, principal).stream().map(this::toResponse).toList();
    }

    private RefundResponse toResponse(Refund r) {
        List<UUID> ticketIds = refundTicketRepository.findTicketIdsByRefundId(r.getId());
        return RefundResponse.from(r, ticketIds);
    }
}
```

- [ ] **Step 3: Controller test (MockMvc-style; mirror existing controller tests)**

Write a `@WebMvcTest`-style test covering:
- Missing `Idempotency-Key` header → 400 `MISSING_IDEMPOTENCY_KEY`
- Cross-org → 404 `NOT_FOUND`
- Happy path → 202 with `RefundResponse` body

(Use the same pattern as `StripeCheckoutControllerTest` if one exists; otherwise plain MockMvc + mocked `RefundService`.)

- [ ] **Step 4: Build + run new tests**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest='com.imin.iminapi.refund.*' -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/dto/ \
        src/main/java/com/imin/iminapi/refund/RefundController.java \
        src/test/java/com/imin/iminapi/refund/RefundControllerTest.java
git commit -m "feat(refunds): RefundController POST + GET endpoints"
```

---

## Task 8: Webhook handler — onChargeRefundUpdated

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/refund/RefundService.java` (add `handleWebhookStatusChange` method)
- Test: `imin-api/src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceRefundTest.java`

- [ ] **Step 1: Add inventory-release method to `RefundService`**

Append to `RefundService.java`:

```java
    private final TicketTierRepository tierRepo;   // inject in constructor
    private final ApplicationEventPublisher publisher;   // inject in constructor

    /**
     * Called by the webhook dispatcher when a charge.refund.updated event arrives.
     * Performs the race-safe status transition and (on SUCCEEDED) the inventory release.
     */
    @Transactional
    public void handleWebhookStatusChange(String stripeRefundId, RefundStatus newStatus,
                                          String failureCode, String failureMessage) {
        Refund refund = refunds.findByStripeRefundId(stripeRefundId).orElse(null);
        if (refund == null) {
            log.warn("[refund-webhook] no DB row for stripe refund {} — skipping (likely dashboard-initiated)", stripeRefundId);
            return;
        }
        if (refund.getStatus() == newStatus) return;
        if (refund.getStatus().isTerminal()) {
            log.info("[refund-webhook] refund {} already terminal ({}); ignoring late {}",
                refund.getId(), refund.getStatus(), newStatus);
            return;
        }

        int won = refunds.updateStatusIfCurrent(refund.getId(), refund.getStatus(), newStatus);
        if (won == 0) {
            log.info("[refund-webhook] refund {} concurrently transitioned — no-op", refund.getId());
            return;
        }

        if (newStatus == RefundStatus.SUCCEEDED) {
            releaseInventoryAndMarkTickets(refund);
            publisher.publishEvent(new com.imin.iminapi.refund.event.RefundConfirmedEvent(refund.getId()));
        } else if (newStatus == RefundStatus.FAILED) {
            // Re-read so we can set failure_code/_message on the row (the conditional UPDATE
            // only flipped status; this second save persists the failure detail).
            refund = refunds.findById(refund.getId()).orElseThrow();
            refund.setFailureCode(failureCode);
            refund.setFailureMessage(failureMessage);
            refunds.save(refund);
            publisher.publishEvent(new com.imin.iminapi.refund.event.RefundFailedEvent(refund.getId()));
        }
    }

    private void releaseInventoryAndMarkTickets(Refund refund) {
        List<UUID> ticketIds = refundTickets.findTicketIdsByRefundId(refund.getId());
        List<Ticket> ticketsForRefund = tickets.findAllById(ticketIds);
        Map<UUID, Long> qtyByTier = ticketsForRefund.stream()
            .collect(java.util.stream.Collectors.groupingBy(Ticket::getTierId, java.util.stream.Collectors.counting()));
        for (var entry : qtyByTier.entrySet()) {
            TicketTier tier = tierRepo.findByIdForUpdate(entry.getKey())
                .orElseThrow(() -> new IllegalStateException("Missing tier " + entry.getKey()));
            int decrement = entry.getValue().intValue();
            int newSold = Math.max(0, tier.getSold() - decrement);
            if (tier.getSold() < decrement) {
                log.warn("[refund] tier {} sold={} < decrement={} — clamping (drift)",
                    tier.getId(), tier.getSold(), decrement);
            }
            tier.setSold(newSold);
            tierRepo.save(tier);
        }
        for (Ticket t : ticketsForRefund) t.setState(Ticket.STATE_REFUNDED);
        tickets.saveAll(ticketsForRefund);
        log.info("[refund] released {} ticket(s) across {} tier(s) for refund {}",
            ticketsForRefund.size(), qtyByTier.size(), refund.getId());
    }
```

Add the needed imports + extend the constructor to accept `TicketTierRepository` and `ApplicationEventPublisher`.

- [ ] **Step 2: Create event classes**

```java
// RefundConfirmedEvent.java
package com.imin.iminapi.refund.event;
import java.util.UUID;
public record RefundConfirmedEvent(UUID refundId) {}

// RefundFailedEvent.java
package com.imin.iminapi.refund.event;
import java.util.UUID;
public record RefundFailedEvent(UUID refundId) {}
```

- [ ] **Step 3: Extend `StripeWebhookService` switch + handler**

Inject `RefundService` (constructor + field). Add to the V1 switch (in `handleV1Transactional`):

```java
            case "charge.refund.updated" -> {
                log.info("[stripe-webhook] v1 dispatch → charge.refund.updated eventId={}", eventId);
                onChargeRefundUpdated(event);
            }
```

Add the handler method:

```java
    private void onChargeRefundUpdated(com.stripe.model.Event event) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("charge.refund.updated had no deserialized object — apiVersion={}", event.getApiVersion());
            return;
        }
        // The `charge.refund.updated` event's data.object IS the Refund itself (Stripe doc).
        if (!(obj.get() instanceof com.stripe.model.Refund stripeRefund)) {
            log.warn("charge.refund.updated deserialized to unexpected type: {}", obj.get().getClass().getName());
            return;
        }
        RefundStatus newStatus = RefundStatus.fromStripe(stripeRefund.getStatus());
        refundService.handleWebhookStatusChange(
            stripeRefund.getId(),
            newStatus,
            stripeRefund.getFailureReason(),
            stripeRefund.getFailureReason());
    }
```

- [ ] **Step 4: Write webhook test**

`StripeWebhookServiceRefundTest` — synthetic `charge.refund.updated` payload with real HMAC signature (copy the helper from existing `StripeWebhookServiceTest`). Cases:

```java
@Test
void chargeRefundUpdated_succeeded_triggersStatusChangeAndInventoryRelease() {
    // arrange: build JSON event for charge.refund.updated with status=succeeded
    // sign with test secret, call service.handleV1Endpoint(body, sig)
    // verify refundService.handleWebhookStatusChange called with SUCCEEDED
}

@Test
void chargeRefundUpdated_failed_recordsFailureFields() { ... }

@Test
void chargeRefundUpdated_replay_isDedupSkipped() { ... }
```

- [ ] **Step 5: Build + run tests**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest='com.imin.iminapi.stripe.StripeWebhookServiceRefundTest,com.imin.iminapi.refund.*' -q
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/event/ \
        src/main/java/com/imin/iminapi/refund/RefundService.java \
        src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java \
        src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceRefundTest.java
git commit -m "feat(refunds): charge.refund.updated webhook → inventory release + events"
```

---

## Task 9: RefundConfirmationEmailer — @Async listener

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/refund/email/RefundConfirmationEmailer.java`
- Test: `imin-api/src/test/java/com/imin/iminapi/refund/email/RefundConfirmationEmailerTest.java`

- [ ] **Step 1: Implement the emailer**

Mirror the `@Async @EventListener` pattern from `TicketIssuanceEmailer` (or whatever existing class listens for `TicketsIssuedEvent`).

```java
package com.imin.iminapi.refund.email;

import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.event.RefundConfirmedEvent;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RefundConfirmationEmailer {

    private static final Logger log = LoggerFactory.getLogger(RefundConfirmationEmailer.class);

    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final EventRepository events;
    private final EmailService emailService;
    private final EmailTemplateRenderer renderer;

    public RefundConfirmationEmailer(RefundRepository refunds, OrderRepository orders,
                                     EventRepository events,
                                     EmailService emailService, EmailTemplateRenderer renderer) {
        this.refunds = refunds;
        this.orders = orders;
        this.events = events;
        this.emailService = emailService;
        this.renderer = renderer;
    }

    @Async
    @EventListener
    public void onRefundConfirmed(RefundConfirmedEvent ev) {
        Refund r = refunds.findById(ev.refundId()).orElse(null);
        if (r == null) {
            log.warn("[refund-email] refund {} not found — skipping", ev.refundId());
            return;
        }
        Order order = orders.findById(r.getOrderId()).orElse(null);
        if (order == null || order.getEmail() == null) {
            log.warn("[refund-email] order or email missing for refund {}", r.getId());
            return;
        }
        Event event = events.findById(order.getEventId()).orElse(null);
        String eventName = event != null ? event.getName() : "your event";

        Map<String, Object> vars = Map.of(
            "eventName", eventName,
            "orderShortCode", order.getId().toString().substring(0, 8),
            "amountFormatted", formatAmount(r.getAmountMinor(), r.getCurrency()),
            "currency", r.getCurrency().toUpperCase()
        );
        String html = renderer.render("refund-confirmed.html.mustache", vars);
        String text = renderer.render("refund-confirmed.txt.mustache", vars);
        String subject = "Refund confirmed for " + eventName;

        try {
            emailService.send(order.getEmail(), subject, html, text);
            log.info("[refund-email] sent for refund {} to {}", r.getId(), order.getEmail());
        } catch (RuntimeException e) {
            log.warn("[refund-email] send failed for refund {} — {}", r.getId(), e.getMessage());
            // Swallow: webhook has already acked. A follow-up reconciler could resend.
        }
    }

    private String formatAmount(long minor, String currency) {
        return String.format("%.2f", minor / 100.0) + " " + currency.toUpperCase();
    }
}
```

- [ ] **Step 2: Create templates**

Add `imin-api/src/main/resources/templates/refund-confirmed.html.mustache` and `.txt.mustache` mirroring existing `EmailTemplateRenderer` template format. Body should mention amount, order short code, "5–10 business days", organizer contact.

(If the existing template engine differs, replicate the pattern used by `AccountEmailService`.)

- [ ] **Step 3: Test the emailer**

```java
@Test
void onRefundConfirmed_sendsTemplatedEmail() {
    // arrange refund + order + event + renderer stub
    emailer.onRefundConfirmed(new RefundConfirmedEvent(refundId));
    verify(emailService).send(eq("buyer@example.com"), contains("Refund"), any(), any());
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest='RefundConfirmationEmailerTest' -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/email/ \
        src/main/resources/templates/refund-confirmed.* \
        src/test/java/com/imin/iminapi/refund/email/
git commit -m "feat(refunds): async buyer email on refund confirmation"
```

---

## Task 10: `GET /events/{eventId}/orders` listing endpoint

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/controller/order/EventOrdersController.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/controller/order/dto/OrderRowResponse.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/repository/OrderRepository.java`

- [ ] **Step 1: Add repo finder**

```java
    @Query("""
            select o from Order o
             where o.eventId = :eventId
             order by o.createdAt desc
            """)
    List<Order> findByEventIdOrderByCreatedAtDesc(@Param("eventId") UUID eventId);
```

- [ ] **Step 2: DTO**

```java
package com.imin.iminapi.controller.order.dto;

import java.time.Instant;
import java.util.UUID;

public record OrderRowResponse(
    UUID id,
    String shortCode,
    String email,
    long totalMinor,
    String currency,
    int ticketCount,
    int refundedTicketCount,
    String status,        // paid | partially_refunded | refunded
    Instant createdAt
) {}
```

- [ ] **Step 3: Controller**

```java
package com.imin.iminapi.controller.order;

import com.imin.iminapi.controller.order.dto.OrderRowResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.refund.RefundTicketRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/orders")
public class EventOrdersController {

    private final EventRepository events;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final RefundTicketRepository refundTickets;

    public EventOrdersController(EventRepository events, OrderRepository orders,
                                 TicketRepository tickets, RefundTicketRepository refundTickets) {
        this.events = events;
        this.orders = orders;
        this.tickets = tickets;
        this.refundTickets = refundTickets;
    }

    @GetMapping
    public List<OrderRowResponse> list(@PathVariable UUID eventId, @CurrentUser AuthPrincipal principal) {
        Event event = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!event.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");
        List<Order> rows = orders.findByEventIdOrderByCreatedAtDesc(eventId);
        return rows.stream().map(this::toRow).toList();
    }

    private OrderRowResponse toRow(Order o) {
        int totalTickets = tickets.findByOrderIdOrderByCreatedAtAsc(o.getId()).size();
        int refundedCount = (int) tickets.findByOrderIdOrderByCreatedAtAsc(o.getId()).stream()
            .filter(t -> "refunded".equals(t.getState())).count();
        String status = refundedCount == 0 ? "paid"
                       : refundedCount == totalTickets ? "refunded"
                       : "partially_refunded";
        return new OrderRowResponse(
            o.getId(),
            o.getId().toString().substring(0, 8),
            o.getEmail(),
            o.getTotalMinor(),
            o.getCurrency(),
            totalTickets,
            refundedCount,
            status,
            o.getCreatedAt()
        );
    }
}
```

- [ ] **Step 4: Build**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/controller/order/ \
        src/main/java/com/imin/iminapi/repository/OrderRepository.java
git commit -m "feat(refunds): GET /events/{id}/orders listing for dashboard Orders tab"
```

---

## Task 11: Backend acceptance — full suite green + manual end-to-end

- [ ] **Step 1: Run the full test suite**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -q
```

Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 2: Boot the app locally + manual smoke**

```bash
cd /Users/ivan/imin/imin-api && docker compose up -d && ./mvnw spring-boot:run
```

In another terminal, hit the Swagger UI at `http://localhost:8085/swagger-ui.html` and verify the `POST /api/v1/orders/{orderId}/refund` and `GET /api/v1/events/{eventId}/orders` endpoints appear.

- [ ] **Step 3: Stripe Dashboard webhook subscription**

In Stripe Dashboard → Developers → Webhooks → the existing `/api/v1/stripe/webhook/v1` endpoint, add `charge.refund.updated` to the event list. (Manual step; document in commit message.)

For local dev: `stripe listen --forward-to http://localhost:8085/api/v1/stripe/webhook/v1` already covers all events.

- [ ] **Step 4: Commit any housekeeping**

```bash
git add -A
git status
```

---

## Task 12: Webapp — types + ordersApi + IDEMPOTENT_ENDPOINTS allowlist

**Files:**
- Modify: `imin-webapp/src/shared/api/types.ts`
- Modify: `imin-webapp/src/shared/api/client.ts`
- Create: `imin-webapp/src/features/events/orders/ordersApi.ts`

- [ ] **Step 1: Add types to `types.ts`**

Append:

```typescript
export interface OrderRow {
  id: string;
  shortCode: string;
  email: string;
  totalMinor: number;
  currency: string;
  ticketCount: number;
  refundedTicketCount: number;
  status: 'paid' | 'partially_refunded' | 'refunded';
  createdAt: string;
}

export type RefundReason = 'requested_by_customer' | 'duplicate' | 'fraudulent' | 'other';

export interface Refund {
  id: string;
  orderId: string;
  stripeRefundId: string | null;
  amountMinor: number;
  currency: string;
  applicationFeeRefundMinor: number;
  status: 'requested' | 'pending' | 'succeeded' | 'failed' | 'canceled';
  reason: RefundReason;
  ticketIds: string[];
  failureMessage?: string | null;
  createdAt: string;
}
```

- [ ] **Step 2: Allowlist refund endpoint for idempotency**

In `imin-webapp/src/shared/api/client.ts`, find `IDEMPOTENT_ENDPOINTS` and add:

```typescript
// matches POST /orders/{uuid}/refund
/^\/orders\/[^/]+\/refund$/,
```

(Match the regex/list shape — if it's an array of strings, add a marker the matcher recognizes.)

- [ ] **Step 3: API client**

```typescript
// imin-webapp/src/features/events/orders/ordersApi.ts
import { apiFetch } from '../../../shared/api/client';
import type { OrderRow, Refund, RefundReason } from '../../../shared/api/types';

export function getEventOrders(eventId: string): Promise<OrderRow[]> {
  return apiFetch<OrderRow[]>(`/events/${eventId}/orders`);
}

export function getOrderRefunds(orderId: string): Promise<Refund[]> {
  return apiFetch<Refund[]>(`/orders/${orderId}/refunds`);
}

export interface RefundRequest {
  ticketIds: string[];
  reason: RefundReason;
}

export function refundOrder(orderId: string, body: RefundRequest, idempotencyKey: string): Promise<Refund> {
  return apiFetch<Refund>(`/orders/${orderId}/refund`, {
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}
```

(Confirm the `apiFetch` signature accepts a `headers` override; if not, extend it.)

- [ ] **Step 4: Typecheck**

```bash
cd /Users/ivan/imin/imin-webapp && npm run typecheck
```

Expected: 0 errors.

- [ ] **Step 5: Commit**

```bash
git add src/shared/api/types.ts src/shared/api/client.ts src/features/events/orders/ordersApi.ts
git commit -m "feat(refunds): client types + ordersApi"
```

---

## Task 13: RefundOrderDialog component

**Files:**
- Create: `imin-webapp/src/features/events/orders/RefundOrderDialog.tsx`

- [ ] **Step 1: Implement the dialog**

Mirror existing `ConfirmDialog` + `FormField` usage. The dialog accepts an `OrderRow` + the order's tickets (fetched separately) and lets the user pick full or per-ticket partial.

```typescript
import { useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ConfirmDialog } from '../../../shared/ui/ConfirmDialog/ConfirmDialog';
import { FormField } from '../../../shared/ui/FormField/FormField';
import { refundOrder } from './ordersApi';
import type { OrderRow, RefundReason } from '../../../shared/api/types';
import { ApiError } from '../../../shared/api/errors';

interface RefundableTicket {
  id: string;
  tierName: string;
  priceMinor: number;
}

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  order: OrderRow;
  refundableTickets: RefundableTicket[];   // already filtered to state != refunded
  eventId: string;
}

const REASONS: Array<{ value: RefundReason; label: string }> = [
  { value: 'requested_by_customer', label: 'Requested by customer' },
  { value: 'duplicate', label: 'Duplicate' },
  { value: 'fraudulent', label: 'Fraudulent' },
  { value: 'other', label: 'Other' },
];

export function RefundOrderDialog({ open, onOpenChange, order, refundableTickets, eventId }: Props) {
  const qc = useQueryClient();
  const [mode, setMode] = useState<'full' | 'partial'>('full');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [reason, setReason] = useState<RefundReason>('requested_by_customer');
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  const ticketsToRefund = useMemo(() => {
    if (mode === 'full') return refundableTickets;
    return refundableTickets.filter(t => selectedIds.has(t.id));
  }, [mode, selectedIds, refundableTickets]);

  const totalMinor = ticketsToRefund.reduce((s, t) => s + t.priceMinor, 0);
  const totalFormatted = (totalMinor / 100).toFixed(2);

  const mutation = useMutation({
    mutationFn: () => refundOrder(order.id, {
      ticketIds: ticketsToRefund.map(t => t.id),
      reason,
    }, idempotencyKey),
    onSuccess: () => {
      toast.success(
        `Refund initiated. The buyer will be notified once it's confirmed by their bank (typically 5–10 business days).`
      );
      qc.invalidateQueries({ queryKey: ['events', eventId, 'orders'] });
      onOpenChange(false);
    },
    onError: (e) => {
      const msg = e instanceof ApiError ? e.message : 'Refund failed — please try again';
      toast.error(msg);
    },
  });

  const valid = ticketsToRefund.length > 0;

  return (
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title={`Refund order #${order.shortCode}`}
      message=""
      confirmLabel={valid ? `Refund €${totalFormatted}` : 'Refund'}
      cancelLabel="Cancel"
      dangerous
      busy={mutation.isPending}
      onConfirm={() => valid && mutation.mutate()}
    >
      <div style={{ display: 'grid', gap: 12 }}>
        <FormField label="Refund type">
          <label><input type="radio" checked={mode === 'full'} onChange={() => setMode('full')} /> Full refund ({refundableTickets.length} ticket(s))</label>
          <label><input type="radio" checked={mode === 'partial'} onChange={() => setMode('partial')} /> Partial refund</label>
        </FormField>

        {mode === 'partial' && (
          <FormField label="Tickets to refund">
            {refundableTickets.map(t => (
              <label key={t.id} style={{ display: 'block' }}>
                <input
                  type="checkbox"
                  checked={selectedIds.has(t.id)}
                  onChange={e => {
                    const next = new Set(selectedIds);
                    e.target.checked ? next.add(t.id) : next.delete(t.id);
                    setSelectedIds(next);
                  }}
                />
                {' '}{t.tierName} — €{(t.priceMinor / 100).toFixed(2)}
              </label>
            ))}
          </FormField>
        )}

        <FormField label="Reason">
          <select value={reason} onChange={e => setReason(e.target.value as RefundReason)}>
            {REASONS.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
          </select>
        </FormField>
      </div>
    </ConfirmDialog>
  );
}
```

- [ ] **Step 2: Typecheck**

```bash
cd /Users/ivan/imin/imin-webapp && npm run typecheck
```

Expected: 0 errors. Adjust imports / paths per actual `ConfirmDialog` / `FormField` / `ApiError` exports.

- [ ] **Step 3: Commit**

```bash
git add src/features/events/orders/RefundOrderDialog.tsx
git commit -m "feat(refunds): RefundOrderDialog"
```

---

## Task 14: EventOrdersTab + wire into EventDetailPage

**Files:**
- Create: `imin-webapp/src/features/events/orders/EventOrdersTab.tsx`
- Modify: `imin-webapp/src/features/events/EventDetailPage.tsx`

- [ ] **Step 1: `EventOrdersTab`**

```typescript
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getEventOrders } from './ordersApi';
import { RefundOrderDialog } from './RefundOrderDialog';
import type { OrderRow } from '../../../shared/api/types';

interface Props { eventId: string; }

export function EventOrdersTab({ eventId }: Props) {
  const { data: orders, isLoading, error } = useQuery({
    queryKey: ['events', eventId, 'orders'],
    queryFn: () => getEventOrders(eventId),
  });

  const [refundingOrder, setRefundingOrder] = useState<OrderRow | null>(null);

  if (isLoading) return <div>Loading orders…</div>;
  if (error) return <div>Failed to load orders.</div>;
  if (!orders || orders.length === 0) return <div>No orders yet.</div>;

  return (
    <div>
      <table>
        <thead>
          <tr><th>Order</th><th>Buyer</th><th>Tickets</th><th>Total</th><th>Status</th><th>Created</th><th></th></tr>
        </thead>
        <tbody>
          {orders.map(o => (
            <tr key={o.id}>
              <td>#{o.shortCode}</td>
              <td>{o.email}</td>
              <td>{o.refundedTicketCount > 0 ? `${o.ticketCount - o.refundedTicketCount} of ${o.ticketCount}` : o.ticketCount}</td>
              <td>{(o.totalMinor / 100).toFixed(2)} {o.currency.toUpperCase()}</td>
              <td>{o.status === 'paid' ? 'Paid' : o.status === 'partially_refunded' ? 'Partially refunded' : 'Refunded'}</td>
              <td>{new Date(o.createdAt).toLocaleString()}</td>
              <td>
                <button
                  disabled={o.refundedTicketCount === o.ticketCount}
                  onClick={() => setRefundingOrder(o)}
                >
                  Refund
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {refundingOrder && (
        <RefundOrderDialog
          open={true}
          onOpenChange={(open) => { if (!open) setRefundingOrder(null); }}
          order={refundingOrder}
          refundableTickets={[]}  /* TODO: fetch tickets for this order */
          eventId={eventId}
        />
      )}
    </div>
  );
}
```

Note: refundable tickets for a given order will need a second fetch — either a `GET /orders/{id}/tickets` endpoint OR derive from a single `GET /events/{id}/orders?include=tickets` payload. For Phase A, the simplest path is to add `tickets` (price + tierName + state) onto the existing `OrderRowResponse` DTO and pass them down. **Add this in a quick precursor edit before Step 2: extend `OrderRowResponse` and the controller to include `tickets: List<{id, tierName, priceMinor, state}>`.**

- [ ] **Step 2: Wire into `EventDetailPage`**

Read the existing tab pattern in `EventDetailPage.tsx`. Add an "Orders" tab pointing at `<EventOrdersTab eventId={eventId} />`.

- [ ] **Step 3: Typecheck + lint**

```bash
cd /Users/ivan/imin/imin-webapp && npm run typecheck && npm run lint
```

Expected: 0 errors.

- [ ] **Step 4: Manual smoke**

```bash
cd /Users/ivan/imin/imin-webapp && npm run dev
```

Open the dashboard → navigate to an event → click Orders tab → click Refund on a row → confirm the modal appears with correct totals.

- [ ] **Step 5: Commit**

```bash
git add src/features/events/orders/EventOrdersTab.tsx src/features/events/EventDetailPage.tsx
git commit -m "feat(refunds): Orders tab on EventDetailPage with Refund action"
```

---

## Task 15: Final verification

- [ ] **Step 1: Backend full test pass**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -q
```

- [ ] **Step 2: Webapp build**

```bash
cd /Users/ivan/imin/imin-webapp && npm run build
```

- [ ] **Step 3: Confirm goal artifacts exist**

Run:
```bash
grep -rn "charge.refund.updated" /Users/ivan/imin/imin-api/src/main/java
grep -rn "RefundController" /Users/ivan/imin/imin-api/src/main/java
grep -rn "RefundOrderDialog" /Users/ivan/imin/imin-webapp/src
```

Each should return at least one hit.

---

## Self-review checklist (post-write)

- ✅ Spec coverage: every section in the spec maps to at least one task — Task 1 (V28 schema + precursors P1/P2), Task 6 (validation + idempotency + Stripe call), Task 5 (Stripe SDK), Task 8 (webhook + inventory release), Task 9 (email), Task 10 + 12-14 (UI listing).
- ✅ No `TBD`/`TODO` placeholders in plan steps. The single "TODO" in Task 14's dialog is identified with an explicit fix (extend `OrderRowResponse` to include ticket detail) and the precursor edit is called out.
- ✅ Types consistent across tasks: `RefundStatus` enum used in Refund entity, RefundService, webhook handler. `RefundReason.toStripe()` / `.toWire()` defined in Task 4 and used in Tasks 6/7.
- ✅ Method signatures consistent: `RefundService.createRefund(UUID, AuthPrincipal, String, List<UUID>, RefundReason)` defined in Task 6, called in Task 7 controller with same signature.
- ✅ Idempotency: client-key short-circuit in RefundService (Task 6) + Stripe deterministic key (`refund_<id>`) in StripeRefundService (Task 5) + webhook event dedup (existing infra, no change needed).
- ✅ One known open item: PinAPIVersion (open question #1 in spec) — left out of the plan tasks. Will revisit if user wants it before merge.
