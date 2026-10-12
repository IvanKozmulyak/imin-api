package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The one 3-year cutoff: today in the org's zone minus {@code legal.retention_days}, and the start of that day in
 * the same zone. A blank, invalid or missing org zone falls back to UTC. ConsentGate and the retention purge use it.
 */
@Component
public class RetentionCutoff {

    /** Contact on {@code date} (or at or after {@code at}) is within the window; anything earlier is not. */
    public record Cutoff(LocalDate date, Instant at, ZoneId zone) {}

    private final OrganizationRepository orgRepo;
    private final AudiencePlanLogic logic;
    private final Clock clock;

    public RetentionCutoff(OrganizationRepository orgRepo, AudiencePlanLogic logic, Clock clock) {
        this.orgRepo = orgRepo;
        this.logic = logic;
        this.clock = clock;
    }

    public Cutoff forOrg(UUID orgId) {
        ZoneId zone = orgZone(orgId);
        LocalDate date = LocalDate.now(clock.withZone(zone)).minusDays(logic.logic().legal().retentionDays());
        return new Cutoff(date, date.atStartOfDay(zone).toInstant(), zone);
    }

    private ZoneId orgZone(UUID orgId) {
        String tz = orgId == null ? null : orgRepo.findById(orgId).map(Organization::getTimezone).orElse(null);
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
