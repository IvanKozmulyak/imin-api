package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.model.RecipientStatuses;
import com.imin.iminapi.marketing.model.StockAlert;
import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertReason;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.StockAlertRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Re-checks a stock alert's facts before the drive (freezing them once) and before every batch; when the claim stops
 * holding it cancels, skips the queue as fact_changed, withdraws the alert and tells the owner. Sent rows stay sent.
 */
@Component
public class StockAlertSendGuard {

    private static final Logger log = LoggerFactory.getLogger(StockAlertSendGuard.class);
    /** Log and audit reason for a stock-alert campaign whose drafted alert is gone; never stored on an alert. */
    public static final String ALERT_MISSING = "alert_missing";

    private final StockAlertRepository alerts;
    private final StockFactsReader reader;
    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final StockAlertNotifier notifier;
    private final EventRepository events;
    private final AuditLogger audit;
    private final Clock clock;

    public StockAlertSendGuard(StockAlertRepository alerts, StockFactsReader reader, CampaignRepository campaigns,
                               CampaignRecipientRepository recipients, StockAlertNotifier notifier,
                               EventRepository events, AuditLogger audit, Clock clock) {
        this.alerts = alerts;
        this.reader = reader;
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.notifier = notifier;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** Before the audience is materialized: freezes the facts or withdraws. False means the drive must stop. */
    public boolean beforeDrive(Campaign c) {
        return check(c, true);
    }

    /** Before each batch: the frozen claim must still be true; it never freezes. False means the drive must stop. */
    public boolean beforeBatch(Campaign c) {
        return check(c, false);
    }

    /** The frozen facts' token values in the campaign language; throws when none were frozen, so no batch goes unchecked. */
    public java.util.Map<String, String> frozenTokens(Campaign c) {
        StockAlert a = alerts.findByCampaignId(c.getId())
                .orElseThrow(() -> new IllegalStateException("stock alert campaign " + c.getId() + " has no alert"));
        StockFacts frozen = StockFactsReader.parse(a.getFactsAtSendJson())
                .orElseThrow(() -> new IllegalStateException("stock alert " + a.getId() + " has no frozen facts"));
        return StockFactTokens.values(StockAlertService.kindOf(a), frozen, c.getLocale());
    }

    /** What the alert's claim looks like against the tier rows now. */
    private record Verdict(StockAlert alert, StockAlertKind kind, StockFacts frozen, StockFacts fresh, String reason) {}

    /** The claim judged now against the frozen facts, or before the freeze against the facts read at review. */
    private Verdict verdict(Campaign c, Instant now) {
        StockAlert a = alerts.findByCampaignId(c.getId()).orElse(null);
        // Without its drafted alert the facts cannot be checked, so nothing is sent.
        if (a == null || !StockAlert.DRAFTED.equals(a.getStatus())) return new Verdict(a, null, null, null, ALERT_MISSING);
        StockAlertKind kind = StockAlertService.kindOf(a);
        StockFacts frozen = StockFactsReader.parse(a.getFactsAtSendJson()).orElse(null);
        StockFacts prepared = StockFactsReader.parse(a.getFactsJson()).orElse(null);
        Optional<StockFacts.Snapshot> snap = c.getEventId() == null ? Optional.empty()
                : reader.read(c.getOrgId(), c.getEventId(), now);
        if (snap.isEmpty()) return new Verdict(a, kind, frozen, null, StockAlertReason.EVENT_NOT_ON_SALE);
        int threshold = frozen != null ? frozen.threshold()
                : prepared != null ? prepared.threshold()
                : StockFacts.threshold(null, snap.get().tier(a.getTierId()).map(StockFacts.Tier::quantity).orElse(0));
        // No lead window at send: the change moment is what the email states, and it is compared below.
        Optional<String> reason = StockFacts.blocker(snap.get(), kind, a.getTierId(), threshold, null);
        StockFacts fresh = snap.get().tier(a.getTierId()).isPresent()
                ? StockFacts.of(snap.get(), kind, a.getTierId(), threshold) : null;
        // Before the freeze, what the organizer reviewed is the reference: a moved deadline or price is not sent.
        StockFacts reference = frozen != null ? frozen : prepared;
        if (reason.isEmpty() && reference != null) reason = StockFacts.frozenMismatch(kind, reference, fresh);
        return new Verdict(a, kind, frozen, fresh, reason.orElse(null));
    }

    /** Readiness at Send: why the alert's claim is not true now, or empty ({@code alert_missing} without its alert). */
    public Optional<String> notTrueNow(Campaign c) {
        return Optional.ofNullable(verdict(c, clock.instant()).reason());
    }

    /** Preview and test-send token values: the frozen facts once sent, else facts read now; empty when neither reads. */
    public java.util.Map<String, String> previewTokens(Campaign c, String locale) {
        StockAlert a = alerts.findByCampaignId(c.getId()).orElse(null);
        StockFacts frozen = a == null ? null : StockFactsReader.parse(a.getFactsAtSendJson()).orElse(null);
        if (frozen != null) return StockFactTokens.values(StockAlertService.kindOf(a), frozen, locale);
        Verdict v = verdict(c, clock.instant());
        if (v.kind() == null || v.fresh() == null) return java.util.Map.of();
        return StockFactTokens.values(v.kind(), v.fresh(), locale);
    }

    private boolean check(Campaign c, boolean mayFreeze) {
        Instant now = clock.instant();
        Verdict v = verdict(c, now);
        if (v.reason() != null) return withdraw(c, ALERT_MISSING.equals(v.reason()) ? null : v.alert(), v.reason(), now);
        if (v.frozen() == null && mayFreeze) {
            if (alerts.recordFactsAtSendIfNull(v.alert().getId(), StockFactsReader.json(v.fresh())) == 0) {
                // Another drive froze them first (or the alert moved on): judge against what is stored now.
                return check(c, false);
            }
        }
        return true;
    }

    /** Cancel, skip the queue, mark the alert withdrawn, tell the owner; each step after the cancel is isolated. */
    private boolean withdraw(Campaign c, StockAlert a, String reason, Instant now) {
        if (campaigns.cancelIfActive(c.getId(), c.getOrgId(), now) == 0) {
            // Canceled, failed or sent by another writer: that writer owns the queue and the alert stays as it is.
            c.setStatus(campaigns.findStatusById(c.getId()).orElse(null));
            log.warn("[stock-alerts] campaign {} was no longer active when {} — not withdrawing", c.getId(), reason);
            return false;
        }
        c.setStatus("canceled");
        int notSent = 0;
        try {
            notSent = recipients.skipPendingOfCanceled(c.getId(), CampaignRecipient.SKIP_FACT_CHANGED, now);
        } catch (RuntimeException e) {
            // The campaign is canceled, so no further batch is claimed; the rows only stay pending.
            log.error("[stock-alerts] campaign {} canceled, but its queue was not skipped: {}", c.getId(), e.getMessage());
        }
        log.info("[stock-alerts] campaign {} withdrawn: {} ({} not sent)", c.getId(), reason, notSent);
        try {
            long sent = recipients.countByCampaignIdAndStatusIn(c.getId(), RecipientStatuses.LEFT_THE_QUEUE);
            audit.record(new AuthPrincipal(null, c.getOrgId(), UserRole.MEMBER, null), "CAMPAIGN_CANCELED", "campaign",
                    c.getId(), "Stock alert withdrawn (" + reason + "): " + sent + " sent, " + notSent + " not sent");
        } catch (RuntimeException e) {
            log.error("[stock-alerts] campaign {} withdrawal not audited: {}", c.getId(), e.getMessage());
        }
        if (a == null) return false;
        int marked = 0;
        try {
            marked = alerts.markWithdrawn(a.getId(), reason, now);
        } catch (RuntimeException e) {
            log.error("[stock-alerts] alert {} not marked withdrawn: {}", a.getId(), e.getMessage());
        }
        if (marked == 1) {
            String eventName = events.findActive(a.getEventId()).map(Event::getName).orElse(null);
            String tierName = StockFactsReader.parse(a.getFactsAtSendJson())
                    .or(() -> StockFactsReader.parse(a.getFactsJson()))
                    .map(StockFacts::tierName).orElse("");
            notifier.withdrawn(a.getOrgId(), a.getEventId(), eventName, tierName, reason);
        }
        return false;
    }
}
