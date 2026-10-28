package com.imin.iminapi.checkoutreminder.service;

import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.StickyOptOutWriter;
import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.checkoutreminder.dto.CheckoutReminderPreviewDto;
import com.imin.iminapi.checkoutreminder.dto.CheckoutReminderSummaryDto;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminderStatus;
import com.imin.iminapi.checkoutreminder.model.OrgCheckoutReminderSetting;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.service.QuietHours;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RoleGuard;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.service.analytics.NetOrderRevenue;
import com.imin.iminapi.service.event.TierAvailability;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The organizer side of the reminder (summary, switch, preview, test send) and the buyer's one-click
 * unsubscribe from a reminder.
 */
@Service
public class CheckoutReminderService {

    static final int WINDOW_DAYS = 30;
    static final int RECENT_ROWS = 20;
    private static final int MAX_PREVIEW_QTY = 10;

    /**
     * The checkout box label per language, version checkout-org-named-2026-09 (imin-public
     * {@code buy.marketingOptIn}); only the preview and test send quote it, a real reminder quotes the stored proof.
     */
    private static final Map<String, String> LABELS = Map.of(
            "en", "Email me about events by %s. I agree to receive email marketing and can unsubscribe any time, one click in every email.",
            "es", "Envíame emails sobre los eventos de %s. Acepto recibir marketing por email y puedo darme de baja cuando quiera, con un clic en cada email.",
            "fr", "Envoie-moi des e-mails sur les événements organisés par %s. J’accepte de recevoir du marketing par e-mail et je peux me désinscrire à tout moment, en un clic dans chaque e-mail.",
            "uk", "Пишіть мені на пошту про події організатора %s. Погоджуюсь отримувати email-маркетинг і можу відписатися будь-коли, одним кліком у кожному листі.");

    private final CheckoutReminderProperties props;
    private final CheckoutReminderRepository reminders;
    private final OrgCheckoutReminderSettingRepository settings;
    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final ModerationHoldRepository holds;
    private final OrderRepository orders;
    private final CheckoutReminderEmail email;
    private final CampaignEmailProvider provider;
    private final MarketingEmailProperties emailProps;
    private final StickyOptOutWriter stickyOptOuts;
    private final ConsentService consentService;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final AuditLogger audit;
    private final Clock clock;

    public CheckoutReminderService(CheckoutReminderProperties props, CheckoutReminderRepository reminders,
                                   OrgCheckoutReminderSettingRepository settings, OrganizationRepository orgs,
                                   UserRepository users, EventRepository events, TicketTierRepository tiers,
                                   ModerationHoldRepository holds, OrderRepository orders,
                                   CheckoutReminderEmail email, CampaignEmailProvider provider,
                                   MarketingEmailProperties emailProps, StickyOptOutWriter stickyOptOuts,
                                   ConsentService consentService, ConsumerRepository consumers,
                                   MembershipRepository memberships, AuditLogger audit, Clock clock) {
        this.props = props;
        this.reminders = reminders;
        this.settings = settings;
        this.orgs = orgs;
        this.users = users;
        this.events = events;
        this.tiers = tiers;
        this.holds = holds;
        this.orders = orders;
        this.email = email;
        this.provider = provider;
        this.emailProps = emailProps;
        this.stickyOptOuts = stickyOptOuts;
        this.consentService = consentService;
        this.consumers = consumers;
        this.memberships = memberships;
        this.audit = audit;
        this.clock = clock;
    }

    // ── organizer ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CheckoutReminderSummaryDto summary(AuthPrincipal p) {
        Organization org = requireOrg(p);
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(WINDOW_DAYS));
        Optional<OrgCheckoutReminderSetting> setting = settings.findById(org.getId());

        long sent = 0, waiting = 0, notSent = 0;
        Map<String, Long> byReason = new LinkedHashMap<>();
        for (Object[] row : reminders.countByStatusAndReason(org.getId(), since)) {
            String status = (String) row[0];
            long n = ((Number) row[2]).longValue();
            switch (CheckoutReminderStatus.valueOf(status)) {
                case SENT -> sent += n;
                case PENDING, SENDING -> waiting += n;
                case NOT_SENT -> {
                    notSent += n;
                    byReason.merge(row[1] == null ? "UNKNOWN" : (String) row[1], n, Long::sum);
                }
            }
        }

        List<UUID> recovered = reminders.findRecoveredOrderIds(org.getId(), since);
        Map<String, Long> netByOrder = new HashMap<>();
        Map<String, Long> keptByOrder = new HashMap<>();
        Set<String> currencies = new HashSet<>();
        if (!recovered.isEmpty()) {
            List<Object[]> rows = orders.revenueRowsByOrderIdIn(org.getId(), recovered);
            netByOrder = NetOrderRevenue.sumByKey(rows);
            keptByOrder = NetOrderRevenue.keptOrdersByKey(rows);
            for (Object[] r : rows) currencies.add((String) r[4]);
        }
        long boughtAfter = keptByOrder.values().stream().mapToLong(Long::longValue).sum();
        boolean oneCurrency = currencies.size() <= 1;
        Long salesMinor = oneCurrency ? netByOrder.values().stream().mapToLong(Long::longValue).sum() : null;
        String currency = currencies.size() == 1 ? currencies.iterator().next().toUpperCase()
                : currencies.isEmpty() ? org.getCurrency() : null;

        List<CheckoutReminder> recentRows = reminders.findRecent(org.getId(), since, PageRequest.of(0, RECENT_ROWS));
        Map<UUID, Event> eventsById = new HashMap<>();
        Map<UUID, TicketTier> tiersById = new HashMap<>();
        events.findAllById(recentRows.stream().map(CheckoutReminder::getEventId).distinct().toList())
                .forEach(e -> eventsById.put(e.getId(), e));
        tiers.findAllById(recentRows.stream().map(CheckoutReminder::getTierId).distinct().toList())
                .forEach(t -> tiersById.put(t.getId(), t));
        List<CheckoutReminderSummaryDto.RecentRow> recent = new ArrayList<>();
        for (CheckoutReminder r : recentRows) {
            Event e = eventsById.get(r.getEventId());
            TicketTier t = tiersById.get(r.getTierId());
            UUID orderId = r.getRecoveredOrderId();
            recent.add(new CheckoutReminderSummaryDto.RecentRow(r.getEventId(), e == null ? null : e.getName(),
                    t == null ? null : t.getName(), r.getQty(), r.getStartedAt(), r.getStatus().name(),
                    r.getReason() == null ? null : r.getReason().name(), r.getDueAt(), r.getSentAt(),
                    orderId == null ? null : r.getRecoveredAt(),
                    orderId == null ? null : netByOrder.get(orderId.toString())));
        }

        return new CheckoutReminderSummaryDto(
                props.isEnabled(),
                setting.map(OrgCheckoutReminderSetting::isEnabled).orElse(false),
                setting.filter(OrgCheckoutReminderSetting::isEnabled).map(OrgCheckoutReminderSetting::getEnabledAt).orElse(null),
                setting.filter(OrgCheckoutReminderSetting::isEnabled).map(OrgCheckoutReminderSetting::getEnabledBy)
                        .flatMap(users::findById).map(CheckoutReminderService::firstName).orElse(null),
                RoleGuard.isAtLeast(p, UserRole.ADMIN),
                org.hasLegalIdentity(),
                org.getLegalName(),
                org.getLegalContact(),
                emailProps.displayName(org.displayName()),
                org.getMarketingPausedAt() != null,
                org.getTimezone(),
                QuietHours.EMAIL_QUIET_START.toString(),
                QuietHours.EMAIL_QUIET_END.toString(),
                WINDOW_DAYS,
                props.getDelayMinutes(),
                props.getOutcomeWindowDays(),
                reminders.countUnpaidCheckouts(org.getId(), since),
                sent + waiting + notSent, sent, waiting, notSent, byReason,
                boughtAfter, salesMinor, currency, recent);
    }

    /** Owner or admin; turning on needs the platform switch and the legal name and contact for the footer. */
    @Transactional
    public CheckoutReminderSummaryDto toggle(AuthPrincipal p, boolean enabled) {
        RoleGuard.requireAtLeast(p, UserRole.ADMIN, "turn the checkout reminder on or off");
        Organization org = requireOrg(p);
        Instant now = clock.instant();
        if (enabled) {
            if (!props.isEnabled()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                        "Checkout reminders are not available yet");
            }
            if (!org.hasLegalIdentity()) {
                Map<String, String> missing = new LinkedHashMap<>();
                if (org.getLegalName() == null || org.getLegalName().isBlank()) missing.put("legalName", "required");
                if (org.getLegalContact() == null || org.getLegalContact().isBlank()) missing.put("legalContact", "required");
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                        "Add the organization's legal name and contact first", missing);
            }
            settings.enable(org.getId(), p.userId(), now);
            audit.record(p, AuditActions.CHECKOUT_REMINDER_ENABLED, "organization", org.getId(),
                    "Unfinished checkout reminder turned on");
        } else {
            settings.disable(org.getId(), now);
            int dropped = reminders.disableWaiting(org.getId());
            audit.record(p, AuditActions.CHECKOUT_REMINDER_DISABLED, "organization", org.getId(),
                    "Unfinished checkout reminder turned off; " + dropped + " waiting not sent");
        }
        return summary(p);
    }

    @Transactional(readOnly = true)
    public CheckoutReminderPreviewDto preview(AuthPrincipal p, String rawLocale, Integer rawQty) {
        Organization org = requireOrg(p);
        String lang = EmailLocale.normalize(rawLocale);
        return render(org, lang, rawQty, false)
                .map(r -> new CheckoutReminderPreviewDto(lang, r.message().from(), r.message().subject(),
                        r.message().preheader(), r.message().html(), r.message().text(),
                        new CheckoutReminderPreviewDto.BasedOn(r.event().getId(), r.event().getName(),
                                r.tier().getName(), r.message().linkQty(), r.message().remaining())))
                .orElseGet(() -> new CheckoutReminderPreviewDto(lang, null, null, null, null, null, null));
    }

    /** One "[TEST]" copy to the caller's own address, through the same provider and headers as a real send. */
    @Transactional(readOnly = true)
    public void testSend(AuthPrincipal p, String rawLocale) {
        Organization org = requireOrg(p);
        if (emailProps.getFromAddress() == null || emailProps.getFromAddress().isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                    "Marketing email sender not configured");
        }
        User caller = users.findById(p.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Unknown user"));
        CheckoutReminderEmail.Message m = render(org, EmailLocale.normalize(rawLocale), null, true)
                .map(Rendered::message)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                        "Put a night on sale to send a test"));
        String unsubscribeUrl = emailProps.unsubscribeUrl("preview");
        CampaignEmailProvider.BatchResult result = provider.sendBatch(null, List.of(new CampaignEmailProvider.OutgoingEmail(
                m.from(), caller.getEmail(), m.subject(), m.html(), m.text(), unsubscribeUrl)));
        if (result != null && result.rejected(0)) {
            throw new CampaignEmailProvider.CampaignRejected("Email service rejected the test email", 422);
        }
    }

    // ── buyer ──────────────────────────────────────────────────────────────

    /** The reminder behind an unsubscribe link, for the buyer-site page; reads only. */
    @Transactional(readOnly = true)
    public Optional<CheckoutReminder> find(UUID reminderId) {
        return reminders.findById(reminderId);
    }

    /**
     * One-click unsubscribe from a reminder: a sticky opt-out from this organizer, and an unsubscribe
     * of the buyer's membership in their audience when there is one. A wiped row has nothing to record.
     * The buyer usually has no membership, so the sticky row is the opt-out itself: a failed write
     * answers 500, stamps nothing, and the mail client's retry records it.
     */
    public void unsubscribe(UUID reminderId) {
        CheckoutReminder r = reminders.findById(reminderId).orElse(null);
        if (r == null || r.getEmailNormalized() == null) return;
        stickyOptOuts.recordOrFail(r.getEmailNormalized(), r.getOrgId(), "one_click");
        consumers.findByNormalizedEmail(r.getEmailNormalized())
                .flatMap(c -> memberships.findByOrgIdAndConsumerId(r.getOrgId(), c.getConsumerId()))
                .ifPresent(m -> consentService.unsubscribe(r.getOrgId(), m.getMembershipId(), "one_click",
                        "email", ConsentOrigin.DATA_SUBJECT, null));
        reminders.markUnsubscribed(reminderId, clock.instant());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private record Rendered(CheckoutReminderEmail.Message message, Event event, TicketTier tier) {}

    /** The org's soonest public night with a tier on sale, rendered with the real checkbox label. */
    private Optional<Rendered> render(Organization org, String lang, Integer rawQty, boolean test) {
        Instant now = clock.instant();
        for (Event e : events.findUpcomingLive(org.getId(), now, PageRequest.of(0, 20))) {
            if (e.getVisibility() != EventVisibility.PUBLIC || e.getPublishedAt() == null
                    || holds.salesSuspended(org.getId(), e.getId())) {
                continue;
            }
            for (TicketTier t : tiers.findByEventIdAndEnabledTrueOrderBySortOrderAsc(e.getId())) {
                if (!TierAvailability.isPurchasable(e, t, now)) continue;
                int qty = rawQty == null ? 2 : Math.max(1, Math.min(rawQty, MAX_PREVIEW_QTY));
                String proof = LABELS.getOrDefault(lang, LABELS.get("en")).formatted(org.getName());
                CheckoutReminderEmail.Message m = email.build(new CheckoutReminderEmail.Facts(
                        org, e, t, qty, lang, false, proof), emailProps.unsubscribeUrl("preview"), test);
                return Optional.of(new Rendered(m, e, t));
            }
        }
        return Optional.empty();
    }

    private Organization requireOrg(AuthPrincipal p) {
        return orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private static String firstName(User u) {
        String name = u.getFirstName() == null ? "" : u.getFirstName().trim();
        return name.isEmpty() ? null : name;
    }
}
