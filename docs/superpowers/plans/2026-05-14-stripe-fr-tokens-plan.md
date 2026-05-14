# Stripe Connect: FR Account Tokens + Restored Non-FR Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the Stripe Connect onboarding path on `organization.country`. **FR orgs** mint Account Tokens + Person Tokens client-side via Stripe.js and the backend creates the v2 connected account using `setAccountToken` (PSD2-compliant — no PII touches our server). **Non-FR orgs** get back the richer server-side create payload (with `configuration.recipient` + `stripe_transfers.requested=true`) that was stripped in commit `52e327c` to unblock FR. Both paths use the **v2 Accounts API** — the stripe-java 32.1.0 SDK supports tokens on v2 (`AccountCreateParams.setAccountToken`, `PersonCreateParams.setPersonToken`, `stripeClient.v2().core().accounts().persons().create(...)`).

**Architecture:** Country-aware branch lives in `StripeConnectService.getOrCreateAccount`. New request body on `POST /api/v1/orgs/{orgId}/stripe/connect` carries optional `accountToken` + `personToken`; strict validation rejects token bodies for non-FR orgs (400 INVALID_REQUEST) and missing tokens for FR orgs (400 INVALID_REQUEST). The hosted-onboarding redirect (`/onboarding-link`) stays in the flow for both paths — it picks up anything the token didn't cover (ID document scans, bank account, leftover requirements). Frontend gets `@stripe/stripe-js`, a new `VITE_STRIPE_PUBLISHABLE_KEY` env var, and a country-aware `PaymentsTab` that swaps the "Connect" button for a tokenizing form when the org is in FR.

**Tech Stack:**
- **Backend:** Java 17, Spring Boot 4.0.5, stripe-java 32.1.0, JUnit 5 + Mockito + AssertJ, H2 (PG-compat) for tests.
- **Frontend:** React 19, TypeScript strict + `noUncheckedIndexedAccess`, Vite 8, TanStack Query 5, react-hook-form + zod, CSS Modules (no Tailwind), `@stripe/stripe-js` (new — loader only, no React wrapper).

**Reference docs:**
- Stripe — [Connect Account Tokens & Person Tokens](https://docs.stripe.com/connect/account-tokens)
- Internal — `imin-api/src/main/java/com/imin/iminapi/stripe/StripeConnectService.java` (the file the branch lives in)
- Internal — `imin-webapp/src/features/settings/tabs/PaymentsTab.tsx` (current FE flow)

---

## File Map

**Backend — new (production):**
- None. All logic fits inside the existing `stripe` package.

**Backend — modified:**
- `imin-api/src/main/java/com/imin/iminapi/stripe/StripeConnectService.java` — branch on `org.country`; restore non-FR payload; add token path; new FR-only `createPersonFromToken` helper.
- `imin-api/src/main/java/com/imin/iminapi/stripe/StripeConnectController.java` — accept `ConnectRequest` body on `POST /connect`, pass tokens through, return existing `ConnectResponse`.
- `imin-api/CLAUDE.md` — document the FR vs non-FR split (one paragraph under the Stripe Connect section).

**Backend — new (tests):**
- `imin-api/src/test/java/com/imin/iminapi/stripe/StripeConnectServiceTest.java` — Mockito-driven unit test covering both branches.

**Frontend — new (production):**
- `imin-webapp/src/features/settings/tabs/payments/StripeFrTokenForm.tsx` — the FR-only tokenizing form (company + representative sections).
- `imin-webapp/src/features/settings/tabs/payments/stripeFrSchema.ts` — zod schema + types.
- `imin-webapp/src/shared/stripe/loadStripeJs.ts` — thin lazy wrapper around `loadStripe` (so we don't eagerly load `js.stripe.com` on every dashboard mount).

**Frontend — modified:**
- `imin-webapp/package.json` — add `@stripe/stripe-js`.
- `imin-webapp/.env.example` — document `VITE_STRIPE_PUBLISHABLE_KEY`.
- `imin-webapp/vercel.json` — add `https://js.stripe.com https://api.stripe.com` to `connect-src`; add `https://js.stripe.com` to `script-src`.
- `imin-webapp/src/features/settings/tabs/PaymentsTab.tsx` — country-aware: render `StripeFrTokenForm` for FR orgs (State 1 only — once the account exists, both flows converge on the same "Continue onboarding" / "Ready" states), unchanged for everyone else.
- `imin-webapp/src/shared/copy.ts` — new keys for the FR form (section titles, field labels, TOS disclaimer, submit button copy, error toast).
- `imin-webapp/src/features/settings/Settings.module.css` — minor additions for the form rows (only if existing classes don't compose).

---

## Notes for the implementer

- **Run backend tests:** `./mvnw test`. Single class: `./mvnw test -Dtest=StripeConnectServiceTest`. The test profile uses H2 in PG-compat mode — no Stripe HTTP is touched (`StripeClient` is mocked).
- **Run frontend typecheck:** `npm run typecheck` from `imin-webapp/`. There is no JS test runner. Build verifies via `tsc --noEmit && vite build`.
- **No Tailwind on the webapp.** Use CSS Modules + the existing `Settings.module.css` patterns (`styles.stack`, `styles.stripeRow`, etc.). If you need new classes, add them to `Settings.module.css`. Tokens come from `src/styles/variables.css` — don't hardcode colors.
- **`apiFetch` is the only way to call the backend.** It auto-attaches JWT, handles 401 redirect globally, and (for POSTs in `IDEMPOTENT_ENDPOINTS`) auto-generates an idempotency key. `/stripe/connect` is already in that allowlist via the existing flow — no change needed.
- **Stripe.js loader is async.** Always `await loadStripeJs()` (returns `Stripe | null`); the helper returns `null` if the publishable key is missing — the form must early-exit with a toast in that case rather than crashing.
- **Token validity is ~1 hour.** No need to cache them. Mint per-submit.
- **TOS acceptance is required for FR.** The `tos_shown_and_accepted: true` flag inside `stripe.createToken('account', …)` is what populates `account.tos_acceptance.{date,ip,user_agent}`. The form must visibly show language that links to Stripe's connect-account agreement and your own ToS before submit (covered by the new copy keys).
- **`AccountCreateParams.setAccountToken` lives on v2** — `com.stripe.param.v2.core.AccountCreateParams`, set via the existing `AccountCreateParams.builder()`. No need to migrate to the v1 API.
- **Person create returns `com.stripe.model.v2.core.AccountPerson`** (not `Person`). We only need the call to succeed; we don't persist the id.
- **CSP-blocked requests are silent in prod.** If you forget to update `vercel.json`, `js.stripe.com` and `api.stripe.com` will be blocked at the browser; you'll see CSP-violation messages in DevTools only. Test the deployed preview URL, not just local dev.
- **Address country in the tokenization payload.** The doc samples don't include `country` inside `company.address` / `person.address`, but Stripe requires it for FR. Include `country: 'FR'` in both `company.address` and `person.address` (and the field is also already known from `org.country`).

---

## Task 1: Restore non-FR v2 create payload

**Goal:** Bring back the richer v2 `AccountCreateParams` payload that commits `52e327c` and `9f4b6df` stripped to keep FR onboarding working. After this task, every org (still no country branch yet) gets `configuration.recipient.capabilities.stripe_balance.stripe_transfers.requested=true`, EUR defaults, and fees/losses-collector = STRIPE — same as commit `5351a0f`'s shape, but with `identity.country = org.getCountry()` (not hardcoded `"us"`). This task **introduces a regression for FR orgs** that the next task fixes; for now we're rebuilding from the working baseline.

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/stripe/StripeConnectService.java` (lines 75–136, the `getOrCreateAccount` method body)

- [ ] **Step 1.1: Add `configuration.recipient` block back to the params builder**

Open `imin-api/src/main/java/com/imin/iminapi/stripe/StripeConnectService.java`. Replace the body of `getOrCreateAccount` (lines 75–136) with the version below. The diff vs. the current file:
- Drops the long FR/PSD2 comment block on lines 49–73 (it's about to be wrong — the next task adds country-aware logic).
- Adds `.setConfiguration(...)` with `recipient.capabilities.stripe_balance.stripe_transfers.requested(true)`.
- Keeps `identity.country = org.getCountry()` (no change there).

```java
    /**
     * Step 1 — Create the v2 connected account.
     *
     * Idempotent: if the org already has a connected account, return that id and {@code created=false}.
     * Otherwise create one via the v2 Accounts API and persist the id.
     *
     * Sends server-side: display_name, contact_email, dashboard=express, identity.country (from org),
     * EUR defaults with locale=en_us and fees/losses-collector=STRIPE, and
     * configuration.recipient with the stripe_transfers capability requested. The hosted onboarding
     * link (see {@link #createOnboardingLink}) attaches MERCHANT + RECIPIENT requirements and
     * collects the legal-entity / person fields.
     */
    @Transactional
    public ConnectResult getOrCreateAccount(AuthPrincipal principal, UUID orgId) {
        Organization org = loadOwnedOrg(principal, orgId);

        // Idempotency guard — the spec calls for a simple DB check, not a Stripe-side idempotency key.
        if (org.getStripeAccountId() != null && !org.getStripeAccountId().isBlank()) {
            return new ConnectResult(org.getStripeAccountId(), false);
        }

        AccountCreateParams params =
                AccountCreateParams.builder()
                        .setContactEmail(org.getContactEmail())
                        .setDisplayName(org.getName())
                        .setDashboard(AccountCreateParams.Dashboard.EXPRESS)
                        .setIdentity(
                                AccountCreateParams.Identity.builder()
                                        .setCountry(org.getCountry())
                                        .build()
                        )
                        .setConfiguration(
                                AccountCreateParams.Configuration.builder()
                                        .setRecipient(
                                                AccountCreateParams.Configuration.Recipient.builder()
                                                        .setCapabilities(
                                                                AccountCreateParams.Configuration.Recipient.Capabilities.builder()
                                                                        .setStripeBalance(
                                                                                AccountCreateParams.Configuration.Recipient.Capabilities.StripeBalance.builder()
                                                                                        .setStripeTransfers(
                                                                                                AccountCreateParams.Configuration.Recipient.Capabilities.StripeBalance.StripeTransfers.builder()
                                                                                                        .setRequested(true)
                                                                                                        .build()
                                                                                        )
                                                                                        .build()
                                                                        )
                                                                        .build()
                                                        )
                                                        .build()
                                        )
                                        .build()
                        )
                        .setDefaults(
                                AccountCreateParams.Defaults.builder()
                                        .setCurrency("eur")
                                        .setResponsibilities(
                                                AccountCreateParams.Defaults.Responsibilities.builder()
                                                        .setFeesCollector(
                                                                AccountCreateParams.Defaults.Responsibilities.FeesCollector.STRIPE
                                                        )
                                                        .setLossesCollector(
                                                                AccountCreateParams.Defaults.Responsibilities.LossesCollector.STRIPE
                                                        )
                                                        .build()
                                        )
                                        .addLocale(AccountCreateParams.Defaults.Locale.EN_US)
                                        .build()
                        )
                        .addInclude(AccountCreateParams.Include.IDENTITY)
                        .addInclude(AccountCreateParams.Include.REQUIREMENTS)
                        .build();

        Account account;
        try {
            account = stripeClient.v2().core().accounts().create(params);
        } catch (StripeException e) {
            log.error("Stripe v2 account create failed for org {}: {}", orgId, e.getMessage(), e);
            throw upstream("Failed to create Stripe connected account: " + e.getMessage(), e);
        }

        org.setStripeAccountId(account.getId());
        orgs.save(org);
        return new ConnectResult(account.getId(), true);
    }
```

- [ ] **Step 1.2: Verify the project still compiles**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 1.3: Commit**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripeConnectService.java
git commit -m "Stripe Connect v2: restore configuration.recipient + EUR defaults on create

Reverts the FR-driven strip-out from 52e327c and 9f4b6df. The next
commit reintroduces the FR/PSD2 path as a country-aware branch using
Account Tokens minted client-side via Stripe.js."
```

---

## Task 2: Add country-aware branch + token-based FR path in `getOrCreateAccount`

**Goal:** Pass token IDs into the service and use them when `org.country == "FR"`. For FR, send only `account_token` + dashboard + defaults — no `identity`, no `configuration` server-side. For non-FR, keep the payload from Task 1. After the account is created on the FR path, optionally create a Person from the person token.

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/stripe/StripeConnectService.java`

- [ ] **Step 2.1: Add the imports + the request DTO at the bottom of the file**

Add to the imports at the top:

```java
import com.stripe.param.v2.core.accounts.PersonCreateParams;
```

Add this record at the bottom of `StripeConnectService.java`, next to the existing `ConnectResult` / `StatusResult` records:

```java
    /**
     * Request body for {@link #getOrCreateAccount(AuthPrincipal, UUID, ConnectTokens)}.
     *
     * For FR orgs both tokens are required (rejected with INVALID_REQUEST otherwise).
     * For non-FR orgs both must be null (token bodies are strictly rejected so we never
     * accidentally route PII through a non-PSD2 path).
     */
    public record ConnectTokens(String accountToken, String personToken) {
        public static ConnectTokens empty() { return new ConnectTokens(null, null); }
        public boolean isPresent() { return accountToken != null || personToken != null; }
    }
```

- [ ] **Step 2.2: Update `getOrCreateAccount` signature and add the FR branch**

Replace the method (the entire `getOrCreateAccount` you wrote in Task 1) with this country-aware version. Key points:
- Signature now takes a `ConnectTokens tokens` argument.
- Strict validation up front: FR requires both tokens, non-FR rejects both.
- FR branch builds a minimal `AccountCreateParams` with only `setAccountToken` + dashboard + defaults.
- After FR create succeeds, the person token is consumed via `v2().core().accounts().persons().create(...)`.
- Non-FR branch keeps the Task 1 payload.

```java
    @Transactional
    public ConnectResult getOrCreateAccount(AuthPrincipal principal, UUID orgId, ConnectTokens tokens) {
        Organization org = loadOwnedOrg(principal, orgId);

        if (org.getStripeAccountId() != null && !org.getStripeAccountId().isBlank()) {
            return new ConnectResult(org.getStripeAccountId(), false);
        }

        boolean isFr = "FR".equalsIgnoreCase(org.getCountry());
        validateTokens(tokens, isFr);

        AccountCreateParams params = isFr
                ? buildFrCreateParams(org, tokens)
                : buildDefaultCreateParams(org);

        Account account;
        try {
            account = stripeClient.v2().core().accounts().create(params);
        } catch (StripeException e) {
            log.error("Stripe v2 account create failed for org {}: {}", orgId, e.getMessage(), e);
            throw upstream("Failed to create Stripe connected account: " + e.getMessage(), e);
        }

        if (isFr && tokens.personToken() != null) {
            try {
                stripeClient.v2().core().accounts().persons().create(
                        account.getId(),
                        PersonCreateParams.builder()
                                .setPersonToken(tokens.personToken())
                                .build()
                );
            } catch (StripeException e) {
                log.error("Stripe v2 person create failed for FR org {} (account {}): {}",
                        orgId, account.getId(), e.getMessage(), e);
                throw upstream("Failed to attach Stripe person from token: " + e.getMessage(), e);
            }
        }

        org.setStripeAccountId(account.getId());
        orgs.save(org);
        return new ConnectResult(account.getId(), true);
    }

    private static void validateTokens(ConnectTokens tokens, boolean isFr) {
        if (isFr) {
            if (tokens == null
                    || tokens.accountToken() == null || tokens.accountToken().isBlank()
                    || tokens.personToken() == null || tokens.personToken().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                        "FR organisations must provide both accountToken and personToken");
            }
        } else if (tokens != null && tokens.isPresent()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "accountToken / personToken are only accepted for FR organisations");
        }
    }

    private static AccountCreateParams buildFrCreateParams(Organization org, ConnectTokens tokens) {
        // FR/PSD2: legal-entity and person details must arrive via the Stripe.js-minted account token.
        // We deliberately do NOT set identity.* or configuration.* server-side — Stripe rejects that
        // combination with account_token_required when the platform country is FR.
        return AccountCreateParams.builder()
                .setContactEmail(org.getContactEmail())
                .setDisplayName(org.getName())
                .setDashboard(AccountCreateParams.Dashboard.EXPRESS)
                .setAccountToken(tokens.accountToken())
                .setDefaults(
                        AccountCreateParams.Defaults.builder()
                                .setCurrency("eur")
                                .setResponsibilities(
                                        AccountCreateParams.Defaults.Responsibilities.builder()
                                                .setFeesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities.FeesCollector.STRIPE
                                                )
                                                .setLossesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities.LossesCollector.STRIPE
                                                )
                                                .build()
                                )
                                .addLocale(AccountCreateParams.Defaults.Locale.EN_US)
                                .build()
                )
                .addInclude(AccountCreateParams.Include.IDENTITY)
                .addInclude(AccountCreateParams.Include.REQUIREMENTS)
                .build();
    }

    private static AccountCreateParams buildDefaultCreateParams(Organization org) {
        return AccountCreateParams.builder()
                .setContactEmail(org.getContactEmail())
                .setDisplayName(org.getName())
                .setDashboard(AccountCreateParams.Dashboard.EXPRESS)
                .setIdentity(
                        AccountCreateParams.Identity.builder()
                                .setCountry(org.getCountry())
                                .build()
                )
                .setConfiguration(
                        AccountCreateParams.Configuration.builder()
                                .setRecipient(
                                        AccountCreateParams.Configuration.Recipient.builder()
                                                .setCapabilities(
                                                        AccountCreateParams.Configuration.Recipient.Capabilities.builder()
                                                                .setStripeBalance(
                                                                        AccountCreateParams.Configuration.Recipient.Capabilities.StripeBalance.builder()
                                                                                .setStripeTransfers(
                                                                                        AccountCreateParams.Configuration.Recipient.Capabilities.StripeBalance.StripeTransfers.builder()
                                                                                                .setRequested(true)
                                                                                                .build()
                                                                                )
                                                                                .build()
                                                                )
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .setDefaults(
                        AccountCreateParams.Defaults.builder()
                                .setCurrency("eur")
                                .setResponsibilities(
                                        AccountCreateParams.Defaults.Responsibilities.builder()
                                                .setFeesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities.FeesCollector.STRIPE
                                                )
                                                .setLossesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities.LossesCollector.STRIPE
                                                )
                                                .build()
                                )
                                .addLocale(AccountCreateParams.Defaults.Locale.EN_US)
                                .build()
                )
                .addInclude(AccountCreateParams.Include.IDENTITY)
                .addInclude(AccountCreateParams.Include.REQUIREMENTS)
                .build();
    }
```

The Task 1 inline payload is now `buildDefaultCreateParams`. Remove the duplicate code from inside `getOrCreateAccount`.

- [ ] **Step 2.3: Verify the project compiles**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS. If you get "method getOrCreateAccount(AuthPrincipal, UUID) does not exist", that's the controller still calling the old 2-arg signature — Task 3 fixes it.

- [ ] **Step 2.4: (No commit yet)** Task 3 must land in the same commit because the controller compile-error from Step 2.3 makes Task 2 unbuildable on its own.

---

## Task 3: Plumb the token body through `StripeConnectController`

**Goal:** Accept `accountToken` + `personToken` on `POST /api/v1/orgs/{orgId}/stripe/connect`. The body is optional (so non-FR orgs can keep posting an empty body) but strictly validated in the service.

**Files:**
- Modify: `imin-api/src/main/java/com/imin/iminapi/stripe/StripeConnectController.java`

- [ ] **Step 3.1: Add a request record and update the `connect` endpoint**

Replace the body of `StripeConnectController.connect(...)` (lines 27–31) plus the inline DTO records (lines 52–58) with:

```java
    /**
     * Idempotent — if an account already exists for the org, returns the same id and
     * {@code created=false}. Body is optional for non-FR orgs (empty / absent is fine).
     * FR orgs MUST include both {@code accountToken} and {@code personToken} (see
     * {@link StripeConnectService.ConnectTokens}); the service strictly rejects token
     * bodies posted for non-FR orgs.
     */
    @PostMapping("/connect")
    public ConnectResponse connect(@CurrentUser AuthPrincipal p,
                                   @PathVariable UUID orgId,
                                   @RequestBody(required = false) ConnectRequest body) {
        var tokens = body == null
                ? StripeConnectService.ConnectTokens.empty()
                : new StripeConnectService.ConnectTokens(body.accountToken(), body.personToken());
        var r = connect.getOrCreateAccount(p, orgId, tokens);
        return new ConnectResponse(r.accountId(), r.created());
    }

    // Wire-shape DTOs — kept inline because they only exist on this surface.

    public record ConnectRequest(String accountToken, String personToken) {}

    public record ConnectResponse(String accountId, boolean created) {}
```

Leave the `OnboardingLinkRequest`, `OnboardingLinkResponse`, and `StatusResponse` records as-is.

- [ ] **Step 3.2: Build the project**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3.3: Commit Tasks 2 and 3 together**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripeConnectService.java \
        src/main/java/com/imin/iminapi/stripe/StripeConnectController.java
git commit -m "Stripe Connect: FR account-token path, strict non-FR

POST /api/v1/orgs/{orgId}/stripe/connect now accepts an optional
{accountToken, personToken} body. For FR orgs both are required and
the v2 create uses setAccountToken with no server-side identity. For
non-FR orgs token bodies are strictly rejected (400 INVALID_REQUEST)
and the create payload from 5351a0f is restored (configuration.recipient
+ stripe_transfers.requested=true, EUR defaults). Person tokens are
consumed via v2().core().accounts().persons().create after account
creation."
```

---

## Task 4: Service tests — both branches + validation errors

**Goal:** Lock in the country-aware branching with a single Mockito-driven test class. We don't unit-test the actual Stripe HTTP call (the SDK is hard to fake meaningfully); we verify that the `AccountCreateParams` the service sends to the mocked client matches what we expect for each branch, and that validation errors fire correctly.

**Files:**
- Create: `imin-api/src/test/java/com/imin/iminapi/stripe/StripeConnectServiceTest.java`

- [ ] **Step 4.1: Write the failing test**

Create `imin-api/src/test/java/com/imin/iminapi/stripe/StripeConnectServiceTest.java`:

```java
package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.stripe.StripeClient;
import com.stripe.model.v2.core.Account;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.param.v2.core.accounts.PersonCreateParams;
import com.stripe.service.v2.core.AccountService;
import com.stripe.service.v2.core.CoreService;
import com.stripe.service.v2.V2Services;
import com.stripe.service.v2.core.accounts.PersonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeConnectServiceTest {

    private StripeClient stripeClient;
    private V2Services v2Services;
    private CoreService coreService;
    private AccountService accountService;
    private PersonService personService;
    private OrganizationRepository orgs;
    private StripeProperties props;
    private StripeConnectService svc;

    private final UUID orgId = UUID.randomUUID();
    private final AuthPrincipal principal = new AuthPrincipal(UUID.randomUUID(), orgId, "u@example.com");

    @BeforeEach
    void setUp() throws Exception {
        stripeClient = mock(StripeClient.class);
        v2Services = mock(V2Services.class);
        coreService = mock(CoreService.class);
        accountService = mock(AccountService.class);
        personService = mock(PersonService.class);
        when(stripeClient.v2()).thenReturn(v2Services);
        when(v2Services.core()).thenReturn(coreService);
        when(coreService.accounts()).thenReturn(accountService);
        when(accountService.persons()).thenReturn(personService);

        orgs = mock(OrganizationRepository.class);
        props = new StripeProperties();
        svc = new StripeConnectService(stripeClient, orgs, props);

        Account created = mock(Account.class);
        when(created.getId()).thenReturn("acct_test_123");
        when(accountService.create(any(AccountCreateParams.class))).thenReturn(created);
    }

    @Test
    void nonFrOrg_sendsConfigurationRecipientPayload_andRejectsTokenBody() throws Exception {
        Organization org = org("US");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        // Empty token body succeeds for non-FR
        var result = svc.getOrCreateAccount(principal, orgId,
                StripeConnectService.ConnectTokens.empty());
        assertThat(result.accountId()).isEqualTo("acct_test_123");
        assertThat(result.created()).isTrue();

        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        verify(accountService, times(1)).create(captor.capture());
        AccountCreateParams sent = captor.getValue();
        assertThat(sent.getAccountToken()).isNull();
        assertThat(sent.getIdentity()).isNotNull();
        assertThat(sent.getIdentity().getCountry()).isEqualTo("US");
        assertThat(sent.getConfiguration()).isNotNull();
        // recipient.capabilities.stripe_balance.stripe_transfers.requested = true
        assertThat(sent.getConfiguration().getRecipient()).isNotNull();
        assertThat(sent.getConfiguration().getRecipient().getCapabilities()).isNotNull();
        verify(personService, never()).create(any(String.class), any(PersonCreateParams.class));
    }

    @Test
    void nonFrOrg_withTokenBody_is400() {
        Organization org = org("US");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> svc.getOrCreateAccount(principal, orgId,
                new StripeConnectService.ConnectTokens("ct_acct_x", null)))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void frOrg_withBothTokens_setsAccountToken_andCreatesPerson() throws Exception {
        Organization org = org("FR");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        svc.getOrCreateAccount(principal, orgId,
                new StripeConnectService.ConnectTokens("ct_acct_fr", "ct_person_fr"));

        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        verify(accountService).create(captor.capture());
        AccountCreateParams sent = captor.getValue();
        assertThat(sent.getAccountToken()).isEqualTo("ct_acct_fr");
        // FR path MUST NOT send identity or configuration server-side
        assertThat(sent.getIdentity()).isNull();
        assertThat(sent.getConfiguration()).isNull();

        ArgumentCaptor<PersonCreateParams> pcap = ArgumentCaptor.forClass(PersonCreateParams.class);
        verify(personService).create(eq("acct_test_123"), pcap.capture());
        assertThat(pcap.getValue().getPersonToken()).isEqualTo("ct_person_fr");
    }

    @Test
    void frOrg_missingAccountToken_is400() {
        Organization org = org("FR");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> svc.getOrCreateAccount(principal, orgId,
                new StripeConnectService.ConnectTokens(null, "ct_person_fr")))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void frOrg_missingPersonToken_is400() {
        Organization org = org("FR");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> svc.getOrCreateAccount(principal, orgId,
                new StripeConnectService.ConnectTokens("ct_acct_fr", null)))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void idempotent_skipsStripeWhenAccountAlreadyExists() throws Exception {
        Organization org = org("FR");
        org.setStripeAccountId("acct_existing");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        var result = svc.getOrCreateAccount(principal, orgId,
                StripeConnectService.ConnectTokens.empty());
        assertThat(result.accountId()).isEqualTo("acct_existing");
        assertThat(result.created()).isFalse();
        verify(accountService, never()).create(any(AccountCreateParams.class));
        verify(personService, never()).create(any(String.class), any(PersonCreateParams.class));
    }

    private Organization org(String country) {
        Organization o = new Organization();
        o.setId(orgId);
        o.setOwnerId(principal.userId());
        o.setName("Acme " + country);
        o.setContactEmail("contact@acme.example");
        o.setCountry(country);
        return o;
    }
}
```

> **Note on imports:** the exact package path for `V2Services` and `CoreService` is `com.stripe.service.v2.V2Services` and `com.stripe.service.v2.core.CoreService` in stripe-java 32.1.0. If your IDE flags the import, peek at `stripeClient.v2().getClass()` to confirm — the SDK does sometimes shuffle these between minor versions.

- [ ] **Step 4.2: Run the new test class**

Run: `./mvnw -q test -Dtest=StripeConnectServiceTest`
Expected: 6 tests pass.

If `AuthPrincipal`'s constructor signature differs from `(UUID userId, UUID orgId, String email)`, peek at the record and adjust the test setup — don't change the production class.

If `Organization`'s setters (`setOwnerId`, etc.) don't exist (Lombok-generated), inspect the class and adapt — the test doesn't need to set fields the service doesn't read.

- [ ] **Step 4.3: Commit**

```bash
git add src/test/java/com/imin/iminapi/stripe/StripeConnectServiceTest.java
git commit -m "Stripe Connect tests: country branch + token validation"
```

---

## Task 5: Document the FR path in the backend CLAUDE.md

**Goal:** Drop the misleading FR/PSD2 comments and add a short paragraph in the project-level `CLAUDE.md` so the next engineer sees the country branch.

**Files:**
- Modify: `imin-api/CLAUDE.md` (the `### Stripe Connect` section)

- [ ] **Step 5.1: Append the FR paragraph**

Locate the existing `### Stripe Connect` section in `imin-api/CLAUDE.md` (lines starting with "Per-org Stripe v2 connected accounts..."). Add this paragraph immediately after the existing "Endpoints:" bullet list:

```markdown
**FR vs non-FR onboarding (added 2026-05-14).** `StripeConnectService.getOrCreateAccount` branches on `organization.country`:
- **FR orgs (PSD2):** the `POST /stripe/connect` body must include `accountToken` + `personToken` minted by Stripe.js in the organizer dashboard (`@stripe/stripe-js` → `stripe.createToken('account', ...)` + `stripe.createToken('person', ...)`). The v2 create call sends only `accountToken`, dashboard, and EUR defaults — no `identity` or `configuration` server-side. The person token is consumed via `v2().core().accounts().persons().create(...)` immediately after the account is created. v0 supports `business_type=company` only.
- **Non-FR orgs:** `POST /stripe/connect` body must be empty (token bodies are strictly rejected with `400 INVALID_REQUEST`). The v2 create includes `identity.country = org.country`, `configuration.recipient.capabilities.stripe_balance.stripe_transfers.requested=true`, and EUR defaults.

Both paths still finish through the hosted `/stripe/onboarding-link` redirect — that picks up ID-document uploads, bank account, and any leftover requirements the tokens didn't cover.
```

- [ ] **Step 5.2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document FR vs non-FR Stripe Connect branches"
```

---

## Task 6 (frontend): Add `@stripe/stripe-js`, env var, and CSP entries

> All Task 6+ work happens in the **`imin-webapp`** repo. `cd ../imin-webapp` (or however your worktree is organised) before running these commands.

**Goal:** Get the Stripe loader on the classpath, document the publishable key, and unblock the Stripe origins in the prod CSP.

**Files:**
- Modify: `imin-webapp/package.json`, `imin-webapp/package-lock.json`
- Modify: `imin-webapp/.env.example`
- Modify: `imin-webapp/vercel.json`
- Create: `imin-webapp/src/shared/stripe/loadStripeJs.ts`

- [ ] **Step 6.1: Install the loader package**

Run from `imin-webapp/`:

```bash
npm install @stripe/stripe-js
```

Expected: package.json picks up `"@stripe/stripe-js": "^X.Y.Z"`; `package-lock.json` updates.

- [ ] **Step 6.2: Document the env var in `.env.example`**

Add to `imin-webapp/.env.example` immediately after the `VITE_MAPBOX_TOKEN` block:

```bash
# Stripe.js publishable key. ONLY used for FR organisations to tokenize the
# Connect onboarding payload (Account Token + Person Token). Non-FR orgs skip
# Stripe.js entirely. Get a test key at https://dashboard.stripe.com/apikeys —
# pk_test_... locally, pk_live_... in prod. Public, safe to ship in the bundle.
VITE_STRIPE_PUBLISHABLE_KEY=
```

- [ ] **Step 6.3: Update the prod CSP in `vercel.json`**

Replace the `Content-Security-Policy` value. Current value:

```
default-src 'self'; script-src 'self' https://va.vercel-scripts.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: https:; connect-src 'self' https://api.imin.wtf https://imin-api-production.up.railway.app https://vitals.vercel-insights.com https://va.vercel-scripts.com https://*.ingest.de.sentry.io https://api.mapbox.com; frame-ancestors 'none'
```

New value (added `https://js.stripe.com` to `script-src` and `https://api.stripe.com https://js.stripe.com` to `connect-src`):

```
default-src 'self'; script-src 'self' https://va.vercel-scripts.com https://js.stripe.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: https:; connect-src 'self' https://api.imin.wtf https://imin-api-production.up.railway.app https://vitals.vercel-insights.com https://va.vercel-scripts.com https://*.ingest.de.sentry.io https://api.mapbox.com https://api.stripe.com https://js.stripe.com; frame-ancestors 'none'
```

- [ ] **Step 6.4: Create the lazy Stripe loader**

Create `imin-webapp/src/shared/stripe/loadStripeJs.ts`:

```ts
import { loadStripe, type Stripe } from '@stripe/stripe-js';

const pk = import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY;

let cached: Promise<Stripe | null> | null = null;

/**
 * Lazy Stripe.js loader. Returns null if VITE_STRIPE_PUBLISHABLE_KEY is unset
 * (so non-FR builds and dev environments without a key don't crash when the
 * file is imported — the FR form is the only thing that calls this).
 *
 * The promise is cached across calls so we only inject js.stripe.com once.
 */
export function loadStripeJs(): Promise<Stripe | null> {
  if (!pk) return Promise.resolve(null);
  if (!cached) cached = loadStripe(pk);
  return cached;
}
```

- [ ] **Step 6.5: Verify the typecheck still passes**

Run: `npm run typecheck`
Expected: no errors. (The file isn't imported yet from app code, but `tsc` should accept it.)

- [ ] **Step 6.6: Commit**

```bash
git add package.json package-lock.json .env.example vercel.json src/shared/stripe/loadStripeJs.ts
git commit -m "feat(stripe): add @stripe/stripe-js loader, env var, CSP entries"
```

---

## Task 7 (frontend): The FR tokenizing form

**Goal:** A self-contained component that renders the FR-only "Connect Stripe" form, tokenizes via Stripe.js, and POSTs the tokens to `/stripe/connect`. PaymentsTab will mount it in Task 8.

**Files:**
- Create: `imin-webapp/src/features/settings/tabs/payments/stripeFrSchema.ts`
- Create: `imin-webapp/src/features/settings/tabs/payments/StripeFrTokenForm.tsx`
- Modify: `imin-webapp/src/shared/copy.ts`

- [ ] **Step 7.1: Add copy keys**

In `imin-webapp/src/shared/copy.ts`, locate the `payments: { ... }` block (the one that currently defines `connectTitle`, `connectHint`, etc.). Append these keys inside the same `payments` object:

```ts
    paymentsFr: {
      title: 'Connect Stripe to collect payments',
      hint: 'For FR organisations, Stripe requires us to collect business and representative details before creating the account. Your information is sent directly to Stripe and never touches our servers.',
      companyLegend: 'Business details',
      companyName: 'Legal business name',
      companyStreet: 'Street address',
      companyCity: 'City',
      companyState: 'State / region',
      companyPostal: 'Postal code',
      personLegend: 'Representative',
      personFirstName: 'First name',
      personLastName: 'Last name',
      personStreet: 'Street address',
      personCity: 'City',
      personState: 'State / region',
      personPostal: 'Postal code',
      tos: 'By clicking, you agree to our Terms of Service and the',
      tosLink: 'Stripe Connected Account Agreement',
      submit: 'Submit and continue to Stripe',
      stripeInitFailed: 'Could not initialise Stripe. Please contact support.',
      tokenizeFailed: 'Could not securely send your details to Stripe — please try again.',
    },
```

The TOS sentence is split into a leading half + link half so the JSX can put an `<a>` in between cleanly.

- [ ] **Step 7.2: Add the zod schema**

Create `imin-webapp/src/features/settings/tabs/payments/stripeFrSchema.ts`:

```ts
import { z } from 'zod';

const nonEmpty = (max: number) =>
  z.string().trim().min(1, 'Required').max(max, `Max ${max} characters`);

export const stripeFrSchema = z.object({
  company: z.object({
    name: nonEmpty(120),
    street: nonEmpty(200),
    city: nonEmpty(80),
    state: z.string().trim().max(80).optional().or(z.literal('')),
    postal: nonEmpty(16),
  }),
  person: z.object({
    firstName: nonEmpty(80),
    lastName: nonEmpty(80),
    street: nonEmpty(200),
    city: nonEmpty(80),
    state: z.string().trim().max(80).optional().or(z.literal('')),
    postal: nonEmpty(16),
  }),
});

export type StripeFrFormValues = z.infer<typeof stripeFrSchema>;
```

- [ ] **Step 7.3: Add the form component**

Create `imin-webapp/src/features/settings/tabs/payments/StripeFrTokenForm.tsx`:

```tsx
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import { apiFetch } from '../../../../shared/api/client';
import { copy } from '../../../../shared/copy';
import { loadStripeJs } from '../../../../shared/stripe/loadStripeJs';
import { Button, Card } from '../../../../shared/ui';
import styles from '../../Settings.module.css';
import {
  stripeFrSchema,
  type StripeFrFormValues,
} from './stripeFrSchema';

type ConnectResponse = { accountId: string; created: boolean };
type OnboardingLinkResponse = { url: string };

type Props = { orgId: string };

export function StripeFrTokenForm({ orgId }: Props) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<StripeFrFormValues>({
    resolver: zodResolver(stripeFrSchema),
    defaultValues: {
      company: { name: '', street: '', city: '', state: '', postal: '' },
      person: {
        firstName: '',
        lastName: '',
        street: '',
        city: '',
        state: '',
        postal: '',
      },
    },
  });

  const onboarding = useMutation({
    mutationFn: async (values: StripeFrFormValues) => {
      const stripe = await loadStripeJs();
      if (!stripe) throw new Error('STRIPE_INIT_FAILED');

      const accountResult = await stripe.createToken('account', {
        business_type: 'company',
        company: {
          name: values.company.name,
          address: {
            line1: values.company.street,
            city: values.company.city,
            state: values.company.state || undefined,
            postal_code: values.company.postal,
            country: 'FR',
          },
        },
        tos_shown_and_accepted: true,
      });
      if (accountResult.error || !accountResult.token) {
        throw new Error('TOKENIZE_FAILED');
      }

      const personResult = await stripe.createToken('person', {
        person: {
          first_name: values.person.firstName,
          last_name: values.person.lastName,
          address: {
            line1: values.person.street,
            city: values.person.city,
            state: values.person.state || undefined,
            postal_code: values.person.postal,
            country: 'FR',
          },
        },
      });
      if (personResult.error || !personResult.token) {
        throw new Error('TOKENIZE_FAILED');
      }

      await apiFetch<ConnectResponse>(`/orgs/${orgId}/stripe/connect`, {
        method: 'POST',
        body: {
          accountToken: accountResult.token.id,
          personToken: personResult.token.id,
        },
      });

      const origin = window.location.origin;
      const link = await apiFetch<OnboardingLinkResponse>(
        `/orgs/${orgId}/stripe/onboarding-link`,
        {
          method: 'POST',
          body: {
            returnUrl: `${origin}/settings/payments?stripe=ok`,
            refreshUrl: `${origin}/settings/payments?stripe=refresh`,
          },
        },
      );
      return link.url;
    },
    onSuccess: (url) => {
      window.location.href = url;
    },
    onError: (err) => {
      const code = err instanceof Error ? err.message : '';
      if (code === 'STRIPE_INIT_FAILED') {
        toast.error(copy.settings.paymentsFr.stripeInitFailed);
      } else if (code === 'TOKENIZE_FAILED') {
        toast.error(copy.settings.paymentsFr.tokenizeFailed);
      } else {
        toast.error(copy.errors.genericMutation);
      }
    },
  });

  const c = copy.settings.paymentsFr;

  return (
    <Card>
      <form
        className={styles.stack}
        onSubmit={handleSubmit((v) => onboarding.mutate(v))}
      >
        <header>
          <div className={styles.stripeLabel}>{c.title}</div>
          <div className={styles.stripeHint}>{c.hint}</div>
        </header>

        <fieldset className={styles.stack}>
          <legend>{c.companyLegend}</legend>
          <label>
            <span>{c.companyName}</span>
            <input type="text" autoComplete="organization" {...register('company.name')} />
            {errors.company?.name && <em>{errors.company.name.message}</em>}
          </label>
          <label>
            <span>{c.companyStreet}</span>
            <input type="text" autoComplete="street-address" {...register('company.street')} />
            {errors.company?.street && <em>{errors.company.street.message}</em>}
          </label>
          <label>
            <span>{c.companyCity}</span>
            <input type="text" autoComplete="address-level2" {...register('company.city')} />
            {errors.company?.city && <em>{errors.company.city.message}</em>}
          </label>
          <label>
            <span>{c.companyState}</span>
            <input type="text" autoComplete="address-level1" {...register('company.state')} />
          </label>
          <label>
            <span>{c.companyPostal}</span>
            <input type="text" autoComplete="postal-code" {...register('company.postal')} />
            {errors.company?.postal && <em>{errors.company.postal.message}</em>}
          </label>
        </fieldset>

        <fieldset className={styles.stack}>
          <legend>{c.personLegend}</legend>
          <label>
            <span>{c.personFirstName}</span>
            <input type="text" autoComplete="given-name" {...register('person.firstName')} />
            {errors.person?.firstName && <em>{errors.person.firstName.message}</em>}
          </label>
          <label>
            <span>{c.personLastName}</span>
            <input type="text" autoComplete="family-name" {...register('person.lastName')} />
            {errors.person?.lastName && <em>{errors.person.lastName.message}</em>}
          </label>
          <label>
            <span>{c.personStreet}</span>
            <input type="text" autoComplete="street-address" {...register('person.street')} />
            {errors.person?.street && <em>{errors.person.street.message}</em>}
          </label>
          <label>
            <span>{c.personCity}</span>
            <input type="text" autoComplete="address-level2" {...register('person.city')} />
            {errors.person?.city && <em>{errors.person.city.message}</em>}
          </label>
          <label>
            <span>{c.personState}</span>
            <input type="text" autoComplete="address-level1" {...register('person.state')} />
          </label>
          <label>
            <span>{c.personPostal}</span>
            <input type="text" autoComplete="postal-code" {...register('person.postal')} />
            {errors.person?.postal && <em>{errors.person.postal.message}</em>}
          </label>
        </fieldset>

        <p className={styles.stripeHint}>
          {c.tos}{' '}
          <a
            href="https://stripe.com/connect-account/legal"
            target="_blank"
            rel="noopener noreferrer"
          >
            {c.tosLink}
          </a>
          .
        </p>

        <Button
          type="submit"
          variant="primary"
          disabled={onboarding.isPending}
          loading={onboarding.isPending}
        >
          {c.submit}
        </Button>
      </form>
    </Card>
  );
}
```

> **Note:** the existing PaymentsTab leans on `styles.stripeRow`, `styles.stripeLabel`, and `styles.stripeHint` from `Settings.module.css`. We only reuse those three plus `styles.stack`. If your build complains that any of those classes don't exist, open `Settings.module.css` and reuse the closest equivalent — don't introduce a Tailwind class.

- [ ] **Step 7.4: Verify typecheck**

Run: `npm run typecheck`
Expected: no errors. If `@stripe/stripe-js` types complain that `state` on `Address` is `string | undefined`, the `|| undefined` shim already handles that — the issue is upstream typings that occasionally narrow to `string`. Cast with `state: values.company.state || undefined` (already in the code).

- [ ] **Step 7.5: Commit**

```bash
git add src/features/settings/tabs/payments/ src/shared/copy.ts
git commit -m "feat(payments): FR tokenizing form (Stripe.js account+person tokens)"
```

---

## Task 8 (frontend): Wire the country branch into `PaymentsTab`

**Goal:** When `me.data.org.country === 'FR'` and the org has no Stripe account yet, render `StripeFrTokenForm` instead of the current "Connect Stripe" button. All other states (account exists / onboarding complete) are unchanged — they don't need to know which path created the account.

**Files:**
- Modify: `imin-webapp/src/features/settings/tabs/PaymentsTab.tsx`

- [ ] **Step 8.1: Replace State 1 with the country branch**

In `imin-webapp/src/features/settings/tabs/PaymentsTab.tsx`, replace the State 1 return block (currently lines 209–240, the "// State 1 — no account yet" comment + the JSX below it) with:

```tsx
  // State 1 — no account yet
  const country = me.data?.org.country;
  if (country === 'FR' && orgId) {
    return (
      <div className={styles.stack}>
        <StripeFrTokenForm orgId={orgId} />
      </div>
    );
  }

  return (
    <div className={styles.stack}>
      <Card>
        <div className={styles.stripeRow}>
          <div
            className={`${styles.stripeIcon} ${styles.stripeIconIdle}`}
            aria-hidden="true"
          >
            ⦻
          </div>
          <div className={styles.stripeText}>
            <div className={styles.stripeLabel}>
              {copy.settings.payments.connectTitle}
            </div>
            <div className={styles.stripeHint}>
              {copy.settings.payments.connectHint}
            </div>
          </div>
          <Button
            variant="primary"
            disabled={startOnboarding.isPending || !orgId}
            loading={startOnboarding.isPending}
            onClick={() => startOnboarding.mutate()}
          >
            {copy.settings.payments.connectButton}
          </Button>
        </div>
      </Card>
    </div>
  );
}
```

Add the import at the top of the file (after the existing imports):

```tsx
import { StripeFrTokenForm } from './payments/StripeFrTokenForm';
```

- [ ] **Step 8.2: Verify typecheck and lint**

Run: `npm run typecheck && npm run lint`
Expected: no errors.

- [ ] **Step 8.3: Verify the build**

Run: `npm run build`
Expected: clean Vite build, no CSP warnings printed at build time. Inspect the output to confirm the new `loadStripeJs.ts` and `StripeFrTokenForm.tsx` chunks made it in.

- [ ] **Step 8.4: Commit**

```bash
git add src/features/settings/tabs/PaymentsTab.tsx
git commit -m "feat(payments): country-aware PaymentsTab (FR → token form, default → existing flow)"
```

---

## Task 9: Manual end-to-end verification

**Goal:** Both branches actually onboard against Stripe test mode before claiming done.

**Pre-reqs:**
- A Stripe **test-mode** publishable key (`pk_test_...`) set on `VITE_STRIPE_PUBLISHABLE_KEY` in your local `.env`.
- The Java backend running locally with `STRIPE_SECRET_KEY=sk_test_...`.
- Two test organisations in the local DB: one with `country='FR'` and one with `country='US'`. (Use whatever signup/admin path the app provides — or update via a direct SQL update for the test.)

- [ ] **Step 9.1: Non-FR happy path**

1. Log in as the US-org user.
2. Settings → Payments. Confirm the current button + copy renders (unchanged from before this plan).
3. Click "Connect Stripe to collect payments". Confirm:
   - Browser console: no `js.stripe.com` request (we should never load Stripe.js for non-FR).
   - Backend logs: `POST /v2/core/accounts` succeeds; the org row now has a `stripe_account_id`.
4. Get redirected to Stripe hosted onboarding. Cancel out (don't finish — we just need the round trip).
5. Refresh `/settings/payments`. Confirm State 2 ("Finish your Stripe onboarding") renders.

Expected outcome: an account was created with `identity.country='US'`, `configuration.recipient.capabilities.stripe_balance.stripe_transfers.requested=true`. You can confirm this in the Stripe Dashboard test-mode Connect view.

- [ ] **Step 9.2: Non-FR strict-reject**

While still logged in as the US-org user (and the org now has an account — `startOnboarding` is idempotent, but the check is before that), test the validation directly:

```bash
curl -X POST http://localhost:8085/api/v1/orgs/<US_ORG_ID>/stripe/connect \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"accountToken":"ct_xyz","personToken":"ct_abc"}'
```

Expected: `400 INVALID_REQUEST` with body mentioning "only accepted for FR organisations".

To test on a fresh org without an existing account, delete `stripe_account_id` from the org row first or use a different US org.

- [ ] **Step 9.3: FR happy path**

1. Log in as the FR-org user (or switch org).
2. Settings → Payments. Confirm `StripeFrTokenForm` renders (company + representative sections, TOS line).
3. Fill in plausible test data (Stripe's docs accept any name/address for test keys). Use a Paris postcode like `75001`.
4. Submit. Confirm:
   - Browser network panel: two `POST https://api.stripe.com/v1/tokens` calls succeed.
   - One `POST /api/v1/orgs/.../stripe/connect` with `{accountToken, personToken}` body.
   - One `POST /api/v1/orgs/.../stripe/onboarding-link`.
   - Backend logs: `POST /v2/core/accounts` succeeds; immediately followed by `POST /v2/core/accounts/{acct}/persons` succeeds.
5. Get redirected to Stripe hosted onboarding. Confirm Stripe shows the business name + representative name we sent via tokens are pre-filled.

Expected outcome: a v2 account with `identity.entity_type=company`, `tos_acceptance.date` populated, and one person attached.

- [ ] **Step 9.4: FR validation errors**

In the form:
1. Submit empty → react-hook-form errors render for required fields.
2. Submit with a too-long name (>120 chars) → zod error renders inline.

Backend-side (curl as the FR-org user with an empty body): expect `400 INVALID_REQUEST` "FR organisations must provide both accountToken and personToken".

- [ ] **Step 9.5: Smoke-test prod CSP**

After deploying the webapp to a Vercel preview URL:
1. Open the preview in Chrome with DevTools → Console + Network filtered to "CSP".
2. Trigger the FR form on a deployed preview.
3. Confirm: no `Refused to load … blocked by Content Security Policy` messages for `js.stripe.com` or `api.stripe.com`.

If you see CSP violations, the `vercel.json` from Task 6 didn't deploy — verify the preview is built from the branch with that change.

---

## Self-review checklist (run before opening the PR)

- [ ] Backend: `./mvnw test` clean.
- [ ] Backend: `./mvnw clean package` clean.
- [ ] Frontend: `npm run typecheck && npm run build` clean.
- [ ] OpenAPI: from `imin-webapp/`, `npm run api:check` clean (the `ConnectRequest` body is a new optional field — confirm whether it surfaces in the generated types and whether anyone consumes it).
- [ ] All five backend commits land in this order: restore-non-fr → fr-token-path → tests → docs.
- [ ] All three frontend commits land in this order: deps+csp → form → wire-into-tab.
- [ ] No new code references `js.stripe.com` outside the `loadStripeJs.ts` helper (otherwise the CSP eventually gets out of sync).
- [ ] CLAUDE.md updated; no FR/PSD2 references remain in stale comments inside `StripeConnectService.java`.
