package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.audienceplan.service.RetentionCutoff;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.util.LogSafe;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 3-year retention for every org: deletes memberships with no contact since the cutoff (RetentionPurgeSql), opt-out
 * rows created before it, and the address of finished recipient rows of campaigns that ended before it.
 * Orders and tickets stay. With {@code retention-purge-enabled=false} it only counts and logs.
 */
@Component
public class AudienceRetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AudienceRetentionPurgeJob.class);

    static final int PAGE_SIZE = 500;
    // ponytail: at most 5,000 memberships per night; a larger backlog drains over the following nights.
    static final int MAX_MEMBERSHIPS_PER_RUN = 5_000;

    private static final Instant CURSOR_START = Instant.EPOCH;
    private static final UUID CURSOR_START_ID = new UUID(0L, 0L);

    /** Counts of one run; {@code capped} is true when due memberships were left for the next run. */
    public record Result(boolean enabled, int orgs, int membershipsExpired, int membershipsDeleted,
                         int failed, boolean capped, int optOutsExpired, int optOutsDeleted,
                         int recipientRowsExpired, int recipientRowsRedacted) {}

    private final MembershipRepository memberships;
    private final MarketingOptOutRepository optOuts;
    private final CampaignRecipientRepository recipients;
    private final MembershipGraphDeleter deleter;
    private final RetentionCutoff retentionCutoff;
    private final ConsentGate gate;
    private final AudiencePlanProperties props;
    private final AuditLogger audit;
    private final ObjectProvider<AudienceRetentionPurgeJob> self;
    private final Predicate<UUID> orgFilter;

    @Autowired
    public AudienceRetentionPurgeJob(MembershipRepository memberships, MarketingOptOutRepository optOuts,
                                     CampaignRecipientRepository recipients, MembershipGraphDeleter deleter,
                                     RetentionCutoff retentionCutoff, ConsentGate gate, AudiencePlanProperties props,
                                     AuditLogger audit, ObjectProvider<AudienceRetentionPurgeJob> self) {
        this(memberships, optOuts, recipients, deleter, retentionCutoff, gate, props, audit, self, id -> true);
    }

    /** {@code orgFilter} limits the run to some orgs (tests); the scheduled bean takes every org. */
    public AudienceRetentionPurgeJob(MembershipRepository memberships, MarketingOptOutRepository optOuts,
                                     CampaignRecipientRepository recipients, MembershipGraphDeleter deleter,
                                     RetentionCutoff retentionCutoff, ConsentGate gate, AudiencePlanProperties props,
                                     AuditLogger audit, ObjectProvider<AudienceRetentionPurgeJob> self,
                                     Predicate<UUID> orgFilter) {
        this.memberships = memberships;
        this.optOuts = optOuts;
        this.recipients = recipients;
        this.deleter = deleter;
        this.retentionCutoff = retentionCutoff;
        this.gate = gate;
        this.props = props;
        this.audit = audit;
        this.self = self;
        this.orgFilter = orgFilter;
    }

    @Scheduled(cron = "0 15 4 * * *", zone = "Europe/Paris")
    public void scheduled() {
        try {
            self.getObject().run();
        } catch (Exception e) {
            log.error("AudienceRetentionPurgeJob: run failed (next night retries): {} {}",
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    @SchedulerLock(name = "audience_retention_purge", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public Result run() {
        boolean enabled = Boolean.TRUE.equals(props.getRetentionPurgeEnabled());
        Set<UUID> orgIds = new LinkedHashSet<>(memberships.findDistinctOrgIds());
        orgIds.addAll(optOuts.findDistinctOrgIds());
        orgIds.addAll(recipients.findOrgIdsWithRecipientPii());
        List<String> personSources = gate.personSources();
        Tally t = new Tally();
        for (UUID orgId : orgIds) {
            if (!orgFilter.test(orgId)) continue;
            try {
                purgeOrg(orgId, enabled, personSources, t);
            } catch (Exception e) {
                t.failed++;
                t.lastFailure = e;
                log.warn("AudienceRetentionPurgeJob: org {} skipped: {} {}", orgId,
                        e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
            }
        }
        Result r = new Result(enabled, t.orgs, t.membershipsExpired, t.membershipsDeleted, t.failed, t.capped,
                t.optOutsExpired, t.optOutsDeleted, t.recipientsExpired, t.recipientsRedacted);
        logSummary(r, t.lastFailure);
        return r;
    }

    private void purgeOrg(UUID orgId, boolean enabled, List<String> personSources, Tally t) {
        RetentionCutoff.Cutoff cutoff = retentionCutoff.forOrg(orgId);
        int membersDue = (int) memberships.countRetentionPurgeCandidates(orgId, personSources, cutoff.at(),
                cutoff.date());
        int optOutsDue = (int) optOuts.countByOrgIdAndCreatedAtBefore(orgId, cutoff.at());
        int recipientsDue = (int) recipients.countPiiForCampaignsEndedBefore(orgId, cutoff.at());
        if (membersDue == 0 && optOutsDue == 0 && recipientsDue == 0) return;
        t.orgs++;
        t.membershipsExpired += membersDue;
        t.optOutsExpired += optOutsDue;
        t.recipientsExpired += recipientsDue;
        if (!enabled) return;

        int deleted = membersDue > 0 ? deleteMemberships(orgId, personSources, cutoff, t) : 0;
        int optOutsDeleted = optOutsDue > 0 ? optOuts.deleteByOrgIdAndCreatedAtBefore(orgId, cutoff.at()) : 0;
        t.optOutsDeleted += optOutsDeleted;
        int redacted = recipientsDue > 0 ? recipients.redactPiiForCampaignsEndedBefore(orgId, cutoff.at()) : 0;
        t.recipientsRedacted += redacted;
        if (deleted + optOutsDeleted + redacted > 0) {
            audit.record(systemFor(orgId), AuditActions.AUDIENCE_RETENTION_PURGED, "org", orgId,
                    "Retention purge: " + deleted + " memberships deleted, " + optOutsDeleted
                            + " opt-outs deleted, " + redacted + " recipient rows redacted");
        }
    }

    /** Pages oldest first by keyset, so a member that failed or is no longer due is never fetched twice. */
    private int deleteMemberships(UUID orgId, List<String> personSources, RetentionCutoff.Cutoff cutoff, Tally t) {
        int deleted = 0;
        Instant afterAt = CURSOR_START;
        UUID afterId = CURSOR_START_ID;
        while (true) {
            int room = MAX_MEMBERSHIPS_PER_RUN - t.attempted;
            if (room <= 0) {
                if (!memberships.findRetentionPurgeCandidates(orgId, personSources, cutoff.at(), cutoff.date(),
                        afterAt, afterId, 1).isEmpty()) {
                    t.capped = true;
                }
                return deleted;
            }
            List<Object[]> page = memberships.findRetentionPurgeCandidates(orgId, personSources, cutoff.at(),
                    cutoff.date(), afterAt, afterId, Math.min(PAGE_SIZE, room));
            if (page.isEmpty()) return deleted;
            for (Object[] row : page) {
                UUID id = uuid(row[0]);
                afterAt = instant(row[1]);
                afterId = id;
                t.attempted++;
                try {
                    boolean gone = deleter.delete(orgId, id, () -> !memberships.findRetentionPurgeCandidatesAmong(
                            orgId, personSources, cutoff.at(), cutoff.date(), List.of(id)).isEmpty());
                    if (gone) {
                        deleted++;
                        t.membershipsDeleted++;
                    }
                } catch (Exception e) {
                    t.failed++;
                    t.lastFailure = e;
                    log.warn("AudienceRetentionPurgeJob: skipped membership {}: {} {}", id,
                            e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
                }
            }
        }
    }

    private void logSummary(Result r, Exception lastFailure) {
        String line = "AudienceRetentionPurgeJob: {}, orgs={} memberships expired={} deleted={} failed={} capped={}"
                + " optOuts expired={} deleted={} recipientRows expired={} redacted={}";
        Object[] args = {r.enabled() ? "done" : "dry run", r.orgs(), r.membershipsExpired(), r.membershipsDeleted(),
                r.failed(), r.capped(), r.optOutsExpired(), r.optOutsDeleted(), r.recipientRowsExpired(),
                r.recipientRowsRedacted()};
        int written = r.membershipsDeleted() + r.optOutsDeleted() + r.recipientRowsRedacted();
        if (r.failed() > 0 && written == 0) {
            Object[] withThrowable = java.util.Arrays.copyOf(args, args.length + 1);
            withThrowable[args.length] = lastFailure;
            log.error(line, withThrowable);
        } else if (r.failed() > 0 || r.capped()) {
            log.warn(line, args);
        } else {
            log.info(line, args);
        }
    }

    private static AuthPrincipal systemFor(UUID orgId) {
        return new AuthPrincipal(null, orgId, UserRole.MEMBER, null);
    }

    private static UUID uuid(Object o) {
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    private static Instant instant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        if (o instanceof Timestamp ts) return ts.toInstant();
        throw new IllegalStateException("Unexpected created_at type " + o.getClass().getName());
    }

    private static final class Tally {
        int orgs;
        int membershipsExpired;
        int membershipsDeleted;
        int failed;
        int attempted;
        boolean capped;
        int optOutsExpired;
        int optOutsDeleted;
        int recipientsExpired;
        int recipientsRedacted;
        Exception lastFailure;
    }
}
