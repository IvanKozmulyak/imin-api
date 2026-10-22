package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.StockAlertDto;
import com.imin.iminapi.marketing.dto.StockAlertSettingsRequest;
import com.imin.iminapi.marketing.dto.StockAlertsDto;
import com.imin.iminapi.marketing.dto.StockFactsDto;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.RecipientStatuses;
import com.imin.iminapi.marketing.model.StockAlert;
import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertSettings;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.StockAlertRepository;
import com.imin.iminapi.marketing.repository.StockAlertSettingsRepository;
import com.imin.iminapi.marketing.service.CampaignAttributionService;
import com.imin.iminapi.marketing.service.CampaignLocale;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RoleGuard;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An event's stock-alert card: GET, settings PUT (ADMIN/OWNER, auto needs a confirm, audited), review (ready→drafted
 * with a draft and an audience snapshot; never sends) and dismiss (ready→dismissed).
 */
@Service
public class StockAlertService {

    static final Set<Integer> LEAD_HOURS = Set.of(24, 48, 72);
    /** A fixed threshold only lowers the tier's auto rule, which never exceeds this. */
    static final int MAX_THRESHOLD = StockFacts.AUTO_MAX;
    private static final int MAX_CAMPAIGN_NAME = 120;

    private final StockAlertProperties props;
    private final StockAlertRepository alerts;
    private final StockAlertSettingsRepository settingsRepo;
    private final EventRepository events;
    private final StockFactsReader reader;
    private final StockAlertAudience audience;
    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final CampaignAttributionService attribution;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final AuditLogger audit;
    private final Clock clock;

    public StockAlertService(StockAlertProperties props, StockAlertRepository alerts,
                             StockAlertSettingsRepository settingsRepo, EventRepository events,
                             StockFactsReader reader, StockAlertAudience audience, CampaignRepository campaigns,
                             CampaignRecipientRepository recipients, CampaignAttributionService attribution,
                             OrganizationRepository organizations, UserRepository users, AuditLogger audit,
                             Clock clock) {
        this.props = props;
        this.alerts = alerts;
        this.settingsRepo = settingsRepo;
        this.events = events;
        this.reader = reader;
        this.audience = audience;
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.attribution = attribution;
        this.organizations = organizations;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StockAlertsDto card(AuthPrincipal p, UUID eventId) {
        Event e = requireEvent(p, eventId);
        return cardOf(e, settingsRepo.findByEventId(eventId).orElse(null));
    }

    @Transactional
    public StockAlertsDto putSettings(AuthPrincipal p, UUID eventId, StockAlertSettingsRequest req) {
        requireEnabled();
        Event e = requireEvent(p, eventId);
        RoleGuard.requireAtLeast(p, UserRole.ADMIN, "change stock alerts");
        StockAlertSettingsRequest r = req == null
                ? new StockAlertSettingsRequest(null, null, null, null, null, null) : req;
        StockAlertSettings s = settingsRepo.findByEventId(eventId).orElseGet(() -> {
            StockAlertSettings fresh = new StockAlertSettings();
            fresh.setEventId(eventId);
            fresh.setOrgId(e.getOrgId());
            return fresh;
        });
        String mode = r.mode() == null ? StockAlertSettings.MODE_ASK : r.mode();
        int lead = r.priceUpLeadHours() == null ? 24 : r.priceUpLeadHours();
        Map<String, String> invalid = new LinkedHashMap<>();
        if (r.lastTicketsThreshold() != null && (r.lastTicketsThreshold() < 1 || r.lastTicketsThreshold() > MAX_THRESHOLD)) {
            invalid.put("lastTicketsThreshold", "between_1_and_" + MAX_THRESHOLD);
        }
        if (!LEAD_HOURS.contains(lead)) invalid.put("priceUpLeadHours", "invalid");
        if (!StockAlertSettings.MODE_ASK.equals(mode) && !StockAlertSettings.MODE_AUTO.equals(mode)) {
            invalid.put("mode", "invalid");
        }
        boolean wasAuto = s.isAuto();
        boolean turningAutoOn = StockAlertSettings.MODE_AUTO.equals(mode) && !wasAuto;
        if (turningAutoOn && !Boolean.TRUE.equals(r.autoConfirmed())) invalid.put("autoConfirmed", "required");
        if (!invalid.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Stock alert settings are invalid",
                    invalid);
        }
        Instant now = clock.instant();
        s.setLastTicketsOn(Boolean.TRUE.equals(r.lastTicketsOn()));
        s.setLastTicketsThreshold(r.lastTicketsThreshold());
        s.setPriceUpOn(Boolean.TRUE.equals(r.priceUpOn()));
        s.setPriceUpLeadHours(lead);
        s.setMode(mode);
        if (turningAutoOn) {
            s.setAutoEnabledBy(p.userId());
            s.setAutoEnabledAt(now);
        } else if (!StockAlertSettings.MODE_AUTO.equals(mode)) {
            s.setAutoEnabledBy(null);
            s.setAutoEnabledAt(null);
        }
        s.setUpdatedBy(p.userId());
        s.setUpdatedAt(now);
        StockAlertSettings saved;
        try {
            saved = settingsRepo.saveAndFlush(s);
        } catch (OptimisticLockingFailureException ex) {
            throw ApiException.staleWrite();
        }
        audit.record(p, AuditActions.STOCK_ALERT_SETTINGS_CHANGED, "event", eventId, "Stock alerts: last tickets "
                + (saved.isLastTicketsOn() ? "on" : "off") + " (threshold "
                + (saved.getLastTicketsThreshold() == null ? "auto" : saved.getLastTicketsThreshold()) + "), price up "
                + (saved.isPriceUpOn() ? "on" : "off") + " (" + saved.getPriceUpLeadHours() + " h), mode "
                + saved.getMode());
        if (turningAutoOn) {
            audit.record(p, AuditActions.STOCK_ALERT_AUTO_ON, "event", eventId,
                    "Automatic stock alerts turned on after an explicit confirm");
        } else if (wasAuto && !saved.isAuto()) {
            audit.record(p, AuditActions.STOCK_ALERT_AUTO_OFF, "event", eventId, "Automatic stock alerts turned off");
        }
        return cardOf(e, saved);
    }

    /** "Review and send": a draft campaign for the alert; 409 when it is no longer ready or no longer true. */
    @Transactional
    public CampaignDto review(AuthPrincipal p, UUID eventId, UUID alertId) {
        requireEnabled();
        Event e = requireEvent(p, eventId);
        StockAlert a = requireAlert(p, eventId, alertId);
        if (!StockAlert.READY.equals(a.getStatus())) throw notReady();
        StockAlertSettings s = settingsRepo.findByEventId(eventId).orElse(null);
        Instant now = clock.instant();
        StockFacts.Snapshot snap = reader.snapshot(e, now);
        StockAlertKind kind = kindOf(a);
        StockFacts prepared = StockFactsReader.parse(a.getFactsJson()).orElse(null);
        int threshold = thresholdFor(s, snap, a.getTierId(), prepared);
        Optional<String> blocked = StockFacts.blocker(snap, kind, a.getTierId(), threshold, leadHours(s));
        if (blocked.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "The stock fact this alert quotes is no longer true", Map.of("stockFacts", blocked.get()));
        }
        StockAlertAudience.Reach reach = audience.dryRun(e.getOrgId(), eventId, now);
        if (reach.canReceive() <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, "Nobody can get this alert now",
                    Map.of("audience", "empty"));
        }
        Organization org = organizations.findById(e.getOrgId()).orElse(null);
        String userLocale = users.findById(p.userId()).map(User::getLocale).orElse(null);
        String locale = CampaignLocale.defaultFor(org == null ? null : org.getCountry(), userLocale);
        Campaign c = draft(a, e, StockFacts.of(snap, kind, a.getTierId(), threshold), reach, p.userId(), locale, now);
        audit.record(p, AuditActions.CAMPAIGN_CREATED, "campaign", c.getId(),
                "Stock alert draft created: " + c.getName());
        return CampaignDto.from(c);
    }

    /** "Not this time": the alert never comes back for its tier and kind. */
    @Transactional
    public void dismiss(AuthPrincipal p, UUID eventId, UUID alertId) {
        requireEnabled();
        requireEvent(p, eventId);
        StockAlert a = requireAlert(p, eventId, alertId);
        if (alerts.markDismissed(a.getId(), clock.instant(), p.userId()) == 0) throw notReady();
    }

    /** The draft and its audience snapshot, claimed by ready→drafted; a lost claim throws 409 and rolls both back. */
    Campaign draft(StockAlert a, Event e, StockFacts facts, StockAlertAudience.Reach reach, UUID actor, String locale,
                   Instant now) {
        StockAlertKind kind = kindOf(a);
        StockAlertCopy.Draft copy = StockAlertCopy.draft(kind, e.getName(), facts.tierName(), locale);
        UUID segmentId = audience.snapshot(e.getOrgId(), copy.name(), reach.members());
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(e.getOrgId());
        c.setChannel("email");
        c.setName(copy.name().length() <= MAX_CAMPAIGN_NAME ? copy.name() : copy.name().substring(0, MAX_CAMPAIGN_NAME));
        c.setStatus("draft");
        c.setOrigin(com.imin.iminapi.audienceplan.service.SendPathGuard.STOCK_ALERT_ORIGIN);
        c.setSegmentId(segmentId);
        c.setEventId(e.getId());
        c.setSubject(copy.subject());
        c.setPreheader(copy.preheader());
        c.setBodyMd(copy.bodyMd());
        c.setTemplateKey("classic");
        c.setLocale(locale);
        c.setSkipTicketHolders(true);
        c.setCreatedBy(actor);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        Campaign saved = campaigns.save(c);
        if (alerts.markDrafted(a.getId(), saved.getId(), StockFactsReader.json(facts), now, actor) == 0) {
            throw notReady();
        }
        return saved;
    }

    private StockAlertsDto cardOf(Event e, StockAlertSettings s) {
        Instant now = clock.instant();
        StockFacts.Snapshot snap = reader.snapshot(e, now);
        // ponytail: a full dry run per card read (ConsentGate over the org's audience); cache it if orgs grow past ~50k.
        StockAlertAudience.Reach r = audience.dryRun(e.getOrgId(), e.getId(), now);
        Integer lead = leadHours(s);
        List<StockAlertsDto.Tier> tiers = snap.tiers().stream().map(t -> {
            int threshold = StockFacts.threshold(s == null ? null : s.getLastTicketsThreshold(), t.quantity());
            boolean priceUp = StockFacts.priceUpBlocker(snap, t.id(), null).isEmpty();
            StockFacts.Tier next = priceUp ? StockFacts.nextAt(snap, t).orElse(null) : null;
            return new StockAlertsDto.Tier(t.id(), t.name(), t.enabled(), StockFacts.unavailable(snap, t.id()).isEmpty(),
                    t.remaining(), t.quantity(), threshold, t.priceAllInMinor(), t.saleClosesAt(),
                    next == null ? null : new StockAlertsDto.Next(next.id(), next.name(), next.priceAllInMinor()),
                    next == null ? null : t.saleClosesAt().minus(Duration.ofHours(lead)));
        }).toList();
        List<StockAlert> rows = alerts.findByEventIdOrderByCreatedAtDesc(e.getId());
        // ponytail: one campaign lookup per alert; fine at two alerts per tier, a batch query once events carry dozens.
        List<StockAlertDto> list = rows.stream().map(a -> alertDto(a, a.getCampaignId() == null ? null
                : campaigns.findByIdAndOrgId(a.getCampaignId(), e.getOrgId()).orElse(null))).toList();
        StockAlertsDto.Settings settings = s == null
                ? new StockAlertsDto.Settings(false, null, false, 24, StockAlertSettings.MODE_ASK, null, null)
                : new StockAlertsDto.Settings(s.isLastTicketsOn(), s.getLastTicketsThreshold(), s.isPriceUpOn(),
                        s.getPriceUpLeadHours(), s.getMode(), s.getAutoEnabledAt(), s.getAutoEnabledBy());
        StockAlertsDto.Reach reach = new StockAlertsDto.Reach(r.canReceive(),
                new StockAlertsDto.LeftOut(r.haveTicket(), r.alreadyAlerted(), r.recentlyEmailed(), r.eventCap(),
                        r.monthlyCap(), r.noConsent(), r.experimentHoldout()),
                r.frequencyFloorHours());
        return new StockAlertsDto(props.isEnabled(), settings, reach, tiers, list, e.getTimezone(), e.getCurrency());
    }

    private StockAlertDto alertDto(StockAlert a, Campaign c) {
        StockFacts facts = StockFactsReader.parse(a.getFactsJson()).orElse(null);
        StockFacts atSend = StockFactsReader.parse(a.getFactsAtSendJson()).orElse(null);
        String tierName = atSend != null ? atSend.tierName() : facts == null ? null : facts.tierName();
        return new StockAlertDto(a.getId(), a.getKind(), a.getTierId(), tierName, a.getStatus(),
                c == null ? null : c.getId(), c == null ? null : c.getStatus(),
                c == null ? null : c.getScheduledAt(), c == null ? null : c.getSentAt(),
                c == null ? null : c.getRecipientCount(), c == null ? null : revenue(c),
                StockFactsDto.from(facts), StockFactsDto.from(atSend), a.getReachAtPrepare(), a.getWithdrawnReason(),
                a.getExpiredReason(), a.getCreatedAt(), a.getActedAt());
    }

    // As CampaignService.attributedRevenue: null until a link of the campaign could be in an inbox.
    private Long revenue(Campaign c) {
        boolean sent = c.getSentAt() != null || "sent".equals(c.getStatus()) || "sending".equals(c.getStatus())
                || (("canceled".equals(c.getStatus()) || "failed".equals(c.getStatus()))
                && recipients.countByCampaignIdAndStatusIn(c.getId(), RecipientStatuses.LEFT_THE_QUEUE) > 0);
        return sent ? attribution.attributedRevenueMinor(c.getOrgId(), c.getId()) : null;
    }

    /** The settings' threshold for the tier now; an alert prepared under an older one keeps its own limit. */
    static int thresholdFor(StockAlertSettings s, StockFacts.Snapshot snap, UUID tierId, StockFacts prepared) {
        int quantity = snap.tier(tierId).map(StockFacts.Tier::quantity).orElse(prepared == null ? 0 : prepared.quantity());
        return StockFacts.threshold(s == null ? null : s.getLastTicketsThreshold(), quantity);
    }

    static Integer leadHours(StockAlertSettings s) {
        return s == null ? 24 : s.getPriceUpLeadHours();
    }

    static StockAlertKind kindOf(StockAlert a) {
        return StockAlertKind.fromWire(a.getKind())
                .orElseThrow(() -> new IllegalStateException("unknown stock alert kind " + a.getKind()));
    }

    private void requireEnabled() {
        if (!props.isEnabled()) throw ApiException.notFound("Stock alerts");
    }

    private Event requireEvent(AuthPrincipal p, UUID eventId) {
        return events.findActive(eventId)
                .filter(ev -> p.orgId() != null && p.orgId().equals(ev.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("Event"));
    }

    private StockAlert requireAlert(AuthPrincipal p, UUID eventId, UUID alertId) {
        return alerts.findByIdAndOrgId(alertId, p.orgId())
                .filter(a -> eventId.equals(a.getEventId()))
                .orElseThrow(() -> ApiException.notFound("Stock alert"));
    }

    private static ApiException notReady() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, "This stock alert is no longer waiting");
    }
}
