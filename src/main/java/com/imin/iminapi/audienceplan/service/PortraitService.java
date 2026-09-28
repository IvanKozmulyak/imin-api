package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.NewPeopleGroup;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitCatchment;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitResearchGroup;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitResearchStatus;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitSource;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitTown;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.SizeRange;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.StoredGroup;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.WebSource;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.ai.provenance.AiEmailDisclosure;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.util.EventNormalization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The portrait of a (genre, city): genre-first people, regulars and students in the French towns of the city's
 * catchment from stored open data and the priors, plus any stored LLM research groups, sized from the same open
 * data. Reads only; research is generated elsewhere ({@link PortraitResearchService}). No personal data.
 */
@Service
public class PortraitService {

    public static final String ORIGIN_OPEN_DATA = "open_data";
    public static final String ORIGIN_RESEARCH = "research";
    static final String RESEARCH_KEY_PREFIX = "research_";
    static final String NO_SIZE_METHOD = "none";
    static final String WEB_INPUT = "web";
    static final String STATUS_NONE = "none";
    /** The French towns of the catchment: INSEE and MESR cover France only. */
    public static final String SCOPE_FR_CATCHMENT = "fr_catchment";
    public static final String GROUP_GENRE_FIRST = "genre_first";
    public static final String GROUP_REGULARS = "regulars";
    public static final String GROUP_STUDENTS = "students";
    /** A genre audience: a range of people the event could reach. */
    public static final String KIND_AUDIENCE = "audience";
    /** A whole-population headcount shown as context, never as reachable people. */
    public static final String KIND_CONTEXT = "context";
    static final String STUDENTS_METHOD = "mesr_students";
    static final String FRANCE = "FR";
    static final int MAX_CITY_LENGTH = 100;

    private final AudiencePlanAccess access;
    private final AudiencePlanLogic logic;
    private final PublicDataService publicData;
    private final CatchmentService catchments;
    private final TribeSizeCalculator tribes;
    private final PortraitResearchStore research;
    private final Clock clock;

    public PortraitService(AudiencePlanAccess access, AudiencePlanLogic logic, PublicDataService publicData,
                           CatchmentService catchments, TribeSizeCalculator tribes, PortraitResearchStore research,
                           Clock clock) {
        this.access = access;
        this.logic = logic;
        this.publicData = publicData;
        this.catchments = catchments;
        this.tribes = tribes;
        this.research = research;
        this.clock = clock;
    }

    /** A validated (genre bucket, city) key pair. */
    public record PortraitKey(String genreKey, String cityKey) {}

    /** The endpoint: kill switch first, then 400 for a genre outside the 8 buckets or a malformed city. */
    public AudiencePortraitResponse portrait(UUID orgId, String genre, String city) {
        PortraitKey key = validate(orgId, genre, city);
        return forCity(key.genreKey(), key.cityKey());
    }

    /** Kill switch, then the same 400s as {@link #portrait}; returns the normalised keys. */
    public PortraitKey validate(UUID orgId, String genre, String city) {
        access.requireEnabled(orgId);
        String genreKey = EventNormalization.genreKey(genre);
        if (!logic.genres().whitelist().contains(genreKey)) {
            throw invalid("genre", "must be one of the genre buckets: " + String.join(", ", logic.genres().whitelist()));
        }
        String cityKey = EventNormalization.cityKey(city);
        if (cityKey.isEmpty()) throw invalid("city", "is required");
        if (cityKey.length() > MAX_CITY_LENGTH) throw invalid("city", "must be at most " + MAX_CITY_LENGTH + " characters");
        if (cityKey.codePoints().anyMatch(Character::isISOControl)) throw invalid("city", "must not contain control characters");
        return new PortraitKey(genreKey, cityKey);
    }

    /**
     * Ungated, for callers that already passed the kill switch: the open-data groups, then the stored research
     * groups. {@code genreKey} must be a bucket key; an unknown city, a city without a stored centre or a catchment
     * without French towns gives null sizes.
     */
    public AudiencePortraitResponse forCity(String genreKey, String cityKey) {
        AudiencePortraitResponse open = openData(genreKey, cityKey);
        Optional<PortraitResearchStore.Row> row = research.find(genreKey, cityKey);
        if (row.isEmpty()) {
            return withResearch(open, open.groups(), new PortraitResearchStatus(STATUS_NONE, null, null, false, null));
        }
        PortraitResearchStore.Row r = row.get();
        boolean ready = PortraitResearchStore.READY.equals(r.status());
        boolean stale = ready && r.expiresAt() != null && !r.expiresAt().isAfter(clock.instant());
        List<NewPeopleGroup> groups = new ArrayList<>(open.groups());
        if (ready) {
            List<String> frTowns = frTowns(open);
            int i = 0;
            for (StoredGroup g : r.groups()) {
                groups.add(researchGroup(genreKey, frTowns, ++i, g, r.generatedAt(), stale));
            }
        }
        return withResearch(open, List.copyOf(groups), new PortraitResearchStatus(r.status(),
                r.generatedAt(), ready ? r.expiresAt() : null, stale, ready ? r.reviewedBy() : null));
    }

    private static AudiencePortraitResponse withResearch(AudiencePortraitResponse open, List<NewPeopleGroup> groups,
                                                         PortraitResearchStatus status) {
        return new AudiencePortraitResponse(open.genre(), open.cityKey(), open.catchment(), groups, status,
                open.versions());
    }

    /** The in-scope (French) town keys of a portrait's catchment. */
    static List<String> frTowns(AudiencePortraitResponse p) {
        if (p.catchment() == null) return List.of();
        return p.catchment().towns().stream().filter(PortraitTown::inScope).map(PortraitTown::cityKey).toList();
    }

    /** Only the open-data groups; {@code research} is null. The research prompt is built from this. */
    public AudiencePortraitResponse openData(String genreKey, String cityKey) {
        if (!logic.genres().whitelist().contains(genreKey)) {
            throw new IllegalArgumentException("Not a genre bucket: " + genreKey);
        }
        Optional<Catchment> catchment = centre(cityKey).flatMap(c -> catchments.around(c[0], c[1]));
        List<String> frTowns = new ArrayList<>();
        PortraitCatchment shown = null;
        if (catchment.isPresent()) {
            List<PortraitTown> towns = new ArrayList<>();
            for (Catchment.Town t : catchment.get().towns()) {
                boolean fr = FRANCE.equals(t.country());
                if (fr) frTowns.add(t.cityKey());
                towns.add(new PortraitTown(t.cityKey(), t.name(), t.country(), t.kmStraight(), fr));
            }
            shown = new PortraitCatchment(catchment.get().radiusKm(), SCOPE_FR_CATCHMENT, List.copyOf(towns));
        }

        String method = tribes.shareKey(genreKey).orElse(TribeSizeCalculator.NO_SHARE_METHOD);
        List<NewPeopleGroup> groups = new ArrayList<>();
        if (frTowns.isEmpty()) {
            // Non-FR towns have no census: never pass them to the calculator, the sizes are simply unknown.
            groups.add(group(GROUP_GENRE_FIRST, KIND_AUDIENCE, frTowns, null, method, List.of()));
            groups.add(group(GROUP_REGULARS, KIND_AUDIENCE, frTowns, null, method, List.of()));
        } else {
            TribeSize tribe = tribes.estimate(genreKey, frTowns);
            groups.add(group(GROUP_GENRE_FIRST, KIND_AUDIENCE, frTowns, size(tribe.genreFirst()), method,
                    sources(tribe.genreFirst())));
            groups.add(group(GROUP_REGULARS, KIND_AUDIENCE, frTowns, size(tribe.regulars()), method,
                    sources(tribe.regulars())));
        }
        groups.add(students(frTowns));
        return new AudiencePortraitResponse(genreKey, cityKey, shown, List.copyOf(groups), null,
                new AudiencePortraitResponse.Versions(logic.priorsVersion()));
    }

    /**
     * A stored research group, sized now from open data by its basis over its towns (all French catchment towns
     * when it names none): the population it is drawn from, so kind context. The model never supplies a number.
     */
    private NewPeopleGroup researchGroup(String genreKey, List<String> frTowns, int index, StoredGroup g,
                                         Instant generatedAt, boolean stale) {
        List<String> named = g.towns() == null ? List.of() : g.towns().stream().filter(frTowns::contains).toList();
        List<String> towns = named.isEmpty() ? frTowns : named;
        SizeRange size = null;
        String method = NO_SIZE_METHOD;
        List<PortraitSource> sources = new ArrayList<>();
        for (WebSource w : g.sources() == null ? List.<WebSource>of() : g.sources()) {
            sources.add(new PortraitSource(WEB_INPUT, null, null, w.title(), null, null, w.url(), null, generatedAt,
                    stale));
        }
        String basis = g.basis() == null ? NO_SIZE_METHOD : g.basis();
        if (!towns.isEmpty()) {
            switch (basis) {
                case GROUP_GENRE_FIRST, GROUP_REGULARS -> {
                    TribeSize tribe = tribes.estimate(genreKey, towns);
                    TribeSize.Estimate e = GROUP_GENRE_FIRST.equals(basis) ? tribe.genreFirst() : tribe.regulars();
                    size = size(e);
                    method = tribes.shareKey(genreKey).orElse(TribeSizeCalculator.NO_SHARE_METHOD);
                    sources.addAll(sources(e));
                }
                case GROUP_STUDENTS -> {
                    NewPeopleGroup s = students(towns);
                    size = s.size();
                    method = STUDENTS_METHOD;
                    sources.addAll(s.sources());
                }
                default -> { }
            }
        }
        return new NewPeopleGroup(RESEARCH_KEY_PREFIX + index, ORIGIN_RESEARCH, KIND_CONTEXT, SCOPE_FR_CATCHMENT,
                List.copyOf(towns), size, method, List.copyOf(sources),
                new PortraitResearchGroup(g.label(), g.description(), basis, g.confidence(),
                        AiEmailDisclosure.DISCLOSURE_VALUE, generatedAt));
    }

    /** The regulars group's size, which the plan compares its gap against; null while unknown or absent. */
    public static SizeRange regulars(List<NewPeopleGroup> groups) {
        return groups.stream().filter(g -> GROUP_REGULARS.equals(g.key())).findFirst()
                .map(NewPeopleGroup::size).orElse(null);
    }

    // ── groups ─────────────────────────────────────────────────────────────

    /** All enrolled students of the French towns, a context headcount; unknown when any town has no figure. */
    private NewPeopleGroup students(List<String> frTowns) {
        List<PortraitSource> sources = new ArrayList<>();
        long total = 0;
        boolean known = !frTowns.isEmpty();
        for (String town : frTowns) {
            Optional<OpenDataValue> row = publicData.get(town, OpenDataset.STUDENTS);
            Long headline = row.map(OpenDataValue::headline).orElse(null);
            row.ifPresent(r -> sources.add(source(OpenDataset.STUDENTS.headlineField(), r)));
            if (headline == null) known = false;
            else total += headline;
        }
        SizeRange size = known ? new SizeRange(Math.toIntExact(total), Math.toIntExact(total)) : null;
        return group(GROUP_STUDENTS, KIND_CONTEXT, frTowns, size, STUDENTS_METHOD, sources);
    }

    private NewPeopleGroup group(String key, String kind, List<String> cityKeys, SizeRange size, String method,
                                 List<PortraitSource> sources) {
        return new NewPeopleGroup(key, ORIGIN_OPEN_DATA, kind, SCOPE_FR_CATCHMENT, List.copyOf(cityKeys), size, method,
                List.copyOf(sources), null);
    }

    private static SizeRange size(TribeSize.Estimate e) {
        if (e.low() == null || e.high() == null) return null;
        return new SizeRange(Math.toIntExact(e.low()), Math.toIntExact(e.high()));
    }

    /** Census inputs are re-read for their licence and update date; research rates come from the calculator. */
    private List<PortraitSource> sources(TribeSize.Estimate e) {
        List<PortraitSource> out = new ArrayList<>();
        for (TribeSize.Source s : e.sources()) {
            if (s.cityKey() != null) {
                publicData.get(s.cityKey(), OpenDataset.INSEE_AGE).ifPresent(r -> out.add(source(s.input(), r)));
            } else {
                out.add(new PortraitSource(s.input(), null, null, s.label(), s.period(), null, null, s.note(), null,
                        false));
            }
        }
        return out;
    }

    private static PortraitSource source(String input, OpenDataValue r) {
        return new PortraitSource(input, r.cityKey(), r.dataset().key(), r.attribution(), r.refPeriod(), r.licence(),
                r.sourceUrl(), null, r.fetchedAt(), r.stale());
    }

    /** The city's stored town centre in degrees, or empty. */
    private Optional<double[]> centre(String cityKey) {
        return publicData.get(cityKey, OpenDataset.CENTROID).flatMap(v -> {
            Map<String, Long> f = v.figures();
            Long lat = f.get("lat_e6");
            Long lon = f.get("lon_e6");
            return lat == null || lon == null ? Optional.empty() : Optional.of(new double[] {lat / 1e6, lon / 1e6});
        });
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                Map.of(field, message));
    }
}
