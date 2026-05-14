package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.v2.core.Account;
import com.stripe.model.v2.core.AccountLink;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.param.v2.core.AccountLinkCreateParams;
import com.stripe.param.v2.core.AccountRetrieveParams;
import com.stripe.param.v2.core.accounts.PersonCreateParams;
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

    public StripeConnectService(StripeClient stripeClient,
                                OrganizationRepository orgs,
                                StripeProperties props) {
        this.stripeClient = stripeClient;
        this.orgs = orgs;
        this.props = props;
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
                                // Both configurations are requested on the account, so onboarding
                                // must collect requirements for both — otherwise card_payments
                                // stays inactive and Checkout can't accept the buyer's card.
                                .addConfiguration(AccountLinkCreateParams.UseCase.AccountOnboarding
                                        .Configuration.MERCHANT)
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
     * Always hits Stripe live — never reads cached state.
     *
     * Per the spec: {@code readyToReceivePayments} iff the recipient's stripe_transfers capability
     * status is "active", and {@code onboardingComplete} iff the requirements summary minimum
     * deadline status is neither "currently_due" nor "past_due".
     */
    @Transactional(readOnly = true)
    public StatusResult getStatus(AuthPrincipal principal, UUID orgId) {
        Organization org = loadOwnedOrg(principal, orgId);
        if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) {
            return new StatusResult(null, false, false, null);
        }

        AccountRetrieveParams params = AccountRetrieveParams.builder()
                // include — Stripe v2 hides expensive sub-objects by default; we have to opt in.
                .addInclude(AccountRetrieveParams.Include.CONFIGURATION__RECIPIENT)
                .addInclude(AccountRetrieveParams.Include.REQUIREMENTS)
                .build();

        Account account;
        try {
            // V2 retrieve. We deliberately do NOT cache anywhere — the user wants live truth on every read.
            account = stripeClient.v2().core().accounts().retrieve(org.getStripeAccountId(), params);
        } catch (StripeException e) {
            log.error("Stripe v2 account retrieve failed for {} (org {}): {}",
                    org.getStripeAccountId(), orgId, e.getMessage(), e);
            throw upstream("Failed to fetch Stripe account status: " + e.getMessage(), e);
        }

        String transferStatus = readTransferStatus(account);
        boolean ready = "active".equals(transferStatus);

        String requirementsStatus = readRequirementsStatus(account);
        boolean onboardingComplete = requirementsStatus == null
                || (!"currently_due".equals(requirementsStatus) && !"past_due".equals(requirementsStatus));

        return new StatusResult(account.getId(), ready, onboardingComplete, requirementsStatus);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Organization loadOwnedOrg(AuthPrincipal p, UUID orgId) {
        if (!orgId.equals(p.orgId())) {
            // 404 — don't leak that the org exists under a different user.
            throw ApiException.notFound("Organization");
        }
        return orgs.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private static String readTransferStatus(Account account) {
        try {
            return account.getConfiguration()
                    .getRecipient()
                    .getCapabilities()
                    .getStripeBalance()
                    .getStripeTransfers()
                    .getStatus();
        } catch (NullPointerException ignored) {
            return null;
        }
    }

    /**
     * Reads the {@code requirements.summary.minimum_deadline.status} string per the spec.
     * Returns null if Stripe hasn't populated the summary yet.
     */
    private static String readRequirementsStatus(Account account) {
        try {
            return account.getRequirements()
                    .getSummary()
                    .getMinimumDeadline()
                    .getStatus();
        } catch (NullPointerException ignored) {
            return null;
        }
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
                               boolean readyToReceivePayments,
                               boolean onboardingComplete,
                               String requirementsStatus) {}

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
}
