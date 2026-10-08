package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.engine.Exclusions;
import com.imin.iminapi.audienceplan.engine.ResponseModel;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.marketing.service.MarketingGuardProperties;
import com.imin.iminapi.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reads one org's plan-mailable members and their fatigue counts for an event, then runs {@link CandidateBuilder}. */
@Service
public class CandidateLoader {

    private static final Logger log = LoggerFactory.getLogger(CandidateLoader.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Double>> TASTE = new TypeReference<>() {};

    private final ConsentGate gate;
    private final FanFeatureRepository repo;
    private final CandidateBuilder builder;
    private final MarketingGuardProperties guard;
    private final Clock clock;

    public CandidateLoader(ConsentGate gate, FanFeatureRepository repo, AudiencePlanLogic logic, ResponseModel model,
                           MarketingGuardProperties guard, Clock clock) {
        this.gate = gate;
        this.repo = repo;
        this.builder = new CandidateBuilder(logic, model);
        this.guard = guard;
        this.clock = clock;
    }

    /** The caller has already scoped {@code event} to {@code orgId}; a mismatch is refused, never silently mixed. */
    @Transactional(readOnly = true)
    public CandidateBuilder.Result build(UUID orgId, Event event, int targetTickets, double ticketsPerOrder) {
        return builder.build(input(orgId, event, targetTickets, ticketsPerOrder));
    }

    /** The builder input without running it: plan-mailable people plus the gate's reason counts. */
    @Transactional(readOnly = true)
    public CandidateBuilder.Input input(UUID orgId, Event event, int targetTickets, double ticketsPerOrder) {
        Objects.requireNonNull(orgId, "orgId");
        if (event == null || !orgId.equals(event.getOrgId())) {
            throw new IllegalArgumentException("event does not belong to the org");
        }
        UUID eventId = event.getId();
        Instant now = clock.instant();
        Instant floorSince = now.minus(Duration.ofHours(guard.getFrequencyFloorHours()));
        Instant monthSince = now.minus(Duration.ofDays(Exclusions.MONTHLY_CAP_DAYS));
        Instant scanSince = floorSince.isBefore(monthSince) ? floorSince : monthSince;

        ConsentGate.Breakdown breakdown = gate.breakdown(orgId);
        List<UUID> mailable = gate.mailableMembershipIds(orgId);

        Map<UUID, Object[]> features = new HashMap<>();
        for (Object[] row : repo.findCandidateFeatures(orgId)) features.put(ConsentGate.uuid(row[0]), row);
        Set<UUID> bought = new HashSet<>();
        for (Object id : repo.findMembershipIdsHoldingEventTicket(orgId, eventId)) bought.add(ConsentGate.uuid(id));
        Map<UUID, int[]> sends = new HashMap<>();
        for (Object[] row : repo.countCandidateSends(orgId, eventId, floorSince, monthSince, scanSince)) {
            sends.put(ConsentGate.uuid(row[0]), new int[]{count(row[1]), count(row[2]), count(row[3])});
        }

        List<Person> people = new ArrayList<>(mailable.size());
        for (UUID id : mailable) {
            Object[] f = features.get(id);
            int[] s = sends.getOrDefault(id, new int[3]);
            people.add(new Person(id,
                    f == null ? null : (String) f[1],
                    f == null ? Map.of() : taste(id, f[2]),
                    f == null ? 0 : count(f[3]),
                    bought.contains(id), s[0] > 0, s[1], s[2]));
        }
        return new CandidateBuilder.Input(orgId, event.getGenreKey(), targetTickets, ticketsPerOrder,
                breakdown.exclusions(), people);
    }

    /** Distinct plan-mailable members of the org: the mailable count a plan's inputs hash is built from. */
    @Transactional(readOnly = true)
    public int mailableCount(UUID orgId) {
        return new HashSet<>(gate.mailableMembershipIds(orgId)).size();
    }

    private static int count(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    /** Unreadable taste counts as none (fit {@code other}); the projector rewrites it on the next recompute. */
    private static Map<String, Double> taste(UUID membershipId, Object value) {
        String json = text(value);
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Double> parsed = JSON.readValue(json, TASTE);
            return parsed == null ? Map.of() : parsed;
        } catch (IOException e) {
            log.warn("[candidates] unreadable taste for membership {}: {}", membershipId, e.getMessage());
            return Map.of();
        }
    }

    /** TEXT arrives as String on Postgres. */
    private static String text(Object value) {
        if (value == null) return null;
        return value.toString();
    }
}
