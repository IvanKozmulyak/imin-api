package com.imin.iminapi.service.payout;

import com.imin.iminapi.controller.payout.dto.PayoutConnectResponse;
import com.imin.iminapi.controller.payout.dto.PayoutRowResponse;
import com.imin.iminapi.controller.payout.dto.PayoutsStatusResponse;
import com.imin.iminapi.controller.payout.dto.PayoutsSummaryResponse;
import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.settlement.Settlement;
import com.imin.iminapi.settlement.SettlementObjectType;
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.settlement.SettlementStatus;
import com.imin.iminapi.stripe.StripeConnectService;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.BankAccount;
import com.stripe.model.Card;
import com.stripe.model.ExternalAccount;
import com.stripe.model.LoginLink;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountRetrieveParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side of the organizer Payouts tab. Two data sources:
 *
 * <ul>
 *   <li><b>Settlements read-model</b> ({@link SettlementRepository}) — drives
 *       {@code /payouts} history and {@code /payouts/summary}. This table is
 *       populated by Stripe {@code payout.*} / {@code transfer.*} webhooks.
 *       <b>ponytail:</b> those webhooks are NOT subscribed yet (see imin-api
 *       CLAUDE.md webhook list — no {@code payout.*} events), so the table is
 *       EMPTY in prod today and every summary number is effectively an
 *       <i>estimate</i> of zero until the subscription + handler ship. The math
 *       below is correct the moment rows start arriving; nothing here fabricates
 *       data when the table is empty.</li>
 *   <li><b>Connect mirror + live Stripe reads</b> — drive {@code /payouts/status}.
 *       {@code stripeConnected} comes from the locally-mirrored columns (via
 *       {@link StripeConnectService#getStatus}); {@code accountLast4} and
 *       {@code dashboardUrl} are live, connected-account-scoped Stripe reads that
 *       degrade to {@code null} (never a 502) on failure.</li>
 * </ul>
 *
 * <p>Every settlement query is scoped by {@code principal.orgId()} — cross-org
 * reads are physically impossible. Ownership for the status path is enforced by
 * {@link StripeConnectService#getStatus} (which 404s, no-leak, on a foreign org).
 *
 * <p>The Stripe-touching reads are isolated in their own methods so the
 * projection/summary math stays unit-testable without a live Stripe.
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    /** Payout history page cap — matches the audit list clamp. */
    private static final int MAX_PAGE_SIZE = 100;

    private final SettlementRepository settlements;
    private final EventRepository events;
    private final StripeConnectService connect;
    private final StripeClient stripeClient;

    public PayoutService(SettlementRepository settlements,
                         EventRepository events,
                         StripeConnectService connect,
                         StripeClient stripeClient) {
        this.settlements = settlements;
        this.events = events;
        this.connect = connect;
        this.stripeClient = stripeClient;
    }

    // ── GET /payouts ─────────────────────────────────────────────────────────

    /**
     * Org-scoped payout history, newest first, paginated. {@code page} is
     * 1-based on the wire (converted to a 0-based {@link PageRequest} here).
     */
    @Transactional(readOnly = true)
    public PageResponse<PayoutRowResponse> list(UUID orgId, int page, int pageSize) {
        int clampedSize = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        int clampedPage = Math.max(1, page);
        var pg = PageRequest.of(clampedPage - 1, clampedSize);

        // History surfaces only genuine payout rows. Transfer rows feed the
        // summary "in transit" figure, and dispute/refund annotations live on
        // transfer rows too — none of them are payouts, so they must never show
        // up as a history line.
        var rows = settlements.findByOrgIdAndObjectTypeOrderByCreatedAtDesc(
                orgId, SettlementObjectType.PAYOUT, pg);

        // Denormalize event names in one batch so eventsLabel needs no per-row query.
        Map<UUID, String> names = resolveEventNames(orgId, rows.getContent());

        return PageResponse.from(rows, s -> PayoutRowResponse.from(s, names));
    }

    // ── GET /payouts/summary ─────────────────────────────────────────────────

    /**
     * Summary tiles. inTransit / pending are status sums across all time;
     * thisMonth (+ count) is the sum/count of PAID payouts in the current
     * calendar month (UTC). We sum the {@code PAYOUT} object type for the
     * windowed metric so transfer + payout rows mirroring the same funds aren't
     * double-counted (money-in-the-bank = the payout).
     */
    @Transactional(readOnly = true)
    public PayoutsSummaryResponse summary(UUID orgId) {
        long inTransit = settlements.sumAmountByOrgAndStatus(orgId, SettlementStatus.IN_TRANSIT);
        long pending = settlements.sumAmountByOrgAndStatus(orgId, SettlementStatus.PENDING);

        Instant monthStart = startOfCurrentMonthUtc();
        Instant nextMonthStart = startOfNextMonthUtc();

        // thisMonth = PAID payouts whose paid_at falls in the current calendar
        // month (half-open window). Filtering on paid_at (not created_at) buckets
        // a month-boundary payout by when it actually settled, and the PAID +
        // PAYOUT filter keeps REVERSED/FAILED rows and transfer/dispute/refund
        // annotations out of the "banked this month" figure.
        var countAndSum = settlements.countAndSumPaidByOrgAndTypeInWindow(
                orgId, SettlementObjectType.PAYOUT, SettlementStatus.PAID, monthStart, nextMonthStart);
        long thisMonth = 0L;
        int thisMonthCount = 0;
        if (countAndSum != null && !countAndSum.isEmpty()) {
            Object[] tuple = countAndSum.get(0);
            thisMonthCount = (int) toLong(tuple[0]);
            thisMonth = toLong(tuple[1]);
        }

        // arrivesOnLabel: the soonest arrival among in-transit payouts, if any.
        String arrivesOnLabel = nextArrivalLabel(orgId);

        return new PayoutsSummaryResponse(
                inTransit,
                thisMonth,
                pending,
                arrivesOnLabel,                                   // optional → null when unknown
                thisMonthCount == 0 ? null : thisMonthCount);     // optional → null when n/a
    }

    // ── GET /payouts/status ──────────────────────────────────────────────────

    /**
     * Connect status for the Payouts tab header. {@code stripeConnected} is the
     * mirror's readiness gate; {@code accountLast4} / {@code dashboardUrl} are
     * best-effort live Stripe reads that degrade to {@code null}.
     */
    @Transactional(readOnly = true)
    public PayoutsStatusResponse status(AuthPrincipal principal, UUID orgId) {
        // Reuse the mirror read — also enforces org ownership (404 no-leak on mismatch)
        // and self-heals a stale mirror.
        StripeConnectService.StatusResult mirror = connect.getStatus(principal, orgId);

        boolean connected = mirror.accountId() != null && mirror.readyToReceivePayments();
        String acctId = mirror.accountId();

        // Live reads only make sense once an account exists. last4 may still be
        // null (no external account attached yet); dashboardUrl only when ready.
        String last4 = (acctId == null) ? null : fetchAccountLast4(acctId);
        String dashboardUrl = (acctId != null && mirror.readyToReceivePayments())
                ? fetchDashboardUrl(acctId)
                : null;

        return new PayoutsStatusResponse(connected, last4, dashboardUrl);
    }

    // ── POST /payouts/connect ────────────────────────────────────────────────

    /**
     * Idempotent connect: ensures a connected account exists (delegating to
     * {@link StripeConnectService#getOrCreateAccount}, which is itself
     * idempotent) and returns a fresh Stripe onboarding URL. The
     * {@code Idempotency-Key} header is honored by the underlying create being
     * idempotent on the org's existing account id — a replay returns the same
     * account with a new (single-use) onboarding link.
     */
    @Transactional
    public PayoutConnectResponse connect(AuthPrincipal principal, UUID orgId, String idempotencyKey) {
        // idempotencyKey is accepted for parity with other idempotent endpoints;
        // getOrCreateAccount is naturally idempotent on the org's account id, so a
        // replay never mints a second account.
        var account = connect.getOrCreateAccount(principal, orgId);
        String url = connect.createOnboardingLink(principal, orgId, null, null);
        return new PayoutConnectResponse(url, account.accountId());
    }

    // ── Stripe-touching reads (the network seam — swallow failures) ──────────

    /**
     * Last 4 of the connected account's external (bank/card) account. Expands
     * {@code external_accounts} on a v1 retrieve SCOPED to the connected account
     * via {@code setStripeAccount(acctId)} (the account was minted on v2 but the
     * acct_ id is shared across surfaces). Nullable: an Express account may have
     * no external account attached yet, and a Stripe error degrades to null.
     */
    String fetchAccountLast4(String acctId) {
        try {
            RequestOptions onAcct = RequestOptions.builder().setStripeAccount(acctId).build();
            AccountRetrieveParams params = AccountRetrieveParams.builder()
                    .addExpand("external_accounts")
                    .build();
            Account acct = stripeClient.accounts().retrieve(acctId, params, onAcct);
            if (acct.getExternalAccounts() == null || acct.getExternalAccounts().getData() == null) {
                return null;
            }
            for (ExternalAccount ea : acct.getExternalAccounts().getData()) {
                if (ea instanceof BankAccount b) return b.getLast4();
                if (ea instanceof Card c) return c.getLast4();
            }
            return null;
        } catch (StripeException | RuntimeException e) {
            // Degrade honestly: no last4 rather than a 502 on the whole status read.
            log.warn("[payouts] external-account last4 read failed for {}: {}", acctId, e.getMessage());
            return null;
        }
    }

    /**
     * Single-use Express dashboard login link. Valid only because the account
     * was created with {@code dashboard=express}; create fresh per request,
     * NEVER cache. Degrades to null on failure (e.g. onboarding incomplete).
     */
    String fetchDashboardUrl(String acctId) {
        try {
            LoginLink link = stripeClient.accounts().loginLinks().create(acctId);
            return link.getUrl();
        } catch (StripeException | RuntimeException e) {
            log.warn("[payouts] dashboard login link create failed for {}: {}", acctId, e.getMessage());
            return null;
        }
    }

    // ── helpers (pure, unit-testable) ────────────────────────────────────────

    /**
     * Build event-id → name map for the rows being rendered. Only the org's own
     * events are loaded, and only ids actually referenced are looked up.
     */
    private Map<UUID, String> resolveEventNames(UUID orgId, List<Settlement> rows) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Settlement s : rows) {
            for (UUID id : splitCsv(s.getEventIds())) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) return Map.of();

        Map<UUID, String> names = new HashMap<>();
        for (Event e : events.findAllById(ids)) {
            // Defence in depth: only label events that actually belong to this org.
            if (orgId.equals(e.getOrgId())) {
                names.put(e.getId(), e.getName());
            }
        }
        return names;
    }

    /** Soonest arrival label among in-transit payouts for the org (or null). */
    private String nextArrivalLabel(UUID orgId) {
        Instant soonest = null;
        for (Settlement s : settlements.findByOrgIdAndStatusOrderByCreatedAtDesc(orgId, SettlementStatus.IN_TRANSIT)) {
            Instant a = s.getArrivalAt();
            if (a != null && (soonest == null || a.isBefore(soonest))) {
                soonest = a;
            }
        }
        return soonest == null ? null : formatArrivalLabel(soonest);
    }

    /** "Jun 18" style short arrival label (UTC). */
    private static String formatArrivalLabel(Instant arrival) {
        ZonedDateTime z = arrival.atZone(ZoneOffset.UTC);
        String month = z.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return month + " " + z.getDayOfMonth();
    }

    private static Instant startOfCurrentMonthUtc() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        return now.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant startOfNextMonthUtc() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        return now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static List<UUID> splitCsv(String csv) {
        List<UUID> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(UUID.fromString(t));
            } catch (IllegalArgumentException ignored) {
                // ponytail: tolerate a malformed id in the CSV.
            }
        }
        return out;
    }

    /** Object[] tuple elements from a JPQL aggregate may be Long or BigInteger. */
    private static long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }
}
