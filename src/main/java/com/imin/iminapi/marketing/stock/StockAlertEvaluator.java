package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.StockAlert;
import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertReason;
import com.imin.iminapi.marketing.model.StockAlertSettings;
import com.imin.iminapi.marketing.repository.StockAlertRepository;
import com.imin.iminapi.marketing.repository.StockAlertSettingsRepository;
import com.imin.iminapi.marketing.service.CampaignLocale;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.marketing.service.QuietHours;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.RoleGuard;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Each pass (behind the flag) expires ready alerts that stopped holding and stores one ready alert per (event, tier,
 * kind) when the claim holds and someone can get it; auto mode drafts and schedules it in the next allowed slot.
 */
@Component
public class StockAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(StockAlertEvaluator.class);
    // ponytail: over 500 ready alerts platform-wide, the newest wait a pass before they are re-checked or expired.
    static final int READY_PAGE = 500;

    private final StockAlertProperties props;
    private final StockAlertSettingsRepository settingsRepo;
    private final StockAlertRepository alerts;
    private final EventRepository events;
    private final StockFactsReader reader;
    private final StockAlertAudience audience;
    private final StockAlertNotifier notifier;
    private final StockAlertService service;
    private final CampaignService campaignService;
    private final QuietHours quietHours;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final TransactionTemplate newTx;
    private final Clock clock;

    public StockAlertEvaluator(StockAlertProperties props, StockAlertSettingsRepository settingsRepo,
                               StockAlertRepository alerts, EventRepository events, StockFactsReader reader,
                               StockAlertAudience audience, StockAlertNotifier notifier, StockAlertService service,
                               CampaignService campaignService, QuietHours quietHours,
                               OrganizationRepository organizations, UserRepository users,
                               PlatformTransactionManager txManager, Clock clock) {
        this.props = props;
        this.settingsRepo = settingsRepo;
        this.alerts = alerts;
        this.events = events;
        this.reader = reader;
        this.audience = audience;
        this.notifier = notifier;
        this.service = service;
        this.campaignService = campaignService;
        this.quietHours = quietHours;
        this.organizations = organizations;
        this.users = users;
        this.clock = clock;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${imin.marketing.stock-alerts.evaluator-delay-ms:300000}", initialDelay = 60_000)
    @SchedulerLock(name = "stock_alert_evaluator", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        if (!props.isEnabled()) return;
        runOnce(clock.instant());
    }

    void runOnce(Instant now) {
        int expired = expireReady(now);
        int prepared = 0;
        for (StockAlertSettings s : settingsRepo.findActiveForEvaluation(now)) {
            try {
                prepared += evaluate(s, now);
            } catch (RuntimeException e) {
                // One event must not stop the pass; it is read again on the next one.
                log.error("[stock-alerts] event {} failed: {}", s.getEventId(), e.getMessage(), e);
            }
        }
        log.info("[stock-alerts] pass: {} prepared, {} expired", prepared, expired);
    }

    /** Ready alerts whose claim no longer holds expire with the reason; the others get today's facts. */
    private int expireReady(Instant now) {
        Map<UUID, Optional<StockFacts.Snapshot>> snapshots = new HashMap<>();
        Map<UUID, Optional<StockAlertSettings>> settings = new HashMap<>();
        int expired = 0;
        for (StockAlert a : alerts.findReady(PageRequest.of(0, READY_PAGE))) {
            try {
                Optional<StockFacts.Snapshot> snap = snapshots.computeIfAbsent(a.getEventId(),
                        id -> reader.read(a.getOrgId(), id, now));
                StockAlertSettings s = settings.computeIfAbsent(a.getEventId(), settingsRepo::findByEventId).orElse(null);
                StockAlertKind kind = StockAlertService.kindOf(a);
                Optional<String> reason;
                int threshold = 0;
                if (snap.isEmpty()) {
                    reason = Optional.of(StockAlertReason.EVENT_NOT_ON_SALE);
                } else {
                    threshold = StockAlertService.thresholdFor(s, snap.get(), a.getTierId(),
                            StockFactsReader.parse(a.getFactsJson()).orElse(null));
                    reason = StockFacts.blocker(snap.get(), kind, a.getTierId(), threshold, StockAlertService.leadHours(s));
                }
                if (reason.isPresent()) {
                    expired += alerts.markExpired(a.getId(), reason.get(), now);
                } else {
                    alerts.refreshReadyFacts(a.getId(),
                            StockFactsReader.json(StockFacts.of(snap.get(), kind, a.getTierId(), threshold)));
                }
            } catch (RuntimeException e) {
                log.error("[stock-alerts] ready alert {} not re-checked: {}", a.getId(), e.getMessage(), e);
            }
        }
        return expired;
    }

    private int evaluate(StockAlertSettings s, Instant now) {
        Event e = events.findActive(s.getEventId()).orElse(null);
        if (e == null) return 0;
        StockFacts.Snapshot snap = reader.snapshot(e, now);
        if (!snap.eventOnSale()) return 0;   // sales suspended since the query
        List<StockAlertKind> kinds = new ArrayList<>(2);
        if (s.isLastTicketsOn()) kinds.add(StockAlertKind.LAST_TICKETS);
        if (s.isPriceUpOn()) kinds.add(StockAlertKind.PRICE_UP);
        StockAlertAudience.Reach reach = null;
        String locale = null;
        int prepared = 0;
        for (StockFacts.Tier t : snap.tiers()) {
            for (StockAlertKind kind : kinds) {
                int threshold = StockFacts.threshold(s.getLastTicketsThreshold(), t.quantity());
                if (StockFacts.blocker(snap, kind, t.id(), threshold, s.getPriceUpLeadHours()).isPresent()) continue;
                if (alerts.existsByEventIdAndTierIdAndKind(e.getId(), t.id(), kind.wire())) continue;
                if (reach == null) reach = audience.dryRun(e.getOrgId(), e.getId(), now);
                // Nobody can get it: store nothing, so a later pass can still prepare it once someone can.
                if (reach.canReceive() <= 0) continue;
                if (locale == null) locale = campaignLocale(e.getOrgId());
                StockFacts facts = StockFacts.of(snap, kind, t.id(), threshold);
                UUID id = UUID.randomUUID();
                if (alerts.insertIfAbsent(id, e.getOrgId(), e.getId(), t.id(), kind.wire(), StockFactsReader.json(facts),
                        reach.canReceive(), locale, now) == 0) {
                    continue;
                }
                prepared++;
                if (s.isAuto()) auto(s, e, id, kind, facts, reach, now);
                else notifier.ready(e.getOrgId(), e.getId(), e.getName(), kind, facts);
            }
        }
        return prepared;
    }

    /** Draft and schedule without a click; anything that refuses leaves the alert for the owner to review. */
    private void auto(StockAlertSettings s, Event e, UUID alertId, StockAlertKind kind, StockFacts facts,
                      StockAlertAudience.Reach reach, Instant now) {
        Organization org = organizations.findById(e.getOrgId()).orElse(null);
        String tz = org == null ? null : org.getTimezone();
        Instant when = quietHours.nextEmailAllowed(tz, now.plus(Duration.ofMinutes(props.getAutoMinLeadMinutes())));
        if (kind == StockAlertKind.PRICE_UP && facts.closesAt() != null
                && when.isAfter(facts.closesAt().minus(Duration.ofHours(props.getPriceUpMinMarginHours())))) {
            notifier.ready(e.getOrgId(), e.getId(), e.getName(), kind, facts);
            return;
        }
        User admin = enablingAdmin(s, e.getOrgId());
        if (admin == null) {
            log.warn("[stock-alerts] event {}: the admin who turned on automatic alerts can no longer — asking", e.getId());
            notifier.ready(e.getOrgId(), e.getId(), e.getName(), kind, facts);
            return;
        }
        StockAlert a = alerts.findById(alertId).orElse(null);
        Campaign c = null;
        if (a != null) {
            try {
                c = newTx.execute(st -> service.draft(a, e, facts, reach, admin.getId(), a.getLocale(), now));
            } catch (RuntimeException ex) {
                log.warn("[stock-alerts] alert {}: automatic draft not made: {}", alertId, ex.getMessage());
            }
        }
        if (c == null) {
            // Nothing was drafted, so the alert is still ready: ask, as in ask mode.
            notifier.ready(e.getOrgId(), e.getId(), e.getName(), kind, facts);
            return;
        }
        try {
            campaignService.scheduleAutomated(c.getId(), when, admin.getId());
            notifier.scheduled(e.getOrgId(), e.getId(), e.getName(), facts.tierName(), when, tz);
        } catch (RuntimeException ex) {
            // The draft exists but nothing is scheduled: point the owner at the draft to send it themselves.
            log.warn("[stock-alerts] alert {}: automatic send refused: {}", alertId, ex.getMessage());
            notifier.draftWaiting(e.getOrgId(), e.getId(), e.getName(), facts.tierName(), c.getId());
        }
    }

    /** The admin who turned automatic sending on, while still an admin or owner of this org. */
    private User enablingAdmin(StockAlertSettings s, UUID orgId) {
        if (s.getAutoEnabledBy() == null) return null;
        return users.findById(s.getAutoEnabledBy())
                .filter(u -> orgId.equals(u.getOrgId()))
                .filter(u -> RoleGuard.isAtLeast(new AuthPrincipal(u.getId(), orgId, u.getRole(), null), UserRole.ADMIN))
                .orElse(null);
    }

    /** The email language a new campaign of this org gets: org country, else the owner's language. */
    private String campaignLocale(UUID orgId) {
        Organization org = organizations.findById(orgId).orElse(null);
        String ownerLocale = users.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .filter(u -> u.getRole() == UserRole.OWNER)
                .findFirst().map(User::getLocale).orElse(null);
        return CampaignLocale.defaultFor(org == null ? null : org.getCountry(), ownerLocale);
    }
}
