# Stripe Connect Status — Local State Mirroring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mirror Stripe v2 Connect account state to local DB columns updated via webhook, and serve `/stripe/status` from those columns instead of a synchronous Stripe call — so the FE can render the 5 onboarding states (Not started / Onboarding / Pending verification / Restricted / Active) without paying a Stripe round-trip on every page load.

**Architecture:** Extend the `organizations` table with the inputs of a 5-state machine plus a derived `stripe_connect_state` column. A new `StripeConnectStatusMirror` service fetches the v2 Account, projects fields, derives state, and writes the columns. The webhook (`v2.core.account.requirements.updated`, `v2.core.account.recipient.capability_status_updated`) invokes the mirror. `StripeConnectService.getStatus` reads the columns; on the first read after `getOrCreateAccount` (when `stripe_connect_status_updated_at IS NULL`) it falls back to a one-shot live fetch through the mirror so we don't have to backfill historical accounts in a separate job. The DTO grows a `state` enum field so the FE doesn't re-derive.

**Tech Stack:** Java 17, Spring Boot 4.0.5, JPA/Flyway (PostgreSQL 17 prod, H2-PG mode tests), `com.stripe:stripe-java` v2 API, JUnit 5.

**Out of scope:** A dedicated periodic reconciler (webhooks + lazy first-read covers all paths; reconciler is YAGNI). Backfill for accounts where the webhook never fires is handled implicitly by the lazy first-read.

**v1 ↔ v2 mapping (important — the spec uses v1 Connect terminology, the code is v2):**
| Spec field (v1) | v2 source | Stored column |
|---|---|---|
| `charges_enabled` | n/a — RECIPIENT-only accounts can't accept charges | (not stored — derive from `state == ACTIVE` if FE asks) |
| `payouts_enabled` | `configuration.recipient.capabilities.stripe_balance.stripe_transfers.status == "active"` | `stripe_payouts_enabled` |
| `details_submitted` | First time `requirements.summary.minimum_deadline.status` ≠ `currently_due` (sticky once true) | `stripe_details_submitted` |
| `requirements.currently_due` | Union of field names from `requirements.entries[].requirements[].field_name` where `requirements[].status == "currently_due"` | `stripe_requirements_currently_due` (jsonb array) |
| `requirements.past_due` | same but `status == "past_due"` | `stripe_requirements_past_due` (jsonb array) |
| `requirements.disabled_reason` | `requirements.disabled_reason` (v2 surfaces this) | `stripe_disabled_reason` |

**State derivation (computed at write time, stored in `stripe_connect_state`):**
```
NOT_STARTED          ← no stripeAccountId
ONBOARDING           ← stripeAccountId set, !details_submitted
PENDING_VERIFICATION ← details_submitted, currently_due empty, payouts_enabled == false
RESTRICTED           ← details_submitted, currently_due non-empty (regardless of payouts)
ACTIVE               ← payouts_enabled == true && currently_due empty
```

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V33__add_stripe_connect_status.sql` | Add columns + derived-state default for existing orgs |
| `src/main/java/com/imin/iminapi/model/Organization.java` | New JPA fields for the status columns + state enum |
| `src/main/java/com/imin/iminapi/stripe/StripeConnectState.java` | Enum (NOT_STARTED / ONBOARDING / PENDING_VERIFICATION / RESTRICTED / ACTIVE) |
| `src/main/java/com/imin/iminapi/stripe/StripeConnectStatusMirror.java` | New service — fetches v2 Account, projects to columns, derives state, persists |
| `src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java` | Modify `handleV2Endpoint` to call the mirror with the event's `account_id` |
| `src/main/java/com/imin/iminapi/stripe/StripeConnectService.java` | Refactor `getStatus` to read from columns + lazy first-read via mirror |
| `src/main/java/com/imin/iminapi/stripe/StripeConnectController.java` | DTO grows `state` field |
| `src/test/java/com/imin/iminapi/stripe/StripeConnectStatusMirrorTest.java` | New — Account → columns projection + state derivation |
| `src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceConnectTest.java` | New — webhook calls mirror with the correct account id |
| `src/test/java/com/imin/iminapi/stripe/StripeConnectServiceStatusTest.java` | Update — getStatus reads from DB, doesn't call Stripe |

Each task is self-contained: a failing test, the minimal change, a passing test, a commit.

---

### Task 1: Migration — add status columns

**Files:**
- Create: `src/main/resources/db/migration/V33__add_stripe_connect_status.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Stripe Connect status mirror — fields previously fetched live on every /stripe/status call.
-- Source events: v2.core.account.requirements.updated, v2.core.account.recipient.capability_status_updated.
ALTER TABLE organizations
    ADD COLUMN stripe_connect_state            VARCHAR(32),
    ADD COLUMN stripe_payouts_enabled          BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN stripe_details_submitted        BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN stripe_requirements_currently_due JSONB    NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN stripe_requirements_past_due      JSONB    NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN stripe_disabled_reason          VARCHAR(128),
    ADD COLUMN stripe_connect_status_updated_at TIMESTAMP(6) WITHOUT TIME ZONE;

-- Existing rows: derive an initial state from what we already know. The mirror's lazy
-- first-read (StripeConnectService.getStatus) will refine these on the next call.
UPDATE organizations
SET stripe_connect_state = CASE
        WHEN stripe_account_id IS NULL OR stripe_account_id = '' THEN 'NOT_STARTED'
        ELSE 'ONBOARDING'
    END;

ALTER TABLE organizations
    ALTER COLUMN stripe_connect_state SET NOT NULL,
    ALTER COLUMN stripe_connect_state SET DEFAULT 'NOT_STARTED';
```

- [ ] **Step 2: Run Flyway via test boot to verify**

Run: `./mvnw test -Dtest=IminApiApplicationTests`
Expected: PASS (Flyway applies V33 against H2-PG; context loads).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V33__add_stripe_connect_status.sql
git commit -m "feat(stripe): V33 add Connect status mirror columns"
```

---

### Task 2: Add `StripeConnectState` enum

**Files:**
- Create: `src/main/java/com/imin/iminapi/stripe/StripeConnectState.java`

- [ ] **Step 1: Write the enum**

```java
package com.imin.iminapi.stripe;

/**
 * The 5 user-facing states for the organizer-side Stripe Connect onboarding banner.
 * Derived from the persisted mirror columns; see StripeConnectStatusMirror#derive.
 */
public enum StripeConnectState {
    NOT_STARTED,
    ONBOARDING,
    PENDING_VERIFICATION,
    RESTRICTED,
    ACTIVE
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripeConnectState.java
git commit -m "feat(stripe): add StripeConnectState enum"
```

---

### Task 3: Add JPA fields to `Organization`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/model/Organization.java`

- [ ] **Step 1: Add a JSON column converter (used for the two jsonb arrays)**

Create: `src/main/java/com/imin/iminapi/model/StringListJsonConverter.java`

```java
package com.imin.iminapi.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Collections;
import java.util.List;

/** Persists a List<String> as a Postgres jsonb array (and an H2-PG VARCHAR jsonb shim). */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize requirements list", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(dbData, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse requirements list: " + dbData, e);
        }
    }
}
```

- [ ] **Step 2: Add fields to `Organization`**

Add the following inside the entity class (after line 52, the existing `stripeAccountId` field). Add the necessary imports at the top.

```java
import com.imin.iminapi.stripe.StripeConnectState;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.ArrayList;
import java.util.List;
```

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "stripe_connect_state", nullable = false, length = 32)
    private StripeConnectState stripeConnectState = StripeConnectState.NOT_STARTED;

    @Column(name = "stripe_payouts_enabled", nullable = false)
    private boolean stripePayoutsEnabled = false;

    @Column(name = "stripe_details_submitted", nullable = false)
    private boolean stripeDetailsSubmitted = false;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "stripe_requirements_currently_due", nullable = false, columnDefinition = "jsonb")
    private List<String> stripeRequirementsCurrentlyDue = new ArrayList<>();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "stripe_requirements_past_due", nullable = false, columnDefinition = "jsonb")
    private List<String> stripeRequirementsPastDue = new ArrayList<>();

    @Column(name = "stripe_disabled_reason", length = 128)
    private String stripeDisabledReason;

    @Column(name = "stripe_connect_status_updated_at")
    private Instant stripeConnectStatusUpdatedAt;
```

- [ ] **Step 3: Run a smoke test**

Run: `./mvnw test -Dtest=IminApiApplicationTests`
Expected: PASS (context starts, schema validates).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/Organization.java src/main/java/com/imin/iminapi/model/StringListJsonConverter.java
git commit -m "feat(stripe): persist Connect status fields on Organization"
```

---

### Task 4: `StripeConnectStatusMirror` — projection + state derivation

**Files:**
- Create: `src/main/java/com/imin/iminapi/stripe/StripeConnectStatusMirror.java`
- Test: `src/test/java/com/imin/iminapi/stripe/StripeConnectStatusMirrorTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.stripe.StripeClient;
import com.stripe.model.v2.core.Account;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeConnectStatusMirrorTest {

    private final StripeClient stripeClient = mock(StripeClient.class);

    @Test
    void derive_active_when_transfers_active_and_no_currently_due() {
        Organization org = newOrg();
        Account account = StripeFixtures.accountActive("acct_1");

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.ACTIVE);
        assertThat(org.isStripePayoutsEnabled()).isTrue();
        assertThat(org.isStripeDetailsSubmitted()).isTrue();
        assertThat(org.getStripeRequirementsCurrentlyDue()).isEmpty();
    }

    @Test
    void derive_restricted_when_details_submitted_and_currently_due_nonempty() {
        Organization org = newOrg();
        org.setStripeDetailsSubmitted(true); // previously submitted
        Account account = StripeFixtures.accountWithCurrentlyDue(
                "acct_2", List.of("individual.verification.document", "business_profile.url"));

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.RESTRICTED);
        assertThat(org.getStripeRequirementsCurrentlyDue())
                .containsExactlyInAnyOrder("individual.verification.document", "business_profile.url");
    }

    @Test
    void derive_pending_verification_when_submitted_no_currently_due_not_active() {
        Organization org = newOrg();
        Account account = StripeFixtures.accountPendingVerification("acct_3");

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.PENDING_VERIFICATION);
        assertThat(org.isStripeDetailsSubmitted()).isTrue();
        assertThat(org.isStripePayoutsEnabled()).isFalse();
    }

    @Test
    void derive_onboarding_when_account_exists_but_not_submitted() {
        Organization org = newOrg();
        Account account = StripeFixtures.accountOnboarding("acct_4",
                List.of("individual.first_name", "individual.last_name"));

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.ONBOARDING);
        assertThat(org.isStripeDetailsSubmitted()).isFalse();
    }

    @Test
    void details_submitted_is_sticky_once_true() {
        Organization org = newOrg();
        org.setStripeDetailsSubmitted(true);
        // Even if the latest account snapshot looks like fresh onboarding (e.g., Stripe
        // re-opened requirements), once we've seen submission we never un-flag it.
        Account account = StripeFixtures.accountOnboarding("acct_5", List.of("foo"));

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.isStripeDetailsSubmitted()).isTrue();
        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.RESTRICTED);
    }

    private static Organization newOrg() {
        Organization o = new Organization();
        o.setName("Test");
        o.setSlug("test");
        o.setContactEmail("ops@example.com");
        o.setCountry("FR");
        o.setStripeAccountId("acct_test");
        return o;
    }
}
```

- [ ] **Step 2: Create the `StripeFixtures` test helper**

Create: `src/test/java/com/imin/iminapi/stripe/StripeFixtures.java`

```java
package com.imin.iminapi.stripe;

import com.stripe.model.v2.core.Account;

import java.util.List;

/**
 * Builds com.stripe.model.v2.core.Account instances by feeding raw JSON through the
 * SDK's deserializer — the Account class has no public constructors. Each helper
 * mirrors a real Stripe scenario the mirror must handle.
 */
final class StripeFixtures {

    private StripeFixtures() {}

    static Account accountActive(String id) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "active" } }
                  }
                }
              },
              "requirements": {
                "summary": { "minimum_deadline": { "status": "verified" } },
                "entries": []
              }
            }
            """.formatted(id));
    }

    static Account accountPendingVerification(String id) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "pending" } }
                  }
                }
              },
              "requirements": {
                "summary": { "minimum_deadline": { "status": "pending_verification" } },
                "entries": []
              }
            }
            """.formatted(id));
    }

    static Account accountWithCurrentlyDue(String id, List<String> fields) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "restricted" } }
                  }
                }
              },
              "requirements": {
                "summary": { "minimum_deadline": { "status": "currently_due" } },
                "disabled_reason": "requirements.past_due",
                "entries": [ %s ]
              }
            }
            """.formatted(id, entriesJson(fields, "currently_due")));
    }

    static Account accountOnboarding(String id, List<String> fields) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "unverified" } }
                  }
                }
              },
              "requirements": {
                "summary": { "minimum_deadline": { "status": "currently_due" } },
                "entries": [ %s ]
              }
            }
            """.formatted(id, entriesJson(fields, "currently_due")));
    }

    private static String entriesJson(List<String> fields, String status) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("""
                { "requirements": [ { "field_name": "%s", "status": "%s" } ] }
                """.formatted(fields.get(i), status));
        }
        return sb.toString();
    }

    private static Account parse(String json) {
        return com.stripe.net.ApiResource.GSON.fromJson(json, Account.class);
    }
}
```

- [ ] **Step 3: Run tests — they should fail (mirror class doesn't exist yet)**

Run: `./mvnw test -Dtest=StripeConnectStatusMirrorTest`
Expected: FAIL — compilation error, `StripeConnectStatusMirror` not found.

- [ ] **Step 4: Implement `StripeConnectStatusMirror`**

```java
package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.util.Times;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.v2.core.Account;
import com.stripe.param.v2.core.AccountRetrieveParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Fetches a Stripe v2 Account and projects the relevant fields onto the
 * {@link Organization} columns added in V33. Pure projection lives in
 * {@link #applyTo(Organization, Account)} so it's unit-testable without
 * Stripe; {@link #syncFromStripe(String)} is the live path used by the
 * webhook and the lazy first-read.
 */
@Service
public class StripeConnectStatusMirror {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectStatusMirror.class);

    private final StripeClient stripeClient;
    private final OrganizationRepository orgs;

    public StripeConnectStatusMirror(StripeClient stripeClient, OrganizationRepository orgs) {
        this.stripeClient = stripeClient;
        this.orgs = orgs;
    }

    /** Webhook + lazy-backfill entry. Looks up org by stripeAccountId, fetches, persists. */
    @Transactional
    public void syncFromStripe(String stripeAccountId) {
        if (stripeAccountId == null || stripeAccountId.isBlank()) return;
        Organization org = orgs.findByStripeAccountId(stripeAccountId).orElse(null);
        if (org == null) {
            log.warn("[stripe-mirror] no org for stripeAccountId={} — skipping", stripeAccountId);
            return;
        }

        AccountRetrieveParams params = AccountRetrieveParams.builder()
                .addInclude(AccountRetrieveParams.Include.CONFIGURATION__RECIPIENT)
                .addInclude(AccountRetrieveParams.Include.REQUIREMENTS)
                .build();
        Account account;
        try {
            account = stripeClient.v2().core().accounts().retrieve(stripeAccountId, params);
        } catch (StripeException e) {
            log.error("[stripe-mirror] retrieve failed for {}: {}", stripeAccountId, e.getMessage(), e);
            return; // leave existing columns; the next webhook will retry
        }

        applyTo(org, account);
        orgs.save(org);
        log.info("[stripe-mirror] persisted state={} payouts={} currentlyDue={} for {}",
                org.getStripeConnectState(), org.isStripePayoutsEnabled(),
                org.getStripeRequirementsCurrentlyDue().size(), stripeAccountId);
    }

    /** Pure projection — unit-testable, no Stripe call. */
    static void applyTo(Organization org, Account account) {
        boolean payoutsEnabled = "active".equals(readTransferStatus(account));
        String minDeadlineStatus = readMinimumDeadlineStatus(account);
        List<String> currentlyDue = readRequirementFields(account, "currently_due");
        List<String> pastDue = readRequirementFields(account, "past_due");
        String disabledReason = readDisabledReason(account);

        // details_submitted is sticky: once we observe submission, never un-flag.
        // Sources that indicate submission: payouts active, OR deadline status != currently_due
        // (e.g., pending_verification, verified, past_due — anything that means Stripe is past
        // the initial intake), OR currently_due empty for a non-fresh account.
        boolean observedSubmission = payoutsEnabled
                || (minDeadlineStatus != null && !"currently_due".equals(minDeadlineStatus));
        if (observedSubmission) {
            org.setStripeDetailsSubmitted(true);
        }

        org.setStripePayoutsEnabled(payoutsEnabled);
        org.setStripeRequirementsCurrentlyDue(currentlyDue);
        org.setStripeRequirementsPastDue(pastDue);
        org.setStripeDisabledReason(disabledReason);
        org.setStripeConnectState(derive(org, currentlyDue.isEmpty(), payoutsEnabled));
        org.setStripeConnectStatusUpdatedAt(Times.nowMicros());
    }

    private static StripeConnectState derive(Organization org, boolean noCurrentlyDue, boolean payoutsEnabled) {
        if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) {
            return StripeConnectState.NOT_STARTED;
        }
        if (!org.isStripeDetailsSubmitted()) {
            return StripeConnectState.ONBOARDING;
        }
        if (!noCurrentlyDue) {
            return StripeConnectState.RESTRICTED;
        }
        if (payoutsEnabled) {
            return StripeConnectState.ACTIVE;
        }
        return StripeConnectState.PENDING_VERIFICATION;
    }

    private static String readTransferStatus(Account a) {
        try {
            return a.getConfiguration().getRecipient().getCapabilities()
                    .getStripeBalance().getStripeTransfers().getStatus();
        } catch (NullPointerException ignored) { return null; }
    }

    private static String readMinimumDeadlineStatus(Account a) {
        try {
            return a.getRequirements().getSummary().getMinimumDeadline().getStatus();
        } catch (NullPointerException ignored) { return null; }
    }

    private static String readDisabledReason(Account a) {
        try { return a.getRequirements().getDisabledReason(); }
        catch (NullPointerException ignored) { return null; }
    }

    private static List<String> readRequirementFields(Account a, String status) {
        try {
            var entries = a.getRequirements().getEntries();
            if (entries == null) return List.of();
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (var entry : entries) {
                if (entry.getRequirements() == null) continue;
                for (var req : entry.getRequirements()) {
                    if (status.equals(req.getStatus()) && req.getFieldName() != null) {
                        out.add(req.getFieldName());
                    }
                }
            }
            return new ArrayList<>(out);
        } catch (NullPointerException ignored) {
            return List.of();
        }
    }
}
```

- [ ] **Step 5: Add the repository lookup**

Modify: `src/main/java/com/imin/iminapi/repository/OrganizationRepository.java` — add:

```java
java.util.Optional<com.imin.iminapi.model.Organization> findByStripeAccountId(String stripeAccountId);
```

(If the file uses no imports inline, place the method using fully qualified names as shown, or add a matching `import` at the top of the file.)

- [ ] **Step 6: Re-run tests — expect PASS**

Run: `./mvnw test -Dtest=StripeConnectStatusMirrorTest`
Expected: PASS — all 5 derivation cases green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripeConnectStatusMirror.java \
        src/main/java/com/imin/iminapi/repository/OrganizationRepository.java \
        src/test/java/com/imin/iminapi/stripe/StripeConnectStatusMirrorTest.java \
        src/test/java/com/imin/iminapi/stripe/StripeFixtures.java
git commit -m "feat(stripe): mirror v2 account state to Organization columns"
```

---

### Task 5: Webhook invokes mirror on v2 events

**Files:**
- Modify: `src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java:189-224`
- Test: `src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceConnectTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.imin.iminapi.stripe;

import com.stripe.StripeClient;
import com.stripe.model.v2.core.Event;
import com.stripe.model.v2.core.EventNotification;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeWebhookServiceConnectTest {

    @Test
    void v2_requirements_updated_triggers_mirror_with_account_id() throws Exception {
        StripeClient stripeClient = mock(StripeClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);
        StripeProperties props = new StripeProperties();
        props.setWebhookSecretV2("whsec_test");

        EventNotification notif = mock(EventNotification.class);
        when(notif.getType()).thenReturn("v2.core.account.requirements.updated");
        when(notif.getId()).thenReturn("evt_123");
        when(stripeClient.parseEventNotification(any(), any(), any())).thenReturn(notif);

        Event full = mock(Event.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(full.getRelatedObject().getId()).thenReturn("acct_777");
        when(full.getType()).thenReturn("v2.core.account.requirements.updated");
        when(full.getId()).thenReturn("evt_123");
        when(stripeClient.v2().core().events().retrieve("evt_123")).thenReturn(full);

        StripeWebhookService svc = new StripeWebhookService(
                stripeClient, props, mock(com.imin.iminapi.repository.PromoCodeRepository.class),
                mock(com.imin.iminapi.service.event.InventoryService.class),
                mock(WebhookEventDedupService.class),
                mock(com.imin.iminapi.service.ticket.PaidCheckoutService.class),
                mock(com.imin.iminapi.refund.RefundService.class));
        svc.setConnectMirror(mirror);

        svc.handleV2Endpoint("{}", "t=1,v1=fake");

        verify(mirror).syncFromStripe(eq("acct_777"));
    }
}
```

- [ ] **Step 2: Run test, expect FAIL (no `setConnectMirror`, mirror not wired)**

Run: `./mvnw test -Dtest=StripeWebhookServiceConnectTest`
Expected: FAIL — compilation error.

- [ ] **Step 3: Wire mirror into `StripeWebhookService`**

Add field + setter and replace the no-op block:

```java
// Add to the field declarations near `private StripeWebhookService self;`
private StripeConnectStatusMirror connectMirror;

@org.springframework.beans.factory.annotation.Autowired(required = false)
public void setConnectMirror(StripeConnectStatusMirror connectMirror) {
    this.connectMirror = connectMirror;
}
```

Replace the body of the `if ("v2.core.account.requirements.updated".equals(type) ...)` block (around line 210-220) with:

```java
if ("v2.core.account.requirements.updated".equals(type)
        || "v2.core.account.recipient.capability_status_updated".equals(type)) {
    try {
        Event full = stripeClient.v2().core().events().retrieve(id);
        log.info("[stripe-webhook] v2 account-state event type={} eventId={} created={}",
                full.getType(), full.getId(), full.getCreated());
        String accountId = full.getRelatedObject() == null ? null : full.getRelatedObject().getId();
        if (accountId == null) {
            log.warn("[stripe-webhook] v2 account-state event {} has no related_object.id — cannot mirror", id);
        } else if (connectMirror == null) {
            log.warn("[stripe-webhook] v2 account-state event {} — connect mirror not wired", id);
        } else {
            connectMirror.syncFromStripe(accountId);
        }
    } catch (StripeException e) {
        log.warn("[stripe-webhook] v2 failed to fetch full event id={} type={} — {}",
                id, type, e.getMessage());
    }
} else {
```

- [ ] **Step 4: Re-run test — expect PASS**

Run: `./mvnw test -Dtest=StripeWebhookServiceConnectTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java \
        src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceConnectTest.java
git commit -m "feat(stripe): webhook persists Connect status via mirror"
```

---

### Task 6: Refactor `getStatus` to read from DB + lazy first-read

**Files:**
- Modify: `src/main/java/com/imin/iminapi/stripe/StripeConnectService.java:278-309`
- Test: `src/test/java/com/imin/iminapi/stripe/StripeConnectServiceStatusTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class StripeConnectServiceStatusTest {

    @Test
    void status_reads_from_db_without_calling_stripe_when_already_synced() {
        StripeClient stripeClient = mock(StripeClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);

        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setId(orgId);
        org.setStripeAccountId("acct_1");
        org.setStripeConnectState(StripeConnectState.ACTIVE);
        org.setStripePayoutsEnabled(true);
        org.setStripeDetailsSubmitted(true);
        org.setStripeConnectStatusUpdatedAt(java.time.Instant.now());
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService svc = new StripeConnectService(
                stripeClient, orgs, new StripeProperties(), null, mirror);
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), orgId, "u@example.com", java.util.Set.of());

        StripeConnectService.StatusResult result = svc.getStatus(p, orgId);

        assertThat(result.state()).isEqualTo(StripeConnectState.ACTIVE);
        assertThat(result.readyToReceivePayments()).isTrue();
        assertThat(result.currentlyDue()).isEmpty();
        // Crucial: no live Stripe call when state is already synced.
        verify(mirror, never()).syncFromStripe(any());
    }

    @Test
    void status_triggers_lazy_sync_when_never_synced() {
        StripeClient stripeClient = mock(StripeClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);

        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setId(orgId);
        org.setStripeAccountId("acct_2");
        org.setStripeConnectState(StripeConnectState.ONBOARDING);
        org.setStripeConnectStatusUpdatedAt(null); // never synced
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService svc = new StripeConnectService(
                stripeClient, orgs, new StripeProperties(), null, mirror);
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), orgId, "u@example.com", java.util.Set.of());

        svc.getStatus(p, orgId);

        verify(mirror).syncFromStripe("acct_2");
    }

    @Test
    void status_returns_not_started_when_no_account() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setId(orgId);
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService svc = new StripeConnectService(
                mock(StripeClient.class), orgs, new StripeProperties(), null,
                mock(StripeConnectStatusMirror.class));
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), orgId, "u@example.com", java.util.Set.of());

        assertThat(svc.getStatus(p, orgId).state()).isEqualTo(StripeConnectState.NOT_STARTED);
    }
}
```

- [ ] **Step 2: Run test — FAIL (constructor + StatusResult fields don't match)**

Run: `./mvnw test -Dtest=StripeConnectServiceStatusTest`
Expected: FAIL — compilation error.

- [ ] **Step 3: Refactor `StripeConnectService`**

In `StripeConnectService`:

1. Add `StripeConnectStatusMirror` constructor param + field.

Replace the primary constructor:

```java
@org.springframework.beans.factory.annotation.Autowired
public StripeConnectService(StripeClient stripeClient,
                            OrganizationRepository orgs,
                            StripeProperties props,
                            AuditLogger auditLogger,
                            StripeConnectStatusMirror mirror) {
    this.stripeClient = stripeClient;
    this.orgs = orgs;
    this.props = props;
    this.auditLogger = auditLogger;
    this.mirror = mirror;
}

/** Legacy 3-arg constructor for existing tests. */
public StripeConnectService(StripeClient stripeClient,
                            OrganizationRepository orgs,
                            StripeProperties props) {
    this(stripeClient, orgs, props, null, null);
}
```

Add field: `private final StripeConnectStatusMirror mirror;`

2. Replace `getStatus`:

```java
@Transactional(readOnly = true)
public StatusResult getStatus(AuthPrincipal principal, UUID orgId) {
    Organization org = loadOwnedOrg(principal, orgId);

    if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) {
        return new StatusResult(null, StripeConnectState.NOT_STARTED, false, false,
                java.util.List.of(), java.util.List.of(), null);
    }

    // Lazy first-read: if we've never received a webhook for this account, fetch
    // synchronously this one time so the FE doesn't see stale ONBOARDING forever.
    if (org.getStripeConnectStatusUpdatedAt() == null && mirror != null) {
        mirror.syncFromStripe(org.getStripeAccountId());
        org = orgs.findById(orgId).orElse(org); // re-read for the updated columns
    }

    return new StatusResult(
            org.getStripeAccountId(),
            org.getStripeConnectState(),
            org.isStripePayoutsEnabled(),
            org.isStripeDetailsSubmitted(),
            java.util.List.copyOf(org.getStripeRequirementsCurrentlyDue()),
            java.util.List.copyOf(org.getStripeRequirementsPastDue()),
            org.getStripeDisabledReason());
}
```

3. Replace the `StatusResult` record:

```java
public record StatusResult(String accountId,
                           StripeConnectState state,
                           boolean readyToReceivePayments,
                           boolean detailsSubmitted,
                           java.util.List<String> currentlyDue,
                           java.util.List<String> pastDue,
                           String disabledReason) {}
```

4. The old `getStatus` no longer needs `readTransferStatus` / `readRequirementsStatus`. Delete them (they're inlined to the mirror now).

- [ ] **Step 4: Re-run test — expect PASS**

Run: `./mvnw test -Dtest=StripeConnectServiceStatusTest`
Expected: PASS — 3 cases green.

- [ ] **Step 5: Run the full Stripe test suite to catch downstream breakage**

Run: `./mvnw test -Dtest='com.imin.iminapi.stripe.*'`
Expected: PASS. Tests that constructed `StripeConnectService` 3-arg keep working via the legacy ctor.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripeConnectService.java \
        src/test/java/com/imin/iminapi/stripe/StripeConnectServiceStatusTest.java
git commit -m "refactor(stripe): getStatus reads from mirrored columns"
```

---

### Task 7: Expose new fields on the controller DTO

**Files:**
- Modify: `src/main/java/com/imin/iminapi/stripe/StripeConnectController.java:55-75`

- [ ] **Step 1: Replace `StatusResponse` and the mapping**

```java
@GetMapping("/status")
public StatusResponse status(@CurrentUser AuthPrincipal p, @PathVariable UUID orgId) {
    var s = connect.getStatus(p, orgId);
    return new StatusResponse(
            s.accountId(),
            s.state(),
            s.readyToReceivePayments(),
            s.detailsSubmitted(),
            s.currentlyDue(),
            s.pastDue(),
            s.disabledReason());
}

public record StatusResponse(String accountId,
                              StripeConnectState state,
                              boolean readyToReceivePayments,
                              boolean detailsSubmitted,
                              java.util.List<String> currentlyDue,
                              java.util.List<String> pastDue,
                              String disabledReason) {}
```

(Keep the other records `ConnectResponse`, `AccountSessionResponse`, `OnboardingLinkRequest`, `OnboardingLinkResponse` untouched.)

- [ ] **Step 2: Verify Swagger sees the new shape**

Run: `./mvnw spring-boot:run` in one terminal, then:

```bash
curl -s http://localhost:8085/v3/api-docs.yaml | grep -A 30 '/api/v1/orgs/{orgId}/stripe/status'
```

Expected: Response schema mentions `state`, `currentlyDue`, `pastDue`, `disabledReason`. Stop the server (Ctrl-C).

- [ ] **Step 3: Run full Stripe test suite**

Run: `./mvnw test -Dtest='com.imin.iminapi.stripe.*'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripeConnectController.java
git commit -m "feat(stripe): /status returns state enum + currently_due list"
```

---

### Task 8: CLAUDE.md updates

**Files:**
- Modify: `CLAUDE.md` (Stripe Connect section, around line "State model")

- [ ] **Step 1: Replace the "State model" paragraph**

Find:

> State model: account ids are persisted (`organizations.stripe_account_id`, `ticket_tiers.stripe_product_id`, `ticket_tiers.stripe_price_id`); onboarding/capability state is fetched live from Stripe on every status read.

Replace with:

> State model: account ids are persisted (`organizations.stripe_account_id`, `ticket_tiers.stripe_product_id`, `ticket_tiers.stripe_price_id`). Connect onboarding/capability state is **mirrored locally** to `organizations.stripe_connect_state` (enum: `NOT_STARTED` / `ONBOARDING` / `PENDING_VERIFICATION` / `RESTRICTED` / `ACTIVE`) plus `stripe_payouts_enabled`, `stripe_details_submitted`, `stripe_requirements_currently_due` (jsonb), `stripe_requirements_past_due` (jsonb), `stripe_disabled_reason`, `stripe_connect_status_updated_at`. The mirror is driven by the v2 webhook (`requirements.updated`, `capability_status_updated`) via `StripeConnectStatusMirror`. `GET /stripe/status` reads from the mirror; the first call after `POST /stripe/connect` triggers a one-shot lazy sync. `details_submitted` is sticky — once observed true, never un-flagged.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update Stripe Connect state-model note for mirrored columns"
```

---

## Self-Review Notes

- **Spec coverage:** All five states are produced by `derive()`. Lazy first-read covers existing accounts without forcing a backfill migration. Webhook persists on both subscribed v2 event types.
- **Type consistency:** Status field/method names are consistent across mirror → service → controller (`state`, `payoutsEnabled`/`readyToReceivePayments`, `detailsSubmitted`, `currentlyDue`, `pastDue`, `disabledReason`).
- **No placeholders:** Every step has explicit code, exact commands, and expected outputs.
