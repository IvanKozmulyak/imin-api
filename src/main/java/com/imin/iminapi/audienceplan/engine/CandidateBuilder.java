package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Exclusion;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Genres;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Confidence;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Rate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Splits an org's plan-mailable members into class × genre-fit segments for one event. Each person lands in
 * exactly one segment (the highest mid rate); everyone else is counted under one exclusion reason.
 */
public final class CandidateBuilder {

    private static final double TIE = 1e-9;

    /** One plan-mailable member; {@code classKey} is null when the member has no feature row yet. */
    public record Person(UUID membershipId, String classKey, Map<String, Double> taste, int noShowN,
                         boolean boughtThisEvent, boolean contactedWithinFloor, int sendsThisEvent, int sends30d) {}

    /**
     * {@code mailable} are the ConsentGate-mailable members; {@code consentGateExclusions} are its reason counts
     * for everyone else. {@code ticketsPerOrder} is the mid assumption, applied to all three bounds.
     */
    public record Input(UUID orgId, String eventGenreKey, int targetTickets, double ticketsPerOrder,
                        Map<String, Integer> consentGateExclusions, List<Person> mailable) {}

    /** {@code rate} is the class × fit band without no-shows; {@code expectedTickets} is the unrounded sum. */
    public record Segment(String classKey, Fit fit, Band rate, Confidence confidence, Band expectedTickets,
                          List<UUID> membershipIds) {
        public int mailable() { return membershipIds.size(); }
    }

    /**
     * {@code smallGroupsNotShown} counts hidden segments (their people are under {@code small_group});
     * {@code otherGenreHeldBack} counts people whose only fit is {@code other} when it was not needed.
     * {@code unknown}-fit segments bypass the {@code other} gate and do not count toward its coverage.
     */
    public record Result(List<Segment> segments, int smallGroupsNotShown, boolean otherGenreInvited,
                         int otherGenreHeldBack, Map<String, Integer> exclusions) {}

    private final AudiencePlanLogic logic;
    private final ResponseModel model;

    public CandidateBuilder(AudiencePlanLogic logic, ResponseModel model) {
        this.logic = Objects.requireNonNull(logic);
        this.model = Objects.requireNonNull(model);
    }

    public Result build(Input in) {
        if (in.targetTickets() <= 0) throw new IllegalArgumentException("target must be > 0: " + in.targetTickets());
        if (!(in.ticketsPerOrder() > 0)) {
            throw new IllegalArgumentException("tickets per order must be > 0: " + in.ticketsPerOrder());
        }

        Map<String, Integer> counts = new HashMap<>();
        Map<Key, Group> groups = new LinkedHashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (Person p : in.mailable()) {
            if (!seen.add(p.membershipId())) continue;
            String reason = exclusion(p);
            if (reason == null && (p.classKey() == null || !logic.priors().classes().containsKey(p.classKey()))) {
                reason = Exclusions.NO_CLASS;
            }
            if (reason != null) {
                counts.merge(reason, 1, Integer::sum);
                continue;
            }
            Fit bestFit = null;
            Rate best = null;
            // Fits iterate SAME, ADJACENT, OTHER, so an equal mid keeps the closer fit; UNKNOWN is never mixed in.
            for (Fit fit : fits(p.taste(), in.eventGenreKey(), logic.genres())) {
                Rate r = model.rate(in.orgId(), p.classKey(), fit, p.noShowN());
                if (best == null || r.band().mid() > best.band().mid()) {
                    best = r;
                    bestFit = fit;
                }
            }
            groups.computeIfAbsent(new Key(p.classKey(), bestFit), k -> new Group()).add(p.membershipId(), best.band());
        }

        int minSize = logic.logic().minSegmentToShow();
        List<Segment> shown = new ArrayList<>();
        int hiddenGroups = 0;
        int hiddenPeople = 0;
        for (Map.Entry<Key, Group> e : groups.entrySet()) {
            if (e.getKey().fit() == Fit.OTHER) continue;
            if (e.getValue().ids.size() < minSize) {
                hiddenGroups++;
                hiddenPeople += e.getValue().ids.size();
            } else {
                shown.add(segment(in, e.getKey(), e.getValue()));
            }
        }

        // Unknown-fit segments are always shown but are not genre evidence, so only same + adjacent count here.
        double closeMid = shown.stream().filter(s -> s.fit() == Fit.SAME || s.fit() == Fit.ADJACENT)
                .mapToDouble(s -> s.expectedTickets().mid()).sum();
        double coverage = (double) Math.round(closeMid) / in.targetTickets();
        boolean inviteOther = coverage < logic.logic().inviteOtherGenreOnlyIfCoverageBelow();

        int heldBack = 0;
        for (Map.Entry<Key, Group> e : groups.entrySet()) {
            if (e.getKey().fit() != Fit.OTHER) continue;
            int size = e.getValue().ids.size();
            if (!inviteOther) {
                heldBack += size;
            } else if (size < minSize) {
                hiddenGroups++;
                hiddenPeople += size;
            } else {
                shown.add(segment(in, e.getKey(), e.getValue()));
            }
        }
        if (hiddenPeople > 0) counts.merge(Exclusions.SMALL_GROUP, hiddenPeople, Integer::sum);

        shown.sort(order());
        return new Result(List.copyOf(shown), hiddenGroups, inviteOther, heldBack,
                Exclusions.merge(in.consentGateExclusions(), counts));
    }

    /** First listed exclusion that applies; the consent gate already ran upstream and always applies. */
    private String exclusion(Person p) {
        for (Exclusion e : logic.logic().exclusions()) {
            String reason = switch (e) {
                case CONSENT_GATE_FALSE -> null;
                case BOUGHT_THIS_EVENT -> p.boughtThisEvent() ? Exclusions.BOUGHT_THIS_EVENT : null;
                case EMAILED_LAST_48H -> p.contactedWithinFloor() ? Exclusions.CONTACTED_48H : null;
                case SENDS_THIS_EVENT_GTE_2 -> p.sendsThisEvent() >= Exclusions.EVENT_CAP_SENDS ? Exclusions.EVENT_CAP : null;
                case SENDS_30D_GTE_4 -> p.sends30d() >= Exclusions.MONTHLY_CAP_SENDS ? Exclusions.MONTHLY_CAP : null;
            };
            if (reason != null) return reason;
        }
        return null;
    }

    /** One candidate fit per top taste bucket (ties included); no taste gives {@code unknown}, no event genre {@code other}. */
    static EnumSet<Fit> fits(Map<String, Double> taste, String eventGenreKey, Genres genres) {
        EnumSet<Fit> out = EnumSet.noneOf(Fit.class);
        double max = 0;
        if (taste != null) for (Double w : taste.values()) if (w != null && w > max) max = w;
        if (max <= 0) return EnumSet.of(Fit.UNKNOWN);
        for (Map.Entry<String, Double> t : taste.entrySet()) {
            if (t.getValue() != null && t.getValue() >= max - TIE) out.add(fit(t.getKey(), eventGenreKey, genres));
        }
        return out;
    }

    static Fit fit(String personBucket, String eventGenreKey, Genres genres) {
        if (eventGenreKey == null || !genres.whitelist().contains(eventGenreKey)) return Fit.OTHER;
        if (eventGenreKey.equals(personBucket)) return Fit.SAME;
        return genres.adjacency().getOrDefault(eventGenreKey, Set.of()).contains(personBucket) ? Fit.ADJACENT : Fit.OTHER;
    }

    private Segment segment(Input in, Key key, Group g) {
        Rate rate = model.rate(in.orgId(), key.classKey(), key.fit(), 0);
        double tpo = in.ticketsPerOrder();
        Band expected = new Band(g.low * tpo, g.mid * tpo, g.high * tpo);
        List<UUID> ids = g.ids.stream().sorted().toList();
        return new Segment(key.classKey(), key.fit(), rate.band(), rate.confidence(), expected, ids);
    }

    /** Highest mid rate first, then class order of the logic file, then closer fit. */
    private Comparator<Segment> order() {
        List<String> classOrder = logic.logic().classes().stream().map(AudiencePlanLogic.ClassRule::key).toList();
        return Comparator.<Segment>comparingDouble(s -> -s.rate().mid())
                .thenComparingInt(s -> classOrder.indexOf(s.classKey()))
                .thenComparing(Segment::fit);
    }

    private record Key(String classKey, Fit fit) {}

    private static final class Group {
        final List<UUID> ids = new ArrayList<>();
        double low;
        double mid;
        double high;

        void add(UUID id, Band band) {
            ids.add(id);
            low += band.low();
            mid += band.mid();
            high += band.high();
        }
    }
}
