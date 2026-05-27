package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.AccountSession;
import com.stripe.model.v2.core.Account;
import com.stripe.model.v2.core.AccountLink;
import com.stripe.param.AccountSessionCreateParams;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.param.v2.core.AccountLinkCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages a single Stripe v2 Connected Account per Organization.
 *
 * Status is intentionally NEVER cached locally — every call to {@link #getStatus(AuthPrincipal, UUID)}
 * hits Stripe live. This matches the user's instruction: the DB stores only the account id;
 * onboarding progress + capability state are derived on demand from the live account object.
 */
@Service
public class StripeConnectService {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectService.class);

    private final StripeClient stripeClient;
    private final OrganizationRepository orgs;
    private final StripeProperties props;
    /** Optional audit logger — null in older test constructors. */
    private final AuditLogger auditLogger;
    /** Optional mirror — null in legacy constructors used by older tests. */
    private final StripeConnectStatusMirror mirror;

    /** Legacy 3-arg constructor for existing tests. */
    public StripeConnectService(StripeClient stripeClient,
                                OrganizationRepository orgs,
                                StripeProperties props) {
        this(stripeClient, orgs, props, null, null);
    }

    /** Legacy 4-arg constructor for tests written before the mirror existed. */
    public StripeConnectService(StripeClient stripeClient,
                                OrganizationRepository orgs,
                                StripeProperties props,
                                AuditLogger auditLogger) {
        this(stripeClient, orgs, props, auditLogger, null);
    }

    /** Primary constructor — Spring uses this one. */
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

    /**
     * Step 1 — Create the v2 connected account.
     *
     * Idempotent: if the org already has a connected account, return that id and {@code created=false}.
     * Otherwise create one via the v2 Accounts API and persist the id.
     *
     * Sends server-side: display_name, contact_email, dashboard=express, identity.country (from org),
     * defaults: locale=en_us and fees/losses-collector=APPLICATION (the platform —
     * required because only the RECIPIENT configuration is attached; STRIPE
     * responsibilities would error with invalid_fields). No explicit default currency —
     * Stripe picks it from the country, and
     * configuration.recipient with the stripe_transfers capability requested. The hosted onboarding
     * link (see {@link #createOnboardingLink}) attaches MERCHANT + RECIPIENT requirements and
     * collects the legal-entity / person fields.
     */
    @Transactional
    public ConnectResult getOrCreateAccount(AuthPrincipal principal, UUID orgId) {
        Organization org = loadOwnedOrg(principal, orgId);

        if (org.getStripeAccountId() != null && !org.getStripeAccountId().isBlank()) {
            return new ConnectResult(org.getStripeAccountId(), false);
        }

        log.info("Stripe connect: creating account for org={} country={}",
                orgId, org.getCountry());

        Account account;
        try {
            account = stripeClient.v2().core().accounts().create(buildCreateParams(org));
        } catch (StripeException e) {
            log.error("Stripe v2 account create failed for org {}: {}", orgId, e.getMessage(), e);
            throw upstream("Failed to create Stripe connected account: " + e.getMessage(), e);
        }

        org.setStripeAccountId(account.getId());
        orgs.save(org);
        if (auditLogger != null) {
            // Only fires on a NEW account creation (the idempotent early-return above
            // never reaches this line).
            auditLogger.record(principal, AuditActions.STRIPE_ONBOARDED, null, null,
                    "Connected Stripe account");
        }
        return new ConnectResult(account.getId(), true);
    }

    /**
     * Creates a v1 AccountSession bound to the org's connected account, enabling the
     * {@code account_onboarding} embedded component. The returned {@code clientSecret}
     * is short-lived and feeds Stripe's {@code @stripe/connect-js} loader on the FE,
     * which then renders the in-dashboard onboarding iframe — PSD2-compliant because
     * the user's PII goes straight to Stripe inside that iframe, never through our server.
     *
     * <p>Endpoint is on the v1 path ({@code /v1/account_sessions}) even though the
     * connected account itself was minted via {@code /v2/core/accounts} — Stripe's
     * AccountSessions API is the same surface for v1 and v2 accounts.
     */
    @Transactional(readOnly = true)
    public String createAccountSession(AuthPrincipal principal, UUID orgId) {
        Organization org = loadOwnedOrg(principal, orgId);
        if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Stripe connected account not yet created — call /stripe/connect first");
        }

        AccountSessionCreateParams params = AccountSessionCreateParams.builder()
                .setAccount(org.getStripeAccountId())
                .setComponents(
                        AccountSessionCreateParams.Components.builder()
                                .setAccountOnboarding(
                                        AccountSessionCreateParams.Components.AccountOnboarding.builder()
                                                .setEnabled(true)
                                                .build()
                                )
                                .build()
                )
                .build();

        AccountSession session;
        try {
            session = stripeClient.accountSessions().create(params);
        } catch (StripeException e) {
            log.error("Stripe v1 account session create failed for org {} (account {}): {}",
                    orgId, org.getStripeAccountId(), e.getMessage(), e);
            throw upstream("Failed to create Stripe account session: " + e.getMessage(), e);
        }
        return session.getClientSecret();
    }

    private static AccountCreateParams buildCreateParams(Organization org) {
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
                                // No setCurrency — Stripe defaults to the local currency of the
                                // account's country (FR→EUR, US→USD, GB→GBP, …), which is what
                                // we want. Ticket prices carry their own currency on the event,
                                // so the account-level default only affects payout settlement.
                                .setResponsibilities(
                                        AccountCreateParams.Defaults.Responsibilities.builder()
                                                .setFeesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities.FeesCollector.APPLICATION
                                                )
                                                .setLossesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities.LossesCollector.APPLICATION
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

    /**
     * Creates a single-use onboarding URL for the org's connected account. The org MUST already
     * have an account (call {@link #getOrCreateAccount} first). Returns the Stripe-hosted URL the
     * organizer's browser should be sent to.
     *
     * V2 account links live under {@code /v2/core/account_links} and require the {@code use_case}
     * envelope (rather than the V1 flat {@code type='account_onboarding'} shape).
     */
    @Transactional(readOnly = true)
    public String createOnboardingLink(AuthPrincipal principal, UUID orgId,
                                       String returnUrl, String refreshUrl) {
        Organization org = loadOwnedOrg(principal, orgId);
        if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Stripe connected account not yet created — call /stripe/connect first");
        }

        String effectiveReturn = nonBlank(returnUrl)
                ? returnUrl : props.getReturnUrlBase() + "/settings/payments?stripe=return";
        String effectiveRefresh = nonBlank(refreshUrl)
                ? refreshUrl : props.getReturnUrlBase() + "/settings/payments?stripe=refresh";

        AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                .setAccount(org.getStripeAccountId())
                .setUseCase(AccountLinkCreateParams.UseCase.builder()
                        .setType(AccountLinkCreateParams.UseCase.Type.ACCOUNT_ONBOARDING)
                        .setAccountOnboarding(AccountLinkCreateParams.UseCase.AccountOnboarding.builder()
                                // RECIPIENT only — that's the single configuration applied to the
                                // account on create. Stripe rejects the AccountLink with
                                // `configs_must_match_to_use_account_links` if we ask for a
                                // configuration the account doesn't have. MERCHANT is intentionally
                                // not on the connected account: Checkout is created on the *platform*
                                // account using destination charges (transfer_data.destination +
                                // application_fee_amount, see StripeCheckoutService), so the
                                // connected account only needs the stripe_transfers capability.
                                .addConfiguration(AccountLinkCreateParams.UseCase.AccountOnboarding
                                        .Configuration.RECIPIENT)
                                // Up-front onboarding: collect every requirement we'll eventually need,
                                // not just the currently_due ones. Matches Stripe's hosted-onboarding
                                // sample and avoids the organizer being yanked back through onboarding
                                // each time a new deadline becomes currently_due.
                                .setCollectionOptions(AccountLinkCreateParams.UseCase.AccountOnboarding
                                        .CollectionOptions.builder()
                                        .setFields(AccountLinkCreateParams.UseCase.AccountOnboarding
                                                .CollectionOptions.Fields.EVENTUALLY_DUE)
                                        .build())
                                .setRefreshUrl(effectiveRefresh)
                                .setReturnUrl(effectiveReturn)
                                .build())
                        .build())
                .build();

        AccountLink link;
        try {
            // V2 endpoint — short-lived URL (a few minutes). DO NOT cache; create a fresh one per request.
            link = stripeClient.v2().core().accountLinks().create(params);
        } catch (StripeException e) {
            log.error("Stripe v2 account link create failed for org {}: {}", orgId, e.getMessage(), e);
            throw upstream("Failed to create Stripe onboarding link: " + e.getMessage(), e);
        }
        return link.getUrl();
    }

    /**
     * Reads Connect status from the locally-mirrored columns updated by the v2 webhook.
     *
     * <p>Falls back to a one-shot synchronous fetch through {@link StripeConnectStatusMirror}
     * when the org has an account id but the mirror has never been populated
     * ({@code stripe_connect_status_updated_at IS NULL}). This avoids a backfill job
     * for historical accounts and shields the FE from a stale ONBOARDING banner on
     * the first read after account creation.
     */
    @Transactional
    public StatusResult getStatus(AuthPrincipal principal, UUID orgId) {
        Organization org = loadOwnedOrg(principal, orgId);

        if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) {
            return new StatusResult(null, StripeConnectState.NOT_STARTED, false, false,
                    List.of(), List.of(), null);
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
                List.copyOf(org.getStripeRequirementsCurrentlyDue()),
                List.copyOf(org.getStripeRequirementsPastDue()),
                org.getStripeDisabledReason());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Organization loadOwnedOrg(AuthPrincipal p, UUID orgId) {
        if (!orgId.equals(p.orgId())) {
            // 404 — don't leak that the org exists under a different user.
            throw ApiException.notFound("Organization");
        }
        return orgs.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private static boolean nonBlank(String s) { return s != null && !s.isBlank(); }

    private ApiException upstream(String message, Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE, message, cause);
    }

    /** @noinspection unused — used by tests + reflection-friendly */
    static List<String> readyStatuses() { return List.of("active"); }

    // ── DTOs returned to controllers ───────────────────────────────────────

    public record ConnectResult(String accountId, boolean created) {}

    public record StatusResult(String accountId,
                               StripeConnectState state,
                               boolean readyToReceivePayments,
                               boolean detailsSubmitted,
                               List<String> currentlyDue,
                               List<String> pastDue,
                               String disabledReason) {}
}
