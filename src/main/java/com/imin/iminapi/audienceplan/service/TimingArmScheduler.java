package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.TimingArm;
import com.imin.iminapi.audienceplan.engine.ArmTimes;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Send times of invitation arms. Approval computes the arm's time (or arms a slump arm); a Momentum SLUMP schedules
 * the armed slump drafts. Callers apply the sends switch first; the dispatcher still refuses while it is off.
 */
@Service
public class TimingArmScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimingArmScheduler.class);

    static final String LAUNCH = key(TimingArm.LAUNCH);
    static final String D3 = key(TimingArm.D3);
    static final String EARLY_BIRD_END = key(TimingArm.EARLY_BIRD_END);
    static final String SLUMP = key(TimingArm.SLUMP);

    /** Either a send instant, or {@code armed} for a slump arm that waits for Momentum. */
    public record Approval(Instant at, boolean armed) {}

    private final AudienceExperimentRepository experiments;
    private final EventRepository events;
    private final OrganizationRepository organizations;
    private final TicketTierRepository tiers;
    private final CampaignRepository campaigns;
    private final AudiencePlanAccess access;
    private final AuditLogger audit;
    private final Clock clock;

    public TimingArmScheduler(AudienceExperimentRepository experiments, EventRepository events,
                              OrganizationRepository organizations, TicketTierRepository tiers,
                              CampaignRepository campaigns, AudiencePlanAccess access, AuditLogger audit,
                              Clock clock) {
        this.experiments = experiments;
        this.events = events;
        this.organizations = organizations;
        this.tiers = tiers;
        this.campaigns = campaigns;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Empty when the campaign is not an invitation arm (the caller keeps its own time). The arm owns the time, so a
     * client {@code requestedAt} is refused.
     */
    @Transactional
    public Optional<Approval> onApproval(Campaign c, Instant requestedAt) {
        if (c.getId() == null) return Optional.empty();
        Optional<AudienceExperiment> found = experiments.findFirstByCampaignIdAndOrgId(c.getId(), c.getOrgId());
        if (found.isEmpty()) return Optional.empty();
        AudienceExperiment arm = found.get();
        if (requestedAt != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                    Map.of("scheduledAt", "an invitation arm sets its own send time; leave it out"));
        }
        Instant now = clock.instant();
        Event event = events.findActive(arm.getEventId()).filter(e -> c.getOrgId().equals(e.getOrgId()))
                .orElseThrow(() -> ApiException.invalidState("The invited event no longer exists"));
        Instant start = event.getStartsAt();
        if (start == null || !start.isAfter(now)) throw ApiException.invalidState("The event has already started");

        if (SLUMP.equals(arm.getArm())) {
            if (!"draft".equals(c.getStatus())) throw ApiException.invalidState("Campaign is not in draft");
            if (arm.getArmedAt() != null) throw ApiException.invalidState("This slump arm is already armed");
            arm.setArmedAt(now.truncatedTo(ChronoUnit.MICROS));
            experiments.save(arm);
            return Optional.of(new Approval(null, true));
        }

        ZoneId eventZone = PlanService.zone(event.getTimezone());
        Instant at;
        if (LAUNCH.equals(arm.getArm())) {
            Instant onSale = event.getOnSaleAt();
            at = onSale != null && onSale.isAfter(now) ? onSale : now;
        } else if (D3.equals(arm.getArm())) {
            at = ArmTimes.d3(start, eventZone);
        } else if (EARLY_BIRD_END.equals(arm.getArm())) {
            at = ArmTimes.earlyBirdEnd(tiers.findByEventIdOrderBySortOrderAsc(event.getId()), start, eventZone)
                    .orElseThrow(() -> ApiException.invalidState("The early-bird tier no longer ends before D-3"));
        } else {
            return Optional.empty();
        }
        if (at.isBefore(now)) throw ApiException.invalidState("This arm's send time has passed");
        at = ArmTimes.outOfQuietHours(at, orgZone(c.getOrgId()));
        if (!at.isBefore(start)) throw ApiException.invalidState("This arm would go out after the event starts");
        return Optional.of(new Approval(at, false));
    }

    /** An edited slump draft needs a fresh approval, so an edit clears its arming. */
    @Transactional
    public void disarm(Campaign c) {
        if (c.getId() == null) return;
        experiments.findFirstByCampaignIdAndOrgId(c.getId(), c.getOrgId())
                .filter(arm -> arm.getArmedAt() != null)
                .ifPresent(arm -> {
                    arm.setArmedAt(null);
                    experiments.save(arm);
                });
    }

    /** Whether a new invitation may pick the early-bird arm: its 18:00 send is still ahead and before D-3. */
    @Transactional(readOnly = true)
    public boolean earlyBirdOffered(Event event, Instant now) {
        if (event.getStartsAt() == null) return false;
        return ArmTimes.earlyBirdEnd(tiers.findByEventIdOrderBySortOrderAsc(event.getId()), event.getStartsAt(),
                PlanService.zone(event.getTimezone())).filter(at -> at.isAfter(now)).isPresent();
    }

    /**
     * Schedules the event's armed slump drafts for now (pushed out of quiet hours). Does nothing while the org is off
     * the beta, sends are off or the org lacks its legal identity. Own transaction: the evaluator may hold one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int fireSlump(UUID orgId, UUID eventId) {
        if (!access.isEnabled(orgId) || !access.sendsEnabled()) return 0;
        List<AudienceExperiment> armed = experiments.findByOrgIdAndEventIdAndArmAndArmedAtIsNotNull(orgId, eventId, SLUMP);
        if (armed.isEmpty()) return 0;
        Instant now = clock.instant();
        Event event = events.findActive(eventId).filter(e -> orgId.equals(e.getOrgId())).orElse(null);
        if (event == null || event.getStartsAt() == null || !event.getStartsAt().isAfter(now)) return 0;
        Organization org = organizations.findById(orgId).orElse(null);
        if (org == null || !org.hasLegalIdentity()) {
            log.info("TimingArmScheduler: slump arms of event {} held, org has no legal identity", eventId);
            return 0;
        }
        Instant at = ArmTimes.outOfQuietHours(now, PlanService.zone(org.getTimezone()));
        if (!at.isBefore(event.getStartsAt())) return 0;
        AuthPrincipal system = new AuthPrincipal(null, orgId, UserRole.MEMBER, null);
        int scheduled = 0;
        for (AudienceExperiment arm : armed) {
            if (arm.getCampaignId() == null) continue;
            // Only a draft moves; a deleted, cancelled or already scheduled campaign is left alone.
            if (campaigns.markScheduledIfDraft(arm.getCampaignId(), orgId, at) == 1) {
                scheduled++;
                audit.record(system, AuditActions.CAMPAIGN_SENT, "campaign", arm.getCampaignId(),
                        "Audience plan slump arm scheduled at " + at);
            }
        }
        return scheduled;
    }

    private ZoneId orgZone(UUID orgId) {
        return PlanService.zone(organizations.findById(orgId).map(Organization::getTimezone).orElse(null));
    }

    private static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }
}
