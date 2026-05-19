# Ticket Issuance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Within 60 seconds of `payment_intent.succeeded`, the buyer receives an email containing a tamper-evident, QR-coded, single-use ticket that is viewable on a tokenized web URL, addable to Apple Wallet, and ready to be redeemed exactly once at the gate. Issuance is idempotent on the source Order; a buyer who loses the email can self-recover; a buyer returning to the Stripe-success page sees their tickets immediately.

**Architecture:** PI-succeeded webhook (already routed) → `PaidCheckoutService.issuePaidOrder` (new) persists `Order` + N `Ticket` rows in the dedup-protected handler transaction → `@TransactionalEventListener(AFTER_COMMIT) @Async` dispatches a Resend email with hyperlinked QR images that point at `/api/v1/public/tickets/{token}/qr.png`. Same QR endpoint feeds the web ticket page and an on-demand `.pkpass`. Idempotency is two-layered: `processed_webhook_events` PK (Stripe event id, V25 in-flight) plus a new `orders.stripe_payment_intent_id UNIQUE`. Single-use redemption is an atomic UPDATE with a state predicate.

**Tech Stack:** Java 17, Spring Boot 4, Spring Data JPA, Flyway, PostgreSQL 17 (H2 in tests), Spring `@Async`, zxing (already in classpath), `de.brendamour:jpasskit` (new dep), Stripe Java SDK (already in classpath), Resend (already wired). Next.js 16 / React 19 on `imin-public` for the buyer surfaces.

**Spec:** [`docs/superpowers/specs/2026-05-19-ticket-issuance-design.md`](../specs/2026-05-19-ticket-issuance-design.md).

**Precondition (not part of this plan):** The uncommitted in-flight changes on `master` (V25 dedup migration, `WebhookEventDedupService`, PI-driven `StripeWebhookService`) must be committed and pass tests **before** Task 1 starts. The plan assumes they are committed; otherwise tests added later won't have the dedup wiring to call against. The file structure section calls out which tasks depend on those pieces.

---

## File Structure

### Files to create (`imin-api`)

| Path | Responsibility |
|---|---|
| `src/main/resources/db/migration/V26__ticket_issuance.sql` | PI-id column + UNIQUE on Order; ticket redemption columns + default state; `order_recovery_attempts` table. |
| `src/main/java/com/imin/iminapi/model/TicketState.java` | Enum `ISSUED`, `REDEEMED`, `REVOKED`. Parses legacy `'pre'` as `ISSUED`. |
| `src/main/java/com/imin/iminapi/service/ticket/QrPayloadSigner.java` | `sign(token)` → `imin1.<token>.<hmac>`; `verify(payload)` → `Optional<String>` (the verified token). |
| `src/main/java/com/imin/iminapi/service/ticket/QrImageRenderer.java` | zxing wrapper. PNG bytes for a payload. |
| `src/main/java/com/imin/iminapi/service/ticket/TicketProperties.java` | `@ConfigurationProperties("imin.ticket")` — signing secret, recovery window, max per hour. |
| `src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java` | The webhook-side issuance: persist Order + Tickets idempotently. |
| `src/main/java/com/imin/iminapi/service/ticket/TicketsIssuedEvent.java` | Spring `ApplicationEvent` record: `(UUID orderId)`. |
| `src/main/java/com/imin/iminapi/service/ticket/TicketIssuanceEmailer.java` | `@Async` listener that loads order + tickets + sends the confirmation email. |
| `src/main/java/com/imin/iminapi/service/ticket/TicketRedeemService.java` | Atomic state transition; result enum. |
| `src/main/java/com/imin/iminapi/service/ticket/OrderRecoveryService.java` | Rate-limited email-based recovery. |
| `src/main/java/com/imin/iminapi/service/ticket/CheckoutStatusService.java` | Maps `sessionId` → `{status, orderToken?}` for the success page. |
| `src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java` | jpasskit-based `.pkpass` generator. Returns 503 marker when unconfigured. |
| `src/main/java/com/imin/iminapi/config/AsyncConfig.java` | `@EnableAsync` + dedicated `ticketEmailExecutor` bean. |
| `src/main/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetController.java` | New endpoints: `/qr.png`, `/apple-wallet.pkpass`. |
| `src/main/java/com/imin/iminapi/controller/publicapi/PublicCheckoutController.java` | `GET /checkout/{sessionId}`. |
| `src/main/java/com/imin/iminapi/controller/publicapi/PublicRecoveryController.java` | `POST /orders/recover`. |
| `src/main/java/com/imin/iminapi/controller/event/TicketRedeemController.java` | `POST /api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem`. |
| `src/main/java/com/imin/iminapi/model/OrderRecoveryAttempt.java` + repository | DB-backed rate-limit counter. |
| `src/main/resources/email-templates/ticket-issued.html` + `.txt` | Mustache-style templates consumed by `EmailTemplateRenderer`. |
| `src/main/resources/email-templates/order-recovery.html` + `.txt` | Recovery email. |
| `src/main/resources/wallet/icon.png` (29×29), `icon@2x.png` (58×58), `icon@3x.png` (87×87), `logo.png` (160×50), `logo@2x.png`, `logo@3x.png` | Bundled `.pkpass` art. Use the existing imin mark. |
| `src/test/java/com/imin/iminapi/service/ticket/QrPayloadSignerTest.java` | Sign/verify round-trip, tampering, version prefix. |
| `src/test/java/com/imin/iminapi/service/ticket/PaidCheckoutServiceTest.java` | Issuance happy path + idempotency. |
| `src/test/java/com/imin/iminapi/service/ticket/TicketRedeemServiceTest.java` | State machine matrix. |
| `src/test/java/com/imin/iminapi/service/ticket/OrderRecoveryServiceTest.java` | Found, not-found, rate limit. |
| `src/test/java/com/imin/iminapi/service/ticket/CheckoutStatusServiceTest.java` | ready/pending. |
| `src/test/java/com/imin/iminapi/service/ticket/AppleWalletPassServiceTest.java` | `.pkpass` shape; unconfigured → marker. |
| `src/test/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetControllerTest.java` | MockMvc for `/qr.png` and `.pkpass`. |

### Files to modify (`imin-api`)

| Path | What |
|---|---|
| `pom.xml` | Add `de.brendamour:jpasskit:0.4.0` (or latest stable). |
| `src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java` | Mirror `tier_id` / `qty` / `event_id` / `promo_id` onto `payment_intent_data.metadata`. |
| `src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java` | After `onPaymentIntentSucceeded` does inventory + promo, call `paidCheckoutService.issuePaidOrder(pi)`. |
| `src/main/java/com/imin/iminapi/model/Order.java` | Add `stripePaymentIntentId` field. |
| `src/main/java/com/imin/iminapi/model/Ticket.java` | Add `redeemedAt`, `redeemedByUserId`. |
| `src/main/java/com/imin/iminapi/repository/OrderRepository.java` | `findByStripePaymentIntentId`, `findByStripeSessionId`, `findByEmailAndEventIdAndCreatedAtAfter`. |
| `src/main/java/com/imin/iminapi/repository/TicketRepository.java` | `redeemAtomic(token, userId)` returning row count. |
| `src/main/java/com/imin/iminapi/service/event/FreeCheckoutService.java` | New tickets default to `'issued'` (set explicitly so we stop minting `'pre'`). |
| `src/main/java/com/imin/iminapi/controller/publicapi/PublicOrderController.java` | `PublicTicketResponse` gains `qrPayload`, `qrUrl`, `walletAvailable`, normalizes `'pre' → 'issued'`. |
| `src/main/java/com/imin/iminapi/dto/publicapi/PublicTicketResponse.java` | New fields. |
| `src/main/java/com/imin/iminapi/security/SecurityConfig.java` | `permitAll` for the new public ticket-asset and recovery paths. |
| `src/main/resources/application.yaml` | Bind `imin.ticket.*` and Apple Wallet env vars; register `IminApiApplication` for the new `@ConfigurationProperties`. |
| `src/main/java/com/imin/iminapi/IminApiApplication.java` | `@EnableConfigurationProperties(TicketProperties.class)` if not picked up by classpath scanning. |

### Files to create (`imin-public`)

| Path | Responsibility |
|---|---|
| `app/recover/page.tsx` | Recovery form. |
| `lib/api/checkout.ts` | `getCheckoutStatus(sessionId)`, `recoverOrder(email, eventId?)`. |

### Files to modify (`imin-public`)

| Path | What |
|---|---|
| `app/tickets/[token]/page.tsx` | Replace placeholder block with `<img src={qrUrl}>` + "Add to Apple Wallet" link. |
| `app/order/[token]/page.tsx` | Add "Lost this email? Resend" link. |
| `app/e/[id]/success/page.tsx` | Rewrite — server-side call to `/checkout/{sessionId}`; `redirect()` to `/order/{token}` on ready; meta-refresh on pending; error state otherwise. |
| `lib/api/public-events.ts` | Re-export the new `lib/api/checkout.ts` helpers (or merge). Update `PublicTicket` type for `qrUrl`/`walletAvailable`. |

---

## Task 1 — Mirror Session metadata onto PaymentIntentData

**Why first:** without this, every subsequent test that exercises the PI handler is moot — the metadata won't arrive.

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java`
- Modify: `imin-api/src/test/java/com/imin/iminapi/stripe/StripeCheckoutServiceTest.java`

- [ ] **Step 1: Read the current builder block to anchor the edit.**

Open `StripeCheckoutService.java` and locate the `builder` chain that starts at line ~229 with `SessionCreateParams.Builder builder = SessionCreateParams.builder()...` and the `.setPaymentIntentData(...)` call at line ~256.

- [ ] **Step 2: Write the failing test.**

Find `StripeCheckoutServiceTest`. Add this test (adapt class members if needed — most fields are already mocked):

```java
@Test
void session_create_mirrors_metadata_onto_payment_intent_data() throws Exception {
    // Given a buyable tier on a publicly visible event with promo
    setUpValidEventAndTier();
    PromoCode promo = setUpValidPromo(eventId);

    // When createCheckoutSession is invoked
    service.createCheckoutSession(eventId, tierId, 2, promo.getCode(), null, null);

    // Then the SessionCreateParams sent to Stripe carry the metadata on
    // BOTH the session-level Metadata AND inside payment_intent_data.metadata.
    ArgumentCaptor<SessionCreateParams> captor =
            ArgumentCaptor.forClass(SessionCreateParams.class);
    verify(stripeSessions).create(captor.capture());
    SessionCreateParams sent = captor.getValue();

    Map<String, String> sessionMeta = sent.getMetadata();
    assertEquals(tierId.toString(), sessionMeta.get("tier_id"));
    assertEquals("2", sessionMeta.get("qty"));
    assertEquals(promo.getId().toString(), sessionMeta.get("promo_id"));

    Map<String, String> piMeta = sent.getPaymentIntentData().getMetadata();
    assertNotNull(piMeta, "payment_intent_data.metadata must be set so the PI webhook can read it");
    assertEquals(tierId.toString(), piMeta.get("tier_id"));
    assertEquals("2", piMeta.get("qty"));
    assertEquals(promo.getId().toString(), piMeta.get("promo_id"));
    assertEquals(eventId.toString(), piMeta.get("event_id"));
}
```

If `setUpValidEventAndTier()` / `setUpValidPromo()` helpers don't exist in the test class, write minimal inline fixtures using the same shape `StripeCheckoutServiceTest` already uses for its happy-path test.

- [ ] **Step 3: Run the test — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=StripeCheckoutServiceTest#session_create_mirrors_metadata_onto_payment_intent_data
```

Expected: FAIL — `piMeta` is null because the current builder only calls `.setApplicationFeeAmount` + `.setTransferData` on `PaymentIntentData`, never `.putMetadata`.

- [ ] **Step 4: Implement.**

In `StripeCheckoutService.createCheckoutSession`, replace the `.setPaymentIntentData(...)` block:

```java
SessionCreateParams.PaymentIntentData.Builder piData =
        SessionCreateParams.PaymentIntentData.builder()
                .setApplicationFeeAmount(applicationFee)
                .setTransferData(SessionCreateParams.PaymentIntentData.TransferData.builder()
                        .setDestination(org.getStripeAccountId())
                        .build())
                .putMetadata("tier_id", tierId.toString())
                .putMetadata("qty", String.valueOf(quantity))
                .putMetadata("event_id", eventId.toString());
if (promo != null) {
    piData.putMetadata("promo_id", promo.getId().toString());
}

builder.setPaymentIntentData(piData.build())
        .setSuccessUrl(props.getPublicReturnUrlBase()
                + "/e/" + eventId + "/success?session_id={CHECKOUT_SESSION_ID}")
        .setCancelUrl(props.getPublicReturnUrlBase() + "/e/" + eventId)
        .putMetadata("tier_id", tierId.toString())
        .putMetadata("qty", String.valueOf(quantity))
        .putMetadata("event_id", eventId.toString());

if (promo != null) {
    String couponId = createOneShotCoupon(promo, eventId, tier.getStripeProductId());
    builder.addDiscount(SessionCreateParams.Discount.builder()
            .setCoupon(couponId)
            .build());
    builder.putMetadata("promo_id", promo.getId().toString());
}
```

(The existing `if (promo != null)` block at the bottom of the builder chain still handles the session-level `promo_id` metadata + the Stripe coupon attach; only the PI-level metadata is being added.)

- [ ] **Step 5: Run all tests in this class.**

```bash
cd imin-api && ./mvnw test -Dtest=StripeCheckoutServiceTest
```

Expected: PASS (new test passes; existing tests untouched).

- [ ] **Step 6: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java src/test/java/com/imin/iminapi/stripe/StripeCheckoutServiceTest.java
git commit -m "Mirror checkout metadata onto payment_intent_data

The PI-driven webhook reads tier_id/qty/promo_id from PI metadata.
Without mirroring, every fulfilment lookup would be empty and orders
would silently never persist.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2 — V26 migration

**Files:**
- Create: `imin-api/src/main/resources/db/migration/V26__ticket_issuance.sql`

- [ ] **Step 1: Write the migration.**

```sql
-- Order: PaymentIntent id is the canonical idempotency key for the paid path.
ALTER TABLE orders ADD COLUMN stripe_payment_intent_id VARCHAR(128);
ALTER TABLE orders ADD CONSTRAINT orders_stripe_payment_intent_id_unique UNIQUE (stripe_payment_intent_id);

-- Ticket: redemption state machine. Default state flips from 'pre' to 'issued'
-- so new rows use the new vocabulary; existing 'pre' rows are translated as
-- 'issued' at the API boundary (no destructive backfill).
ALTER TABLE tickets ADD COLUMN redeemed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tickets ADD COLUMN redeemed_by_user_id UUID;
ALTER TABLE tickets ALTER COLUMN state SET DEFAULT 'issued';

-- Self-recovery rate-limit counter.
CREATE TABLE order_recovery_attempts (
    id           UUID PRIMARY KEY,
    email        VARCHAR(254) NOT NULL,
    ip_hash      VARCHAR(64) NOT NULL,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_recovery_email_time ON order_recovery_attempts (email, attempted_at);
CREATE INDEX idx_recovery_ip_time    ON order_recovery_attempts (ip_hash, attempted_at);
```

- [ ] **Step 2: Boot the app to verify Flyway accepts the migration.**

```bash
cd imin-api && ./mvnw test -Dtest=IminApiApplicationTests
```

Expected: PASS. The default smoke test boots the context, which runs Flyway against H2.

- [ ] **Step 3: Commit.**

```bash
cd imin-api && git add src/main/resources/db/migration/V26__ticket_issuance.sql
git commit -m "Add V26 ticket-issuance migration

PaymentIntent id UNIQUE on Order for idempotency.
Ticket redemption columns and 'issued' default state.
order_recovery_attempts counter table.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3 — Order/Ticket entity + repository updates

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/model/Order.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/model/Ticket.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/repository/OrderRepository.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/repository/TicketRepository.java`

- [ ] **Step 1: Add `stripePaymentIntentId` to `Order`.**

```java
@Column(name = "stripe_payment_intent_id", length = 128)
private String stripePaymentIntentId;
```

Place under the existing `stripeSessionId` field. Lombok `@Getter @Setter` already in place; no manual accessors needed.

- [ ] **Step 2: Add redemption fields to `Ticket`.**

```java
@Column(name = "redeemed_at")
private Instant redeemedAt;

@Column(name = "redeemed_by_user_id")
private UUID redeemedByUserId;
```

- [ ] **Step 3: Extend `OrderRepository`.**

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByToken(String token);
    Optional<Order> findByStripePaymentIntentId(String paymentIntentId);
    Optional<Order> findByStripeSessionId(String sessionId);

    @Query("""
            select o from Order o
             where lower(o.email) = lower(:email)
               and (:eventId is null or o.eventId = :eventId)
               and o.createdAt > :cutoff
             order by o.createdAt desc
            """)
    List<Order> findRecentForRecovery(@Param("email") String email,
                                       @Param("eventId") UUID eventId,
                                       @Param("cutoff") Instant cutoff);
}
```

- [ ] **Step 4: Extend `TicketRepository`.**

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    Optional<Ticket> findByToken(String token);
    List<Ticket> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /**
     * Atomic single-use redemption. Returns the number of rows updated:
     * 1 → fresh redemption, 0 → either already redeemed, revoked, or token unknown.
     * The caller does a follow-up select to disambiguate when zero.
     */
    @Modifying
    @Transactional
    @Query("""
            update Ticket t
               set t.state = 'redeemed',
                   t.redeemedAt = :now,
                   t.redeemedByUserId = :userId
             where t.token = :token
               and t.state in ('issued', 'pre')
            """)
    int redeemAtomic(@Param("token") String token,
                      @Param("userId") UUID userId,
                      @Param("now") Instant now);
}
```

- [ ] **Step 5: Run the boot test.**

```bash
cd imin-api && ./mvnw test -Dtest=IminApiApplicationTests
```

Expected: PASS — JPA metamodel still resolves, repos compile.

- [ ] **Step 6: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/model/Order.java src/main/java/com/imin/iminapi/model/Ticket.java src/main/java/com/imin/iminapi/repository/OrderRepository.java src/main/java/com/imin/iminapi/repository/TicketRepository.java
git commit -m "Extend Order/Ticket entities for paid-flow issuance and redemption

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4 — TicketState enum

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/model/TicketState.java`

- [ ] **Step 1: Write the enum.**

```java
package com.imin.iminapi.model;

/**
 * Ticket lifecycle states. Wire format is the lowercase name as stored in
 * {@code tickets.state}. {@code 'pre'} is legacy (free-checkout default before
 * V26); we translate it as {@link #ISSUED} at the API boundary instead of
 * doing a destructive backfill.
 */
public enum TicketState {
    ISSUED,
    REDEEMED,
    REVOKED;

    public String wire() {
        return name().toLowerCase();
    }

    public static TicketState fromWire(String wire) {
        if (wire == null) return ISSUED;
        return switch (wire) {
            case "pre", "issued" -> ISSUED;
            case "redeemed", "checkedIn" -> REDEEMED;
            case "revoked" -> REVOKED;
            default -> throw new IllegalArgumentException("Unknown ticket state: " + wire);
        };
    }
}
```

The `'checkedIn'` mapping exists because the `imin-public` frontend already has a `checkedIn` label in its state map. We accept it as an inbound synonym to avoid a noisy cross-repo flag flip.

- [ ] **Step 2: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/model/TicketState.java
git commit -m "Add TicketState enum (ISSUED/REDEEMED/REVOKED) with legacy parsing

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5 — QrPayloadSigner

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/QrPayloadSigner.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/TicketProperties.java`
- Create: `imin-api/src/test/java/com/imin/iminapi/service/ticket/QrPayloadSignerTest.java`
- Modify: `imin-api/src/main/resources/application.yaml`
- Modify: `imin-api/src/main/java/com/imin/iminapi/IminApiApplication.java`

- [ ] **Step 1: Write `TicketProperties`.**

```java
package com.imin.iminapi.service.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imin.ticket")
public class TicketProperties {
    /** Required at boot; app fails fast if blank. 32+ bytes of entropy (hex or base64). */
    private String signingSecret = "";
    private int recoveryWindowDays = 90;
    private int recoveryMaxPerHour = 5;

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String s) { this.signingSecret = s; }
    public int getRecoveryWindowDays() { return recoveryWindowDays; }
    public void setRecoveryWindowDays(int v) { this.recoveryWindowDays = v; }
    public int getRecoveryMaxPerHour() { return recoveryMaxPerHour; }
    public void setRecoveryMaxPerHour(int v) { this.recoveryMaxPerHour = v; }
}
```

- [ ] **Step 2: Register the properties class.**

Open `IminApiApplication.java` and locate the existing `@EnableConfigurationProperties(...)` annotation (or `@SpringBootApplication`). Add `TicketProperties.class` to the enabled list. If no `@EnableConfigurationProperties` annotation exists, add one:

```java
@EnableConfigurationProperties({ EmailProperties.class, TicketProperties.class /* plus any others already listed */ })
```

If `EmailProperties` is already registered there, just append `TicketProperties.class` to the array.

- [ ] **Step 3: Add config bindings to `application.yaml`.**

Add under the top-level `imin:` section (alongside the existing `email:` block):

```yaml
imin:
  ticket:
    signing-secret: ${IMIN_TICKET_SIGNING_SECRET:}
    recovery-window-days: ${IMIN_TICKET_RECOVERY_WINDOW_DAYS:90}
    recovery-max-per-hour: ${IMIN_TICKET_RECOVERY_MAX_PER_HOUR:5}
```

- [ ] **Step 4: Write the failing test.**

```java
package com.imin.iminapi.service.ticket;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class QrPayloadSignerTest {

    private QrPayloadSigner signer(String secret) {
        TicketProperties p = new TicketProperties();
        p.setSigningSecret(secret);
        return new QrPayloadSigner(p);
    }

    @Test
    void sign_and_verify_round_trip() {
        QrPayloadSigner s = signer("test-secret-32-bytes-of-entropy-xx");
        String payload = s.sign("abc123token");
        assertTrue(payload.startsWith("imin1."), payload);
        assertEquals(Optional.of("abc123token"), s.verify(payload));
    }

    @Test
    void tampered_token_fails_verification() {
        QrPayloadSigner s = signer("test-secret-32-bytes-of-entropy-xx");
        String payload = s.sign("abc123token");
        String tampered = payload.replace("abc123token", "differentToken");
        assertEquals(Optional.empty(), s.verify(tampered));
    }

    @Test
    void tampered_signature_fails_verification() {
        QrPayloadSigner s = signer("test-secret-32-bytes-of-entropy-xx");
        String payload = s.sign("abc123token");
        // Flip the last char of the signature.
        char last = payload.charAt(payload.length() - 1);
        String tampered = payload.substring(0, payload.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertEquals(Optional.empty(), s.verify(tampered));
    }

    @Test
    void missing_version_prefix_is_rejected() {
        QrPayloadSigner s = signer("test-secret-32-bytes-of-entropy-xx");
        assertEquals(Optional.empty(), s.verify("imin0.abc.xyz"));
        assertEquals(Optional.empty(), s.verify("garbage"));
        assertEquals(Optional.empty(), s.verify(null));
    }

    @Test
    void different_secret_fails_verification() {
        QrPayloadSigner a = signer("test-secret-32-bytes-of-entropy-xx");
        QrPayloadSigner b = signer("OTHER-secret-32-bytes-of-entropy-x");
        String payload = a.sign("abc123token");
        assertEquals(Optional.empty(), b.verify(payload));
    }

    @Test
    void blank_secret_throws_at_construction() {
        TicketProperties p = new TicketProperties();
        p.setSigningSecret("");
        assertThrows(IllegalStateException.class, () -> new QrPayloadSigner(p));
    }
}
```

- [ ] **Step 5: Run the test — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=QrPayloadSignerTest
```

Expected: FAIL with "QrPayloadSigner cannot be resolved" or compile error.

- [ ] **Step 6: Implement `QrPayloadSigner`.**

```java
package com.imin.iminapi.service.ticket;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Signs and verifies tamper-evident QR payloads.
 *
 * <p>Format: {@code imin1.<ticketToken>.<hmacB64UrlNoPad>}, where the HMAC is
 * the first 16 bytes of HMAC-SHA256(secret, "v1|" + ticketToken).
 *
 * <p>The version prefix lets us rotate the signing scheme without re-issuing
 * every outstanding ticket — a v2 verifier would accept both during a window.
 */
@Component
public class QrPayloadSigner {

    private static final String VERSION = "imin1";
    private static final int HMAC_BYTES = 16;
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] key;

    public QrPayloadSigner(TicketProperties props) {
        if (props.getSigningSecret() == null || props.getSigningSecret().isBlank()) {
            throw new IllegalStateException(
                    "IMIN_TICKET_SIGNING_SECRET is required to sign/verify ticket QR payloads");
        }
        this.key = props.getSigningSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String ticketToken) {
        byte[] sig = mac(ticketToken);
        return VERSION + "." + ticketToken + "." + ENC.encodeToString(sig);
    }

    public Optional<String> verify(String payload) {
        if (payload == null) return Optional.empty();
        String[] parts = payload.split("\\.", 3);
        if (parts.length != 3) return Optional.empty();
        if (!VERSION.equals(parts[0])) return Optional.empty();
        String token = parts[1];
        byte[] presented;
        try {
            presented = DEC.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        byte[] expected = mac(token);
        if (!MessageDigest.isEqual(presented, expected)) return Optional.empty();
        return Optional.of(token);
    }

    private byte[] mac(String token) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] full = m.doFinal(("v1|" + token).getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(full, HMAC_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
```

- [ ] **Step 7: Run the test — expect pass.**

```bash
cd imin-api && ./mvnw test -Dtest=QrPayloadSignerTest
```

Expected: all 6 tests PASS.

- [ ] **Step 8: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/service/ticket/QrPayloadSigner.java src/main/java/com/imin/iminapi/service/ticket/TicketProperties.java src/test/java/com/imin/iminapi/service/ticket/QrPayloadSignerTest.java src/main/resources/application.yaml src/main/java/com/imin/iminapi/IminApiApplication.java
git commit -m "Add QrPayloadSigner with HMAC-SHA256 tamper-evidence

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6 — QrImageRenderer

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/QrImageRenderer.java`
- Create: `imin-api/src/test/java/com/imin/iminapi/service/ticket/QrImageRendererTest.java`

- [ ] **Step 1: Write the failing test.**

```java
package com.imin.iminapi.service.ticket;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class QrImageRendererTest {

    @Test
    void renders_decodable_png_at_requested_size() throws Exception {
        QrImageRenderer r = new QrImageRenderer();
        byte[] png = r.render("imin1.abc.xyz", 320);
        assertNotNull(png);
        assertTrue(png.length > 100, "png suspiciously small");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertEquals(320, img.getWidth());
        assertEquals(320, img.getHeight());
    }

    @Test
    void rejects_empty_payload() {
        QrImageRenderer r = new QrImageRenderer();
        assertThrows(IllegalArgumentException.class, () -> r.render("", 320));
        assertThrows(IllegalArgumentException.class, () -> r.render(null, 320));
    }
}
```

- [ ] **Step 2: Run the test — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=QrImageRendererTest
```

Expected: FAIL — class not found.

- [ ] **Step 3: Implement.**

```java
package com.imin.iminapi.service.ticket;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

@Component
public class QrImageRenderer {

    public byte[] render(String payload, int sizePx) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (sizePx <= 0) {
            throw new IllegalArgumentException("sizePx must be positive");
        }
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(
                    payload,
                    BarcodeFormat.QR_CODE,
                    sizePx, sizePx,
                    Map.of(
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                            EncodeHintType.MARGIN, 2));
            BufferedImage img = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < sizePx; y++) {
                for (int x = 0; x < sizePx; x++) {
                    img.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Failed to render QR PNG", e);
        }
    }
}
```

- [ ] **Step 4: Run the test — expect pass.**

```bash
cd imin-api && ./mvnw test -Dtest=QrImageRendererTest
```

Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/service/ticket/QrImageRenderer.java src/test/java/com/imin/iminapi/service/ticket/QrImageRendererTest.java
git commit -m "Add QrImageRenderer (zxing PNG)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7 — AsyncConfig (ticket email executor)

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/config/AsyncConfig.java`

- [ ] **Step 1: Write the config class.**

```java
package com.imin.iminapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated executor for post-issuance async work so a burst of Stripe
 * deliveries can't starve other @Async callers. Small pool because the
 * work is short and Resend tolerates parallelism.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "ticketEmailExecutor")
    public Executor ticketEmailExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("ticket-email-");
        exec.initialize();
        return exec;
    }
}
```

- [ ] **Step 2: Boot smoke test.**

```bash
cd imin-api && ./mvnw test -Dtest=IminApiApplicationTests
```

Expected: PASS.

- [ ] **Step 3: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/config/AsyncConfig.java
git commit -m "Add dedicated executor for ticket email dispatch

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8 — PaidCheckoutService.issuePaidOrder

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/TicketsIssuedEvent.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java`
- Create: `imin-api/src/test/java/com/imin/iminapi/service/ticket/PaidCheckoutServiceTest.java`

- [ ] **Step 1: Write the event record.**

```java
package com.imin.iminapi.service.ticket;

import java.util.UUID;

public record TicketsIssuedEvent(UUID orderId) {}
```

- [ ] **Step 2: Write the failing test.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.IminApiApplication;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.model.TicketTier;
import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.SessionCollection;
import com.stripe.model.checkout.Session;
import com.stripe.service.ChargeService;
import com.stripe.service.checkout.SessionService;
import com.stripe.services.checkout.CheckoutSessionListParams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = IminApiApplication.class)
class PaidCheckoutServiceTest {

    @Autowired PaidCheckoutService service;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired CapturedEvents captured;

    @MockBean StripeClient stripeClient;

    @Test
    @Transactional
    void issues_order_and_tickets_with_PI_id_idempotently() throws Exception {
        Event event = persistEvent();
        TicketTier tier = persistTier(event, "GA", 1500);

        PaymentIntent pi = pi("pi_test_1", 3000, "eur",
                Map.of("tier_id", tier.getId().toString(),
                       "qty", "2",
                       "event_id", event.getId().toString()));
        mockChargeEmail(pi, "buyer@example.com");
        mockSessionLookup(pi, "cs_test_1");

        service.issuePaidOrder(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_1").orElseThrow();
        assertEquals("buyer@example.com", order.getEmail());
        assertEquals("cs_test_1", order.getStripeSessionId());
        assertEquals(3000L, order.getTotalMinor());
        assertEquals("stripe", order.getPaymentMethod());
        assertEquals(event.getId(), order.getEventId());

        List<Ticket> issued = tickets.findByOrderIdOrderByCreatedAtAsc(order.getId());
        assertEquals(2, issued.size());
        issued.forEach(t -> {
            assertEquals(tier.getId(), t.getTierId());
            assertEquals("GA", t.getTierName());
            assertEquals("issued", t.getState());
        });

        // Retry: a second call with the same PI must not duplicate.
        service.issuePaidOrder(pi);
        assertEquals(1, orders.findAll().stream().filter(o ->
                "pi_test_1".equals(o.getStripePaymentIntentId())).count());
        assertEquals(2, tickets.findByOrderIdOrderByCreatedAtAsc(order.getId()).size());

        // The AFTER_COMMIT listener fires once we commit; assert in the
        // outer @Component captor (Spring's @TransactionalEventListener does
        // not fire inside the rollback-on-exit @Transactional test, so we
        // assert only that the inner code ran cleanly here).
    }

    private PaymentIntent pi(String id, long amount, String currency, Map<String, String> meta) {
        PaymentIntent p = new PaymentIntent();
        p.setId(id);
        p.setAmount(amount);
        p.setCurrency(currency);
        p.setMetadata(meta);
        p.setLatestCharge("ch_" + id);
        return p;
    }

    private void mockChargeEmail(PaymentIntent pi, String email) throws Exception {
        Charge c = new Charge();
        Charge.BillingDetails bd = new Charge.BillingDetails();
        bd.setEmail(email);
        c.setBillingDetails(bd);
        ChargeService charges = org.mockito.Mockito.mock(ChargeService.class);
        when(stripeClient.charges()).thenReturn(charges);
        when(charges.retrieve(eqOrNull(pi.getLatestCharge()))).thenReturn(c);
    }

    private void mockSessionLookup(PaymentIntent pi, String sessionId) throws Exception {
        Session s = new Session();
        s.setId(sessionId);
        SessionCollection coll = new SessionCollection();
        coll.setData(List.of(s));
        com.stripe.service.checkout.CheckoutService checkout =
                org.mockito.Mockito.mock(com.stripe.service.checkout.CheckoutService.class);
        SessionService sessions = org.mockito.Mockito.mock(SessionService.class);
        when(stripeClient.checkout()).thenReturn(checkout);
        when(checkout.sessions()).thenReturn(sessions);
        when(sessions.list(any())).thenReturn(coll);
    }

    private static String eqOrNull(String s) {
        return s == null ? org.mockito.ArgumentMatchers.isNull() : org.mockito.ArgumentMatchers.eq(s);
    }

    private Event persistEvent() { /* persist a minimal Event row; copy from FreeCheckoutServiceTest fixtures */ }
    private TicketTier persistTier(Event e, String name, int priceMinor) { /* same */ }

    @Component
    static class CapturedEvents {
        final List<TicketsIssuedEvent> received = new CopyOnWriteArrayList<>();
        @EventListener
        void on(TicketsIssuedEvent e) { received.add(e); }
    }
}
```

> ⚠ The two `persistEvent`/`persistTier` helper bodies are intentionally elided — replace them by copy-pasting the equivalent fixtures from the existing `FreeCheckoutServiceTest` (which already builds an Event + TicketTier row via the autowired repos). Keep test fixtures DRY: a shared `TestFixtures` util in `src/test/java/com/imin/iminapi/testsupport/` is acceptable if the same pattern shows up in three+ tests across this plan.

- [ ] **Step 3: Run the test — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=PaidCheckoutServiceTest
```

Expected: FAIL — `PaidCheckoutService` not found.

- [ ] **Step 4: Implement `PaidCheckoutService`.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionListParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PaidCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(PaidCheckoutService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final StripeClient stripeClient;
    private final ApplicationEventPublisher publisher;

    public PaidCheckoutService(OrderRepository orders,
                                TicketRepository tickets,
                                EventRepository events,
                                TicketTierRepository tiers,
                                StripeClient stripeClient,
                                ApplicationEventPublisher publisher) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.tiers = tiers;
        this.stripeClient = stripeClient;
        this.publisher = publisher;
    }

    /**
     * Idempotent. Caller (the webhook handler) is already inside a transaction;
     * this method joins it (default propagation REQUIRED).
     */
    public void issuePaidOrder(PaymentIntent pi) {
        if (pi == null || pi.getId() == null) {
            log.warn("issuePaidOrder called with null PI — skipping");
            return;
        }
        // Short-circuit: already issued.
        if (orders.findByStripePaymentIntentId(pi.getId()).isPresent()) {
            log.info("Order already exists for PaymentIntent {} — skipping (idempotent)", pi.getId());
            return;
        }

        Map<String, String> meta = pi.getMetadata();
        if (meta == null) {
            log.warn("PaymentIntent {} has no metadata — skipping issuance", pi.getId());
            return;
        }
        String tierIdRaw = meta.get("tier_id");
        String qtyRaw = meta.get("qty");
        String eventIdRaw = meta.get("event_id");
        if (tierIdRaw == null || qtyRaw == null || eventIdRaw == null) {
            log.warn("PaymentIntent {} missing required metadata (tier_id/qty/event_id) — skipping", pi.getId());
            return;
        }

        UUID tierId, eventId;
        int qty;
        try {
            tierId = UUID.fromString(tierIdRaw);
            eventId = UUID.fromString(eventIdRaw);
            qty = Integer.parseInt(qtyRaw);
        } catch (IllegalArgumentException e) {
            log.warn("PaymentIntent {} has malformed metadata: {}", pi.getId(), e.getMessage());
            return;
        }

        Event event = events.findById(eventId).orElseThrow(() ->
                new IllegalStateException("Event " + eventId + " for PI " + pi.getId() + " is missing"));
        TicketTier tier = tiers.findById(tierId).orElseThrow(() ->
                new IllegalStateException("Tier " + tierId + " for PI " + pi.getId() + " is missing"));

        String buyerEmail = resolveBuyerEmail(pi);
        if (buyerEmail == null) {
            throw new IllegalStateException("Could not resolve buyer email for PI " + pi.getId()
                    + "; webhook will be retried by Stripe");
        }

        String sessionId = resolveSessionId(pi);

        Order order = new Order();
        order.setToken(randomToken());
        order.setEventId(event.getId());
        order.setOrgId(event.getOrgId());
        order.setEmail(buyerEmail.trim().toLowerCase(Locale.ROOT));
        order.setTotalMinor(pi.getAmount() == null ? 0L : pi.getAmount());
        order.setCurrency(pi.getCurrency() == null ? event.getCurrency() : pi.getCurrency().toLowerCase(Locale.ROOT));
        order.setPaymentMethod("stripe");
        order.setStripePaymentIntentId(pi.getId());
        order.setStripeSessionId(sessionId);
        String promoIdRaw = meta.get("promo_id");
        if (promoIdRaw != null && !promoIdRaw.isBlank()) {
            try {
                order.setPromoCodeId(UUID.fromString(promoIdRaw));
            } catch (IllegalArgumentException ignored) {
                log.warn("Malformed promo_id on PI {}: {}", pi.getId(), promoIdRaw);
            }
        }

        try {
            orders.save(order);
        } catch (DataIntegrityViolationException dup) {
            // Race: another concurrent webhook delivery raced us between findByStripePaymentIntentId
            // and save. Treat as success — the other deliverer is finishing the work.
            log.info("PaymentIntent {} hit duplicate-key on Order insert — treating as success", pi.getId());
            return;
        }

        for (int i = 0; i < qty; i++) {
            Ticket t = new Ticket();
            t.setToken(randomToken());
            t.setOrderId(order.getId());
            t.setEventId(event.getId());
            t.setTierId(tier.getId());
            t.setTierName(tier.getName());
            t.setState("issued");
            tickets.save(t);
        }

        publisher.publishEvent(new TicketsIssuedEvent(order.getId()));
        log.info("Issued {} ticket(s) for PI {} → order {}", qty, pi.getId(), order.getId());
    }

    private String resolveBuyerEmail(PaymentIntent pi) {
        if (pi.getLatestCharge() != null) {
            try {
                Charge c = stripeClient.charges().retrieve(pi.getLatestCharge());
                if (c.getBillingDetails() != null && c.getBillingDetails().getEmail() != null) {
                    return c.getBillingDetails().getEmail();
                }
            } catch (StripeException e) {
                log.warn("Failed to retrieve charge {} for PI {}: {}",
                        pi.getLatestCharge(), pi.getId(), e.getMessage());
            }
        }
        // Fallback: list checkout sessions for the PI.
        try {
            var coll = stripeClient.checkout().sessions().list(
                    SessionListParams.builder().setPaymentIntent(pi.getId()).setLimit(1L).build());
            if (coll != null && coll.getData() != null && !coll.getData().isEmpty()) {
                Session s = coll.getData().get(0);
                if (s.getCustomerDetails() != null && s.getCustomerDetails().getEmail() != null) {
                    return s.getCustomerDetails().getEmail();
                }
                if (s.getCustomerEmail() != null) return s.getCustomerEmail();
            }
        } catch (StripeException e) {
            log.warn("Failed to list sessions for PI {}: {}", pi.getId(), e.getMessage());
        }
        return null;
    }

    private String resolveSessionId(PaymentIntent pi) {
        try {
            var coll = stripeClient.checkout().sessions().list(
                    SessionListParams.builder().setPaymentIntent(pi.getId()).setLimit(1L).build());
            if (coll != null && coll.getData() != null && !coll.getData().isEmpty()) {
                return coll.getData().get(0).getId();
            }
        } catch (StripeException e) {
            log.warn("Could not resolve Session for PI {}: {}", pi.getId(), e.getMessage());
        }
        return null;
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

> Note: `resolveBuyerEmail` and `resolveSessionId` both make a "list sessions for PI" Stripe call. In a follow-up commit (not in this task), collapse these to one shared call to avoid the duplicate round-trip on the happy path. For the first ship, correctness beats hot-path savings.

- [ ] **Step 5: Run the test — expect pass.**

```bash
cd imin-api && ./mvnw test -Dtest=PaidCheckoutServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java src/main/java/com/imin/iminapi/service/ticket/TicketsIssuedEvent.java src/test/java/com/imin/iminapi/service/ticket/PaidCheckoutServiceTest.java
git commit -m "Add PaidCheckoutService.issuePaidOrder (idempotent on PI id)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9 — Wire PaidCheckoutService into the webhook

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java`
- Modify: `imin-api/src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceTest.java`

- [ ] **Step 1: Add a failing test asserting issuance is called on PI.succeeded.**

```java
@Test
void payment_intent_succeeded_invokes_paid_checkout_service() throws Exception {
    String rawBody = stripeFixturePiSucceeded(/* pi id */ "pi_test_xyz",
            /* metadata */ Map.of("tier_id", tierId.toString(),
                                   "qty", "1",
                                   "event_id", eventId.toString()));
    String sig = stripeSig(rawBody, webhookSecret);

    service.handle(rawBody, sig);

    verify(paidCheckoutService).issuePaidOrder(argThat(pi -> "pi_test_xyz".equals(pi.getId())));
}
```

Add `@MockBean PaidCheckoutService paidCheckoutService;` and ensure `StripeWebhookServiceTest` already has the dedup mock from the in-flight commit; if not, mock it.

- [ ] **Step 2: Run the test — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=StripeWebhookServiceTest#payment_intent_succeeded_invokes_paid_checkout_service
```

Expected: FAIL — `verify(paidCheckoutService).issuePaidOrder(...)` never called.

- [ ] **Step 3: Wire it in.**

In `StripeWebhookService`:

```java
private final PaidCheckoutService paidCheckoutService;

// add to constructor: PaidCheckoutService paidCheckoutService, assign

private void onPaymentIntentSucceeded(com.stripe.model.Event event) {
    PaymentIntent pi = extractPaymentIntent(event, "payment_intent.succeeded");
    if (pi == null) return;

    Map<String, String> meta = pi.getMetadata();
    TierMeta tierMeta = parseTierMeta(meta, pi.getId());
    if (tierMeta != null) {
        inventoryService.confirmSold(tierMeta.tierId, tierMeta.qty);
        log.info("Confirmed sold qty={} on tier {} after payment_intent {}",
                tierMeta.qty, tierMeta.tierId, pi.getId());
    }

    if (meta != null) {
        String promoIdRaw = meta.get("promo_id");
        if (promoIdRaw != null && !promoIdRaw.isBlank()) {
            try {
                UUID promoId = UUID.fromString(promoIdRaw);
                int rows = promos.incrementUsedCount(promoId);
                if (rows == 0) {
                    log.warn("Promo code {} not found when handling payment_intent {} — skipped",
                            promoId, pi.getId());
                } else {
                    log.info("Incremented usedCount on promo {} after payment_intent {}",
                            promoId, pi.getId());
                }
            } catch (IllegalArgumentException e) {
                log.warn("PaymentIntent {} has malformed promo_id metadata: {}",
                        pi.getId(), promoIdRaw);
            }
        }
    }

    // Issue the order + tickets. Joins the existing handleV1 transaction.
    paidCheckoutService.issuePaidOrder(pi);
}
```

- [ ] **Step 4: Run all webhook tests.**

```bash
cd imin-api && ./mvnw test -Dtest=StripeWebhookServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceTest.java
git commit -m "Wire PaidCheckoutService.issuePaidOrder into PI.succeeded handler

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10 — Ticket-issued email

**Files:**
- Create: `imin-api/src/main/resources/email-templates/ticket-issued.html`
- Create: `imin-api/src/main/resources/email-templates/ticket-issued.txt`
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/TicketIssuanceEmailer.java`
- Create: `imin-api/src/test/java/com/imin/iminapi/service/ticket/TicketIssuanceEmailerTest.java`

- [ ] **Step 1: Write the HTML template.**

`ticket-issued.html`:

```html
<!doctype html>
<html><body style="font-family: -apple-system, BlinkMacSystemFont, sans-serif; max-width: 560px; margin: 0 auto; padding: 24px;">
  <h2 style="margin-top: 0;">You're in for {{eventName}}.</h2>
  <p style="color: #444;">{{eventWhen}} · {{eventWhere}}</p>

  <div style="margin: 24px 0; padding: 16px; border: 1px solid #e5e5e5; border-radius: 8px;">
    {{ticketBlocks}}
  </div>

  <p><a href="{{orderUrl}}" style="color: #0a66c2;">View your order online</a></p>
  <p style="color: #888; font-size: 13px;">Lost this email? <a href="{{recoverUrl}}" style="color: #0a66c2;">Recover your tickets</a>.</p>
</body></html>
```

`ticket-issued.txt`:

```
You're in for {{eventName}}.
{{eventWhen}} · {{eventWhere}}

{{ticketBlocksText}}

View your order online: {{orderUrl}}
Lost this email? Recover your tickets: {{recoverUrl}}
```

> `{{ticketBlocks}}` is a single pre-rendered substring assembled by `TicketIssuanceEmailer` — the existing `EmailTemplateRenderer` only supports single-placeholder substitution, no loops. We pre-render the per-ticket HTML in Java and inject the result as one variable (already HTML-escaped per-field by the assembler).

- [ ] **Step 2: Write the failing test.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketIssuanceEmailerTest {

    @Test
    void renders_email_with_per_ticket_qr_links_and_recover_url() {
        EmailService email = mock(EmailService.class);
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();
        OrderRepository orders = mock(OrderRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        EventRepository events = mock(EventRepository.class);
        EmailProperties emailProps = new EmailProperties();
        emailProps.setAppBaseUrl("https://imin.wtf");
        AppleWalletPassService wallet = mock(AppleWalletPassService.class);
        when(wallet.isConfigured()).thenReturn(true);

        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setToken("ORDER_TOK");
        order.setEventId(eventId);
        order.setEmail("buyer@example.com");

        Event event = new Event();
        event.setId(eventId);
        event.setName("Saturn Night");
        event.setStartsAt(java.time.OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant());
        event.setTimezone("Europe/Paris");
        event.setVenueName("Le Petit Bain");
        event.setVenueCity("Paris");

        Ticket t1 = new Ticket();
        t1.setToken("TKT_A");
        t1.setTierName("GA");
        Ticket t2 = new Ticket();
        t2.setToken("TKT_B");
        t2.setTierName("GA");

        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tickets.findByOrderIdOrderByCreatedAtAsc(orderId)).thenReturn(List.of(t1, t2));

        TicketIssuanceEmailer emailer = new TicketIssuanceEmailer(orders, tickets, events, email, renderer, emailProps, wallet);
        emailer.onTicketsIssued(new TicketsIssuedEvent(orderId));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email).send(eq("buyer@example.com"), subject.capture(), html.capture(), text.capture());

        assertTrue(subject.getValue().contains("Saturn Night"));
        // Per-ticket QR + wallet link in HTML
        assertTrue(html.getValue().contains("/api/v1/public/tickets/TKT_A/qr.png"));
        assertTrue(html.getValue().contains("/api/v1/public/tickets/TKT_B/qr.png"));
        assertTrue(html.getValue().contains("/api/v1/public/tickets/TKT_A/apple-wallet.pkpass"));
        // Order link in HTML
        assertTrue(html.getValue().contains("/order/ORDER_TOK"));
        // Recover link
        assertTrue(html.getValue().contains("/recover"));
        // Plain-text shows the same URLs
        assertTrue(text.getValue().contains("/order/ORDER_TOK"));
    }
}
```

- [ ] **Step 3: Run the test — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=TicketIssuanceEmailerTest
```

Expected: FAIL — class not defined.

- [ ] **Step 4: Implement `TicketIssuanceEmailer`.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TicketIssuanceEmailer {

    private static final Logger log = LoggerFactory.getLogger(TicketIssuanceEmailer.class);

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final AppleWalletPassService wallet;

    public TicketIssuanceEmailer(OrderRepository orders,
                                  TicketRepository tickets,
                                  EventRepository events,
                                  EmailService email,
                                  EmailTemplateRenderer renderer,
                                  EmailProperties emailProps,
                                  AppleWalletPassService wallet) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.wallet = wallet;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onTicketsIssued(TicketsIssuedEvent evt) {
        try {
            send(evt.orderId());
        } catch (Exception e) {
            log.warn("Ticket issuance email failed for order {}: {}", evt.orderId(), e.getMessage(), e);
        }
    }

    void send(java.util.UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        Event event = events.findById(order.getEventId()).orElseThrow();
        List<Ticket> issued = tickets.findByOrderIdOrderByCreatedAtAsc(order.getId());
        if (issued.isEmpty()) {
            log.warn("Order {} has no tickets — skipping email", orderId);
            return;
        }

        String base = baseUrl();
        String orderUrl = base + "/order/" + order.getToken();
        String recoverUrl = base + "/recover";
        String whenText = formatWhen(event);
        String whereText = formatWhere(event);

        StringBuilder htmlBlocks = new StringBuilder();
        StringBuilder textBlocks = new StringBuilder();
        boolean walletOn = wallet.isConfigured();
        for (int i = 0; i < issued.size(); i++) {
            Ticket t = issued.get(i);
            String qrUrl = base + "/api/v1/public/tickets/" + t.getToken() + "/qr.png";
            String ticketUrl = base + "/tickets/" + t.getToken();
            String walletUrl = base + "/api/v1/public/tickets/" + t.getToken() + "/apple-wallet.pkpass";
            String label = "Ticket " + (i + 1) + " of " + issued.size() + " — " + escape(t.getTierName());

            htmlBlocks.append("<div style=\"margin-bottom: 24px;\">")
                    .append("<div style=\"font-weight: 600; margin-bottom: 8px;\">").append(label).append("</div>")
                    .append("<img src=\"").append(qrUrl).append("\" alt=\"QR\" style=\"display:block; width: 220px; height: 220px;\"/>")
                    .append("<p style=\"margin: 8px 0;\"><a href=\"").append(ticketUrl).append("\">Open this ticket</a></p>");
            if (walletOn) {
                htmlBlocks.append("<p style=\"margin: 8px 0;\"><a href=\"").append(walletUrl).append("\">Add to Apple Wallet</a></p>");
            }
            htmlBlocks.append("</div>");

            textBlocks.append(label).append('\n')
                    .append("  Open: ").append(ticketUrl).append('\n');
            if (walletOn) {
                textBlocks.append("  Apple Wallet: ").append(walletUrl).append('\n');
            }
            textBlocks.append('\n');
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", event.getName() == null ? "" : event.getName());
        values.put("eventWhen", whenText);
        values.put("eventWhere", whereText);
        values.put("ticketBlocks", htmlBlocks.toString());      // raw HTML — already per-field escaped above
        values.put("ticketBlocksText", textBlocks.toString());
        values.put("orderUrl", orderUrl);
        values.put("recoverUrl", recoverUrl);

        // The renderer escapes every value when emitting HTML. We sidestep that for the
        // pre-assembled blocks by post-processing: rendering the template once with empty
        // strings for the block keys, then injecting the raw HTML. Simpler to assemble inline:
        EmailTemplateRenderer.Rendered r = renderer.render("ticket-issued",
                Map.of(
                        "eventName", values.get("eventName"),
                        "eventWhen", values.get("eventWhen"),
                        "eventWhere", values.get("eventWhere"),
                        "ticketBlocks", "__TICKETS__",
                        "ticketBlocksText", "__TICKETS__",
                        "orderUrl", orderUrl,
                        "recoverUrl", recoverUrl));
        String html = r.html().replace("__TICKETS__", htmlBlocks.toString());
        String text = r.text().replace("__TICKETS__", textBlocks.toString());

        String subject = issued.size() == 1
                ? "Your ticket for " + event.getName()
                : "Your tickets for " + event.getName();

        email.send(order.getEmail(), subject, html, text);
        log.info("Sent issuance email for order {} ({} tickets) to {}",
                order.getId(), issued.size(), order.getEmail());
    }

    private String baseUrl() {
        String base = emailProps.getAppBaseUrl();
        if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base == null ? "" : base;
    }

    private String formatWhen(Event event) {
        if (event.getStartsAt() == null) return "";
        ZoneId zone = event.getTimezone() == null ? ZoneId.systemDefault() : ZoneId.of(event.getTimezone());
        return DateTimeFormatter.ofPattern("EEEE, d LLL yyyy · HH:mm")
                .withZone(zone)
                .format(event.getStartsAt());
    }

    private String formatWhere(Event event) {
        String name = event.getVenueName();
        String city = event.getVenueCity();
        if (name != null && city != null) return name + ", " + city;
        if (name != null) return name;
        if (city != null) return city;
        return "";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
```

- [ ] **Step 5: Run the test — expect pass.**

```bash
cd imin-api && ./mvnw test -Dtest=TicketIssuanceEmailerTest
```

Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/service/ticket/TicketIssuanceEmailer.java src/main/resources/email-templates/ticket-issued.html src/main/resources/email-templates/ticket-issued.txt src/test/java/com/imin/iminapi/service/ticket/TicketIssuanceEmailerTest.java
git commit -m "Add async TicketIssuanceEmailer (Resend, hyperlinked QR)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11 — `/qr.png` + `/apple-wallet.pkpass` (asset endpoints + AppleWalletPassService stub)

> Apple Wallet is implemented as a "graceful stub" first so the rest of the pipeline can ship without certs. The full pkpass signer is added in Task 12.

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetController.java`
- Create: `imin-api/src/test/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetControllerTest.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/security/SecurityConfig.java`

- [ ] **Step 1: Stub `AppleWalletPassService`.**

```java
package com.imin.iminapi.service.ticket;

import org.springframework.stereotype.Service;

/**
 * Apple Wallet pass generation. Returns false from {@link #isConfigured()}
 * until Task 12 wires in jpasskit + Apple Pass Type ID cert env vars.
 */
@Service
public class AppleWalletPassService {

    public boolean isConfigured() {
        return false;
    }

    public byte[] generatePass(String ticketToken) {
        throw new IllegalStateException("Apple Wallet not configured");
    }
}
```

- [ ] **Step 2: Write the failing controller test.**

```java
package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.IminApiApplication;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = IminApiApplication.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PublicTicketAssetControllerTest {

    @Autowired MockMvc mvc;
    @Autowired TicketRepository tickets;

    @Test
    void qr_png_returns_decodable_image() throws Exception {
        Ticket t = persistTicket();
        byte[] bytes = mvc.perform(get("/api/v1/public/tickets/" + t.getToken() + "/qr.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(img);
        assertTrue(img.getWidth() >= 256);
    }

    @Test
    void qr_png_404_on_unknown_token() throws Exception {
        mvc.perform(get("/api/v1/public/tickets/no-such-token/qr.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void apple_wallet_503_when_unconfigured() throws Exception {
        Ticket t = persistTicket();
        mvc.perform(get("/api/v1/public/tickets/" + t.getToken() + "/apple-wallet.pkpass"))
                .andExpect(status().isServiceUnavailable());
    }

    private Ticket persistTicket() {
        Ticket t = new Ticket();
        t.setToken("TKT_" + UUID.randomUUID());
        t.setOrderId(UUID.randomUUID());
        t.setEventId(UUID.randomUUID());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setState("issued");
        return tickets.save(t);
    }
}
```

- [ ] **Step 3: Run the test — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=PublicTicketAssetControllerTest
```

Expected: 404 on all three (endpoints don't exist yet).

- [ ] **Step 4: Implement the controller.**

```java
package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.QrImageRenderer;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicTicketAssetController {

    private final TicketRepository tickets;
    private final QrPayloadSigner signer;
    private final QrImageRenderer renderer;
    private final AppleWalletPassService wallet;

    public PublicTicketAssetController(TicketRepository tickets,
                                        QrPayloadSigner signer,
                                        QrImageRenderer renderer,
                                        AppleWalletPassService wallet) {
        this.tickets = tickets;
        this.signer = signer;
        this.renderer = renderer;
        this.wallet = wallet;
    }

    @GetMapping(value = "/api/v1/public/tickets/{token}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrPng(@PathVariable String token) {
        Ticket t = tickets.findByToken(token).orElseThrow(() -> ApiException.notFound("Ticket"));
        byte[] png = renderer.render(signer.sign(t.getToken()), 320);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    @GetMapping("/api/v1/public/tickets/{token}/apple-wallet.pkpass")
    public ResponseEntity<byte[]> applePass(@PathVariable String token) {
        Ticket t = tickets.findByToken(token).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (!wallet.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        byte[] pkpass = wallet.generatePass(t.getToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.apple.pkpass")
                .body(pkpass);
    }
}
```

- [ ] **Step 5: Permit the new public paths in `SecurityConfig`.**

Open `SecurityConfig.java`, find the `permitAll` chain for `/api/v1/public/**` (already exists for orders/tickets). The whole `public` namespace is already wildcarded; verify by reading `SecurityConfig.java`. If not, add `"/api/v1/public/tickets/*/qr.png"` and `"/api/v1/public/tickets/*/apple-wallet.pkpass"` explicitly.

- [ ] **Step 6: Run the test — expect pass.**

```bash
cd imin-api && ./mvnw test -Dtest=PublicTicketAssetControllerTest
```

Expected: PASS.

- [ ] **Step 7: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java src/main/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetController.java src/test/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetControllerTest.java
git commit -m "Add /qr.png and /apple-wallet.pkpass endpoints (wallet stubbed)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12 — Apple Wallet pass generation (jpasskit)

**Files:**
- Modify: `imin-api/pom.xml`
- Replace: `imin-api/src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/AppleWalletProperties.java`
- Create: `imin-api/src/main/resources/wallet/icon.png`, `icon@2x.png`, `icon@3x.png`, `logo.png`, `logo@2x.png`, `logo@3x.png`
- Create: `imin-api/src/test/java/com/imin/iminapi/service/ticket/AppleWalletPassServiceTest.java`

- [ ] **Step 1: Add dependency.**

In `pom.xml`, inside `<dependencies>`:

```xml
<dependency>
    <groupId>de.brendamour</groupId>
    <artifactId>jpasskit</artifactId>
    <version>0.4.0</version>
</dependency>
```

(Check Maven Central for the latest stable version before pinning — bump if there's a newer release.)

- [ ] **Step 2: Bundle wallet artwork.**

Copy the existing imin mark from `imin-public/public/icon.svg` (rasterized at the required sizes) into `imin-api/src/main/resources/wallet/`:
- `icon.png` 29×29
- `icon@2x.png` 58×58
- `icon@3x.png` 87×87
- `logo.png` 160×50
- `logo@2x.png` 320×100
- `logo@3x.png` 480×150

Use `convert` (ImageMagick) or any rasterizer. If only a one-off PNG is available, scale it; Apple is lenient on resolution but the filenames are fixed.

- [ ] **Step 3: `AppleWalletProperties`.**

```java
package com.imin.iminapi.service.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imin.apple-wallet")
public class AppleWalletProperties {
    private String passTypeId = "";
    private String teamId = "";
    private String certP12Base64 = "";
    private String certPassword = "";
    private String wwdrPemBase64 = "";

    public String getPassTypeId() { return passTypeId; }
    public void setPassTypeId(String v) { this.passTypeId = v; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String v) { this.teamId = v; }
    public String getCertP12Base64() { return certP12Base64; }
    public void setCertP12Base64(String v) { this.certP12Base64 = v; }
    public String getCertPassword() { return certPassword; }
    public void setCertPassword(String v) { this.certPassword = v; }
    public String getWwdrPemBase64() { return wwdrPemBase64; }
    public void setWwdrPemBase64(String v) { this.wwdrPemBase64 = v; }

    public boolean fullyConfigured() {
        return !passTypeId.isBlank()
                && !teamId.isBlank()
                && !certP12Base64.isBlank()
                && !certPassword.isBlank()
                && !wwdrPemBase64.isBlank();
    }
}
```

Add binding to `application.yaml`:

```yaml
imin:
  apple-wallet:
    pass-type-id: ${APPLE_WALLET_PASS_TYPE_ID:}
    team-id: ${APPLE_WALLET_TEAM_ID:}
    cert-p12-base64: ${APPLE_WALLET_CERT_P12_BASE64:}
    cert-password: ${APPLE_WALLET_CERT_PASSWORD:}
    wwdr-pem-base64: ${APPLE_WALLET_WWDR_PEM_BASE64:}
```

Register `AppleWalletProperties.class` on `IminApiApplication`'s `@EnableConfigurationProperties` list.

- [ ] **Step 4: Write the failing test.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppleWalletPassServiceTest {

    @Test
    void unconfigured_returns_false_and_throws_on_generate() {
        AppleWalletProperties props = new AppleWalletProperties();
        AppleWalletPassService svc = new AppleWalletPassService(props,
                mock(TicketRepository.class), mock(OrderRepository.class), mock(EventRepository.class),
                mock(QrPayloadSigner.class));
        assertFalse(svc.isConfigured());
        assertThrows(IllegalStateException.class, () -> svc.generatePass("any-token"));
    }

    @Test
    void configured_pkpass_is_a_zip_containing_pass_json_and_signature() throws Exception {
        // Use test fixture certs from src/test/resources/wallet-test/...
        AppleWalletProperties props = new AppleWalletProperties();
        props.setPassTypeId("pass.test.imin");
        props.setTeamId("TESTTEAMID");
        props.setCertP12Base64(loadFixtureBase64("wallet-test/test.p12"));
        props.setCertPassword("test");
        props.setWwdrPemBase64(loadFixtureBase64("wallet-test/wwdr.pem"));

        TicketRepository tickets = mock(TicketRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        EventRepository events = mock(EventRepository.class);
        QrPayloadSigner signer = mock(QrPayloadSigner.class);
        when(signer.sign("TKT_X")).thenReturn("imin1.TKT_X.SIG");

        Ticket t = new Ticket();
        t.setToken("TKT_X");
        t.setOrderId(UUID.randomUUID());
        t.setEventId(UUID.randomUUID());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        when(tickets.findByToken("TKT_X")).thenReturn(Optional.of(t));

        Order o = new Order();
        o.setId(t.getOrderId());
        o.setEventId(t.getEventId());
        o.setOrgId(UUID.randomUUID());
        when(orders.findById(t.getOrderId())).thenReturn(Optional.of(o));

        Event e = new Event();
        e.setId(t.getEventId());
        e.setName("Saturn Night");
        e.setStartsAt(java.time.OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant());
        e.setTimezone("Europe/Paris");
        e.setVenueName("Le Petit Bain");
        e.setVenueCity("Paris");
        when(events.findById(t.getEventId())).thenReturn(Optional.of(e));

        AppleWalletPassService svc = new AppleWalletPassService(props, tickets, orders, events, signer);
        assertTrue(svc.isConfigured());
        byte[] pkpass = svc.generatePass("TKT_X");
        assertNotNull(pkpass);

        // .pkpass is a ZIP; assert key entries.
        boolean sawPassJson = false, sawSignature = false, sawManifest = false;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(pkpass))) {
            var entry = z.getNextEntry();
            while (entry != null) {
                if ("pass.json".equals(entry.getName())) sawPassJson = true;
                if ("signature".equals(entry.getName())) sawSignature = true;
                if ("manifest.json".equals(entry.getName())) sawManifest = true;
                entry = z.getNextEntry();
            }
        }
        assertTrue(sawPassJson);
        assertTrue(sawSignature);
        assertTrue(sawManifest);
    }

    private static String loadFixtureBase64(String resource) throws Exception {
        try (var in = AppleWalletPassServiceTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "Test fixture missing: " + resource);
            return java.util.Base64.getEncoder().encodeToString(in.readAllBytes());
        }
    }
}
```

> ⚠ The second test depends on `src/test/resources/wallet-test/test.p12` (self-signed) and `wallet-test/wwdr.pem` (Apple WWDR cert). To generate the self-signed: `openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 -subj "/CN=imin-test"; openssl pkcs12 -export -inkey key.pem -in cert.pem -out test.p12 -password pass:test`. WWDR cert is downloadable from Apple's developer site (publicly available). Check both into `src/test/resources/wallet-test/`. If the test environment can't host these, mark the configured-path test `@Disabled("requires wallet test fixtures")` and rely on the unconfigured-path test only.

- [ ] **Step 5: Implement `AppleWalletPassService` (full).**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import de.brendamour.jpasskit.passes.PKEventTicket;
import de.brendamour.jpasskit.signing.PKFileBasedSigningUtil;
import de.brendamour.jpasskit.signing.PKInMemorySigningUtil;
import de.brendamour.jpasskit.signing.PKPassTemplateInMemory;
import de.brendamour.jpasskit.signing.PKSigningInformation;
import de.brendamour.jpasskit.signing.PKSigningInformationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

@Service
public class AppleWalletPassService {

    private static final Logger log = LoggerFactory.getLogger(AppleWalletPassService.class);

    private final AppleWalletProperties props;
    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final EventRepository events;
    private final QrPayloadSigner signer;

    public AppleWalletPassService(AppleWalletProperties props,
                                   TicketRepository tickets,
                                   OrderRepository orders,
                                   EventRepository events,
                                   QrPayloadSigner signer) {
        this.props = props;
        this.tickets = tickets;
        this.orders = orders;
        this.events = events;
        this.signer = signer;
    }

    public boolean isConfigured() {
        return props.fullyConfigured();
    }

    public byte[] generatePass(String ticketToken) {
        if (!isConfigured()) {
            throw new IllegalStateException("Apple Wallet not configured");
        }
        Ticket t = tickets.findByToken(ticketToken).orElseThrow();
        Order order = orders.findById(t.getOrderId()).orElseThrow();
        Event event = events.findById(t.getEventId()).orElseThrow();

        PKEventTicket ticketStyle = PKEventTicket.builder()
                .primaryField(PKField.builder().key("event").label("Event").value(event.getName()).build())
                .secondaryField(PKField.builder().key("when").label("Date")
                        .value(formatWhen(event)).build())
                .secondaryField(PKField.builder().key("where").label("Venue")
                        .value(formatWhere(event)).build())
                .auxiliaryField(PKField.builder().key("tier").label("Tier").value(t.getTierName()).build())
                .build();

        String qrPayload = signer.sign(t.getToken());

        PKPass pass = PKPass.builder()
                .passTypeIdentifier(props.getPassTypeId())
                .teamIdentifier(props.getTeamId())
                .serialNumber(t.getToken())
                .organizationName("imin")
                .description(event.getName() + " — " + t.getTierName())
                .barcodeBuilder(PKBarcode.builder()
                        .format(PKBarcodeFormat.PKBarcodeFormatQR)
                        .message(qrPayload)
                        .messageEncoding("iso-8859-1")
                        .altText(t.getToken()))
                .eventTicket(ticketStyle)
                .build();

        try {
            PKPassTemplateInMemory template = new PKPassTemplateInMemory();
            template.addFile(PKPassTemplateInMemory.PK_ICON, readResource("wallet/icon.png"));
            template.addFile(PKPassTemplateInMemory.PK_ICON_2X, readResource("wallet/icon@2x.png"));
            template.addFile(PKPassTemplateInMemory.PK_ICON_3X, readResource("wallet/icon@3x.png"));
            template.addFile(PKPassTemplateInMemory.PK_LOGO, readResource("wallet/logo.png"));
            template.addFile(PKPassTemplateInMemory.PK_LOGO_2X, readResource("wallet/logo@2x.png"));
            template.addFile(PKPassTemplateInMemory.PK_LOGO_3X, readResource("wallet/logo@3x.png"));

            PKSigningInformation signing = new PKSigningInformationUtil().loadSigningInformation(
                    new ByteArrayInputStream(Base64.getDecoder().decode(props.getCertP12Base64())),
                    props.getCertPassword(),
                    new ByteArrayInputStream(Base64.getDecoder().decode(props.getWwdrPemBase64())));

            return new PKInMemorySigningUtil().createSignedAndZippedPkPassArchive(pass, template, signing);
        } catch (Exception e) {
            log.error("Failed to build Apple Wallet pass for token {}: {}", ticketToken, e.getMessage(), e);
            throw new IllegalStateException("Failed to build Apple Wallet pass", e);
        }
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static String formatWhen(Event e) {
        if (e.getStartsAt() == null) return "";
        ZoneId zone = e.getTimezone() == null ? ZoneId.systemDefault() : ZoneId.of(e.getTimezone());
        return DateTimeFormatter.ofPattern("EEE d LLL · HH:mm")
                .withZone(zone)
                .format(e.getStartsAt());
    }

    private static String formatWhere(Event e) {
        if (e.getVenueName() != null && e.getVenueCity() != null) return e.getVenueName() + ", " + e.getVenueCity();
        if (e.getVenueName() != null) return e.getVenueName();
        if (e.getVenueCity() != null) return e.getVenueCity();
        return "";
    }
}
```

- [ ] **Step 6: Run.**

```bash
cd imin-api && ./mvnw test -Dtest=AppleWalletPassServiceTest
```

Expected: unconfigured test PASS. Configured test PASS if wallet-test fixtures are checked in, otherwise the `@Disabled` marker keeps CI green.

- [ ] **Step 7: Commit.**

```bash
cd imin-api && git add pom.xml src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java src/main/java/com/imin/iminapi/service/ticket/AppleWalletProperties.java src/main/resources/wallet src/main/resources/application.yaml src/main/java/com/imin/iminapi/IminApiApplication.java src/test/java/com/imin/iminapi/service/ticket/AppleWalletPassServiceTest.java src/test/resources/wallet-test
git commit -m "Implement Apple Wallet .pkpass generation via jpasskit

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13 — Checkout status endpoint + success-page wiring

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/CheckoutStatusService.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/controller/publicapi/PublicCheckoutController.java`
- Create: `imin-api/src/test/java/com/imin/iminapi/service/ticket/CheckoutStatusServiceTest.java`

- [ ] **Step 1: Write the failing test.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckoutStatusServiceTest {

    @Test
    void ready_when_order_present() {
        OrderRepository orders = mock(OrderRepository.class);
        Order o = new Order(); o.setId(UUID.randomUUID()); o.setToken("ORDTOK");
        when(orders.findByStripeSessionId("cs_x")).thenReturn(Optional.of(o));

        CheckoutStatusService s = new CheckoutStatusService(orders);
        CheckoutStatusService.Result r = s.statusFor("cs_x");
        assertEquals(CheckoutStatusService.Status.READY, r.status());
        assertEquals("ORDTOK", r.orderToken());
    }

    @Test
    void pending_when_no_order_yet() {
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findByStripeSessionId("cs_pending")).thenReturn(Optional.empty());

        CheckoutStatusService s = new CheckoutStatusService(orders);
        CheckoutStatusService.Result r = s.statusFor("cs_pending");
        assertEquals(CheckoutStatusService.Status.PENDING, r.status());
        assertNull(r.orderToken());
    }
}
```

- [ ] **Step 2: Run — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=CheckoutStatusServiceTest
```

Expected: FAIL — class missing.

- [ ] **Step 3: Implement.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CheckoutStatusService {

    public enum Status { READY, PENDING, FAILED }

    public record Result(Status status, String orderToken) {}

    private final OrderRepository orders;

    public CheckoutStatusService(OrderRepository orders) {
        this.orders = orders;
    }

    public Result statusFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new Result(Status.PENDING, null);
        }
        Optional<Order> o = orders.findByStripeSessionId(sessionId);
        return o.map(order -> new Result(Status.READY, order.getToken()))
                .orElse(new Result(Status.PENDING, null));
    }
}
```

- [ ] **Step 4: Add the controller.**

```java
package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.service.ticket.CheckoutStatusService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PublicCheckoutController {

    private final CheckoutStatusService statusService;

    public PublicCheckoutController(CheckoutStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/api/v1/public/checkout/{sessionId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String sessionId) {
        CheckoutStatusService.Result r = statusService.statusFor(sessionId);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", r.status().name().toLowerCase());
        if (r.orderToken() != null) body.put("orderToken", r.orderToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}
```

- [ ] **Step 5: Run all checkout-related tests.**

```bash
cd imin-api && ./mvnw test -Dtest='*CheckoutStatusService*,*PublicCheckoutController*'
```

Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/service/ticket/CheckoutStatusService.java src/main/java/com/imin/iminapi/controller/publicapi/PublicCheckoutController.java src/test/java/com/imin/iminapi/service/ticket/CheckoutStatusServiceTest.java
git commit -m "Add /api/v1/public/checkout/{sessionId} for success-page redirect

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 14 — Order recovery

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/model/OrderRecoveryAttempt.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/repository/OrderRecoveryAttemptRepository.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/OrderRecoveryService.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/controller/publicapi/PublicRecoveryController.java`
- Create: `imin-api/src/main/resources/email-templates/order-recovery.html`, `.txt`
- Create: `imin-api/src/test/java/com/imin/iminapi/service/ticket/OrderRecoveryServiceTest.java`

- [ ] **Step 1: Entity + repo.**

```java
package com.imin.iminapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_recovery_attempts")
@Getter @Setter
public class OrderRecoveryAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "ip_hash", nullable = false, length = 64)
    private String ipHash;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt = Instant.now();
}
```

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.OrderRecoveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface OrderRecoveryAttemptRepository extends JpaRepository<OrderRecoveryAttempt, UUID> {
    long countByEmailAndAttemptedAtAfter(String email, Instant cutoff);
    long countByIpHashAndAttemptedAtAfter(String ipHash, Instant cutoff);
}
```

- [ ] **Step 2: Failing test.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderRecoveryServiceTest {

    private OrderRecoveryService build(OrderRepository orders, EmailService email,
                                        OrderRecoveryAttemptRepository attempts) {
        EmailProperties ep = new EmailProperties(); ep.setAppBaseUrl("https://imin.wtf");
        TicketProperties tp = new TicketProperties();
        tp.setSigningSecret("x".repeat(32));
        tp.setRecoveryMaxPerHour(5);
        tp.setRecoveryWindowDays(90);
        return new OrderRecoveryService(orders, email, new EmailTemplateRenderer(), ep, tp, attempts);
    }

    @Test
    void sends_email_when_orders_match() {
        OrderRepository orders = mock(OrderRepository.class);
        EmailService email = mock(EmailService.class);
        OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);
        when(attempts.countByEmailAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);
        when(attempts.countByIpHashAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);

        Order o = new Order();
        o.setId(UUID.randomUUID()); o.setToken("ORDTOK"); o.setEmail("buyer@example.com");
        o.setEventId(UUID.randomUUID());
        o.setCreatedAt(Instant.now());
        when(orders.findRecentForRecovery(eq("buyer@example.com"), isNull(), any()))
                .thenReturn(List.of(o));

        OrderRecoveryService svc = build(orders, email, attempts);
        svc.requestRecovery("Buyer@Example.com ", null, "1.2.3.4");

        verify(email).send(eq("buyer@example.com"), contains("Recover"), contains("/order/ORDTOK"), anyString());
        verify(attempts).save(any());
    }

    @Test
    void silent_when_no_matches() {
        OrderRepository orders = mock(OrderRepository.class);
        EmailService email = mock(EmailService.class);
        OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);
        when(attempts.countByEmailAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);
        when(attempts.countByIpHashAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);
        when(orders.findRecentForRecovery(anyString(), any(), any())).thenReturn(List.of());

        OrderRecoveryService svc = build(orders, email, attempts);
        svc.requestRecovery("nobody@example.com", null, "1.2.3.4");

        verify(email, never()).send(any(), any(), any(), any());
        verify(attempts).save(any()); // attempt is logged regardless
    }

    @Test
    void rate_limited_by_email() {
        OrderRepository orders = mock(OrderRepository.class);
        EmailService email = mock(EmailService.class);
        OrderRecoveryAttemptRepository attempts = mock(OrderRecoveryAttemptRepository.class);
        when(attempts.countByEmailAndAttemptedAtAfter(eq("buyer@example.com"), any())).thenReturn(5L);
        when(attempts.countByIpHashAndAttemptedAtAfter(anyString(), any())).thenReturn(0L);

        OrderRecoveryService svc = build(orders, email, attempts);
        svc.requestRecovery("buyer@example.com", null, "1.2.3.4");

        verify(orders, never()).findRecentForRecovery(any(), any(), any());
        verify(email, never()).send(any(), any(), any(), any());
        verify(attempts).save(any()); // attempt still logged
    }
}
```

- [ ] **Step 3: Run — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=OrderRecoveryServiceTest
```

Expected: FAIL — class missing.

- [ ] **Step 4: Implement the service.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.OrderRecoveryAttempt;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryService.class);

    private final OrderRepository orders;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final TicketProperties ticketProps;
    private final OrderRecoveryAttemptRepository attempts;

    public OrderRecoveryService(OrderRepository orders,
                                 EmailService email,
                                 EmailTemplateRenderer renderer,
                                 EmailProperties emailProps,
                                 TicketProperties ticketProps,
                                 OrderRecoveryAttemptRepository attempts) {
        this.orders = orders;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.ticketProps = ticketProps;
        this.attempts = attempts;
    }

    @Transactional
    public void requestRecovery(String rawEmail, UUID eventIdOrNull, String clientIp) {
        if (rawEmail == null) return;
        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.contains("@")) {
            recordAttempt(normalized, clientIp);
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        String ipHash = hashIp(clientIp);

        // Log the attempt regardless. This is what enforces rate-limit on the next call.
        recordAttempt(normalized, clientIp);

        long byEmail = attempts.countByEmailAndAttemptedAtAfter(normalized, cutoff);
        long byIp    = attempts.countByIpHashAndAttemptedAtAfter(ipHash, cutoff);
        int cap = ticketProps.getRecoveryMaxPerHour();
        if (byEmail > cap || byIp > cap) {
            log.info("Recovery rate-limited (email={} byEmail={} byIp={})",
                    normalized, byEmail, byIp);
            return;
        }

        Instant recoveryCutoff = Instant.now().minus(Duration.ofDays(ticketProps.getRecoveryWindowDays()));
        List<Order> found = orders.findRecentForRecovery(normalized, eventIdOrNull, recoveryCutoff);
        if (found.isEmpty()) {
            log.info("Recovery: no orders found for {}", normalized);
            return;
        }

        String base = baseUrl();
        StringBuilder linksHtml = new StringBuilder();
        StringBuilder linksText = new StringBuilder();
        for (Order o : found) {
            String url = base + "/order/" + o.getToken();
            linksHtml.append("<li><a href=\"").append(url).append("\">").append(url).append("</a></li>");
            linksText.append("- ").append(url).append('\n');
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("links", "__LINKS__"); // sidestep auto-escape; replace after render
        EmailTemplateRenderer.Rendered r = renderer.render("order-recovery", values);
        String html = r.html().replace("__LINKS__", linksHtml.toString());
        String text = r.text().replace("__LINKS__", linksText.toString());

        try {
            email.send(normalized, "Recover your tickets · imin", html, text);
            log.info("Recovery: sent {} order link(s) to {}", found.size(), normalized);
        } catch (Exception e) {
            log.warn("Recovery email failed for {}: {}", normalized, e.getMessage());
        }
    }

    private void recordAttempt(String email, String clientIp) {
        OrderRecoveryAttempt a = new OrderRecoveryAttempt();
        a.setEmail(email == null ? "" : email);
        a.setIpHash(hashIp(clientIp));
        attempts.save(a);
    }

    private static String hashIp(String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((ip == null ? "" : ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    private String baseUrl() {
        String base = emailProps.getAppBaseUrl();
        if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base == null ? "" : base;
    }
}
```

- [ ] **Step 5: Email templates.**

`order-recovery.html`:

```html
<!doctype html>
<html><body style="font-family: -apple-system, BlinkMacSystemFont, sans-serif; max-width: 560px; margin: 0 auto; padding: 24px;">
  <h2 style="margin-top: 0;">Your tickets · imin</h2>
  <p>Tap an order below to open your tickets and QR codes:</p>
  <ul>{{links}}</ul>
  <p style="color: #888; font-size: 13px;">If you didn't ask for this, you can safely ignore it.</p>
</body></html>
```

`order-recovery.txt`:

```
Your tickets · imin

Open one of these to see your tickets and QR codes:

{{links}}

If you didn't ask for this, you can safely ignore it.
```

- [ ] **Step 6: Controller.**

```java
package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.service.ticket.OrderRecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PublicRecoveryController {

    public record Req(@NotBlank String email, UUID eventId) {}

    private final OrderRecoveryService service;

    public PublicRecoveryController(OrderRecoveryService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/public/orders/recover")
    public ResponseEntity<Void> recover(@RequestBody Req req, HttpServletRequest http) {
        service.requestRecovery(req.email(), req.eventId(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 7: Run.**

```bash
cd imin-api && ./mvnw test -Dtest=OrderRecoveryServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/model/OrderRecoveryAttempt.java src/main/java/com/imin/iminapi/repository/OrderRecoveryAttemptRepository.java src/main/java/com/imin/iminapi/service/ticket/OrderRecoveryService.java src/main/java/com/imin/iminapi/controller/publicapi/PublicRecoveryController.java src/main/resources/email-templates/order-recovery.html src/main/resources/email-templates/order-recovery.txt src/test/java/com/imin/iminapi/service/ticket/OrderRecoveryServiceTest.java
git commit -m "Add /api/v1/public/orders/recover with email+IP rate limit

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 15 — Redemption endpoint

**Files:**
- Create: `imin-api/src/main/java/com/imin/iminapi/service/ticket/TicketRedeemService.java`
- Create: `imin-api/src/main/java/com/imin/iminapi/controller/event/TicketRedeemController.java`
- Create: `imin-api/src/test/java/com/imin/iminapi/service/ticket/TicketRedeemServiceTest.java`

- [ ] **Step 1: Failing test.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.IminApiApplication;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = IminApiApplication.class)
class TicketRedeemServiceTest {

    @Autowired TicketRedeemService service;
    @Autowired TicketRepository tickets;
    @Autowired QrPayloadSigner signer;

    @Test
    @Transactional
    void redeem_atomic_transitions_issued_to_redeemed() {
        Ticket t = persist("issued", UUID.randomUUID());
        UUID userId = UUID.randomUUID();
        String payload = signer.sign(t.getToken());

        TicketRedeemService.Result r = service.redeem(t.getEventId(), payload, userId);
        assertEquals(TicketRedeemService.Outcome.REDEEMED, r.outcome());

        TicketRedeemService.Result again = service.redeem(t.getEventId(), payload, userId);
        assertEquals(TicketRedeemService.Outcome.ALREADY_REDEEMED, again.outcome());
    }

    @Test
    @Transactional
    void invalid_signature_returns_invalid_without_state_change() {
        Ticket t = persist("issued", UUID.randomUUID());
        TicketRedeemService.Result r = service.redeem(t.getEventId(),
                "imin1." + t.getToken() + ".BADSIG", UUID.randomUUID());
        assertEquals(TicketRedeemService.Outcome.INVALID, r.outcome());
    }

    @Test
    @Transactional
    void wrong_event_returns_wrong_event() {
        Ticket t = persist("issued", UUID.randomUUID());
        TicketRedeemService.Result r = service.redeem(UUID.randomUUID(),
                signer.sign(t.getToken()), UUID.randomUUID());
        assertEquals(TicketRedeemService.Outcome.WRONG_EVENT, r.outcome());
    }

    @Test
    @Transactional
    void revoked_returns_revoked() {
        Ticket t = persist("revoked", UUID.randomUUID());
        TicketRedeemService.Result r = service.redeem(t.getEventId(),
                signer.sign(t.getToken()), UUID.randomUUID());
        assertEquals(TicketRedeemService.Outcome.REVOKED, r.outcome());
    }

    private Ticket persist(String state, UUID eventId) {
        Ticket t = new Ticket();
        t.setToken("TKT_" + UUID.randomUUID());
        t.setOrderId(UUID.randomUUID());
        t.setEventId(eventId);
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setState(state);
        return tickets.save(t);
    }
}
```

- [ ] **Step 2: Run — expect failure.**

```bash
cd imin-api && ./mvnw test -Dtest=TicketRedeemServiceTest
```

Expected: FAIL — class missing.

- [ ] **Step 3: Implement.**

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TicketRedeemService {

    public enum Outcome { REDEEMED, ALREADY_REDEEMED, WRONG_EVENT, REVOKED, INVALID }

    public record Result(Outcome outcome, Ticket ticket) {}

    private final TicketRepository tickets;
    private final QrPayloadSigner signer;

    public TicketRedeemService(TicketRepository tickets, QrPayloadSigner signer) {
        this.tickets = tickets;
        this.signer = signer;
    }

    @Transactional
    public Result redeem(UUID expectedEventId, String qrPayload, UUID userId) {
        Optional<String> token = signer.verify(qrPayload);
        if (token.isEmpty()) return new Result(Outcome.INVALID, null);

        Optional<Ticket> opt = tickets.findByToken(token.get());
        if (opt.isEmpty()) return new Result(Outcome.INVALID, null);
        Ticket t = opt.get();

        if (!t.getEventId().equals(expectedEventId)) {
            return new Result(Outcome.WRONG_EVENT, null); // don't leak the actual event id
        }
        if ("revoked".equals(t.getState())) {
            return new Result(Outcome.REVOKED, t);
        }

        int rows = tickets.redeemAtomic(t.getToken(), userId, Instant.now());
        if (rows == 1) {
            // Re-load to surface the canonical timestamps.
            Ticket fresh = tickets.findByToken(t.getToken()).orElse(t);
            return new Result(Outcome.REDEEMED, fresh);
        }
        // 0 rows: either already redeemed or state changed to revoked between SELECT and UPDATE.
        Ticket fresh = tickets.findByToken(t.getToken()).orElse(t);
        if ("revoked".equals(fresh.getState())) return new Result(Outcome.REVOKED, fresh);
        return new Result(Outcome.ALREADY_REDEEMED, fresh);
    }
}
```

- [ ] **Step 4: Controller.**

```java
package com.imin.iminapi.controller.event;

import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.ticket.TicketRedeemService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
public class TicketRedeemController {

    public record Req(@NotBlank String qrPayload) {}

    private final TicketRedeemService service;

    public TicketRedeemController(TicketRedeemService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem")
    public ResponseEntity<Map<String, Object>> redeem(@PathVariable UUID orgId,
                                                       @PathVariable UUID eventId,
                                                       @RequestBody Req req,
                                                       @CurrentUser AuthPrincipal me) {
        // Authorization: caller must belong to orgId. The auth framework already
        // enforces "logged in"; here we additionally enforce org membership.
        if (me == null || me.orgId() == null || !me.orgId().equals(orgId)) {
            return ResponseEntity.status(403).build();
        }

        TicketRedeemService.Result r = service.redeem(eventId, req.qrPayload(), me.userId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", switch (r.outcome()) {
            case REDEEMED -> "redeemed";
            case ALREADY_REDEEMED -> "already_redeemed";
            case WRONG_EVENT -> "wrong_event";
            case REVOKED -> "revoked";
            case INVALID -> "invalid";
        });
        if (r.ticket() != null && r.outcome() != TicketRedeemService.Outcome.WRONG_EVENT) {
            Map<String, Object> tk = new LinkedHashMap<>();
            tk.put("token", r.ticket().getToken());
            tk.put("tierName", r.ticket().getTierName());
            if (r.ticket().getRedeemedAt() != null) tk.put("redeemedAt", r.ticket().getRedeemedAt().toString());
            body.put("ticket", tk);
        }
        return ResponseEntity.ok(body);
    }
}
```

> If `AuthPrincipal.orgId()` doesn't exist, swap to whatever the codebase uses (look at any other org-scoped controller for the canonical pattern; e.g. `StripeConnectController`).

- [ ] **Step 5: Run.**

```bash
cd imin-api && ./mvnw test -Dtest=TicketRedeemServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/service/ticket/TicketRedeemService.java src/main/java/com/imin/iminapi/controller/event/TicketRedeemController.java src/test/java/com/imin/iminapi/service/ticket/TicketRedeemServiceTest.java
git commit -m "Add organizer-authenticated single-use ticket redemption

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 16 — Expose qrPayload/qrUrl/walletAvailable on PublicTicketResponse

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/dto/publicapi/PublicTicketResponse.java`
- Modify: `imin-api/src/main/java/com/imin/iminapi/controller/publicapi/PublicOrderController.java`

- [ ] **Step 1: Add fields to the response record.**

Open `PublicTicketResponse.java`. It's a record. Add `qrPayload`, `qrUrl`, `walletAvailable` to the canonical constructor and update the order-block accordingly. Match the existing top-level record shape.

```java
public record PublicTicketResponse(
        String token,
        String state,
        String tierName,
        String qrPayload,
        String qrUrl,
        boolean walletAvailable,
        Event event,
        Order order
) { /* nested records unchanged */ }
```

- [ ] **Step 2: Wire it in the controller.**

`PublicOrderController.getTicket`:

```java
String qrPayload = signer.sign(ticket.getToken());
String base = emailProps.getAppBaseUrl();
if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
String qrUrl = base + "/api/v1/public/tickets/" + ticket.getToken() + "/qr.png";

var body = new PublicTicketResponse(
        ticket.getToken(),
        normalizeState(ticket.getState()),
        ticket.getTierName(),
        qrPayload,
        qrUrl,
        wallet.isConfigured(),
        new PublicTicketResponse.Event(...),
        new PublicTicketResponse.Order(order.getToken(), order.getEmail()));
```

Add `private static String normalizeState(String wire) { return TicketState.fromWire(wire).wire(); }` to the controller (or wire to a static helper).

Inject `QrPayloadSigner`, `EmailProperties`, `AppleWalletPassService` into the constructor (already DI-friendly).

- [ ] **Step 3: Run the full public test suite.**

```bash
cd imin-api && ./mvnw test -Dtest=PublicOrderControllerTest
```

Add a one-liner assertion to that test (or create one if missing) that `qrPayload` and `qrUrl` come back non-null. Expected: PASS.

- [ ] **Step 4: Commit.**

```bash
cd imin-api && git add src/main/java/com/imin/iminapi/dto/publicapi/PublicTicketResponse.java src/main/java/com/imin/iminapi/controller/publicapi/PublicOrderController.java
git commit -m "Surface qrPayload/qrUrl/walletAvailable on ticket response

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 17 — `imin-public` ticket page: real QR + wallet

**Files:**
- Modify: `imin-public/lib/api/public-events.ts`
- Modify: `imin-public/app/tickets/[token]/page.tsx`

- [ ] **Step 1: Extend the `PublicTicket` type.**

Open `lib/api/public-events.ts` and find the `PublicTicket` type. Add:

```typescript
qrPayload: string;
qrUrl: string;
walletAvailable: boolean;
```

If `getPublicTicket` does a hand-rolled parse, mirror the new fields. The BE response already returns them after Task 16.

- [ ] **Step 2: Replace the placeholder block in the page.**

Open `app/tickets/[token]/page.tsx`. Find the comment-marked placeholder block (`{/* Placeholder scan code — real QR generation is a follow-up. */}`) and replace it with:

```tsx
<div style={{ marginTop: 8, display: "flex", flexDirection: "column", alignItems: "center", gap: 8 }}>
  <img
    src={ticket.qrUrl}
    alt="Ticket QR code"
    width={256}
    height={256}
    style={{ display: "block", border: "1px solid var(--border)", borderRadius: 8, padding: 8, background: "white" }}
  />
  {ticket.walletAvailable ? (
    <a
      href={`${ticket.qrUrl.replace("/qr.png", "/apple-wallet.pkpass")}`}
      className="bx-btn"
      style={{ marginTop: 4 }}
    >
      Add to Apple Wallet
    </a>
  ) : null}
</div>
```

- [ ] **Step 3: Boot the imin-public dev server.**

```bash
cd imin-public && pnpm install && pnpm dev
```

Manually navigate to a known `/tickets/{token}` and verify the QR renders and the Wallet button appears when configured. (No automated FE test wired in this repo for buyer pages — verification is manual per the project's existing pattern.)

- [ ] **Step 4: Commit.**

```bash
cd imin-public && git add lib/api/public-events.ts app/tickets/[token]/page.tsx
git commit -m "Render real ticket QR and Apple Wallet button

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 18 — Success page redirect to order

**Files:**
- Create: `imin-public/lib/api/checkout.ts`
- Modify: `imin-public/app/e/[id]/success/page.tsx`

- [ ] **Step 1: Add the API helper.**

```typescript
// imin-public/lib/api/checkout.ts
const API_BASE = process.env.NEXT_PUBLIC_API_BASE;
if (!API_BASE) throw new Error("NEXT_PUBLIC_API_BASE is not set");

export type CheckoutStatus =
  | { status: "ready"; orderToken: string }
  | { status: "pending" }
  | { status: "failed" };

export async function getCheckoutStatus(sessionId: string): Promise<CheckoutStatus> {
  const res = await fetch(`${API_BASE}/checkout/${encodeURIComponent(sessionId)}`, {
    cache: "no-store",
  });
  if (!res.ok) return { status: "pending" };
  return res.json();
}

export async function recoverOrder(email: string, eventId?: string): Promise<void> {
  await fetch(`${API_BASE}/orders/recover`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, eventId }),
  });
}
```

- [ ] **Step 2: Rewrite the success page.**

```tsx
// imin-public/app/e/[id]/success/page.tsx
import Link from "next/link";
import { redirect } from "next/navigation";
import type { Metadata } from "next";
import { ArrowLeft, Clock, CheckCircle2 } from "lucide-react";
import { Topbar } from "@/components/buyer/topbar";
import { Footer } from "@/components/buyer/footer";
import { getCheckoutStatus } from "@/lib/api/checkout";

interface PageProps {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}

export const metadata: Metadata = {
  title: "Ticket confirmed · imin.wtf",
  robots: { index: false, follow: false },
};

// Read the attempt counter from the URL so meta-refresh terminates after ~15s.
function readAttempt(searchParams: { [key: string]: string | string[] | undefined }): number {
  const raw = searchParams.attempt;
  const v = Array.isArray(raw) ? raw[0] : raw;
  const n = v ? Number.parseInt(v, 10) : 0;
  return Number.isFinite(n) && n >= 0 ? n : 0;
}

export default async function CheckoutSuccessPage({ params, searchParams }: PageProps) {
  const { id } = await params;
  const sp = await searchParams;
  const rawSession = sp.session_id;
  const sessionId = Array.isArray(rawSession) ? rawSession[0] : rawSession;

  if (!sessionId) {
    // Stripe always appends session_id={CHECKOUT_SESSION_ID}; if missing, the user got here weirdly.
    return renderStillProcessing(id, /* terminal */ true);
  }

  const status = await getCheckoutStatus(sessionId);
  if (status.status === "ready") {
    redirect(`/order/${status.orderToken}`);
  }

  const attempt = readAttempt(sp);
  const terminal = attempt >= 7; // ~14s of polling

  return renderStillProcessing(id, terminal, sessionId, attempt);
}

function renderStillProcessing(eventId: string, terminal: boolean, sessionId?: string, attempt = 0) {
  const nextHref = sessionId
    ? `/e/${eventId}/success?session_id=${encodeURIComponent(sessionId)}&attempt=${attempt + 1}`
    : null;

  return (
    <div className="bx">
      <Topbar showSearch={false} viewLabel="Ticket confirmed" />
      {/* Meta-refresh polls every 2s until terminal */}
      {!terminal && nextHref ? (
        // eslint-disable-next-line @next/next/no-head-element
        <head>
          <meta httpEquiv="refresh" content={`2; url=${nextHref}`} />
        </head>
      ) : null}

      <div className="bx-event-stage">
        <div className="bx-container">
          <Link href={`/e/${eventId}`} className="imin-back">
            <ArrowLeft size={12} /> Back to event
          </Link>

          <div
            className="bx-narrow"
            style={{
              display: "flex", flexDirection: "column", gap: 16, alignItems: "flex-start",
              padding: 24, border: "1px solid var(--border)", borderRadius: "var(--radius-lg)",
              background: "var(--surface)", boxShadow: "var(--shadow-md)",
            }}
          >
            <div style={{ display: "inline-flex", alignItems: "center", gap: 10, color: "var(--accent2, var(--accent))" }}>
              {terminal ? <CheckCircle2 size={20} /> : <Clock size={20} />}
              <span className="bx-eyebrow">{terminal ? "Payment received" : "Finalizing your tickets…"}</span>
            </div>

            <h1 className="bx-h1">
              {terminal ? "Your tickets are on the way." : "Almost there."}
            </h1>

            <p className="bx-prose" style={{ margin: 0 }}>
              {terminal
                ? "Your payment is in. Your tickets are being prepared and will arrive by email within a minute. If you don't see them, check spam, then use the recovery form."
                : "Just hanging on for the payment confirmation. This page will refresh automatically."}
            </p>

            {sessionId ? (
              <div style={{ fontFamily: "var(--mono)", fontSize: 12, color: "var(--text3)" }}>
                Reference: <code style={{ color: "var(--text2)" }}>{sessionId}</code>
              </div>
            ) : null}

            <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
              <Link href="/recover" className="bx-btn">Recover by email</Link>
              <Link href={`/e/${eventId}`} className="bx-btn">Back to event</Link>
            </div>
          </div>
        </div>
      </div>
      <Footer />
    </div>
  );
}
```

> The happy path is the server-side `redirect("/order/{token}")` — buyer never sees this "Finalizing" panel under normal conditions. It's only the safety net for the (small) window between Stripe redirect and webhook completion, plus the recovery hint when the webhook truly didn't fire.

- [ ] **Step 3: Manual verification.**

```bash
cd imin-public && pnpm dev
```

Trigger a test-mode Stripe checkout end-to-end. Confirm you land on `/order/{token}` directly. To exercise the pending branch, add a `?attempt=1` to the URL with a fresh (not-yet-processed) session_id.

- [ ] **Step 4: Commit.**

```bash
cd imin-public && git add lib/api/checkout.ts app/e/[id]/success/page.tsx
git commit -m "Success page redirects to /order/{token} when issuance is ready

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 19 — Recovery page + "Lost this email?" link on order

**Files:**
- Create: `imin-public/app/recover/page.tsx`
- Modify: `imin-public/app/order/[token]/page.tsx`

- [ ] **Step 1: Recovery page.**

```tsx
// imin-public/app/recover/page.tsx
"use client";

import { useState } from "react";
import Link from "next/link";
import { Topbar } from "@/components/buyer/topbar";
import { Footer } from "@/components/buyer/footer";
import { recoverOrder } from "@/lib/api/checkout";

export default function RecoverPage() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    try {
      await recoverOrder(email.trim());
      setSent(true);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="bx">
      <Topbar showSearch={false} viewLabel="Recover" />
      <main className="bx-narrow" style={{ paddingTop: 32 }}>
        <div style={{ padding: 24, border: "1px solid var(--border)", borderRadius: "var(--radius-lg)", background: "var(--surface)" }}>
          <h1 className="bx-h1" style={{ marginTop: 0 }}>Recover your tickets</h1>
          {sent ? (
            <p>If we have orders for that address, we just emailed the links. Check your inbox (and spam).</p>
          ) : (
            <form onSubmit={onSubmit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <label className="bx-eyebrow">Email used at checkout</label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                style={{ padding: "10px 12px", border: "1px solid var(--border)", borderRadius: 6 }}
              />
              <button type="submit" disabled={submitting} className="bx-btn bx-btn--primary">
                {submitting ? "Sending…" : "Email me my tickets"}
              </button>
              <Link href="/events" className="imin-link">← Back to events</Link>
            </form>
          )}
        </div>
      </main>
      <Footer />
    </div>
  );
}
```

- [ ] **Step 2: Add "Lost this email?" on the order page.**

In `app/order/[token]/page.tsx`, append near the footer of the rendered order card:

```tsx
<p style={{ marginTop: 16, color: "var(--text3)", fontSize: 13 }}>
  Lost this email? <Link href="/recover" className="imin-link">Resend it</Link>.
</p>
```

- [ ] **Step 3: Manual verification.**

```bash
cd imin-public && pnpm dev
```

Open `/recover`, submit a known email + an unknown email. Confirm both show the same "sent" state.

- [ ] **Step 4: Commit.**

```bash
cd imin-public && git add app/recover/page.tsx app/order/[token]/page.tsx
git commit -m "Add /recover page and order-page link

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 20 — End-to-end manual verification

**Goal:** prove the 60 s SLO and all stitched paths work against staging.

- [ ] **Step 1: Set required env vars on staging.**

```
IMIN_TICKET_SIGNING_SECRET=<32+ bytes>
APPLE_WALLET_*=<configured>     # optional
STRIPE_WEBHOOK_SECRET=<whsec from dashboard>
```

- [ ] **Step 2: Trigger a real checkout.**

In a logged-in browser, open a known public event, buy 2 tickets. Land on Stripe-hosted checkout, complete with a 4242 4242 4242 4242 card.

Expected:
- Browser redirects to `/e/{id}/success?session_id=cs_...` and within ~1 s redirects to `/order/{orderToken}`.
- Email arrives at the buyer's inbox within 60 s of `payment_intent.succeeded`. Subject contains the event name. Two QR images render inline.
- Open `/tickets/{token}` in the email. QR renders, "Add to Apple Wallet" is visible (if wallet configured).
- Click "Add to Apple Wallet" on iOS — Wallet opens the pass card. The barcode scans cleanly with any QR app.

- [ ] **Step 3: Test idempotency.**

In the Stripe dashboard, find the `payment_intent.succeeded` event and click "Resend". Confirm:
- No second email goes out.
- `orders` table still has exactly one row for this PI.
- Logs show "Order already exists for PaymentIntent … — skipping (idempotent)".

- [ ] **Step 4: Test redemption.**

Authenticate as the organizer. From a scanner app or curl, POST the QR payload to `/api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem`. First call returns `redeemed`; second returns `already_redeemed`. Wrong event id returns `wrong_event`. Tampered payload returns `invalid`.

- [ ] **Step 5: Test recovery.**

`/recover`, submit the buyer's email. Confirm recovery email arrives. Submit an unknown email — UI shows the same success state, no email goes out, an attempt row is logged.

- [ ] **Step 6: Tag and merge.**

```bash
cd imin-api && git tag v-ticket-issuance-2026-05-19
cd imin-public && git tag v-ticket-issuance-2026-05-19
```

(Mention to ops that wallet certs must land in prod env before announcing the feature externally.)

---

## Self-review

**Spec coverage**

- §3 reuse → Tasks 3, 8, 10 reuse `orders`/`tickets`, `EmailService`, `EmailTemplateRenderer`, `PublicOrderController`, V25 dedup, FreeCheckout shape.
- §5 V26 migration → Task 2.
- §6/§6a webhook flow + metadata mirror → Tasks 1, 8, 9.
- §7 QR payload → Task 5.
- §8 redemption → Task 15.
- §9 Apple Wallet → Tasks 11 (stub + endpoint), 12 (real impl).
- §10 async email → Tasks 7 (executor), 10 (emailer).
- §11 recovery → Task 14.
- §12 idempotency → covered by Tasks 8 (PI-id UNIQUE belt+suspenders), 9 (handler stitching), 15 (atomic UPDATE).
- §13 60s SLO → assertions in Task 20 (manual). Sentry-span instrumentation deferred to a follow-up commit; the architecture already commits to the necessary @Async + AFTER_COMMIT structure.
- §14 public API additions → Tasks 11, 13, 14, 15, 16.
- §15 frontend → Tasks 17, 18, 19.
- §15a checkout-status endpoint → Task 13.
- §16 approaches → not a task; reference only.
- §17 config → Tasks 5, 12 expose env vars in `application.yaml`.
- §18 testing → unit tests embedded in each task; manual flow in Task 20.
- §19 rollout → matches the task ordering.

**Placeholder scan**

- The two `persistEvent`/`persistTier` helper bodies in Task 8 are explicitly called out as "copy from FreeCheckoutServiceTest"; the engineer has a real source to copy from. Acceptable.
- The "set up wallet test fixtures" caveat in Task 12 step 4 is explicit and gives the openssl command — engineer can produce them.
- No `TBD` / "TODO" / "fill in later".

**Type consistency**

- `TicketState.fromWire` accepts both `'pre'` and `'issued'` — both Task 4 (where it's defined) and Task 16 (where it's called via `normalizeState`) agree on the mapping. The redeem service compares the raw string `'revoked'` (Task 15) which matches `TicketState.REVOKED.wire()`.
- `Order.stripePaymentIntentId` is added in Task 3 entity, used in Task 8 service. Repo finder `findByStripePaymentIntentId` defined in Task 3, called in Task 8. Match.
- `TicketRepository.redeemAtomic` signature `(token, userId, now)` defined in Task 3, called from Task 15 with same args. Match.
- `AppleWalletPassService` has `isConfigured()` + `generatePass(String)` in both Task 11 (stub) and Task 12 (real). Match.
- `CheckoutStatusService.Status` enum values: `READY`, `PENDING`, `FAILED` — only `READY` and `PENDING` are returned by `statusFor`. `FAILED` is reserved for the optional Stripe-retrieve path mentioned in spec §15a; safe to leave defined but unused for v1.

No issues found. Proceed to handoff.
