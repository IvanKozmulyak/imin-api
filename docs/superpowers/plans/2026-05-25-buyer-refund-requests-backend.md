# Buyer Refund Requests — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the `imin-api` backend for buyer-initiated refund requests (public magic-link → form → submit → organizer review/approve/reject), and fix the existing promo-code refund-amount bug in `RefundService`.

**Architecture:** Two new tables (`refund_requests`, `refund_request_tokens`). One new service (`RefundRequestService`) that orchestrates: magic-link issuance (modeled on `OrderRecoveryService`), token redemption (single-use, hashed at rest), request submission, list/detail, approve (invokes existing `RefundService.createRefund`), reject. Two new controllers (one public, one organizer-authenticated). Five new Resend templates. The `RefundService` amount calculation is migrated from face-price sum to `Order.totalMinor`-anchored proportional allocation with a last-refund clamp so a full-order refund always equals exactly `Order.totalMinor`.

**Tech Stack:** Java 17 · Spring Boot 4 · Spring Data JPA · Flyway · H2 (PG-compat) for tests · Resend · JUnit 5 + AssertJ + Mockito · Lombok.

**Spec:** `docs/superpowers/specs/2026-05-25-buyer-refund-requests-design.md`

---

## File Structure

### Created

```
src/main/java/com/imin/iminapi/refund/
  RefundRequest.java                          # JPA entity
  RefundRequestStatus.java                    # PENDING/APPROVED/REJECTED/WITHDRAWN
  RefundRequestReason.java                    # buyer-facing enum + Stripe mapping
  RefundRequestToken.java                     # JPA entity
  RefundRequestRepository.java                # JpaRepository
  RefundRequestTokenRepository.java           # JpaRepository
  RefundRequestService.java                   # orchestration
  RefundRequestTokenSweeper.java              # @Scheduled cleanup
  RefundRequestController.java                # organizer-authenticated surface
  email/RefundRequestEmailer.java             # @EventListener
  event/RefundRequestSubmittedEvent.java
  event/RefundRequestRejectedEvent.java
  dto/RefundRequestSummaryResponse.java       # list row
  dto/RefundRequestDetailResponse.java        # detail
  dto/PublicRefundFormResponse.java           # GET /by-token/{t}
  dto/PublicRefundSubmitRequest.java          # POST /by-token/{t} body
  dto/PublicRefundSubmitResponse.java         # POST /by-token/{t} response
  dto/RefundRequestApproveRequest.java
  dto/RefundRequestRejectRequest.java
  dto/RefundRequestDecisionResponse.java
  dto/ProposedRefundResponse.java             # nested in detail/error

src/main/java/com/imin/iminapi/controller/publicapi/
  PublicRefundRequestController.java          # public surface

src/main/resources/db/migration/
  V30__refund_requests.sql
  V31__refund_request_tokens.sql

src/main/resources/email-templates/
  refund-request-link.html
  refund-request-link.txt
  refund-request-received-buyer.html
  refund-request-received-buyer.txt
  refund-request-notify-organizer.html
  refund-request-notify-organizer.txt
  refund-request-notify-imin.html
  refund-request-notify-imin.txt
  refund-request-rejected.html
  refund-request-rejected.txt

src/test/java/com/imin/iminapi/refund/
  RefundRequestServiceTest.java
  RefundRequestEmailerTest.java
  PublicRefundRequestControllerTest.java
  RefundRequestControllerTest.java
  RefundRequestTokenSweeperTest.java
```

### Modified

```
src/main/java/com/imin/iminapi/refund/RefundService.java          # promo-code amount fix
src/main/java/com/imin/iminapi/refund/RefundRepository.java       # add aggregate query for prior refunded
src/main/java/com/imin/iminapi/security/ErrorCode.java            # new codes
src/main/java/com/imin/iminapi/email/EmailProperties.java         # refundRequestInbox + tokenTtlMinutes
src/main/java/com/imin/iminapi/config/SecurityConfig.java         # whitelist /api/v1/public/refund-requests/**
src/test/java/com/imin/iminapi/refund/RefundServiceTest.java      # extend with promo + drift tests
src/main/resources/application.yaml                               # bind new env vars
```

---

## Phase 1 — Promo-code fix in RefundService

This phase has no dependencies on the rest. Land it first; it can be reviewed independently.

### Task 1.1: Extend `RefundServiceTest` with promo / drift tests

**Files:**
- Modify: `src/test/java/com/imin/iminapi/refund/RefundServiceTest.java`

- [ ] **Step 1: Add four new tests at the bottom of `RefundServiceTest`** (before the closing `}`):

```java
    @org.junit.jupiter.api.Nested
    class PromoCodeAmountAllocation {

        @Test
        void full_order_with_promo_refunds_total_minor_not_face_price() {
            // Order total 8000 (promo applied), face price sums to 10000.
            // A full-order refund must call Stripe with 8000, not 10000.
            Order o = paidOrder();
            o.setTotalMinor(8000);
            o.setApplicationFeeMinor(400);
            when(orders.findById(orderId)).thenReturn(Optional.of(o));

            Ticket t1 = ticket(5000);
            Ticket t2 = ticket(5000);
            List<UUID> ids = List.of(t1.getId(), t2.getId());
            when(tickets.findByIdInAndOrderId(ids, orderId)).thenReturn(List.of(t1, t2));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
            when(refundTickets.findRefundedTicketIds(ids)).thenReturn(Set.of());
            when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-1")).thenReturn(Optional.empty());
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
            when(refunds.save(any())).thenAnswer(inv -> inv.getArgument(0));

            com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
            stripeRefund.setId("re_1");
            stripeRefund.setStatus("pending");
            when(stripeRefunds.create(eq("pi_x"), eq(8000L), eq("eur"), any(), eq(400L), anyString()))
                .thenReturn(stripeRefund);

            service.createRefund(orderId, principal, "idem-1", ids, RefundReason.OTHER);

            // Verify the exact arguments passed to Stripe.
            ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> fee = ArgumentCaptor.forClass(Long.class);
            org.mockito.Mockito.verify(stripeRefunds)
                .create(eq("pi_x"), amount.capture(), eq("eur"), any(), fee.capture(), anyString());
            assertThat(amount.getValue()).isEqualTo(8000L);
            assertThat(fee.getValue()).isEqualTo(400L);
        }

        @Test
        void partial_with_promo_uses_proportional_total_minor_allocation() {
            // Order total 8000, face total 10000, refunding one of two 5000-face tickets.
            // Proportional amount = round(8000 * 5000 / 10000) = 4000.
            Order o = paidOrder();
            o.setTotalMinor(8000);
            o.setApplicationFeeMinor(400);
            when(orders.findById(orderId)).thenReturn(Optional.of(o));

            Ticket t1 = ticket(5000);
            Ticket t2 = ticket(5000);
            List<UUID> ids = List.of(t1.getId());
            when(tickets.findByIdInAndOrderId(ids, orderId)).thenReturn(List.of(t1));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
            when(refundTickets.findRefundedTicketIds(ids)).thenReturn(Set.of());
            when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-1")).thenReturn(Optional.empty());
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
            when(refunds.save(any())).thenAnswer(inv -> inv.getArgument(0));

            com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
            stripeRefund.setId("re_2");
            stripeRefund.setStatus("pending");
            when(stripeRefunds.create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), anyString()))
                .thenReturn(stripeRefund);

            service.createRefund(orderId, principal, "idem-1", ids, RefundReason.OTHER);

            org.mockito.Mockito.verify(stripeRefunds)
                .create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), anyString());
        }

        @Test
        void last_remaining_refund_clamps_to_total_minor_minus_prior_refunds() {
            // Two partial refunds with rounding: prior refunded 4000, this one
            // should clamp to remaining (4000) even if the proportional formula
            // overshoots due to integer rounding.
            Order o = paidOrder();
            o.setTotalMinor(8000);
            o.setApplicationFeeMinor(400);
            when(orders.findById(orderId)).thenReturn(Optional.of(o));

            Ticket t1 = ticket(5000);
            Ticket t2 = ticket(5001);  // deliberately quirky face to provoke rounding
            List<UUID> ids = List.of(t2.getId());
            when(tickets.findByIdInAndOrderId(ids, orderId)).thenReturn(List.of(t2));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
            when(refundTickets.findRefundedTicketIds(ids)).thenReturn(Set.of());
            when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-final")).thenReturn(Optional.empty());
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(4000L);
            when(refunds.save(any())).thenAnswer(inv -> inv.getArgument(0));

            com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
            stripeRefund.setId("re_3");
            stripeRefund.setStatus("pending");
            when(stripeRefunds.create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), anyString()))
                .thenReturn(stripeRefund);

            service.createRefund(orderId, principal, "idem-final", ids, RefundReason.OTHER);

            org.mockito.Mockito.verify(stripeRefunds)
                .create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), anyString());
        }

        @Test
        void zero_remaining_returns_409() {
            // Prior refunds already cover the full order total.
            Order o = paidOrder();
            o.setTotalMinor(8000);
            when(orders.findById(orderId)).thenReturn(Optional.of(o));

            Ticket t1 = ticket(5000);
            List<UUID> ids = List.of(t1.getId());
            when(tickets.findByIdInAndOrderId(ids, orderId)).thenReturn(List.of(t1));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1));
            when(refundTickets.findRefundedTicketIds(ids)).thenReturn(Set.of());
            when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-z")).thenReturn(Optional.empty());
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(8000L);

            ApiException ex = (ApiException) assertThatThrownBy(() ->
                service.createRefund(orderId, principal, "idem-z", ids, RefundReason.OTHER))
                .isInstanceOf(ApiException.class).actual();
            assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_NOT_REFUNDABLE);
            verifyNoInteractions(stripeRefunds);
        }
    }
```

- [ ] **Step 2: Run the new tests to confirm they fail**

```bash
./mvnw test -Dtest=RefundServiceTest -q
```

Expected: 4 failures in the `PromoCodeAmountAllocation` nested class. The compile may fail first because `refunds.sumActiveAmountByOrderId(orderId)` and `tickets.findByOrderId(orderId)` don't exist yet — that is the failure signal. Proceed.

- [ ] **Step 3: Commit the failing tests**

```bash
git add src/test/java/com/imin/iminapi/refund/RefundServiceTest.java
git commit -m "test: failing tests for promo-code refund amount allocation"
```

### Task 1.2: Add repository methods needed by the fix

**Files:**
- Modify: `src/main/java/com/imin/iminapi/refund/RefundRepository.java`
- Modify: `src/main/java/com/imin/iminapi/repository/TicketRepository.java`

- [ ] **Step 1: Add `sumActiveAmountByOrderId` to `RefundRepository`**

Open `src/main/java/com/imin/iminapi/refund/RefundRepository.java` and add the method. Add the imports if missing:

```java
import org.springframework.data.jpa.repository.Query;

// inside the interface:
@Query("""
    select coalesce(sum(r.amountMinor), 0) from Refund r
    where r.orderId = :orderId
      and r.status in (com.imin.iminapi.refund.RefundStatus.REQUESTED,
                       com.imin.iminapi.refund.RefundStatus.PENDING,
                       com.imin.iminapi.refund.RefundStatus.SUCCEEDED)
""")
long sumActiveAmountByOrderId(java.util.UUID orderId);
```

Rationale (also add this as a one-line comment above the method): *only sum refunds that have committed money or are in-flight; CANCELED and FAILED rows don't count against the order's refundable balance.*

- [ ] **Step 2: Add `findByOrderId` to `TicketRepository`**

Open `src/main/java/com/imin/iminapi/repository/TicketRepository.java`. Check if a similar method already exists. If not, add:

```java
List<Ticket> findByOrderId(java.util.UUID orderId);
```

(Spring Data derives the query from the method name; no `@Query` needed.)

- [ ] **Step 3: Compile**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRepository.java src/main/java/com/imin/iminapi/repository/TicketRepository.java
git commit -m "feat(refund): add repository methods for amount allocation fix"
```

### Task 1.3: Implement the amount-calc fix in `RefundService`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/refund/RefundService.java`

- [ ] **Step 1: Replace the amount calculation block**

In `RefundService.createRefund`, find this block (currently at lines ~127–140):

```java
        long refundAmountMinor = selected.stream().mapToLong(Ticket::getPriceMinor).sum();
        if (refundAmountMinor <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "Refund amount must be positive");
        }

        // Per-refund proportional formula. Applied uniformly across refunds —
        // sum of all refunds' app-fee refunds equals the original fee when the
        // order is fully refunded (with at most N−1 cents rounding error for N refunds).
        long appFeeRefundMinor = 0;
        if (order.getTotalMinor() > 0 && order.getApplicationFeeMinor() > 0) {
            appFeeRefundMinor = Math.round(
                (double) order.getApplicationFeeMinor() * refundAmountMinor / order.getTotalMinor());
        }
```

Replace with:

```java
        long refundAmountMinor = computeRefundAmountMinor(order, selected);
        if (refundAmountMinor <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "Refund amount must be positive");
        }

        // Application-fee refund stays proportional to refund amount over order total.
        // This continues the existing semantics; only the principal calc has moved off
        // face price. Total app-fee refunds across all refunds equal the original fee
        // when the order is fully refunded (within at most N−1 cents drift over N refunds).
        long appFeeRefundMinor = 0;
        if (order.getTotalMinor() > 0 && order.getApplicationFeeMinor() > 0) {
            appFeeRefundMinor = Math.round(
                (double) order.getApplicationFeeMinor() * refundAmountMinor / order.getTotalMinor());
        }
```

- [ ] **Step 2: Add the `computeRefundAmountMinor` helper** at the bottom of the class (just above the closing `}`):

```java
    /**
     * Refund principal = totalMinor × (selected face / order face), clamped to
     * remaining (= totalMinor − prior active refunds). Anchoring on totalMinor
     * (not face price) means promo-discounted orders refund the actual paid
     * amount; the clamp absorbs cross-refund rounding drift so a full-order
     * refund always equals exactly totalMinor.
     */
    private long computeRefundAmountMinor(Order order, List<Ticket> selected) {
        long totalMinor = order.getTotalMinor();
        if (totalMinor <= 0) return 0;

        long selectedFace = selected.stream().mapToLong(Ticket::getPriceMinor).sum();
        long orderFace = tickets.findByOrderId(order.getId()).stream()
            .mapToLong(Ticket::getPriceMinor).sum();
        if (orderFace <= 0 || selectedFace <= 0) return 0;

        long proposed = Math.round((double) totalMinor * selectedFace / orderFace);

        long priorRefunded = refunds.sumActiveAmountByOrderId(order.getId());
        long remaining = totalMinor - priorRefunded;
        if (remaining <= 0) return 0;

        return Math.min(proposed, remaining);
    }
```

- [ ] **Step 3: Run the promo tests again**

```bash
./mvnw test -Dtest=RefundServiceTest -q
```

Expected: all `PromoCodeAmountAllocation` tests pass; no regression in the older tests.

- [ ] **Step 4: Run the broader refund suite to catch regressions**

```bash
./mvnw test -Dtest='RefundServiceTest,RefundControllerTest' -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundService.java
git commit -m "fix(refund): anchor refund amount on Order.totalMinor (promo-safe)

Refund amounts were summed from Ticket.priceMinor (face price), which
exceeds what the buyer paid when a promo code was applied. Switch to
proportional allocation against Order.totalMinor with a remaining clamp
so a full-order refund always equals exactly totalMinor."
```

---

## Phase 2 — Schema, enums, entities, repos

### Task 2.1: Migration `V30__refund_requests.sql`

**Files:**
- Create: `src/main/resources/db/migration/V30__refund_requests.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V30__refund_requests.sql
-- Buyer-initiated refund requests. Distinct from the Stripe-side `refunds`
-- table (V28) — a request is a CS/audit concept and may or may not become a
-- Stripe refund. See spec 2026-05-25-buyer-refund-requests-design.md.

CREATE TABLE refund_requests (
  id                   UUID PRIMARY KEY,
  order_id             UUID NOT NULL REFERENCES orders(id),
  org_id               UUID NOT NULL,
  event_id             UUID NOT NULL,
  buyer_email          VARCHAR(254) NOT NULL,
  buyer_phone          VARCHAR(32),
  reason               VARCHAR(32) NOT NULL,
  explanation          TEXT NOT NULL,
  status               VARCHAR(16) NOT NULL,
  decision_note        TEXT,
  decided_by_user_id   UUID REFERENCES users(id),
  decided_at           TIMESTAMP WITH TIME ZONE,
  refund_id            UUID REFERENCES refunds(id),
  created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_refund_requests_org_status_created
  ON refund_requests (org_id, status, created_at DESC);
CREATE INDEX idx_refund_requests_event_created
  ON refund_requests (event_id, created_at DESC);
CREATE INDEX idx_refund_requests_order_status
  ON refund_requests (order_id, status);

-- Partial-unique index enforces "at most one PENDING request per order" at
-- the storage layer. Two concurrent submits race-lose with a unique violation
-- that the service maps to REFUND_REQUEST_ALREADY_OPEN.
CREATE UNIQUE INDEX uq_refund_requests_one_open_per_order
  ON refund_requests (order_id) WHERE status = 'PENDING';
```

- [ ] **Step 2: Run a boot test to confirm the migration applies cleanly**

```bash
./mvnw test -Dtest=IminApiApplicationTests -q
```

Expected: BUILD SUCCESS. The boot test runs Flyway against H2-PG; failure means the SQL is invalid.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V30__refund_requests.sql
git commit -m "feat(refund-request): V30 schema for refund_requests"
```

### Task 2.2: Migration `V31__refund_request_tokens.sql`

**Files:**
- Create: `src/main/resources/db/migration/V31__refund_request_tokens.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V31__refund_request_tokens.sql
-- Magic-link tokens for the buyer-facing refund-request form. Tokens are
-- 32 random bytes, Base64URL-encoded, stored as SHA-256 hex — we never
-- persist the raw token. Single-use: `consumed_at` is set on first POST.

CREATE TABLE refund_request_tokens (
  id                UUID PRIMARY KEY,
  token_hash        CHAR(64) NOT NULL UNIQUE,
  order_id          UUID NOT NULL REFERENCES orders(id),
  email_normalized  VARCHAR(254) NOT NULL,
  expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
  consumed_at       TIMESTAMP WITH TIME ZONE,
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_refund_request_tokens_expires_at
  ON refund_request_tokens (expires_at);
```

- [ ] **Step 2: Boot test**

```bash
./mvnw test -Dtest=IminApiApplicationTests -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V31__refund_request_tokens.sql
git commit -m "feat(refund-request): V31 schema for refund_request_tokens"
```

### Task 2.3: `RefundRequestStatus` enum

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/RefundRequestStatus.java`

- [ ] **Step 1: Write the enum**

```java
package com.imin.iminapi.refund;

public enum RefundRequestStatus {
    PENDING, APPROVED, REJECTED, WITHDRAWN;

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == WITHDRAWN;
    }
}
```

- [ ] **Step 2: Compile**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestStatus.java
git commit -m "feat(refund-request): add RefundRequestStatus enum"
```

### Task 2.4: `RefundRequestReason` enum (with Stripe mapping)

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/RefundRequestReason.java`

- [ ] **Step 1: Write the enum**

```java
package com.imin.iminapi.refund;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Buyer-facing reason on a refund request. Distinct from {@link RefundReason}
 * (which mirrors Stripe's enum). We keep the buyer's softer/richer choice on
 * the request row even after approval; only the Stripe call sees the mapped
 * value via {@link #toStripeReason()}.
 */
public enum RefundRequestReason {
    CANT_ATTEND,
    EVENT_CHANGED,
    DUPLICATE_PURCHASE,
    NOT_AS_DESCRIBED,
    OTHER;

    public RefundReason toStripeReason() {
        return switch (this) {
            case DUPLICATE_PURCHASE -> RefundReason.DUPLICATE;
            case OTHER -> RefundReason.OTHER;
            default -> RefundReason.REQUESTED_BY_CUSTOMER;
        };
    }

    @JsonValue
    public String toWire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static RefundRequestReason fromWire(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toLowerCase(Locale.ROOT);
        for (RefundRequestReason r : values()) {
            if (r.toWire().equals(normalized)) return r;
        }
        String accepted = Arrays.stream(values()).map(RefundRequestReason::toWire).collect(Collectors.joining(", "));
        throw new IllegalArgumentException(
            "Unknown refund-request reason '" + value + "'. Accepted: " + accepted);
    }
}
```

- [ ] **Step 2: Compile**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestReason.java
git commit -m "feat(refund-request): add RefundRequestReason enum with Stripe mapping"
```

### Task 2.5: `RefundRequest` JPA entity

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/RefundRequest.java`

- [ ] **Step 1: Write the entity**

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
@Table(name = "refund_requests")
@Getter
@Setter
public class RefundRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "buyer_email", nullable = false, length = 254)
    private String buyerEmail;

    @Column(name = "buyer_phone", length = 32)
    private String buyerPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundRequestReason reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RefundRequestStatus status;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "refund_id")
    private UUID refundId;

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

- [ ] **Step 2: Boot test**

```bash
./mvnw test -Dtest=IminApiApplicationTests -q
```

Expected: BUILD SUCCESS (Hibernate validates entity ↔ schema mapping at startup).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequest.java
git commit -m "feat(refund-request): RefundRequest entity"
```

### Task 2.6: `RefundRequestToken` JPA entity

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/RefundRequestToken.java`

- [ ] **Step 1: Write the entity**

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
@Table(name = "refund_request_tokens")
@Getter
@Setter
public class RefundRequestToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** SHA-256 hex of the raw token. The raw token is never persisted. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "email_normalized", nullable = false, length = 254)
    private String emailNormalized;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();

    @PrePersist
    void touch() {
        Instant now = Times.nowMicros();
        if (createdAt == null) createdAt = now;
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }
}
```

- [ ] **Step 2: Boot test**

```bash
./mvnw test -Dtest=IminApiApplicationTests -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestToken.java
git commit -m "feat(refund-request): RefundRequestToken entity"
```

### Task 2.7: `RefundRequestRepository`

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/RefundRequestRepository.java`

- [ ] **Step 1: Write the repository**

```java
package com.imin.iminapi.refund;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    Optional<RefundRequest> findByIdAndOrgId(UUID id, UUID orgId);

    @Query("""
        select rr from RefundRequest rr
        where rr.orgId = :orgId
          and (:eventId is null or rr.eventId = :eventId)
          and rr.status in :statuses
          and (rr.createdAt < :beforeAt or (rr.createdAt = :beforeAt and rr.id < :beforeId))
        order by rr.createdAt desc, rr.id desc
    """)
    List<RefundRequest> page(@Param("orgId") UUID orgId,
                             @Param("eventId") UUID eventId,
                             @Param("statuses") List<RefundRequestStatus> statuses,
                             @Param("beforeAt") Instant beforeAt,
                             @Param("beforeId") UUID beforeId,
                             Pageable pageable);

    boolean existsByOrderIdAndStatus(UUID orderId, RefundRequestStatus status);

    Optional<RefundRequest> findFirstByOrderIdAndStatus(UUID orderId, RefundRequestStatus status);
}
```

- [ ] **Step 2: Boot test**

```bash
./mvnw test -Dtest=IminApiApplicationTests -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestRepository.java
git commit -m "feat(refund-request): RefundRequestRepository"
```

### Task 2.8: `RefundRequestTokenRepository`

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/RefundRequestTokenRepository.java`

- [ ] **Step 1: Write the repository**

```java
package com.imin.iminapi.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefundRequestTokenRepository extends JpaRepository<RefundRequestToken, UUID> {

    Optional<RefundRequestToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from RefundRequestToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
```

- [ ] **Step 2: Boot test**

```bash
./mvnw test -Dtest=IminApiApplicationTests -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestTokenRepository.java
git commit -m "feat(refund-request): RefundRequestTokenRepository"
```

---

## Phase 3 — Errors, config, DTOs

### Task 3.1: New `ErrorCode` values

**Files:**
- Modify: `src/main/java/com/imin/iminapi/security/ErrorCode.java`

- [ ] **Step 1: Append the new codes**

Open `src/main/java/com/imin/iminapi/security/ErrorCode.java`. After `STRIPE_REFUND_FAILED`, append:

```java
    , REFUND_TOKEN_EXPIRED_OR_CONSUMED
    , REFUND_REQUEST_ALREADY_OPEN
    , NO_REFUNDABLE_TICKETS
    , REFUND_REQUEST_NOT_PENDING
    , REFUND_APPROVAL_NOT_CONFIRMED
```

After the edit, the enum body's last lines should read (preserving the existing trailing comma-vs-semicolon style — adjust syntax as needed; the final identifier ends with `;`):

```java
    STRIPE_REFUND_FAILED,
    REFUND_TOKEN_EXPIRED_OR_CONSUMED,
    REFUND_REQUEST_ALREADY_OPEN,
    NO_REFUNDABLE_TICKETS,
    REFUND_REQUEST_NOT_PENDING,
    REFUND_APPROVAL_NOT_CONFIRMED
}
```

- [ ] **Step 2: Compile**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/security/ErrorCode.java
git commit -m "feat(error): add refund-request error codes"
```

### Task 3.2: `EmailProperties` — add `refundRequestInbox` + `refundRequestTokenTtlMinutes`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/email/EmailProperties.java`
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Add fields + accessors**

In `EmailProperties.java`, add inside the class body (after `buyerSiteBaseUrl`):

```java
    // Imin's internal notification inbox for new refund requests. Falls back to
    // replyTo, then fromAddress, when blank — populated at read time, not on set.
    private String refundRequestInbox = "";
    // Magic-link TTL in minutes. Knob for incident response without redeploying.
    private int refundRequestTokenTtlMinutes = 60;

    public String getRefundRequestInbox() { return refundRequestInbox; }
    public void setRefundRequestInbox(String refundRequestInbox) { this.refundRequestInbox = refundRequestInbox; }
    public int getRefundRequestTokenTtlMinutes() { return refundRequestTokenTtlMinutes; }
    public void setRefundRequestTokenTtlMinutes(int v) { this.refundRequestTokenTtlMinutes = v; }

    /** Pick refund-request inbox with fallback chain: explicit → reply-to → from. */
    public String resolveRefundRequestInbox() {
        if (refundRequestInbox != null && !refundRequestInbox.isBlank()) return refundRequestInbox;
        if (replyTo != null && !replyTo.isBlank()) return replyTo;
        return fromAddress;
    }
```

- [ ] **Step 2: Bind the env var in `application.yaml`**

Open `src/main/resources/application.yaml`, find the `imin.email:` block, and add the two keys to it (use `${ENV_VAR:default}` placeholder syntax as the existing block does):

```yaml
imin:
  email:
    # ... existing keys ...
    refund-request-inbox: ${IMIN_REFUND_REQUEST_INBOX:}
    refund-request-token-ttl-minutes: ${IMIN_REFUND_REQUEST_TOKEN_TTL_MINUTES:60}
```

If the `imin.email` block doesn't exist verbatim (file structure differs), open the file with the Read tool first and place the keys under whatever existing `imin.email:` block is there. Do not invent a new block.

- [ ] **Step 3: Boot test**

```bash
./mvnw test -Dtest=IminApiApplicationTests -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/email/EmailProperties.java src/main/resources/application.yaml
git commit -m "feat(email): add refund-request inbox + token-ttl config"
```

### Task 3.3: DTOs

Each DTO is a single `record`. Group them in one task for brevity; commit at the end.

**Files:** all under `src/main/java/com/imin/iminapi/refund/dto/`

- [ ] **Step 1: Create `ProposedRefundResponse`**

```java
package com.imin.iminapi.refund.dto;

import java.util.List;
import java.util.UUID;

public record ProposedRefundResponse(
    long amountMinor,
    long appFeeRefundMinor,
    String currency,
    List<UUID> ticketIds
) {}
```

- [ ] **Step 2: Create `PublicRefundFormResponse`**

```java
package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.RefundRequestReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicRefundFormResponse(
    UUID orderId,
    EventSummary event,
    List<TicketLine> tickets,
    long estimatedRefundMinor,
    String currency,
    List<String> reasons
) {
    public record EventSummary(String name, Instant startsAt, String venueName, String currency) {}
    public record TicketLine(UUID id, String tierName, long faceMinor) {}

    public static List<String> defaultReasons() {
        return List.of(
            RefundRequestReason.CANT_ATTEND.toWire(),
            RefundRequestReason.EVENT_CHANGED.toWire(),
            RefundRequestReason.DUPLICATE_PURCHASE.toWire(),
            RefundRequestReason.NOT_AS_DESCRIBED.toWire(),
            RefundRequestReason.OTHER.toWire()
        );
    }
}
```

- [ ] **Step 3: Create `PublicRefundSubmitRequest`**

```java
package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.RefundRequestReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PublicRefundSubmitRequest(
    @NotNull RefundRequestReason reason,
    @NotNull @Size(min = 1, max = 2000) String explanation,
    @Size(max = 32) String phone
) {}
```

- [ ] **Step 4: Create `PublicRefundSubmitResponse`**

```java
package com.imin.iminapi.refund.dto;

import java.time.Instant;
import java.util.UUID;

public record PublicRefundSubmitResponse(UUID id, String status, Instant submittedAt) {}
```

- [ ] **Step 5: Create `RefundRequestSummaryResponse`**

```java
package com.imin.iminapi.refund.dto;

import java.time.Instant;
import java.util.UUID;

public record RefundRequestSummaryResponse(
    UUID id,
    UUID orderId,
    UUID eventId,
    String eventName,
    String buyerEmail,
    String status,
    String reason,
    Instant createdAt,
    Instant decidedAt,
    int ticketCount,
    long estimatedRefundMinor,
    String currency,
    UUID refundId,
    String refundStatus
) {}
```

- [ ] **Step 6: Create `RefundRequestDetailResponse`**

```java
package com.imin.iminapi.refund.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundRequestDetailResponse(
    UUID id,
    UUID orderId,
    UUID eventId,
    String eventName,
    String buyerEmail,
    String buyerPhone,
    String status,
    String reason,
    String explanation,
    String decisionNote,
    Instant createdAt,
    Instant decidedAt,
    List<TicketLine> tickets,
    ProposedRefundResponse proposedRefund,
    UUID refundId,
    String refundStatus
) {
    public record TicketLine(UUID id, String tierName, long faceMinor, String state) {}
}
```

- [ ] **Step 7: Create `RefundRequestApproveRequest`**

```java
package com.imin.iminapi.refund.dto;

public record RefundRequestApproveRequest(boolean confirm, String note) {}
```

- [ ] **Step 8: Create `RefundRequestRejectRequest`**

```java
package com.imin.iminapi.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundRequestRejectRequest(
    @NotBlank @Size(max = 1000) String note
) {}
```

- [ ] **Step 9: Create `RefundRequestDecisionResponse`**

```java
package com.imin.iminapi.refund.dto;

import java.util.UUID;

public record RefundRequestDecisionResponse(String status, UUID refundId, String refundStatus) {}
```

- [ ] **Step 10: Compile**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/dto/
git commit -m "feat(refund-request): DTOs for public + organizer surfaces"
```

### Task 3.4: Application events

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/event/RefundRequestSubmittedEvent.java`
- Create: `src/main/java/com/imin/iminapi/refund/event/RefundRequestRejectedEvent.java`

- [ ] **Step 1: Write both events**

```java
// RefundRequestSubmittedEvent.java
package com.imin.iminapi.refund.event;
import java.util.UUID;
public record RefundRequestSubmittedEvent(UUID requestId) {}
```

```java
// RefundRequestRejectedEvent.java
package com.imin.iminapi.refund.event;
import java.util.UUID;
public record RefundRequestRejectedEvent(UUID requestId) {}
```

- [ ] **Step 2: Compile**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/event/
git commit -m "feat(refund-request): application events"
```

---

## Phase 4 — `RefundRequestService`: link issuance + lookup + submit

### Task 4.1: Skeleton `RefundRequestService` (link issuance only) — TDD

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/RefundRequestService.java`
- Create: `src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java`

This task only implements `requestLink` (the public magic-link issuance endpoint). Form-load and submit come in 4.2 and 4.3.

- [ ] **Step 1: Write the failing test file**

```java
package com.imin.iminapi.refund;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.OrderRecoveryAttempt;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundRequestServiceTest {

    OrderRepository orders = mock(OrderRepository.class);
    OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);
    RefundRequestTokenRepository tokens = mock(RefundRequestTokenRepository.class);
    RefundRequestRepository requests = mock(RefundRequestRepository.class);
    EmailService email = mock(EmailService.class);
    EmailTemplateRenderer renderer = mock(EmailTemplateRenderer.class);
    EmailProperties emailProps = new EmailProperties();
    TicketProperties ticketProps = new TicketProperties();
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    RefundRequestService service;

    @BeforeEach
    void setUp() {
        emailProps.setBuyerSiteBaseUrl("https://app.test");
        emailProps.setFromAddress("noreply@test");
        ticketProps.setRecoveryMaxPerHour(5);
        when(renderer.render(anyString(), any()))
            .thenReturn(new EmailTemplateRenderer.Rendered("<html/>", "txt"));
        service = new RefundRequestService(orders, attempts, tokens, requests,
            email, renderer, emailProps, ticketProps, publisher);
    }

    @Test
    void requestLink_no_order_returns_silently_and_records_attempt() {
        when(orders.findRecentForRecovery(eq("nobody@x"), any(), any()))
            .thenReturn(List.of());

        service.requestLink("nobody@x", "1.2.3.4");

        verify(attempts).save(any(OrderRecoveryAttempt.class));
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
        verify(tokens, never()).save(any());
    }

    @Test
    void requestLink_paid_order_sends_email_and_persists_token() {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(2000);
        o.setStripePaymentIntentId("pi_1");
        when(orders.findRecentForRecovery(eq("buyer@example.com"), any(), any()))
            .thenReturn(List.of(o));

        service.requestLink("buyer@example.com", "1.2.3.4");

        verify(tokens).save(any(RefundRequestToken.class));
        verify(email).send(eq("buyer@example.com"), anyString(), anyString(), anyString());
    }

    @Test
    void requestLink_skips_free_orders() {
        Order free = new Order();
        free.setId(UUID.randomUUID());
        free.setEmail("buyer@example.com");
        free.setTotalMinor(0);
        free.setStripePaymentIntentId(null);
        when(orders.findRecentForRecovery(eq("buyer@example.com"), any(), any()))
            .thenReturn(List.of(free));

        service.requestLink("buyer@example.com", "1.2.3.4");

        verify(tokens, never()).save(any());
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void requestLink_rate_limited_by_email_is_silent() {
        when(attempts.countByEmailAndAttemptedAtAfter(eq("buyer@example.com"), any()))
            .thenReturn(100L);

        service.requestLink("buyer@example.com", "1.2.3.4");

        verify(attempts).save(any(OrderRecoveryAttempt.class));
        verify(tokens, never()).save(any());
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails (class missing)**

```bash
./mvnw test -Dtest=RefundRequestServiceTest -q
```

Expected: COMPILATION FAILURE — `RefundRequestService` not found.

- [ ] **Step 3: Implement `RefundRequestService` (link-issuance scope only)**

```java
package com.imin.iminapi.refund;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.OrderRecoveryAttempt;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketProperties;
import com.imin.iminapi.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestration for buyer-initiated refund requests.
 *
 * <p>Public-surface methods (link, lookup, submit) are anti-enumeration-safe:
 * they never reveal whether an email matches an order. Organizer-surface
 * methods (approve, reject, list) require an authenticated principal whose
 * orgId is verified against the request's orgId.
 */
@Service
public class RefundRequestService {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final OrderRepository orders;
    private final OrderRecoveryAttemptRepository attempts;
    private final RefundRequestTokenRepository tokens;
    private final RefundRequestRepository requests;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final TicketProperties ticketProps;
    private final ApplicationEventPublisher publisher;

    public RefundRequestService(OrderRepository orders,
                                OrderRecoveryAttemptRepository attempts,
                                RefundRequestTokenRepository tokens,
                                RefundRequestRepository requests,
                                EmailService email,
                                EmailTemplateRenderer renderer,
                                EmailProperties emailProps,
                                TicketProperties ticketProps,
                                ApplicationEventPublisher publisher) {
        this.orders = orders;
        this.attempts = attempts;
        this.tokens = tokens;
        this.requests = requests;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.ticketProps = ticketProps;
        this.publisher = publisher;
    }

    @Transactional
    public void requestLink(String rawEmail, String clientIp) {
        if (rawEmail == null) return;
        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);

        // Log attempt up-front so the rate-limit counters tick on invalid input too.
        OrderRecoveryAttempt a = new OrderRecoveryAttempt();
        a.setEmail(normalized);
        a.setIpHash(hashIp(clientIp));
        attempts.save(a);

        if (normalized.isEmpty() || !normalized.contains("@")) return;

        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        long byEmail = attempts.countByEmailAndAttemptedAtAfter(normalized, cutoff);
        long byIp = attempts.countByIpHashAndAttemptedAtAfter(hashIp(clientIp), cutoff);
        int cap = ticketProps.getRecoveryMaxPerHour();
        if (byEmail > cap || byIp > cap) {
            log.info("[refund-request] rate-limited email={} byEmail={} byIp={}",
                normalized, byEmail, byIp);
            return;
        }

        Instant recoveryCutoff = Instant.now()
            .minus(Duration.ofDays(ticketProps.getRecoveryWindowDays()));
        List<Order> found = orders.findRecentForRecovery(normalized, null, recoveryCutoff);
        Order chosen = found.stream()
            .filter(o -> o.getTotalMinor() > 0 && o.getStripePaymentIntentId() != null
                && !o.getStripePaymentIntentId().isBlank())
            .findFirst()
            .orElse(null);
        if (chosen == null) {
            log.info("[refund-request] no refundable order for {}", normalized);
            return;
        }

        String raw = generateRawToken();
        String hash = sha256Hex(raw);

        RefundRequestToken token = new RefundRequestToken();
        token.setTokenHash(hash);
        token.setOrderId(chosen.getId());
        token.setEmailNormalized(normalized);
        token.setExpiresAt(Times.nowMicros()
            .plus(Duration.ofMinutes(emailProps.getRefundRequestTokenTtlMinutes())));
        tokens.save(token);

        String url = baseUrl() + "/refund/" + raw;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("link", url);
        values.put("ttlMinutes", String.valueOf(emailProps.getRefundRequestTokenTtlMinutes()));
        EmailTemplateRenderer.Rendered r = renderer.render("refund-request-link", values);

        try {
            email.send(normalized, "Request a refund · imin", r.html(), r.text());
            log.info("[refund-request] token-issued orderId={} emailHash={}",
                chosen.getId(), sha256Hex(normalized));
        } catch (Exception e) {
            log.warn("[refund-request] link email failed for {}: {}", normalized, e.getMessage());
        }
    }

    // ---------- helpers ----------

    private String baseUrl() {
        String base = emailProps.getBuyerSiteBaseUrl();
        if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base == null ? "" : base;
    }

    private static String generateRawToken() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    private static String hashIp(String ip) {
        return sha256Hex(ip);
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=RefundRequestServiceTest -q
```

Expected: BUILD SUCCESS, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestService.java src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java
git commit -m "feat(refund-request): RefundRequestService.requestLink with anti-enumeration"
```

### Task 4.2: `lookupByToken` (GET /by-token/{t}) — TDD

**Files:**
- Modify: `src/main/java/com/imin/iminapi/refund/RefundRequestService.java`
- Modify: `src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java`
- Read: existing `TicketTierRepository` to understand the per-tier name lookup (use `Read` tool to find `findById` signature)

- [ ] **Step 1: Add failing tests at the bottom of `RefundRequestServiceTest`**

Append a new `@Nested` block before the file's closing brace:

```java
    @org.junit.jupiter.api.Nested
    class LookupByToken {

        java.util.UUID orderId;
        Order o;
        RefundRequestToken token;

        @BeforeEach
        void seed() {
            orderId = java.util.UUID.randomUUID();
            o = new Order();
            o.setId(orderId);
            o.setEmail("buyer@example.com");
            o.setEventId(java.util.UUID.randomUUID());
            o.setTotalMinor(8000);
            o.setCurrency("eur");
            o.setStripePaymentIntentId("pi_x");

            token = new RefundRequestToken();
            token.setOrderId(orderId);
            token.setEmailNormalized("buyer@example.com");
            token.setExpiresAt(Instant.now().plusSeconds(600));
        }

        @Test
        void returns_410_when_token_unknown() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

            com.imin.iminapi.security.ApiException ex = (com.imin.iminapi.security.ApiException) assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.lookupByToken("any"));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED);
        }

        @Test
        void returns_410_when_expired() {
            token.setExpiresAt(Instant.now().minusSeconds(60));
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            com.imin.iminapi.security.ApiException ex = (com.imin.iminapi.security.ApiException) assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.lookupByToken("raw"));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED);
        }

        @Test
        void returns_410_when_consumed() {
            token.setConsumedAt(Instant.now().minusSeconds(60));
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            com.imin.iminapi.security.ApiException ex = (com.imin.iminapi.security.ApiException) assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.lookupByToken("raw"));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED);
        }

        @Test
        void returns_409_when_no_refundable_tickets() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            // Stub TicketRepository to return zero refundable tickets — pulled in below.
            // Add the relevant mock declaration: TicketRepository tickets = mock(TicketRepository.class);
            // in the parent class fields if not already present.
        }
    }
```

**Important:** the test above references a `TicketRepository tickets` mock and a `RefundRepository refunds` mock that the service needs but the original Phase 4.1 setup didn't include. Before continuing, add these to the parent-class field declarations + constructor wiring + service constructor signature in Step 2 below.

- [ ] **Step 2: Extend `RefundRequestService` constructor and fields**

Add fields and constructor params (matching declaration in 4.1):

```java
    private final com.imin.iminapi.repository.TicketRepository tickets;
    private final com.imin.iminapi.refund.RefundTicketRepository refundTickets;
    private final com.imin.iminapi.repository.TicketTierRepository tiers;
    private final RefundService refundService;  // for amount calc reuse (see step 3)
```

Add corresponding constructor parameters and `this.x = x` assignments. Update the `@BeforeEach` in the test to construct with the new mocks (and add `mock(...)` field declarations: `tickets`, `refundTickets`, `tiers`, `refundService`).

- [ ] **Step 3: Expose amount calc on `RefundService`**

Open `RefundService.java`. Make the private `computeRefundAmountMinor` method package-private and add an `@param` doc comment so it's clear callers must pass the full `Order` and the candidate ticket set. The signature stays the same:

```java
    /** Visible for {@link RefundRequestService} so request previews and approvals use the same math. */
    long computeRefundAmountMinor(Order order, List<Ticket> selected) { ... }
```

Also add a package-private helper that returns the proportional app-fee refund:

```java
    long computeAppFeeRefundMinor(Order order, long refundAmountMinor) {
        if (order.getTotalMinor() <= 0 || order.getApplicationFeeMinor() <= 0) return 0;
        return Math.round(
            (double) order.getApplicationFeeMinor() * refundAmountMinor / order.getTotalMinor());
    }
```

Refactor `createRefund` to call `computeAppFeeRefundMinor` instead of inlining the formula. Run `./mvnw test -Dtest=RefundServiceTest -q` and verify no regressions.

Commit this refactor as its own step before continuing:

```bash
git add src/main/java/com/imin/iminapi/refund/RefundService.java
git commit -m "refactor(refund): expose amount calc helpers for request flow"
```

- [ ] **Step 4: Implement `lookupByToken`**

Add to `RefundRequestService`:

```java
    @Transactional(readOnly = true)
    public com.imin.iminapi.refund.dto.PublicRefundFormResponse lookupByToken(String rawToken) {
        RefundRequestToken token = tokens.findByTokenHash(sha256Hex(rawToken))
            .filter(t -> t.getConsumedAt() == null)
            .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.GONE,
                com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED,
                "Refund link is no longer valid"));

        Order order = orders.findById(token.getOrderId())
            .orElseThrow(() -> new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.GONE,
                com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED,
                "Refund link is no longer valid"));

        List<com.imin.iminapi.model.Ticket> refundable = refundableTicketsFor(order);
        if (refundable.isEmpty()) {
            throw new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                com.imin.iminapi.security.ErrorCode.NO_REFUNDABLE_TICKETS,
                "All tickets on this order have already been refunded or used");
        }

        long estimated = refundService.computeRefundAmountMinor(order, refundable);

        java.util.Map<java.util.UUID, String> tierNames = new java.util.HashMap<>();
        for (com.imin.iminapi.model.Ticket t : refundable) {
            tierNames.computeIfAbsent(t.getTierId(), id -> tiers.findById(id)
                .map(com.imin.iminapi.model.TicketTier::getName).orElse(""));
        }

        return new com.imin.iminapi.refund.dto.PublicRefundFormResponse(
            order.getId(),
            new com.imin.iminapi.refund.dto.PublicRefundFormResponse.EventSummary(
                /* Event name lookup intentionally omitted here; FE can hit existing
                   /api/v1/public/events/{eventId} for richer detail if needed. */
                null, null, null, order.getCurrency()),
            refundable.stream()
                .map(t -> new com.imin.iminapi.refund.dto.PublicRefundFormResponse.TicketLine(
                    t.getId(),
                    tierNames.getOrDefault(t.getTierId(), ""),
                    t.getPriceMinor()))
                .toList(),
            estimated,
            order.getCurrency(),
            com.imin.iminapi.refund.dto.PublicRefundFormResponse.defaultReasons()
        );
    }

    private List<com.imin.iminapi.model.Ticket> refundableTicketsFor(Order order) {
        java.util.List<com.imin.iminapi.model.Ticket> all = tickets.findByOrderId(order.getId());
        java.util.List<java.util.UUID> ids = all.stream()
            .map(com.imin.iminapi.model.Ticket::getId).toList();
        java.util.Set<java.util.UUID> alreadyRefunded = ids.isEmpty()
            ? java.util.Set.of() : refundTickets.findRefundedTicketIds(ids);
        return all.stream()
            .filter(t -> !alreadyRefunded.contains(t.getId()))
            .filter(t -> !com.imin.iminapi.model.Ticket.STATE_REDEEMED.equals(t.getState()))
            .toList();
    }
```

**Note on event-name lookup:** the spec includes `event.name` and `startsAt` etc. in the response, but plumbing event lookup through this service would balloon the change. For now return `null`s for event details and document the deferral via an inline comment as shown. The FE will use the existing `/api/v1/public/events/{eventId}/...` endpoint if it needs the event metadata, since `orderId` is already part of the response (and the FE can call `/public/orders/{token}` if it has an order token, though it doesn't here). **Open follow-up:** after backend lands, decide whether to add an `EventQueryService.findById(orderId)` shortcut into this response or leave the FE to fetch. Track in §17 of the spec.

- [ ] **Step 5: Flesh out the placeholder `returns_409_when_no_refundable_tickets` test**

In the test file, replace the stub test body added in Step 1 with:

```java
        @Test
        void returns_409_when_no_refundable_tickets() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of());

            com.imin.iminapi.security.ApiException ex = (com.imin.iminapi.security.ApiException) assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.lookupByToken("raw"));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.NO_REFUNDABLE_TICKETS);
        }
```

- [ ] **Step 6: Add a happy-path test**

```java
        @Test
        void returns_form_data_for_valid_token() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(java.util.UUID.randomUUID());
            t.setOrderId(orderId);
            t.setTierId(java.util.UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(refundService.computeRefundAmountMinor(eq(o), org.mockito.ArgumentMatchers.anyList())).thenReturn(2000L);

            var resp = service.lookupByToken("raw");

            assertThat(resp.estimatedRefundMinor()).isEqualTo(2000L);
            assertThat(resp.tickets()).hasSize(1);
            assertThat(resp.reasons()).contains("cant_attend", "other");
        }
```

- [ ] **Step 7: Run the tests**

```bash
./mvnw test -Dtest=RefundRequestServiceTest -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestService.java src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java
git commit -m "feat(refund-request): lookupByToken returns form context"
```

### Task 4.3: `submitByToken` (POST /by-token/{t}) — TDD

**Files:**
- Modify: `src/main/java/com/imin/iminapi/refund/RefundRequestService.java`
- Modify: `src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java`

- [ ] **Step 1: Add failing tests**

Append another nested class:

```java
    @org.junit.jupiter.api.Nested
    class SubmitByToken {

        java.util.UUID orderId;
        Order o;
        RefundRequestToken token;

        @BeforeEach
        void seed() {
            orderId = java.util.UUID.randomUUID();
            o = new Order();
            o.setId(orderId);
            o.setOrgId(java.util.UUID.randomUUID());
            o.setEventId(java.util.UUID.randomUUID());
            o.setEmail("buyer@example.com");
            o.setTotalMinor(8000);
            o.setCurrency("eur");

            token = new RefundRequestToken();
            token.setOrderId(orderId);
            token.setEmailNormalized("buyer@example.com");
            token.setExpiresAt(Instant.now().plusSeconds(600));
        }

        com.imin.iminapi.refund.dto.PublicRefundSubmitRequest req() {
            return new com.imin.iminapi.refund.dto.PublicRefundSubmitRequest(
                RefundRequestReason.CANT_ATTEND, "Can't make it", null);
        }

        @Test
        void submits_writes_request_burns_token_and_publishes_event() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(java.util.UUID.randomUUID());
            t.setOrderId(orderId);
            t.setTierId(java.util.UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(requests.existsByOrderIdAndStatus(orderId, RefundRequestStatus.PENDING)).thenReturn(false);
            when(requests.save(any())).thenAnswer(inv -> {
                RefundRequest rr = inv.getArgument(0);
                rr.setId(java.util.UUID.randomUUID());
                return rr;
            });

            var resp = service.submitByToken("raw", req());

            assertThat(resp.status()).isEqualTo("pending");
            verify(requests).save(any(RefundRequest.class));
            verify(tokens).save(org.mockito.ArgumentMatchers.argThat(t2 -> t2.getConsumedAt() != null));
            verify(publisher).publishEvent(any(com.imin.iminapi.refund.event.RefundRequestSubmittedEvent.class));
        }

        @Test
        void submits_returns_409_when_a_pending_request_already_exists() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(java.util.UUID.randomUUID());
            t.setOrderId(orderId);
            t.setTierId(java.util.UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(requests.existsByOrderIdAndStatus(orderId, RefundRequestStatus.PENDING)).thenReturn(true);

            com.imin.iminapi.security.ApiException ex = (com.imin.iminapi.security.ApiException) assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.submitByToken("raw", req()));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_REQUEST_ALREADY_OPEN);
        }
    }
```

- [ ] **Step 2: Implement `submitByToken` in the service**

```java
    @Transactional
    public com.imin.iminapi.refund.dto.PublicRefundSubmitResponse submitByToken(
            String rawToken,
            com.imin.iminapi.refund.dto.PublicRefundSubmitRequest body) {

        RefundRequestToken token = tokens.findByTokenHash(sha256Hex(rawToken))
            .filter(t -> t.getConsumedAt() == null)
            .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.GONE,
                com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED,
                "Refund link is no longer valid"));

        Order order = orders.findById(token.getOrderId())
            .orElseThrow(() -> new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.GONE,
                com.imin.iminapi.security.ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED,
                "Refund link is no longer valid"));

        List<com.imin.iminapi.model.Ticket> refundable = refundableTicketsFor(order);
        if (refundable.isEmpty()) {
            // Burn the token so a retry doesn't hit lookup-then-409 again.
            token.setConsumedAt(Times.nowMicros());
            tokens.save(token);
            throw new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                com.imin.iminapi.security.ErrorCode.NO_REFUNDABLE_TICKETS,
                "All tickets on this order have already been refunded or used");
        }

        if (requests.existsByOrderIdAndStatus(order.getId(), RefundRequestStatus.PENDING)) {
            throw new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                com.imin.iminapi.security.ErrorCode.REFUND_REQUEST_ALREADY_OPEN,
                "A refund request is already open for this order");
        }

        RefundRequest rr = new RefundRequest();
        rr.setOrderId(order.getId());
        rr.setOrgId(order.getOrgId());
        rr.setEventId(order.getEventId());
        rr.setBuyerEmail(order.getEmail() == null ? "" : order.getEmail().toLowerCase(Locale.ROOT));
        rr.setBuyerPhone(body.phone());
        rr.setReason(body.reason());
        rr.setExplanation(body.explanation());
        rr.setStatus(RefundRequestStatus.PENDING);

        try {
            rr = requests.save(rr);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Partial unique index raced.
            throw new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                com.imin.iminapi.security.ErrorCode.REFUND_REQUEST_ALREADY_OPEN,
                "A refund request is already open for this order");
        }

        token.setConsumedAt(Times.nowMicros());
        tokens.save(token);

        publisher.publishEvent(new com.imin.iminapi.refund.event.RefundRequestSubmittedEvent(rr.getId()));
        log.info("[refund-request] issued requestId={} orderId={}", rr.getId(), order.getId());
        return new com.imin.iminapi.refund.dto.PublicRefundSubmitResponse(
            rr.getId(), rr.getStatus().name().toLowerCase(Locale.ROOT), rr.getCreatedAt());
    }
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=RefundRequestServiceTest -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestService.java src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java
git commit -m "feat(refund-request): submitByToken creates request and burns token"
```

---

## Phase 5 — Organizer-side service methods (list, detail, approve, reject)

### Task 5.1: `getRequest` and `listRequests` — TDD

**Files:**
- Modify: `src/main/java/com/imin/iminapi/refund/RefundRequestService.java`
- Modify: `src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java`

- [ ] **Step 1: Add failing tests for `getRequest`**

```java
    @org.junit.jupiter.api.Nested
    class GetRequestForOrganizer {

        @Test
        void returns_404_when_request_belongs_to_other_org() {
            java.util.UUID rid = java.util.UUID.randomUUID();
            when(requests.findByIdAndOrgId(rid, java.util.UUID.randomUUID()))
                .thenReturn(Optional.empty());

            com.imin.iminapi.security.ApiException ex = (com.imin.iminapi.security.ApiException) assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.getRequest(rid, java.util.UUID.randomUUID()));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.NOT_FOUND);
        }
    }
```

- [ ] **Step 2: Implement `getRequest`**

```java
    @Transactional(readOnly = true)
    public com.imin.iminapi.refund.dto.RefundRequestDetailResponse getRequest(
            java.util.UUID id, java.util.UUID orgId) {
        RefundRequest rr = requests.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> com.imin.iminapi.security.ApiException.notFound("RefundRequest"));

        Order order = orders.findById(rr.getOrderId())
            .orElseThrow(() -> com.imin.iminapi.security.ApiException.notFound("Order"));

        List<com.imin.iminapi.model.Ticket> all = tickets.findByOrderId(order.getId());
        java.util.Set<java.util.UUID> alreadyRefunded = refundTickets.findRefundedTicketIds(
            all.stream().map(com.imin.iminapi.model.Ticket::getId).toList());
        List<com.imin.iminapi.model.Ticket> refundable = all.stream()
            .filter(t -> !alreadyRefunded.contains(t.getId()))
            .filter(t -> !com.imin.iminapi.model.Ticket.STATE_REDEEMED.equals(t.getState()))
            .toList();

        java.util.Map<java.util.UUID, String> tierNames = new java.util.HashMap<>();
        for (com.imin.iminapi.model.Ticket t : all) {
            tierNames.computeIfAbsent(t.getTierId(), id2 -> tiers.findById(id2)
                .map(com.imin.iminapi.model.TicketTier::getName).orElse(""));
        }

        com.imin.iminapi.refund.dto.ProposedRefundResponse proposed = null;
        if (rr.getStatus() == RefundRequestStatus.PENDING && !refundable.isEmpty()) {
            long amount = refundService.computeRefundAmountMinor(order, refundable);
            long appFee = refundService.computeAppFeeRefundMinor(order, amount);
            proposed = new com.imin.iminapi.refund.dto.ProposedRefundResponse(
                amount, appFee, order.getCurrency(),
                refundable.stream().map(com.imin.iminapi.model.Ticket::getId).toList());
        }

        // Linked refund status (only when a refund was attached at approval).
        String refundStatus = null;
        if (rr.getRefundId() != null) {
            refundStatus = (String) null; // resolved at controller layer if needed; null is acceptable
        }

        return new com.imin.iminapi.refund.dto.RefundRequestDetailResponse(
            rr.getId(),
            order.getId(),
            order.getEventId(),
            null, // eventName left null for MVP; same rationale as Phase 4.2 Step 4 note
            rr.getBuyerEmail(),
            rr.getBuyerPhone(),
            rr.getStatus().name().toLowerCase(Locale.ROOT),
            rr.getReason().toWire(),
            rr.getExplanation(),
            rr.getDecisionNote(),
            rr.getCreatedAt(),
            rr.getDecidedAt(),
            all.stream()
                .map(t -> new com.imin.iminapi.refund.dto.RefundRequestDetailResponse.TicketLine(
                    t.getId(), tierNames.getOrDefault(t.getTierId(), ""),
                    t.getPriceMinor(), t.getState()))
                .toList(),
            proposed,
            rr.getRefundId(),
            refundStatus
        );
    }
```

- [ ] **Step 3: Add failing tests for `listRequests`**

```java
    @org.junit.jupiter.api.Nested
    class ListRequestsForOrganizer {

        @Test
        void filters_by_status_and_event() {
            java.util.UUID orgId = java.util.UUID.randomUUID();
            java.util.UUID eventId = java.util.UUID.randomUUID();
            when(requests.page(eq(orgId), eq(eventId), eq(List.of(RefundRequestStatus.PENDING)),
                any(), any(), any())).thenReturn(List.of());

            var page = service.listRequests(orgId, eventId,
                List.of(RefundRequestStatus.PENDING), null, 25);
            assertThat(page).isEmpty();
        }
    }
```

- [ ] **Step 4: Implement `listRequests`**

```java
    @Transactional(readOnly = true)
    public List<com.imin.iminapi.refund.dto.RefundRequestSummaryResponse> listRequests(
            java.util.UUID orgId,
            java.util.UUID eventId,
            List<RefundRequestStatus> statuses,
            String cursorBase64,
            int limit) {

        Instant beforeAt = Instant.MAX;
        java.util.UUID beforeId = new java.util.UUID(Long.MAX_VALUE, Long.MAX_VALUE);
        if (cursorBase64 != null && !cursorBase64.isBlank()) {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursorBase64),
                java.nio.charset.StandardCharsets.UTF_8);
            int sep = decoded.indexOf('|');
            if (sep > 0) {
                beforeAt = Instant.parse(decoded.substring(0, sep));
                beforeId = java.util.UUID.fromString(decoded.substring(sep + 1));
            }
        }

        org.springframework.data.domain.PageRequest pageReq =
            org.springframework.data.domain.PageRequest.of(0, Math.min(100, Math.max(1, limit)));
        List<RefundRequest> rows = requests.page(orgId, eventId,
            (statuses == null || statuses.isEmpty()) ? List.of(RefundRequestStatus.values()) : statuses,
            beforeAt, beforeId, pageReq);

        return rows.stream().map(rr -> {
            // ticketCount and estimatedRefundMinor are best-effort live computations.
            // For the list view, ticketCount = order tickets (cached at order detail), estimated = computed
            // off the same algorithm. We accept the per-row cost for now and add caching if it bites.
            List<com.imin.iminapi.model.Ticket> all = tickets.findByOrderId(rr.getOrderId());
            java.util.Set<java.util.UUID> alreadyRefunded = refundTickets.findRefundedTicketIds(
                all.stream().map(com.imin.iminapi.model.Ticket::getId).toList());
            List<com.imin.iminapi.model.Ticket> refundable = all.stream()
                .filter(t -> !alreadyRefunded.contains(t.getId()))
                .filter(t -> !com.imin.iminapi.model.Ticket.STATE_REDEEMED.equals(t.getState()))
                .toList();
            long estimated = 0;
            String currency = null;
            Order order = orders.findById(rr.getOrderId()).orElse(null);
            if (order != null && !refundable.isEmpty()) {
                estimated = refundService.computeRefundAmountMinor(order, refundable);
                currency = order.getCurrency();
            }
            return new com.imin.iminapi.refund.dto.RefundRequestSummaryResponse(
                rr.getId(), rr.getOrderId(), rr.getEventId(), null,
                rr.getBuyerEmail(),
                rr.getStatus().name().toLowerCase(Locale.ROOT),
                rr.getReason().toWire(),
                rr.getCreatedAt(),
                rr.getDecidedAt(),
                refundable.size(),
                estimated,
                currency,
                rr.getRefundId(),
                null);
        }).toList();
    }
```

- [ ] **Step 5: Run tests**

```bash
./mvnw test -Dtest=RefundRequestServiceTest -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestService.java src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java
git commit -m "feat(refund-request): organizer list and detail queries"
```

### Task 5.2: `approveRequest` — TDD

**Files:**
- Modify: `src/main/java/com/imin/iminapi/refund/RefundRequestService.java`
- Modify: `src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java`

- [ ] **Step 1: Add failing tests**

```java
    @org.junit.jupiter.api.Nested
    class ApproveRequest {

        java.util.UUID requestId;
        java.util.UUID orgId;
        RefundRequest rr;
        Order order;
        com.imin.iminapi.security.AuthPrincipal principal;

        @BeforeEach
        void seed() {
            requestId = java.util.UUID.randomUUID();
            orgId = java.util.UUID.randomUUID();
            principal = new com.imin.iminapi.security.AuthPrincipal(
                java.util.UUID.randomUUID(), orgId,
                com.imin.iminapi.model.UserRole.OWNER, java.util.UUID.randomUUID());

            rr = new RefundRequest();
            rr.setId(requestId);
            rr.setOrgId(orgId);
            rr.setOrderId(java.util.UUID.randomUUID());
            rr.setEventId(java.util.UUID.randomUUID());
            rr.setBuyerEmail("buyer@example.com");
            rr.setReason(RefundRequestReason.CANT_ATTEND);
            rr.setExplanation("...");
            rr.setStatus(RefundRequestStatus.PENDING);

            order = new Order();
            order.setId(rr.getOrderId());
            order.setOrgId(orgId);
            order.setEventId(rr.getEventId());
            order.setStripePaymentIntentId("pi_x");
            order.setTotalMinor(8000);
            order.setCurrency("eur");
        }

        @Test
        void requires_confirm_true_otherwise_400_with_proposed_refund() {
            when(requests.findByIdAndOrgId(requestId, orgId)).thenReturn(Optional.of(rr));
            when(orders.findById(rr.getOrderId())).thenReturn(Optional.of(order));
            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(java.util.UUID.randomUUID());
            t.setOrderId(order.getId());
            t.setTierId(java.util.UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(order.getId())).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(refundService.computeRefundAmountMinor(eq(order), any())).thenReturn(8000L);
            when(refundService.computeAppFeeRefundMinor(order, 8000L)).thenReturn(400L);

            com.imin.iminapi.security.ApiException ex = (com.imin.iminapi.security.ApiException) assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.approveRequest(requestId, principal,
                    new com.imin.iminapi.refund.dto.RefundRequestApproveRequest(false, "")));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_APPROVAL_NOT_CONFIRMED);
            assertThat(ex.getFields()).containsKey("proposedRefund");
        }

        @Test
        void confirmed_call_invokes_RefundService_and_marks_request_approved() {
            when(requests.findByIdAndOrgId(requestId, orgId)).thenReturn(Optional.of(rr));
            when(orders.findById(rr.getOrderId())).thenReturn(Optional.of(order));
            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(java.util.UUID.randomUUID());
            t.setOrderId(order.getId());
            t.setTierId(java.util.UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(order.getId())).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());

            Refund stubRefund = new Refund();
            stubRefund.setId(java.util.UUID.randomUUID());
            stubRefund.setStatus(RefundStatus.PENDING);
            when(refundService.createRefund(eq(order.getId()), any(),
                org.mockito.ArgumentMatchers.startsWith("refund-request-"),
                any(), any())).thenReturn(stubRefund);
            when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var resp = service.approveRequest(requestId, principal,
                new com.imin.iminapi.refund.dto.RefundRequestApproveRequest(true, "ok"));

            assertThat(resp.status()).isEqualTo("approved");
            assertThat(resp.refundId()).isEqualTo(stubRefund.getId());
            assertThat(rr.getStatus()).isEqualTo(RefundRequestStatus.APPROVED);
            assertThat(rr.getDecisionNote()).isEqualTo("ok");
        }

        @Test
        void rejects_if_request_already_decided() {
            rr.setStatus(RefundRequestStatus.APPROVED);
            when(requests.findByIdAndOrgId(requestId, orgId)).thenReturn(Optional.of(rr));

            com.imin.iminapi.security.ApiException ex = (com.imin.iminapi.security.ApiException) assertThrows(
                com.imin.iminapi.security.ApiException.class,
                () -> service.approveRequest(requestId, principal,
                    new com.imin.iminapi.refund.dto.RefundRequestApproveRequest(true, "")));
            assertThat(ex.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.REFUND_REQUEST_NOT_PENDING);
        }
    }
```

- [ ] **Step 2: Implement `approveRequest`**

```java
    @Transactional
    public com.imin.iminapi.refund.dto.RefundRequestDecisionResponse approveRequest(
            java.util.UUID id,
            com.imin.iminapi.security.AuthPrincipal principal,
            com.imin.iminapi.refund.dto.RefundRequestApproveRequest body) {

        RefundRequest rr = requests.findByIdAndOrgId(id, principal.orgId())
            .orElseThrow(() -> com.imin.iminapi.security.ApiException.notFound("RefundRequest"));

        if (rr.getStatus() != RefundRequestStatus.PENDING) {
            throw new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                com.imin.iminapi.security.ErrorCode.REFUND_REQUEST_NOT_PENDING,
                "Refund request is not pending");
        }

        Order order = orders.findById(rr.getOrderId())
            .orElseThrow(() -> com.imin.iminapi.security.ApiException.notFound("Order"));

        List<com.imin.iminapi.model.Ticket> all = tickets.findByOrderId(order.getId());
        java.util.Set<java.util.UUID> alreadyRefunded = refundTickets.findRefundedTicketIds(
            all.stream().map(com.imin.iminapi.model.Ticket::getId).toList());
        List<com.imin.iminapi.model.Ticket> refundable = all.stream()
            .filter(t -> !alreadyRefunded.contains(t.getId()))
            .filter(t -> !com.imin.iminapi.model.Ticket.STATE_REDEEMED.equals(t.getState()))
            .toList();
        if (refundable.isEmpty()) {
            throw new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                com.imin.iminapi.security.ErrorCode.NO_REFUNDABLE_TICKETS,
                "No refundable tickets remain on this order");
        }

        long amount = refundService.computeRefundAmountMinor(order, refundable);
        long appFee = refundService.computeAppFeeRefundMinor(order, amount);

        if (!body.confirm()) {
            com.imin.iminapi.refund.dto.ProposedRefundResponse proposed =
                new com.imin.iminapi.refund.dto.ProposedRefundResponse(
                    amount, appFee, order.getCurrency(),
                    refundable.stream().map(com.imin.iminapi.model.Ticket::getId).toList());
            throw new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                com.imin.iminapi.security.ErrorCode.REFUND_APPROVAL_NOT_CONFIRMED,
                "Set confirm:true to issue the refund.",
                java.util.Map.of("proposedRefund", proposed.toString()));
        }

        Refund stripeRefund = refundService.createRefund(
            order.getId(), principal,
            "refund-request-" + rr.getId(),
            refundable.stream().map(com.imin.iminapi.model.Ticket::getId).toList(),
            rr.getReason().toStripeReason());

        rr.setStatus(RefundRequestStatus.APPROVED);
        rr.setDecidedAt(Times.nowMicros());
        rr.setDecidedByUserId(principal.userId());
        rr.setDecisionNote(body.note());
        rr.setRefundId(stripeRefund.getId());
        requests.save(rr);

        log.info("[refund-request] approved id={} refundId={} by={}",
            rr.getId(), stripeRefund.getId(), principal.userId());
        return new com.imin.iminapi.refund.dto.RefundRequestDecisionResponse(
            "approved", stripeRefund.getId(),
            stripeRefund.getStatus().name().toLowerCase(Locale.ROOT));
    }
```

**Note on `ApiException` fields:** the existing constructor accepts `Map<String, String>` (string values only). The proposed-refund object is serialised via `.toString()` in the error response, which is acceptable for the defensive backstop case (typical clients will read the live `proposedRefund` from `GET .../{id}` and never see this 400). If you'd rather return a structured object, extend `ApiException` to accept `Map<String, Object>` in a separate PR — out of scope here.

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=RefundRequestServiceTest -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestService.java src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java
git commit -m "feat(refund-request): approve invokes RefundService with confirm gate"
```

### Task 5.3: `rejectRequest` — TDD

**Files:**
- Modify: `src/main/java/com/imin/iminapi/refund/RefundRequestService.java`
- Modify: `src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java`

- [ ] **Step 1: Add failing tests**

```java
    @org.junit.jupiter.api.Nested
    class RejectRequest {

        @Test
        void rejects_and_publishes_event() {
            java.util.UUID rid = java.util.UUID.randomUUID();
            java.util.UUID orgId = java.util.UUID.randomUUID();
            com.imin.iminapi.security.AuthPrincipal principal =
                new com.imin.iminapi.security.AuthPrincipal(java.util.UUID.randomUUID(),
                    orgId, com.imin.iminapi.model.UserRole.OWNER, java.util.UUID.randomUUID());
            RefundRequest rr = new RefundRequest();
            rr.setId(rid);
            rr.setOrgId(orgId);
            rr.setOrderId(java.util.UUID.randomUUID());
            rr.setEventId(java.util.UUID.randomUUID());
            rr.setBuyerEmail("buyer@example.com");
            rr.setReason(RefundRequestReason.OTHER);
            rr.setExplanation("...");
            rr.setStatus(RefundRequestStatus.PENDING);
            when(requests.findByIdAndOrgId(rid, orgId)).thenReturn(Optional.of(rr));
            when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var resp = service.rejectRequest(rid, principal,
                new com.imin.iminapi.refund.dto.RefundRequestRejectRequest("Past 48h window"));

            assertThat(resp.status()).isEqualTo("rejected");
            assertThat(rr.getStatus()).isEqualTo(RefundRequestStatus.REJECTED);
            assertThat(rr.getDecisionNote()).isEqualTo("Past 48h window");
            verify(publisher).publishEvent(any(com.imin.iminapi.refund.event.RefundRequestRejectedEvent.class));
        }
    }
```

- [ ] **Step 2: Implement `rejectRequest`**

```java
    @Transactional
    public com.imin.iminapi.refund.dto.RefundRequestDecisionResponse rejectRequest(
            java.util.UUID id,
            com.imin.iminapi.security.AuthPrincipal principal,
            com.imin.iminapi.refund.dto.RefundRequestRejectRequest body) {

        RefundRequest rr = requests.findByIdAndOrgId(id, principal.orgId())
            .orElseThrow(() -> com.imin.iminapi.security.ApiException.notFound("RefundRequest"));

        if (rr.getStatus() != RefundRequestStatus.PENDING) {
            throw new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                com.imin.iminapi.security.ErrorCode.REFUND_REQUEST_NOT_PENDING,
                "Refund request is not pending");
        }

        rr.setStatus(RefundRequestStatus.REJECTED);
        rr.setDecidedAt(Times.nowMicros());
        rr.setDecidedByUserId(principal.userId());
        rr.setDecisionNote(body.note());
        requests.save(rr);

        publisher.publishEvent(new com.imin.iminapi.refund.event.RefundRequestRejectedEvent(rr.getId()));
        log.info("[refund-request] rejected id={} by={} noteLen={}",
            rr.getId(), principal.userId(),
            body.note() == null ? 0 : body.note().length());
        return new com.imin.iminapi.refund.dto.RefundRequestDecisionResponse("rejected", null, null);
    }
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=RefundRequestServiceTest -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestService.java src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java
git commit -m "feat(refund-request): reject endpoint with rejection event"
```

---

## Phase 6 — Controllers + SecurityConfig

### Task 6.1: `PublicRefundRequestController`

**Files:**
- Create: `src/main/java/com/imin/iminapi/controller/publicapi/PublicRefundRequestController.java`
- Create: `src/test/java/com/imin/iminapi/refund/PublicRefundRequestControllerTest.java`

- [ ] **Step 1: Write the controller**

```java
package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.refund.RefundRequestService;
import com.imin.iminapi.refund.dto.PublicRefundFormResponse;
import com.imin.iminapi.refund.dto.PublicRefundSubmitRequest;
import com.imin.iminapi.refund.dto.PublicRefundSubmitResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/refund-requests")
public class PublicRefundRequestController {

    private final RefundRequestService service;

    public PublicRefundRequestController(RefundRequestService service) {
        this.service = service;
    }

    public record LinkRequest(String email) {}
    public record LinkResponse(boolean ok) {}

    @PostMapping
    public ResponseEntity<LinkResponse> requestLink(@RequestBody(required = false) LinkRequest req,
                                                    HttpServletRequest http) {
        if (req != null) {
            service.requestLink(req.email(), http.getRemoteAddr());
        }
        return ResponseEntity.ok(new LinkResponse(true));
    }

    @GetMapping("/by-token/{token}")
    public PublicRefundFormResponse formContext(@PathVariable("token") String token) {
        return service.lookupByToken(token);
    }

    @PostMapping("/by-token/{token}")
    public ResponseEntity<PublicRefundSubmitResponse> submit(@PathVariable("token") String token,
                                                             @Valid @RequestBody PublicRefundSubmitRequest body) {
        PublicRefundSubmitResponse resp = service.submitByToken(token, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }
}
```

- [ ] **Step 2: Write a controller integration test**

```java
package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.IminApiApplication;
import com.imin.iminapi.refund.dto.PublicRefundSubmitRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = IminApiApplication.class)
@ActiveProfiles("test")
class PublicRefundRequestControllerTest {

    @Autowired ObjectMapper json;
    @Autowired WebApplicationContext ctx;
    @MockBean RefundRequestService service;

    MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(ctx).build(); }

    @Test
    void post_link_always_returns_200_and_calls_service() throws Exception {
        mvc().perform(post("/api/v1/public/refund-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"buyer@example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
        verify(service).requestLink(anyString(), anyString());
    }

    @Test
    void post_link_with_empty_body_returns_200() throws Exception {
        mvc().perform(post("/api/v1/public/refund-requests")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    @Test
    void submit_returns_201_on_success() throws Exception {
        var resp = new com.imin.iminapi.refund.dto.PublicRefundSubmitResponse(
            java.util.UUID.randomUUID(), "pending", java.time.Instant.now());
        org.mockito.Mockito.when(service.submitByToken(anyString(), any())).thenReturn(resp);

        var body = json.writeValueAsString(new PublicRefundSubmitRequest(
            RefundRequestReason.CANT_ATTEND, "Can't make it.", null));
        mvc().perform(post("/api/v1/public/refund-requests/by-token/t1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("pending"));
    }
}
```

- [ ] **Step 3: Run the controller tests**

```bash
./mvnw test -Dtest=PublicRefundRequestControllerTest -q
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/controller/publicapi/PublicRefundRequestController.java src/test/java/com/imin/iminapi/refund/PublicRefundRequestControllerTest.java
git commit -m "feat(refund-request): public controller"
```

### Task 6.2: SecurityConfig — whitelist the public endpoints

**Files:**
- Modify: `src/main/java/com/imin/iminapi/config/SecurityConfig.java`

- [ ] **Step 1: Find the existing `permitAll` block for `/api/v1/public/**`**

Open `src/main/java/com/imin/iminapi/config/SecurityConfig.java` with the Read tool. Look for the rule that lists existing public endpoints (commonly something like `.requestMatchers("/api/v1/public/**").permitAll()` or per-path entries). If the file already grants `permitAll` to `/api/v1/public/**` collectively, **no change is needed** — proceed to Step 3.

If the file enumerates public paths explicitly (e.g. `/api/v1/public/orders/recover`, `/api/v1/public/events/**`), append a new matcher:

```java
.requestMatchers("/api/v1/public/refund-requests/**").permitAll()
```

at the same level as the others. Match the existing list style exactly.

- [ ] **Step 2: Boot test**

```bash
./mvnw test -Dtest=IminApiApplicationTests -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit (only if changed)**

```bash
git add src/main/java/com/imin/iminapi/config/SecurityConfig.java
git commit -m "chore(security): whitelist /api/v1/public/refund-requests/**"
```

If no changes were needed, skip this step.

### Task 6.3: Organizer-side `RefundRequestController`

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/RefundRequestController.java`
- Create: `src/test/java/com/imin/iminapi/refund/RefundRequestControllerTest.java`

- [ ] **Step 1: Write the controller**

```java
package com.imin.iminapi.refund;

import com.imin.iminapi.refund.dto.RefundRequestApproveRequest;
import com.imin.iminapi.refund.dto.RefundRequestDecisionResponse;
import com.imin.iminapi.refund.dto.RefundRequestDetailResponse;
import com.imin.iminapi.refund.dto.RefundRequestRejectRequest;
import com.imin.iminapi.refund.dto.RefundRequestSummaryResponse;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/refund-requests")
public class RefundRequestController {

    private final RefundRequestService service;

    public RefundRequestController(RefundRequestService service) {
        this.service = service;
    }

    @GetMapping
    public List<RefundRequestSummaryResponse> list(@PathVariable UUID orgId,
                                                   @CurrentUser AuthPrincipal principal,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) UUID eventId,
                                                   @RequestParam(defaultValue = "25") int limit,
                                                   @RequestParam(required = false) String cursor) {
        if (!orgId.equals(principal.orgId())) throw ApiException.notFound("Org");
        List<RefundRequestStatus> statuses = parseStatuses(status);
        return service.listRequests(orgId, eventId, statuses, cursor, limit);
    }

    @GetMapping("/{id}")
    public RefundRequestDetailResponse get(@PathVariable UUID orgId,
                                           @PathVariable UUID id,
                                           @CurrentUser AuthPrincipal principal) {
        if (!orgId.equals(principal.orgId())) throw ApiException.notFound("Org");
        return service.getRequest(id, orgId);
    }

    @PostMapping("/{id}/approve")
    public RefundRequestDecisionResponse approve(@PathVariable UUID orgId,
                                                  @PathVariable UUID id,
                                                  @CurrentUser AuthPrincipal principal,
                                                  @RequestBody(required = false) RefundRequestApproveRequest body) {
        if (!orgId.equals(principal.orgId())) throw ApiException.notFound("Org");
        if (body == null) body = new RefundRequestApproveRequest(false, "");
        return service.approveRequest(id, principal, body);
    }

    @PostMapping("/{id}/reject")
    public RefundRequestDecisionResponse reject(@PathVariable UUID orgId,
                                                 @PathVariable UUID id,
                                                 @CurrentUser AuthPrincipal principal,
                                                 @Valid @RequestBody RefundRequestRejectRequest body) {
        if (!orgId.equals(principal.orgId())) throw ApiException.notFound("Org");
        return service.rejectRequest(id, principal, body);
    }

    private List<RefundRequestStatus> parseStatuses(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> RefundRequestStatus.valueOf(s.toUpperCase(java.util.Locale.ROOT)))
            .toList();
    }
}
```

- [ ] **Step 2: Write a controller test**

```java
package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.IminApiApplication;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = IminApiApplication.class)
@ActiveProfiles("test")
class RefundRequestControllerTest {

    @Autowired WebApplicationContext ctx;
    @Autowired ObjectMapper json;
    @MockBean RefundRequestService service;

    MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(ctx).build(); }

    AuthPrincipal principalFor(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    org.springframework.security.core.Authentication auth(AuthPrincipal p) {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            p, "n/a", java.util.List.of());
    }

    @Test
    void list_forbids_cross_org_access() throws Exception {
        UUID orgInPath = UUID.randomUUID();
        AuthPrincipal mine = principalFor(UUID.randomUUID());
        mvc().perform(get("/api/v1/orgs/{orgId}/refund-requests", orgInPath).with(authentication(auth(mine))))
            .andExpect(status().isNotFound());
    }

    @Test
    void list_returns_rows_for_own_org() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal me = principalFor(orgId);
        org.mockito.Mockito.when(service.listRequests(eq(orgId), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(java.util.List.of());
        mvc().perform(get("/api/v1/orgs/{orgId}/refund-requests", orgId).with(authentication(auth(me))))
            .andExpect(status().isOk());
    }

    @Test
    void approve_passes_body_through() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID rid = UUID.randomUUID();
        AuthPrincipal me = principalFor(orgId);
        org.mockito.Mockito.when(service.approveRequest(eq(rid), any(), any()))
            .thenReturn(new com.imin.iminapi.refund.dto.RefundRequestDecisionResponse(
                "approved", UUID.randomUUID(), "pending"));

        mvc().perform(post("/api/v1/orgs/{orgId}/refund-requests/{id}/approve", orgId, rid)
                .with(authentication(auth(me)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true,\"note\":\"ok\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("approved"));
    }
}
```

- [ ] **Step 3: Run controller tests**

```bash
./mvnw test -Dtest=RefundRequestControllerTest -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestController.java src/test/java/com/imin/iminapi/refund/RefundRequestControllerTest.java
git commit -m "feat(refund-request): organizer controller"
```

---

## Phase 7 — Email templates + emailer

### Task 7.1: Five Resend templates

**Files (all under `src/main/resources/email-templates/`):**
- Create: `refund-request-link.html`
- Create: `refund-request-link.txt`
- Create: `refund-request-received-buyer.html`
- Create: `refund-request-received-buyer.txt`
- Create: `refund-request-notify-organizer.html`
- Create: `refund-request-notify-organizer.txt`
- Create: `refund-request-notify-imin.html`
- Create: `refund-request-notify-imin.txt`
- Create: `refund-request-rejected.html`
- Create: `refund-request-rejected.txt`

- [ ] **Step 1: Check how existing templates use placeholders**

Read `src/main/resources/email-templates/order-recovery.html` to confirm the placeholder syntax (templates use `{{key}}` or `{key}` — match exactly).

- [ ] **Step 2: Create `refund-request-link.html`**

Content (match the project's existing template style — typically minimal inline-style HTML; mirror the order-recovery template skeleton):

```html
<!doctype html>
<html><body style="font-family:-apple-system,Segoe UI,sans-serif;color:#111">
<p>Hi,</p>
<p>You asked to start a refund request on imin. Click the link below to fill out the form. It expires in {{ttlMinutes}} minutes.</p>
<p><a href="{{link}}" style="display:inline-block;padding:10px 16px;background:#111;color:#fff;border-radius:6px;text-decoration:none">Open refund form</a></p>
<p style="color:#666;font-size:12px">If you didn't request this, you can ignore this email.</p>
</body></html>
```

- [ ] **Step 3: Create `refund-request-link.txt`**

```
You asked to start a refund request on imin. Open the link below to fill out the form. It expires in {{ttlMinutes}} minutes.

{{link}}

If you didn't request this, you can ignore this email.
```

- [ ] **Step 4: Create `refund-request-received-buyer.{html,txt}`**

`refund-request-received-buyer.html`:

```html
<!doctype html>
<html><body style="font-family:-apple-system,Segoe UI,sans-serif;color:#111">
<p>Thanks — we got your refund request.</p>
<p>The organiser will review and reply by email. You don't need to do anything else for now.</p>
<p style="color:#666;font-size:12px">Reference: {{requestId}}</p>
</body></html>
```

`refund-request-received-buyer.txt`:

```
Thanks — we got your refund request.

The organiser will review and reply by email. You don't need to do anything else for now.

Reference: {{requestId}}
```

- [ ] **Step 5: Create `refund-request-notify-organizer.{html,txt}`**

`refund-request-notify-organizer.html`:

```html
<!doctype html>
<html><body style="font-family:-apple-system,Segoe UI,sans-serif;color:#111">
<p>A buyer requested a refund.</p>
<ul>
  <li><b>Event:</b> {{eventName}}</li>
  <li><b>Buyer:</b> {{buyerEmail}}{{phoneLine}}</li>
  <li><b>Reason:</b> {{reason}}</li>
</ul>
<p><b>Buyer's note:</b><br>{{explanation}}</p>
<p><a href="{{dashboardUrl}}">Open in dashboard →</a></p>
</body></html>
```

`refund-request-notify-organizer.txt`:

```
A buyer requested a refund.

Event:  {{eventName}}
Buyer:  {{buyerEmail}}{{phoneLine}}
Reason: {{reason}}

Buyer's note:
{{explanation}}

Open in dashboard: {{dashboardUrl}}
```

- [ ] **Step 6: Create `refund-request-notify-imin.{html,txt}`**

Same body as the organizer template (the imin inbox sees the same notification); split as a separate template only so we can tweak imin-side wording later without coupling.

`refund-request-notify-imin.html`:

```html
<!doctype html>
<html><body style="font-family:-apple-system,Segoe UI,sans-serif;color:#111">
<p><b>[imin] new refund request</b></p>
<ul>
  <li><b>Org:</b> {{orgId}}</li>
  <li><b>Event:</b> {{eventName}}</li>
  <li><b>Buyer:</b> {{buyerEmail}}{{phoneLine}}</li>
  <li><b>Reason:</b> {{reason}}</li>
</ul>
<p>{{explanation}}</p>
<p>Dashboard: <a href="{{dashboardUrl}}">{{dashboardUrl}}</a></p>
</body></html>
```

`refund-request-notify-imin.txt`:

```
[imin] new refund request

Org:    {{orgId}}
Event:  {{eventName}}
Buyer:  {{buyerEmail}}{{phoneLine}}
Reason: {{reason}}

{{explanation}}

Dashboard: {{dashboardUrl}}
```

- [ ] **Step 7: Create `refund-request-rejected.{html,txt}`**

`refund-request-rejected.html`:

```html
<!doctype html>
<html><body style="font-family:-apple-system,Segoe UI,sans-serif;color:#111">
<p>Hi,</p>
<p>The organiser reviewed your refund request and was unable to approve it. Their note:</p>
<blockquote style="border-left:3px solid #ccc;padding-left:12px;color:#333">{{decisionNote}}</blockquote>
<p>If you'd like to discuss this further, replying to this email reaches the organiser.</p>
</body></html>
```

`refund-request-rejected.txt`:

```
Hi,

The organiser reviewed your refund request and was unable to approve it. Their note:

  {{decisionNote}}

If you'd like to discuss this further, replying to this email reaches the organiser.
```

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/email-templates/refund-request-*.html src/main/resources/email-templates/refund-request-*.txt
git commit -m "feat(refund-request): five email templates"
```

### Task 7.2: `RefundRequestEmailer` — emit emails on events

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/email/RefundRequestEmailer.java`
- Create: `src/test/java/com/imin/iminapi/refund/RefundRequestEmailerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.imin.iminapi.refund;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.refund.email.RefundRequestEmailer;
import com.imin.iminapi.refund.event.RefundRequestRejectedEvent;
import com.imin.iminapi.refund.event.RefundRequestSubmittedEvent;
import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefundRequestEmailerTest {

    RecordingEmailService emails = new RecordingEmailService();
    EmailTemplateRenderer renderer = mock(EmailTemplateRenderer.class);
    EmailProperties props = new EmailProperties();
    RefundRequestRepository requests = mock(RefundRequestRepository.class);
    EventRepository events = mock(EventRepository.class);
    com.imin.iminapi.repository.UserRepository users = mock(com.imin.iminapi.repository.UserRepository.class);
    com.imin.iminapi.repository.OrganizationRepository orgs = mock(com.imin.iminapi.repository.OrganizationRepository.class);

    RefundRequestEmailer emailer;

    @BeforeEach
    void setUp() {
        props.setFromAddress("noreply@test");
        props.setAppBaseUrl("https://dashboard.test");
        props.setRefundRequestInbox("support+refunds@test");
        when(renderer.render(anyString(), any()))
            .thenReturn(new EmailTemplateRenderer.Rendered("<html/>", "txt"));
        emailer = new RefundRequestEmailer(emails, renderer, props, requests, events, users, orgs);
    }

    private RefundRequest seedRequest() {
        RefundRequest rr = new RefundRequest();
        rr.setId(UUID.randomUUID());
        rr.setOrderId(UUID.randomUUID());
        rr.setOrgId(UUID.randomUUID());
        rr.setEventId(UUID.randomUUID());
        rr.setBuyerEmail("buyer@example.com");
        rr.setReason(RefundRequestReason.CANT_ATTEND);
        rr.setExplanation("text");
        rr.setStatus(RefundRequestStatus.PENDING);
        rr.setCreatedAt(Instant.now());
        return rr;
    }

    @Test
    void on_submitted_sends_three_emails() {
        RefundRequest rr = seedRequest();
        when(requests.findById(rr.getId())).thenReturn(Optional.of(rr));

        emailer.onSubmitted(new RefundRequestSubmittedEvent(rr.getId()));

        assertThat(emails.sent).hasSize(3);
        assertThat(emails.sent.stream().map(s -> s.to)).contains(
            "buyer@example.com", "support+refunds@test");
    }

    @Test
    void on_rejected_sends_buyer_email() {
        RefundRequest rr = seedRequest();
        rr.setStatus(RefundRequestStatus.REJECTED);
        rr.setDecisionNote("Past 48h");
        when(requests.findById(rr.getId())).thenReturn(Optional.of(rr));

        emailer.onRejected(new RefundRequestRejectedEvent(rr.getId()));

        assertThat(emails.sent).hasSize(1);
        assertThat(emails.sent.get(0).to).isEqualTo("buyer@example.com");
    }
}
```

- [ ] **Step 2: Write the emailer**

```java
package com.imin.iminapi.refund.email;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.refund.RefundRequest;
import com.imin.iminapi.refund.RefundRequestRepository;
import com.imin.iminapi.refund.event.RefundRequestRejectedEvent;
import com.imin.iminapi.refund.event.RefundRequestSubmittedEvent;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RefundRequestEmailer {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestEmailer.class);

    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties props;
    private final RefundRequestRepository requests;
    private final EventRepository events;
    private final UserRepository users;
    private final OrganizationRepository orgs;

    public RefundRequestEmailer(EmailService email,
                                EmailTemplateRenderer renderer,
                                EmailProperties props,
                                RefundRequestRepository requests,
                                EventRepository events,
                                UserRepository users,
                                OrganizationRepository orgs) {
        this.email = email;
        this.renderer = renderer;
        this.props = props;
        this.requests = requests;
        this.events = events;
        this.users = users;
        this.orgs = orgs;
    }

    @EventListener
    public void onSubmitted(RefundRequestSubmittedEvent ev) {
        RefundRequest rr = requests.findById(ev.requestId()).orElse(null);
        if (rr == null) return;

        String eventName = events.findById(rr.getEventId())
            .map(Event::getName).orElse("");
        String dashboardUrl = props.getAppBaseUrl()
            + "/events/" + rr.getEventId() + "/refund-requests/" + rr.getId();
        String phoneLine = (rr.getBuyerPhone() == null || rr.getBuyerPhone().isBlank())
            ? "" : " · " + rr.getBuyerPhone();

        Map<String, String> base = new LinkedHashMap<>();
        base.put("requestId", rr.getId().toString());
        base.put("orgId", rr.getOrgId().toString());
        base.put("eventName", eventName);
        base.put("buyerEmail", rr.getBuyerEmail());
        base.put("phoneLine", phoneLine);
        base.put("reason", rr.getReason().toWire());
        base.put("explanation", rr.getExplanation());
        base.put("dashboardUrl", dashboardUrl);

        // Buyer ack.
        EmailTemplateRenderer.Rendered buyer = renderer.render("refund-request-received-buyer", base);
        safeSend(rr.getBuyerEmail(), "We got your refund request · imin", buyer);

        // Organizer notify.
        String organizerEmail = orgs.findById(rr.getOrgId())
            .map(Organization::getOwnerUserId)
            .flatMap(users::findById)
            .map(User::getEmail)
            .orElse(null);
        if (organizerEmail != null) {
            EmailTemplateRenderer.Rendered org = renderer.render("refund-request-notify-organizer", base);
            safeSend(organizerEmail, "New refund request · imin", org);
        }

        // Imin inbox.
        EmailTemplateRenderer.Rendered imin = renderer.render("refund-request-notify-imin", base);
        safeSend(props.resolveRefundRequestInbox(), "[imin] new refund request", imin);
    }

    @EventListener
    public void onRejected(RefundRequestRejectedEvent ev) {
        RefundRequest rr = requests.findById(ev.requestId()).orElse(null);
        if (rr == null) return;

        Map<String, String> values = new LinkedHashMap<>();
        values.put("decisionNote", rr.getDecisionNote() == null ? "" : rr.getDecisionNote());

        EmailTemplateRenderer.Rendered r = renderer.render("refund-request-rejected", values);
        safeSend(rr.getBuyerEmail(), "Your refund request · imin", r);
    }

    private void safeSend(String to, String subject, EmailTemplateRenderer.Rendered r) {
        if (to == null || to.isBlank()) return;
        try {
            email.send(to, subject, r.html(), r.text());
        } catch (Exception e) {
            log.warn("[refund-request] email send failed to={}: {}", to, e.getMessage());
        }
    }
}
```

**Note:** This emailer depends on `OrganizationRepository.findById(...)`, the existing `Organization.getOwnerUserId()` accessor, and `UserRepository.findById(...)`. Before running the tests, confirm with the Read tool that:
- `OrganizationRepository` is a `JpaRepository<Organization, UUID>` (so `findById` exists).
- `Organization` exposes the owner-user-id field via a Lombok-generated getter (typical name: `getOwnerUserId`).
- `User.getEmail()` exists.

If the names differ, update the emailer accordingly. The intent (route the notification to the org's primary owner) is the load-bearing piece; the exact accessor names are codebase-specific.

- [ ] **Step 3: Run emailer test**

```bash
./mvnw test -Dtest=RefundRequestEmailerTest -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/email/RefundRequestEmailer.java src/test/java/com/imin/iminapi/refund/RefundRequestEmailerTest.java
git commit -m "feat(refund-request): emailer for submit + reject events"
```

### Task 7.3: Wire the magic-link email payload to the new template

**Files:**
- Modify: `src/main/java/com/imin/iminapi/refund/RefundRequestService.java`

The link-issuance code in Task 4.1 already invokes `renderer.render("refund-request-link", ...)` and `email.send(...)`. Now the template file actually exists, so the previously stubbed-out renderer mock isn't needed in prod.

- [ ] **Step 1: No code change. Run the full suite to confirm everything still passes**

```bash
./mvnw test -Dtest='RefundRequestServiceTest,RefundRequestEmailerTest,PublicRefundRequestControllerTest,RefundRequestControllerTest' -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: No commit (no diff)**

---

## Phase 8 — Token sweeper

### Task 8.1: `RefundRequestTokenSweeper`

**Files:**
- Create: `src/main/java/com/imin/iminapi/refund/RefundRequestTokenSweeper.java`
- Create: `src/test/java/com/imin/iminapi/refund/RefundRequestTokenSweeperTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.imin.iminapi.refund;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundRequestTokenSweeperTest {

    @Test
    void sweep_calls_repository_with_7_day_cutoff() {
        RefundRequestTokenRepository repo = mock(RefundRequestTokenRepository.class);
        when(repo.deleteExpiredBefore(any())).thenReturn(0);

        new RefundRequestTokenSweeper(repo).sweep();

        verify(repo).deleteExpiredBefore(any());
    }
}
```

- [ ] **Step 2: Write the sweeper**

```java
package com.imin.iminapi.refund;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Component
public class RefundRequestTokenSweeper {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestTokenSweeper.class);

    private final RefundRequestTokenRepository tokens;

    public RefundRequestTokenSweeper(RefundRequestTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * Daily sweep. ShedLock-protected so only one replica runs the delete.
     * Retention: 7 days past expiry — enough for audit / debugging without
     * letting the table grow without bound.
     */
    @Scheduled(cron = "0 7 4 * * *")  // 04:07 server time, daily
    @SchedulerLock(name = "refundRequestTokens.sweep", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));
        int deleted = tokens.deleteExpiredBefore(cutoff);
        log.info("[refund-request] sweep removed {} expired tokens (cutoff={})", deleted, cutoff);
    }
}
```

If the project's existing scheduled jobs don't use ShedLock or the `@Scheduled` infrastructure isn't wired (`@EnableScheduling` on the application class), look for `ReservationSweeper` and mirror its annotations exactly. Match precedent over the snippet above.

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=RefundRequestTokenSweeperTest -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/refund/RefundRequestTokenSweeper.java src/test/java/com/imin/iminapi/refund/RefundRequestTokenSweeperTest.java
git commit -m "feat(refund-request): scheduled sweeper for expired tokens"
```

---

## Phase 9 — End-to-end verification

### Task 9.1: Full test suite

- [ ] **Step 1: Run the entire suite**

```bash
./mvnw test -q
```

Expected: BUILD SUCCESS, no failures, no skipped tests except previously-skipped ones unrelated to this work.

- [ ] **Step 2: If failures, read the report and fix forward; never `--no-verify`**

### Task 9.2: Boot the app and inspect OpenAPI

- [ ] **Step 1: Start the dev DB and the app**

```bash
docker compose up -d
./mvnw spring-boot:run
```

In a second terminal:

- [ ] **Step 2: Fetch the OpenAPI doc and confirm the new paths exist**

```bash
curl -sf http://localhost:8085/v3/api-docs.yaml | grep -E "refund-requests|by-token" | head -20
```

Expected (at minimum):

```
  /api/v1/public/refund-requests:
  /api/v1/public/refund-requests/by-token/{token}:
  /api/v1/orgs/{orgId}/refund-requests:
  /api/v1/orgs/{orgId}/refund-requests/{id}:
  /api/v1/orgs/{orgId}/refund-requests/{id}/approve:
  /api/v1/orgs/{orgId}/refund-requests/{id}/reject:
```

- [ ] **Step 3: Stop the app**

`Ctrl-C` the `./mvnw spring-boot:run` process.

### Task 9.3: Smoke-test the public flow with curl

This is a happy-path manual sanity check against a freshly seeded order. Skip if the project lacks dev seed data; in that case rely on the integration tests.

- [ ] **Step 1: Issue a magic link**

```bash
curl -s -X POST http://localhost:8085/api/v1/public/refund-requests \
  -H 'Content-Type: application/json' \
  -d '{"email":"<an existing order email in dev>"}'
```

Expected: `{"ok":true}`. Check application logs for `[refund-request] token-issued`.

- [ ] **Step 2: Manual final check**

Inspect the dev mailbox / logs for the magic-link URL, open it, fill out the form, watch the dashboard endpoint (`/api/v1/orgs/{orgId}/refund-requests`) return the new row.

---

## Self-review (run before considering the plan complete)

Sweep the plan against the spec sections. Confirm:

- §1 Motivation → Covered by Phases 1–9 collectively.
- §2 Non-goals → No tasks add self-service refunds, multilingual emails, etc. ✓
- §3 Architectural overview → Public + organizer surfaces both implemented; one FK from request to refund. ✓
- §4 Promo-code fix → Phase 1. ✓
- §5 Data model → Tasks 2.1, 2.2, 2.5, 2.6, 2.7, 2.8. ✓
- §6 API surface → Public ctrl: Phase 6.1. Organizer ctrl: Phase 6.3. Approve confirm-gate: Phase 5.2. ✓
- §7 Email surface → Phase 7. ✓
- §8 Application events → Task 3.4 events + Phase 7 listeners. ✓
- §9 Configuration → Task 3.2 env vars; sweeper TTL defaults. ✓
- §13 Security & privacy → SHA-256 hashing in service, leak-safe controller responses, partial-unique index, SecurityConfig whitelist. ✓
- §14 Observability → log lines present in service + emailer + sweeper. ✓
- §15 Testing → Per-phase TDD tasks. ✓

**Out of scope / deferred** (documented inline, not in this plan):
- `eventName` plumbing into list/detail/form responses — currently returns `null` placeholders. Add an `EventQueryService.findById` lookup in a follow-up.
- Structured `proposedRefund` in `REFUND_APPROVAL_NOT_CONFIRMED` error body — currently `.toString()`'d into a string field. Acceptable for MVP; revisit if a non-dashboard client surfaces.
- Linked-refund status in list/detail responses — currently `null`. Add a `RefundRepository.findStatusById` join in a follow-up if dashboards need it.
