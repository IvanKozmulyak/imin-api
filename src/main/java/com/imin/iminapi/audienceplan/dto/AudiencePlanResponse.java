package com.imin.iminapi.audienceplan.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One event's audience plan. Every count is a range; a null number is unknown (never 0). {@code expected} and the
 * coverage ratios are null in cold mode; coverage ratios are truncated to 2 decimals.
 */
public record AudiencePlanResponse(
        UUID id,
        UUID eventId,
        /* cold | warm | hot */
        String mode,
        int capacity,
        int targetTickets,
        int mailable,
        TicketRange expected,
        Coverage coverage,
        Gap gap,
        ReachNeeded reachNeeded,
        Boolean gapExceedsTribe,
        List<Segment> segments,
        int smallGroupsNotShown,
        boolean otherGenreInvited,
        int otherGenreHeldBack,
        Map<String, Integer> exclusions,
        Timing timing,
        List<AudiencePortraitResponse.NewPeopleGroup> newPeople,
        List<Action> actions,
        Assumptions assumptions,
        Summary summary,
        Versions versions,
        Instant createdAt) {

    public record TicketRange(int low, int mid, int high) {}

    /** {@code verdict}: strong | medium | weak | cold. */
    public record Coverage(Double low, Double mid, Double high, String verdict) {}

    public record Gap(int low, int high) {}

    /** {@code status}: estimated | unverified | unknown; bounds are null unless estimated. */
    public record Reach(String status, Integer low, Integer high) {}

    public record ReachNeeded(Reach metaAds, Reach instagramOrganic) {}

    public record Rate(double low, double mid, double high) {}

    /** {@code genreFit}: same | adjacent | other | unknown; {@code confidence}: own | imin | prior. */
    public record Segment(String classKey, String genreFit, int mailable, Rate rate, double ticketsPerOrder,
                          TicketRange expected, String confidence, SegmentReason reason) {}

    /** The class rule bounds from the logic file (null = no bound) and the event genre behind the fit. */
    public record SegmentReason(Integer paidOrdersMin, Integer paidOrdersMax, Integer daysSinceLastPaidMin,
                                Integer daysSinceLastPaidMax, Integer daysSinceLastContactMax,
                                boolean requiresImportBasis, String eventGenre, String genreFit) {}

    /** Dates in the event timezone; {@code d3Date} is null when the D-3 arm is not after the launch date. */
    public record Timing(LocalDate today, LocalDate eventDate, LocalDate launchDate, LocalDate d3Date,
                         int daysToEvent, boolean eventStarted) {}

    /** {@code arm}: launch | d3. */
    public record ArmDate(String arm, LocalDate date) {}

    /**
     * {@code type}: invite | import_with_proof | rethink_target; only invite names a segment, arms and holdout.
     * {@code options} are copy keys, set on rethink_target only (empty otherwise, also on plans stored before them).
     */
    public record Action(String type, String classKey, String genreFit, List<ArmDate> arms, Integer holdoutPct,
                         List<String> options) {
        public Action {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public record Assumptions(int targetPct, double ticketsPerOrder, List<String> excludeSegments) {}

    /**
     * Null until the requested locale's summary is generated (lazily, after a GET). The five text fields are
     * non-null when present. {@code aiGenerated} false = the code template; {@code aiDisclosure} is the ADR-0005
     * marker {@code mode=ai-originated} on model text and null on the template, as is {@code model}.
     */
    public record Summary(String headline, List<String> segmentLines, String gapLine, List<String> actions,
                          List<String> assumptions, String locale, boolean aiGenerated, String aiDisclosure,
                          String model, Instant generatedAt) {}

    /** {@code model} is null until an LLM summary exists. */
    public record Versions(int logic, int priors, String model) {}
}
