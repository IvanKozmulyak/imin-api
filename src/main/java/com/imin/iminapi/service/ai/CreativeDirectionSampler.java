package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.VariantSlot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Draws the three creative directions of a poster run from a vibe's {@link StyleCard} pools, in code
 * rather than by the LLM, so a generation seed reproduces the same run and different generations vary.
 *
 * <p>Every draw of a single run uses one {@link Random} seeded with the run's seed, so the output is
 * 100% deterministic for a given {@code (card, seed)} pair and varies across seeds.
 *
 * <p>The run always carries exactly one of each {@link HeroType} in {@link HeroType#ORDER}
 * (PEOPLE, OBJECT, TYPOGRAPHIC). {@code composition} and {@code accent} are drawn <em>without
 * replacement</em> across the three directions, so no two variants share either. {@code paletteTwist}
 * and {@code typeTreatment} are also drawn without replacement when their pool has at least three
 * entries; smaller pools fall back to independent draws (repeats allowed). {@code heroSubject} comes
 * from the hero-type's pool — PEOPLE and OBJECT draw from disjoint pools, and TYPOGRAPHIC has no
 * pictorial subject ({@code null}).
 */
@Component
public class CreativeDirectionSampler {

    /**
     * The three creative directions of one run plus the single few-shot anchor prompt rotated for
     * this run.
     *
     * @param directions   exactly three {@link CreativeDirection}, hero types in {@link HeroType#ORDER}
     * @param examplePrompt one prompt rotated from {@code card.examplePrompts()}, or {@code null} if none
     */
    public record SampledRun(List<CreativeDirection> directions, String examplePrompt) {}

    /**
     * Sample a run of three creative directions for {@code card}, deterministic in {@code seed}.
     *
     * <p>Null- and empty-safe: a null card yields three directions with the right hero types and all
     * null fields and a null example prompt; any individually empty pool yields {@code null}/empty for
     * that field without throwing.
     */
    public SampledRun sample(StyleCard card, long seed) {
        Random random = new Random(seed);
        List<HeroType> plan = planModes(card);
        int count = plan.size();

        if (card == null) {
            List<CreativeDirection> directions = new ArrayList<>(count);
            for (HeroType mode : plan) {
                directions.add(new CreativeDirection(mode, null, null, null, null, null));
            }
            return new SampledRun(List.copyOf(directions), null);
        }

        List<String> compositions = drawWithoutReplacement(card.compositions(), count, random);
        List<String> accents = drawWithoutReplacement(card.accents(), count, random);
        List<String> paletteTwists = drawWithoutReplacement(card.paletteTwists(), count, random);
        List<String> typeTreatments = drawWithoutReplacement(card.typeTreatments(), count, random);

        // Per-mode subject queues, drawn WITHOUT replacement so a repeated mode (e.g. people,people,
        // people) yields distinct subjects. Deterministic in seed: distinct modes are visited in
        // first-appearance order.
        // A repeated TYPOGRAPHIC slot draws from an empty pool, so drawWithoutReplacement returns
        // nulls — LinkedList (not ArrayDeque) is used because the queue must permit null elements.
        Set<HeroType> distinctModes = new LinkedHashSet<>(plan);
        Map<HeroType, Deque<String>> subjectQueues = new EnumMap<>(HeroType.class);
        for (HeroType mode : distinctModes) {
            int n = (int) plan.stream().filter(mode::equals).count();
            subjectQueues.put(mode, new LinkedList<>(drawWithoutReplacement(card.heroSubjectsFor(mode), n, random)));
        }

        List<CreativeDirection> directions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            HeroType mode = plan.get(i);
            String heroSubject = subjectQueues.get(mode).poll();
            directions.add(new CreativeDirection(
                    mode,
                    heroSubject,
                    compositions.get(i),
                    accents.get(i),
                    paletteTwists.get(i),
                    typeTreatments.get(i)
            ));
        }

        String examplePrompt = pickOne(card.examplePrompts(), random);
        return new SampledRun(List.copyOf(directions), examplePrompt);
    }

    /** The 3 hero modes this run renders: the card's variant_plan, or the legacy order if absent. */
    private static List<HeroType> planModes(StyleCard card) {
        if (card == null || card.variantPlan() == null || card.variantPlan().isEmpty()) {
            return List.of(HeroType.ORDER);
        }
        return card.variantPlan().stream().map(VariantSlot::mode).toList();
    }

    /**
     * Returns a list of length {@code count} of picks from {@code pool}, using {@code random}.
     *
     * <ul>
     *   <li>{@code pool} null/empty → all {@code null} (no draws consumed; size stays {@code count}).</li>
     *   <li>{@code pool.size() >= count} → without replacement: shuffle a copy and take the first
     *       {@code count}, so no two positions collide.</li>
     *   <li>{@code 0 < pool.size() < count} → independent draws (repeats allowed).</li>
     * </ul>
     */
    private List<String> drawWithoutReplacement(List<String> pool, int count, Random random) {
        List<String> result = new ArrayList<>(count);
        if (pool == null || pool.isEmpty()) {
            for (int i = 0; i < count; i++) {
                result.add(null);
            }
            return result;
        }
        if (pool.size() >= count) {
            List<String> copy = new ArrayList<>(pool);
            java.util.Collections.shuffle(copy, random);
            for (int i = 0; i < count; i++) {
                result.add(copy.get(i));
            }
            return result;
        }
        // Pool too small to cover all variants without repeats — fall back to independent draws.
        for (int i = 0; i < count; i++) {
            result.add(pool.get(random.nextInt(pool.size())));
        }
        return result;
    }

    /** One pick from {@code pool} using {@code random}; {@code null} when the pool is null/empty. */
    private String pickOne(List<String> pool, Random random) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }
}
