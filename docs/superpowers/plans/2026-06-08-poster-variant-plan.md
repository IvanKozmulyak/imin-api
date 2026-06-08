# Per-Vibe Variant Plan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the global `[people, object, typographic]` hero binding with a per-vibe declared variant plan, so human-less vibes never get a forced person and hero composition reflects each vibe's real identity.

**Architecture:** Each vibe's style-card declares an ordered 3-slot `variant_plan` over a 5-mode hero vocabulary (`people | object | typographic | scene | abstract_graphic`) plus a `human_policy` and `human_style`. The sampler executes the declared plan; `AiEventDescriptionService.validate` checks against the plan and policy instead of fixed positions. No DB migration (the `variant_style` column is a free `VARCHAR(32)`). A backward-compatible secondary `StyleCard` constructor + null-card defaults keep every existing call site and unit test valid.

**Tech Stack:** Java 17, Spring Boot 4, JUnit 5 + AssertJ, SnakeYAML, Maven (`./mvnw`).

**Spec:** `docs/superpowers/specs/2026-06-08-poster-variant-plan-design.md`

**Before you start:** create a branch — `git checkout -b feat/poster-variant-plan`. Run the full suite once to confirm a green baseline: `./mvnw -q test`.

---

## Task 1: Expand the `HeroType` enum

**Files:**
- Modify: `src/main/java/com/imin/iminapi/dto/HeroType.java`

- [ ] **Step 1: Add the two new modes and update `fromWire`**

In `HeroType.java`, change the enum constants and `fromWire`:

```java
public enum HeroType {
    PEOPLE,
    OBJECT,
    TYPOGRAPHIC,
    SCENE,
    ABSTRACT_GRAPHIC;

    /** The lowercase token used in the LLM JSON contract. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse the LLM JSON token; null/unknown → null so callers can validate. */
    public static HeroType fromWire(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "people" -> PEOPLE;
            case "object" -> OBJECT;
            case "typographic" -> TYPOGRAPHIC;
            case "scene" -> SCENE;
            case "abstract_graphic" -> ABSTRACT_GRAPHIC;
            default -> null;
        };
    }

    /** Legacy default plan order, used only as a fallback when a vibe declares no variant_plan. */
    public static final HeroType[] ORDER = { PEOPLE, OBJECT, TYPOGRAPHIC };
}
```

Note `wire()` for `ABSTRACT_GRAPHIC` returns `"abstract_graphic"` (the enum name lowercased keeps the underscore — correct).

- [ ] **Step 2: Compile**

Run: `./mvnw -q -o compile`
Expected: BUILD SUCCESS (no callers break — only additions).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/HeroType.java
git commit -m "feat(poster): add scene and abstract_graphic hero modes"
```

---

## Task 2: Add `HumanPolicy`, `HumanStyle`, and `VariantSlot` DTOs

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/HumanPolicy.java`
- Create: `src/main/java/com/imin/iminapi/dto/HumanStyle.java`
- Create: `src/main/java/com/imin/iminapi/dto/VariantSlot.java`

- [ ] **Step 1: Create `HumanPolicy`**

```java
package com.imin.iminapi.dto;

import java.util.Locale;

/** Per-vibe rule for whether a human hero may appear across a poster run's variants. */
public enum HumanPolicy {
    /** At least one variant must show a human. */
    REQUIRED,
    /** A human may appear (people-mode slots) but is never forced or capped. */
    OPTIONAL,
    /** At most one variant may show a human hero. */
    RARE,
    /** No variant may show a human hero. */
    FORBIDDEN;

    /** Parse the YAML token; null/unknown → REQUIRED (legacy-safe default). */
    public static HumanPolicy fromWire(String raw) {
        if (raw == null) return REQUIRED;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "optional" -> OPTIONAL;
            case "rare" -> RARE;
            case "forbidden" -> FORBIDDEN;
            case "required" -> REQUIRED;
            default -> REQUIRED;
        };
    }
}
```

- [ ] **Step 2: Create `HumanStyle`**

```java
package com.imin.iminapi.dto;

import java.util.Locale;

/** How a human, when present, must be rendered for a vibe. */
public enum HumanStyle {
    /** Photorealistic person with correct anatomy. */
    PHOTOGRAPHIC,
    /** Motion-blur / halftone / silhouette — never a clean portrait. */
    ABSTRACTED,
    /** A sculpted/rendered material object (chrome, wireframe, posterized), not a photographed person. */
    FIGURE_AS_OBJECT;

    /** Parse the YAML token; null/unknown → null (caller treats null as photographic). */
    public static HumanStyle fromWire(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "abstracted" -> ABSTRACTED;
            case "figure_as_object" -> FIGURE_AS_OBJECT;
            case "photographic" -> PHOTOGRAPHIC;
            default -> null;
        };
    }
}
```

- [ ] **Step 3: Create `VariantSlot`**

```java
package com.imin.iminapi.dto;

/** One slot of a vibe's variant_plan: the hero mode that variant is built around. */
public record VariantSlot(HeroType mode) {}
```

- [ ] **Step 4: Compile**

Run: `./mvnw -q -o compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/HumanPolicy.java src/main/java/com/imin/iminapi/dto/HumanStyle.java src/main/java/com/imin/iminapi/dto/VariantSlot.java
git commit -m "feat(poster): add HumanPolicy, HumanStyle, VariantSlot dtos"
```

---

## Task 3: Extend `StyleCard` with plan/policy fields + backward-compatible constructor

**Files:**
- Modify: `src/main/java/com/imin/iminapi/dto/StyleCard.java`
- Test: `src/test/java/com/imin/iminapi/dto/StyleCardTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/imin/iminapi/dto/StyleCardTest.java`:

```java
package com.imin.iminapi.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StyleCardTest {

    @Test
    void legacyTenArgConstructorDefaultsToRequiredPolicyAndPeopleObjectTypographicPlan() {
        StyleCard card = new StyleCard(
                "v", "photo", List.of(),
                List.of("a person"), List.of("a thing"),
                List.of("comp"), List.of("acc"), List.of("twist"), List.of("type"), List.of("ex"));

        assertThat(card.humanPolicy()).isEqualTo(HumanPolicy.REQUIRED);
        assertThat(card.humanStyle()).isNull();
        assertThat(card.variantPlan()).extracting(VariantSlot::mode)
                .containsExactly(HeroType.PEOPLE, HeroType.OBJECT, HeroType.TYPOGRAPHIC);
        // legacy pools still populate; new pools default empty
        assertThat(card.heroSubjectsScene()).isEmpty();
        assertThat(card.heroSubjectsAbstract()).isEmpty();
    }

    @Test
    void heroSubjectsForRoutesEveryModeToItsPool() {
        StyleCard card = new StyleCard(
                "v", "photo", List.of(),
                List.of("people-1"), List.of("object-1"), List.of("scene-1"), List.of("abstract-1"),
                List.of("comp"), List.of("acc"), List.of("twist"), List.of("type"), List.of("ex"),
                HumanPolicy.OPTIONAL, HumanStyle.ABSTRACTED,
                List.of(new VariantSlot(HeroType.SCENE), new VariantSlot(HeroType.OBJECT), new VariantSlot(HeroType.PEOPLE)));

        assertThat(card.heroSubjectsFor(HeroType.PEOPLE)).containsExactly("people-1");
        assertThat(card.heroSubjectsFor(HeroType.OBJECT)).containsExactly("object-1");
        assertThat(card.heroSubjectsFor(HeroType.SCENE)).containsExactly("scene-1");
        assertThat(card.heroSubjectsFor(HeroType.ABSTRACT_GRAPHIC)).containsExactly("abstract-1");
        assertThat(card.heroSubjectsFor(HeroType.TYPOGRAPHIC)).isEmpty();
    }

    @Test
    void defaultPlanIsThreeSlotsPeopleObjectTypographic() {
        assertThat(StyleCard.defaultPlan()).extracting(VariantSlot::mode)
                .containsExactly(HeroType.PEOPLE, HeroType.OBJECT, HeroType.TYPOGRAPHIC);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q -o test -Dtest=StyleCardTest`
Expected: COMPILE FAILURE (full constructor + new accessors don't exist yet).

- [ ] **Step 3: Rewrite `StyleCard` with the new canonical record + legacy constructor**

Replace the body of `StyleCard.java` with:

```java
package com.imin.iminapi.dto;

import java.util.List;

/**
 * A per-vibe style card — sampling pools, palette, and the per-vibe variant plan / human policy
 * extracted from that vibe's reference posters. Loaded at startup by {@code StyleCardLibrary}.
 * Drives {@code CreativeDirectionSampler}, the art-director prompt, and the style-adherence gate.
 *
 * <p>The 10-arg constructor is retained for back-compat: it defaults to {@link HumanPolicy#REQUIRED},
 * a null {@link HumanStyle}, empty scene/abstract pools, and the legacy people/object/typographic plan.
 *
 * @param heroSubjectsScene    concrete rendered scene/landscape heroes (new mode)
 * @param heroSubjectsAbstract concrete abstract-graphic/texture-field heroes (new mode)
 * @param humanPolicy          whether a human hero is required / optional / rare / forbidden
 * @param humanStyle           how a human, when present, is rendered (null ⇒ photographic)
 * @param variantPlan          the ordered 3 hero-mode slots this vibe renders
 */
public record StyleCard(
        String vibeId,
        String medium,
        List<Rgb> palette,
        List<String> heroSubjectsPeople,
        List<String> heroSubjectsObject,
        List<String> heroSubjectsScene,
        List<String> heroSubjectsAbstract,
        List<String> compositions,
        List<String> accents,
        List<String> paletteTwists,
        List<String> typeTreatments,
        List<String> examplePrompts,
        HumanPolicy humanPolicy,
        HumanStyle humanStyle,
        List<VariantSlot> variantPlan
) {
    /** Legacy 10-arg constructor: empty new pools, REQUIRED policy, null style, legacy plan. */
    public StyleCard(
            String vibeId,
            String medium,
            List<Rgb> palette,
            List<String> heroSubjectsPeople,
            List<String> heroSubjectsObject,
            List<String> compositions,
            List<String> accents,
            List<String> paletteTwists,
            List<String> typeTreatments,
            List<String> examplePrompts) {
        this(vibeId, medium, palette, heroSubjectsPeople, heroSubjectsObject,
                List.of(), List.of(),
                compositions, accents, paletteTwists, typeTreatments, examplePrompts,
                HumanPolicy.REQUIRED, null, defaultPlan());
    }

    /** The legacy hero-mode plan: people, object, typographic. */
    public static List<VariantSlot> defaultPlan() {
        return List.of(
                new VariantSlot(HeroType.PEOPLE),
                new VariantSlot(HeroType.OBJECT),
                new VariantSlot(HeroType.TYPOGRAPHIC));
    }

    /**
     * The subject pool for a hero mode. TYPOGRAPHIC → empty (type is the image; it draws only
     * accent/texture). Null pools normalize to empty.
     */
    public List<String> heroSubjectsFor(HeroType type) {
        return switch (type) {
            case PEOPLE -> nz(heroSubjectsPeople);
            case OBJECT -> nz(heroSubjectsObject);
            case SCENE -> nz(heroSubjectsScene);
            case ABSTRACT_GRAPHIC -> nz(heroSubjectsAbstract);
            case TYPOGRAPHIC -> List.of();
        };
    }

    private static List<String> nz(List<String> list) {
        return list == null ? List.of() : list;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -o test -Dtest=StyleCardTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Verify all existing 10-arg call sites still compile**

Run: `./mvnw -q -o test-compile`
Expected: BUILD SUCCESS (CreativeDirectionSamplerTest, PosterStyleValidationServiceTest use the legacy constructor — unchanged).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/StyleCard.java src/test/java/com/imin/iminapi/dto/StyleCardTest.java
git commit -m "feat(poster): add variant_plan/human_policy/human_style to StyleCard"
```

---

## Task 4: Parse the new style-card keys in `StyleCardLibrary`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/StyleCardLibrary.java`
- Modify: `src/test/resources/vibes/style-cards/test_vibe.yaml`
- Test: `src/test/java/com/imin/iminapi/service/poster/StyleCardLibraryTest.java`

- [ ] **Step 1: Add the new keys to the test fixture**

Append to `src/test/resources/vibes/style-cards/test_vibe.yaml` (add `scene`/`abstract_graphic` under the existing `hero_subjects:` map, then the three top-level keys):

```yaml
hero_subjects:
  people:
    - "a lone vocalist gripping the mic under a single hard flash"
    - "two dancers mid-spin on a sweat-slick floor"
    - "a crowd surfer caught against a wash of stage light"
    - "a DJ silhouetted behind a haze of fog"
  object:
    - "a battered cassette tape spilling its ribbon"
    - "a cracked vinyl record propped against a brick wall"
    - "a chrome boombox shot in raking light"
    - "a fistful of crumpled gig flyers"
  scene:
    - "a fog-drowned warehouse floor seen wide under one shaft of light"
  abstract_graphic:
    - "a field of torn-poster halftone texture in red and bone"
human_policy: optional
human_style: abstracted
variant_plan:
  - { mode: object }
  - { mode: scene }
  - { mode: typographic }
```

(Replace the existing `hero_subjects:` block with the version above — it adds `scene` and `abstract_graphic`, leaving people/object as they were. Keep `compositions`, `accents`, `palette_twists`, `type_treatments`, `example_prompts`, `medium`, `palette` unchanged.)

- [ ] **Step 2: Write the failing test**

Add to `StyleCardLibraryTest.java`:

```java
    @Test
    void parsesVariantPlanPolicyStyleAndNewPools() {
        StyleCard card = library.get("test_vibe").orElseThrow();

        assertThat(card.humanPolicy()).isEqualTo(com.imin.iminapi.dto.HumanPolicy.OPTIONAL);
        assertThat(card.humanStyle()).isEqualTo(com.imin.iminapi.dto.HumanStyle.ABSTRACTED);
        assertThat(card.variantPlan()).extracting(com.imin.iminapi.dto.VariantSlot::mode)
                .containsExactly(HeroType.OBJECT, HeroType.SCENE, HeroType.TYPOGRAPHIC);
        assertThat(card.heroSubjectsFor(HeroType.SCENE)).anyMatch(s -> s.contains("warehouse"));
        assertThat(card.heroSubjectsFor(HeroType.ABSTRACT_GRAPHIC)).anyMatch(s -> s.contains("halftone"));
    }
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -q -o test -Dtest=StyleCardLibraryTest#parsesVariantPlanPolicyStyleAndNewPools`
Expected: FAIL (parser still calls the 10-arg constructor → REQUIRED/default plan, scene/abstract empty).

- [ ] **Step 4: Update `StyleCardLibrary.parse`**

In `StyleCardLibrary.java`, add imports:

```java
import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.HumanPolicy;
import com.imin.iminapi.dto.HumanStyle;
import com.imin.iminapi.dto.VariantSlot;
```

Replace the `return new StyleCard(...)` block in `parse(...)` with:

```java
            List<VariantSlot> plan = toVariantPlan(root.get("variant_plan"));
            return new StyleCard(
                    vibeId,
                    str(root, "medium"),
                    toPalette(root.get("palette")),
                    toStringList(heroSubjects.get("people")),
                    toStringList(heroSubjects.get("object")),
                    toStringList(heroSubjects.get("scene")),
                    toStringList(heroSubjects.get("abstract_graphic")),
                    toStringList(root.get("compositions")),
                    toStringList(root.get("accents")),
                    toStringList(root.get("palette_twists")),
                    toStringList(root.get("type_treatments")),
                    toStringList(root.get("example_prompts")),
                    HumanPolicy.fromWire(str(root, "human_policy")),
                    HumanStyle.fromWire(str(root, "human_style")),
                    plan);
```

Add two private helpers to the class:

```java
    /**
     * Parse a {@code variant_plan:} list of {@code {mode: <hero>}} maps into 3 {@link VariantSlot}s.
     * Falls back to {@link StyleCard#defaultPlan()} when absent, the wrong size, or containing an
     * unknown mode — so a malformed plan degrades to legacy behavior instead of breaking a vibe.
     */
    @SuppressWarnings("unchecked")
    private List<VariantSlot> toVariantPlan(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() != 3) {
            return StyleCard.defaultPlan();
        }
        List<VariantSlot> slots = new ArrayList<>(3);
        for (Object item : list) {
            HeroType mode = null;
            if (item instanceof Map<?, ?> m) {
                mode = HeroType.fromWire(m.get("mode") == null ? null : String.valueOf(m.get("mode")));
            }
            if (mode == null) {
                log.warn("variant_plan slot has unknown mode {} — using legacy plan for this card", item);
                return StyleCard.defaultPlan();
            }
            slots.add(new VariantSlot(mode));
        }
        return slots;
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -o test -Dtest=StyleCardLibraryTest`
Expected: PASS (all tests, including the existing `populatesHeroSubjectPools` etc.).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/StyleCardLibrary.java src/test/resources/vibes/style-cards/test_vibe.yaml src/test/java/com/imin/iminapi/service/poster/StyleCardLibraryTest.java
git commit -m "feat(poster): parse variant_plan/human_policy/human_style + scene/abstract pools"
```

---

## Task 5: Make `CreativeDirectionSampler` execute the variant plan

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/ai/CreativeDirectionSampler.java`
- Test: `src/test/java/com/imin/iminapi/service/ai/CreativeDirectionSamplerTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CreativeDirectionSamplerTest.java` (the existing `fixture()` uses the legacy 10-arg constructor → default plan, so the existing tests stay valid). Add a helper that builds a planned card and two new tests:

```java
    private StyleCard plannedCard(java.util.List<com.imin.iminapi.dto.VariantSlot> plan,
                                  java.util.List<String> scene, java.util.List<String> abstractPool) {
        return new StyleCard(
                "planned", "photo",
                List.of(new Rgb(10, 10, 12)),
                PEOPLE, OBJECTS, scene, abstractPool,
                List.of("c1", "c2", "c3", "c4"),
                List.of("a1", "a2", "a3", "a4"),
                List.of("t1", "t2", "t3", "t4"),
                List.of("ty1", "ty2", "ty3", "ty4"),
                List.of("ex1"),
                com.imin.iminapi.dto.HumanPolicy.OPTIONAL, null, plan);
    }

    @Test
    void heroTypesFollowTheDeclaredPlanNotTheLegacyOrder() {
        var plan = List.of(
                new com.imin.iminapi.dto.VariantSlot(HeroType.SCENE),
                new com.imin.iminapi.dto.VariantSlot(HeroType.OBJECT),
                new com.imin.iminapi.dto.VariantSlot(HeroType.TYPOGRAPHIC));
        SampledRun run = sampler.sample(
                plannedCard(plan, List.of("a wide festival field at dusk"), List.of()), 42L);

        assertThat(run.directions()).extracting(CreativeDirection::heroType)
                .containsExactly(HeroType.SCENE, HeroType.OBJECT, HeroType.TYPOGRAPHIC);
        assertThat(run.directions().get(0).heroSubject()).isEqualTo("a wide festival field at dusk");
        assertThat(run.directions().get(2).heroSubject()).isNull(); // typographic draws no subject
    }

    @Test
    void repeatedModeDrawsDistinctSubjectsWithoutReplacement() {
        var plan = List.of(
                new com.imin.iminapi.dto.VariantSlot(HeroType.PEOPLE),
                new com.imin.iminapi.dto.VariantSlot(HeroType.PEOPLE),
                new com.imin.iminapi.dto.VariantSlot(HeroType.PEOPLE));
        SampledRun run = sampler.sample(plannedCard(plan, List.of(), List.of()), 7L);

        List<String> subjects = run.directions().stream().map(CreativeDirection::heroSubject).toList();
        assertThat(subjects).doesNotContainNull().doesNotHaveDuplicates(); // PEOPLE pool has 4 ≥ 3
        assertThat(PEOPLE).containsAll(subjects);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -o test -Dtest=CreativeDirectionSamplerTest#heroTypesFollowTheDeclaredPlanNotTheLegacyOrder+repeatedModeDrawsDistinctSubjectsWithoutReplacement`
Expected: FAIL (sampler still iterates `HeroType.ORDER`).

- [ ] **Step 3: Rewrite `sample()` to drive off the plan**

In `CreativeDirectionSampler.java`, add imports:

```java
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.VariantSlot;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
```

Replace the `sample(StyleCard card, long seed)` method body with:

```java
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
        Set<HeroType> distinctModes = new LinkedHashSet<>(plan);
        Map<HeroType, Deque<String>> subjectQueues = new EnumMap<>(HeroType.class);
        for (HeroType mode : distinctModes) {
            int n = (int) plan.stream().filter(mode::equals).count();
            subjectQueues.put(mode, new ArrayDeque<>(drawWithoutReplacement(card.heroSubjectsFor(mode), n, random)));
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
```

Note: `drawWithoutReplacement` for the TYPOGRAPHIC pool (empty) returns a list of `null`s of length `n`, so `subjectQueues.get(TYPOGRAPHIC).poll()` yields `null` — preserving "typographic draws no hero subject." The existing `HERO_TYPES` constant is now unused; delete it if present, or leave `HeroType.ORDER` as the single source.

- [ ] **Step 4: Run the sampler tests**

Run: `./mvnw -q -o test -Dtest=CreativeDirectionSamplerTest`
Expected: PASS (both new tests AND all existing tests — the legacy fixture defaults to people/object/typographic, so `returnsExactlyThreeDirectionsWithHeroTypesInOrder`, `heroSubjectsComeFromTheRightPoolAndTypographicIsNull`, determinism, etc. still hold).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/ai/CreativeDirectionSampler.java src/test/java/com/imin/iminapi/service/ai/CreativeDirectionSamplerTest.java
git commit -m "feat(poster): sampler executes per-vibe variant plan with no-replacement repeats"
```

---

## Task 6: Drive the art-director prompt off the plan + human_style

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java`
- Test: `src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java`

- [ ] **Step 1: Update the one existing assertion + add a human_style test**

In `AiEventDescriptionServiceTest.java`, in `buildPrompt_injectsVibeWorldSubjectAndPerVariantDirections`, change the JSON-contract assertion:

```java
        // was: assertThat(prompt).contains("hero_type: people | object | typographic");
        assertThat(prompt).contains("hero_type: one of people | object | typographic | scene | abstract_graphic");
```

Add a new test (uses the existing `sampled()` with people/object/typographic + the null-card path → default photographic line, asserting the contract change and that the default photorealistic rule still appears):

```java
    @Test
    void buildPrompt_emitsFiveModeContractAndDefaultPhotographicRule() {
        String prompt = service.buildPrompt(brief(), BRUTALIST, sampled(), null);
        assertThat(prompt).contains("hero_type: one of people | object | typographic | scene | abstract_graphic");
        // null style-card ⇒ photographic default ⇒ the photorealistic line is present
        assertThat(prompt).contains("Any people must be rendered photorealistically with correct anatomy");
    }
```

- [ ] **Step 2: Run to verify the updated assertion fails**

Run: `./mvnw -q -o test -Dtest=AiEventDescriptionServiceTest#buildPrompt_emitsFiveModeContractAndDefaultPhotographicRule`
Expected: FAIL (contract line still says the 3-mode form).

- [ ] **Step 3: Rewrite the variant-block emission and contract/rule lines**

In `AiEventDescriptionService.java`:

(a) Add imports:

```java
import com.imin.iminapi.dto.HumanStyle;
import com.imin.iminapi.dto.VariantSlot;
```

(b) In `buildPrompt(...)`, resolve the card's human style once near the top (after `List<CreativeDirection> d = sampled.directions();`):

```java
        HumanStyle humanStyle = styleCardLibrary.get(vibe.id())
                .map(StyleCard::humanStyle).orElse(null);
```

(c) Replace the three hard-coded `variantBlock(...)` calls:

```java
        sb.append("CREATIVE DIRECTIONS — one per variant, follow them precisely:\n\n");
        for (int i = 0; i < d.size(); i++) {
            sb.append(variantBlock(i + 1, d.get(i), vibe));
        }
        sb.append("\n");
```

(d) Replace the `hero_type:` contract line in the "Return a JSON object" block:

```java
          .append("    - hero_type: one of people | object | typographic | scene | abstract_graphic, matching the variant role above\n")
```

(e) In the STRICT RULES block, replace the chained element

```java
          .append("- Any people must be rendered photorealistically with correct anatomy; faces may be cropped, motion-blurred, or in shadow for style\n")
```

with a style-conditional one (stays inside the same `sb.append(...)...` chain, so the `.append("- ").append(universalNegative()...)` line that follows is unchanged):

```java
          .append(humanRule(humanStyle)).append("\n")
```

(f) Replace the `variantBlock(...)` helper signature and body, and add `humanRule`:

```java
    private static String variantBlock(int n, CreativeDirection dir, Vibe vibe) {
        HeroType mode = dir.heroType();
        boolean typographic = mode == HeroType.TYPOGRAPHIC;
        StringBuilder b = new StringBuilder();
        b.append("Variant ").append(n).append(" — hero_type \"").append(mode.wire()).append("\":\n");
        if (typographic) {
            b.append("- No pictorial hero: typography IS the image. Texture: ")
             .append(nv(dir.accent(), "expressive type texture and grain")).append("\n");
        } else {
            b.append("- Hero subject: ").append(nv(dir.heroSubject(), vibe.subject())).append("\n");
            b.append("- Treatment: ").append(nv(dir.accent(), "in-keeping texture")).append("\n");
        }
        b.append("- Layout: ").append(nv(dir.composition(), vibe.composition())).append("\n");
        b.append("- Palette: ").append(nv(dir.paletteTwist(), "the vibe palette")).append("; type: ")
         .append(nv(dir.typeTreatment(), vibe.typography())).append("\n\n");
        return b.toString();
    }

    /** The strict human-rendering rule, conditioned on the vibe's human_style (null ⇒ photographic). */
    private static String humanRule(HumanStyle humanStyle) {
        if (humanStyle == HumanStyle.ABSTRACTED) {
            return "- Render any human figure abstracted — motion-blur, halftone, or silhouette — never a clean centered portrait";
        }
        if (humanStyle == HumanStyle.FIGURE_AS_OBJECT) {
            return "- Any human form must be a sculpted/rendered material object (chrome, wireframe, posterized), never a photographed person";
        }
        return "- Any people must be rendered photorealistically with correct anatomy; faces may be cropped, motion-blurred, or in shadow for style";
    }
```

Remove the now-unused `boolean typographic` parameter version of `variantBlock` (the old 5-arg signature) entirely.

- [ ] **Step 4: Run the buildPrompt tests**

Run: `./mvnw -q -o test -Dtest=AiEventDescriptionServiceTest#buildPrompt_injectsVibeWorldSubjectAndPerVariantDirections+buildPrompt_purgesDiffusionNegativeTokensAndForcedTypePlate+buildPrompt_forcesFourByFiveAndDesign+buildPrompt_appendsReinforcementOnRetry+buildPrompt_emitsFiveModeContractAndDefaultPhotographicRule`
Expected: PASS (the `sampled()` fixture still produces people/object/typographic blocks; the contract line is the new 5-mode form).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java
git commit -m "feat(poster): art-director prompt follows variant plan + human_style"
```

---

## Task 7: Make `validate` plan- and policy-driven

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java`
- Test: `src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java`

- [ ] **Step 1: Write failing policy tests**

Add to `AiEventDescriptionServiceTest.java`. These build a `StyleCard` with an explicit plan/policy and call the new card-aware overload. Add imports at the top of the file:

```java
import com.imin.iminapi.dto.HumanPolicy;
import com.imin.iminapi.dto.HumanStyle;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.VariantSlot;
import com.imin.iminapi.dto.Rgb;
```

Add a helper + tests:

```java
    private static StyleCard cardWith(HumanPolicy policy, List<VariantSlot> plan) {
        return new StyleCard(
                "brutalist_techno", "mixed", List.of(new Rgb(13, 13, 13)),
                List.of("p"), List.of("o"), List.of("s"), List.of("ag"),
                List.of("c"), List.of("a"), List.of("tw"), List.of("ty"), List.of("ex"),
                policy, HumanStyle.PHOTOGRAPHIC, plan);
    }

    private static final List<VariantSlot> OBJ_ABS_TYPO = List.of(
            new VariantSlot(HeroType.OBJECT),
            new VariantSlot(HeroType.ABSTRACT_GRAPHIC),
            new VariantSlot(HeroType.TYPOGRAPHIC));

    // A human-less abstract-graphic hero prompt, distinct from objectPrompt()/typographicPrompt()
    // (no person-hero word ⇒ containsHumanHero == false).
    private static String abstractPrompt() {
        return "An edge-to-edge molten liquid-chrome field floods the whole sheet, acid-green ripples melting into "
                + "rose-pink over a near-black ground, oil-slick iridescence catching diffused glow with no discrete "
                + "subject anywhere in the frame. Heavy condensed white lettering reverses out of the haze along the "
                + "upper band while a tracked-out date line sits low and a thin lineup column slides down the right "
                + "margin, fine film grain and gentle gradient banding drifting across the dreamy atmospheric vertical poster.";
    }

    @Test
    void validate_forbidden_rejectsAnyHumanHero() {
        // plan has no people slot; object[0] sneaks in a person hero
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("object", peoplePrompt(), "4:5", "Design"),       // "a lone dancer..."
                new PosterVariant("abstract_graphic", abstractPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c, cardWith(HumanPolicy.FORBIDDEN, OBJ_ABS_TYPO)))
                .contains("forbids human heroes");
    }

    @Test
    void validate_forbidden_acceptsHumanLessRun() {
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("object", objectPrompt(), "4:5", "Design"),
                new PosterVariant("abstract_graphic", abstractPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c, cardWith(HumanPolicy.FORBIDDEN, OBJ_ABS_TYPO))).isNull();
    }

    @Test
    void validate_planOrderIsCheckedAgainstTheCardNotLegacyOrder() {
        // object/abstract_graphic/typographic plan; supplying people first is wrong
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("people", peoplePrompt(), "4:5", "Design"),
                new PosterVariant("abstract_graphic", objectPrompt(), "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c, cardWith(HumanPolicy.RARE, OBJ_ABS_TYPO)))
                .contains("hero_type must be exactly \"object\"");
    }

    @Test
    void validate_rare_rejectsTwoHumanHeroes() {
        var plan = List.of(new VariantSlot(HeroType.OBJECT), new VariantSlot(HeroType.OBJECT),
                new VariantSlot(HeroType.TYPOGRAPHIC));
        // two object variants both built around a person hero
        PosterConcept c = new PosterConcept("brutalist_techno", "p", List.of(
                new PosterVariant("object", peoplePrompt(), "4:5", "Design"),
                new PosterVariant("object", peoplePrompt().replace("dancer", "woman").replace("crowd", "raver")
                        + " with distinct extra wording here today now to differ greatly", "4:5", "Design"),
                new PosterVariant("typographic", typographicPrompt(), "4:5", "Design")));
        assertThat(service.validate(c, cardWith(HumanPolicy.RARE, plan)))
                .contains("at most one human-hero variant");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -o test -Dtest=AiEventDescriptionServiceTest#validate_forbidden_rejectsAnyHumanHero`
Expected: COMPILE FAILURE (no `validate(PosterConcept, StyleCard)` overload yet).

- [ ] **Step 3: Rewrite `validate` into plan/policy form with thin legacy overloads**

In `AiEventDescriptionService.java`, add imports:

```java
import com.imin.iminapi.dto.HumanPolicy;
```

Replace the existing `String validate(PosterConcept concept)` method (lines ~223-266) with the overloads below. Keep `validate(PosterConcept, EventCreatorRequest)` but make it resolve+delegate:

```java
    /** Resolve the style card backing a concept's vibe (null when the vibe has no card). */
    private StyleCard cardFor(PosterConcept concept) {
        return concept == null ? null : styleCardLibrary.get(concept.subStyleTag()).orElse(null);
    }

    String validate(PosterConcept concept) {
        return validate(concept, cardFor(concept));
    }

    String validate(PosterConcept concept, StyleCard card) {
        if (concept == null) return "null concept";
        if (concept.subStyleTag() == null || !vibeLibrary.hasVibe(concept.subStyleTag())) {
            return "sub_style_tag must be a known vibe id";
        }
        List<PosterVariant> variants = concept.variants();
        if (variants == null || variants.size() != 3) return "exactly 3 variants required";

        List<HeroType> plan = planModes(card);
        HumanPolicy policy = (card == null || card.humanPolicy() == null) ? HumanPolicy.REQUIRED : card.humanPolicy();

        for (int i = 0; i < variants.size(); i++) {
            PosterVariant v = variants.get(i);
            String expectedWire = plan.get(i).wire();
            if (v.heroType() == null || !expectedWire.equals(v.heroType().trim().toLowerCase())) {
                return "variant[" + i + "].hero_type must be exactly \"" + expectedWire + "\" (planned: " + planWire(plan) + ")";
            }
            if (v.aspectRatio() == null || !VALID_ASPECTS.contains(v.aspectRatio())) {
                return "variant[" + i + "].aspect_ratio must be one of " + VALID_ASPECTS;
            }
            if (!"Design".equals(v.styleType())) {
                return "variant[" + i + "].style_type must be \"Design\"";
            }
            String p = v.ideogramPrompt();
            if (p == null || p.isBlank()) return "variant[" + i + "].ideogram_prompt is empty";
            int wc = wordCount(p);
            if (wc < MIN_WORDS) return "variant[" + i + "].ideogram_prompt too short (" + wc + " words, min " + MIN_WORDS + ")";
            if (wc > MAX_WORDS) return "variant[" + i + "].ideogram_prompt too long (" + wc + " words, max " + MAX_WORDS + ")";
        }

        // Mode-driven per-slot human rules: a people slot must show a human; a typographic slot must
        // not be built around a person (type is the image).
        for (int i = 0; i < variants.size(); i++) {
            HeroType mode = plan.get(i);
            String p = variants.get(i).ideogramPrompt();
            if (mode == HeroType.PEOPLE && !mentionsHumanSubject(p)) {
                return "variant[" + i + "] (people) must contain a human subject (one of " + HUMAN_NOUNS + ")";
            }
            if (mode == HeroType.TYPOGRAPHIC && containsHumanHero(p)) {
                return "variant[" + i + "] (typographic) must not be built around a person — typography is the image";
            }
        }

        // Policy caps on human heroes across the whole run.
        long humanHeroes = variants.stream().filter(v -> containsHumanHero(v.ideogramPrompt())).count();
        if (policy == HumanPolicy.FORBIDDEN && humanHeroes > 0) {
            return "this vibe forbids human heroes, but a person appears in a variant";
        }
        if (policy == HumanPolicy.RARE && humanHeroes > 1) {
            return "this vibe allows at most one human-hero variant, but " + humanHeroes + " contain a person";
        }
        if (policy == HumanPolicy.REQUIRED
                && variants.stream().noneMatch(v -> mentionsHumanSubject(v.ideogramPrompt()))) {
            return "this vibe requires a human hero, but no variant contains a person";
        }

        // The three prompts must be visually distinct, not paraphrases of each other.
        List<String> prompts = variants.stream().map(PosterVariant::ideogramPrompt).toList();
        if (!PromptDistinctness.allPairwiseBelow(prompts, DISTINCTNESS_THRESHOLD)) {
            return "variants too similar — rewrite with different sentence structures, subjects, and layouts";
        }
        return null;
    }

    /** The 3 planned hero modes for a card, or the legacy order when the card has no plan. */
    private static List<HeroType> planModes(StyleCard card) {
        if (card == null || card.variantPlan() == null || card.variantPlan().isEmpty()) {
            return List.of(HeroType.ORDER);
        }
        return card.variantPlan().stream().map(VariantSlot::mode).toList();
    }

    private static String planWire(List<HeroType> plan) {
        return plan.stream().map(HeroType::wire).reduce((a, b) -> a + ", " + b).orElse("");
    }
```

Then update the request-aware overload to thread the card:

```java
    String validate(PosterConcept concept, EventCreatorRequest request) {
        return validate(concept, request, cardFor(concept));
    }

    String validate(PosterConcept concept, EventCreatorRequest request, StyleCard card) {
        String shapeError = validate(concept, card);
        if (shapeError != null) return shapeError;

        PosterTextSpec textSpec = posterTextSpecFactory.from(request);
        if (textSpec.required().isEmpty()) return null;

        List<PosterVariant> variants = concept.variants();
        for (int i = 0; i < variants.size(); i++) {
            String prompt = variants.get(i).ideogramPrompt().toLowerCase(java.util.Locale.ROOT);
            for (String requiredText : textSpec.required()) {
                if (!prompt.contains(requiredText.toLowerCase(java.util.Locale.ROOT))) {
                    return "variant[" + i + "].ideogram_prompt missing required text \"" + requiredText + "\"";
                }
            }
        }
        return null;
    }
```

Add the import for `VariantSlot` if not already present (`import com.imin.iminapi.dto.VariantSlot;`).

- [ ] **Step 4: Thread the resolved card through `generateConcept`**

In `generateConcept(...)`, change the validation call (was `validate(concept, request)`) to pass the already-resolved `card`:

```java
                String validationError = validate(concept, request, card);
```

- [ ] **Step 5: Run the whole `AiEventDescriptionServiceTest`**

Run: `./mvnw -q -o test -Dtest=AiEventDescriptionServiceTest`
Expected: PASS — the existing tests use `styleCardLibrary` pointed at a nonexistent glob, so `cardFor(...)` returns null → legacy plan (`people, object, typographic`) + REQUIRED policy, preserving every original assertion (`hero_type must be exactly "people"`, `must contain a human subject`, `typographic) must not be built around a person`, distinctness, required text). The four new policy tests pass via explicit cards.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java
git commit -m "feat(poster): validate against variant plan + human policy"
```

---

## Task 8: Update `validateRenderable` message + vision style gate for new modes

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java`
- Modify: `src/main/java/com/imin/iminapi/service/poster/OpenRouterPosterStyleValidationClient.java`

- [ ] **Step 1: Broaden the `validateRenderable` message**

In `validateRenderable`, change the hero-type error string to list all 5 modes (the `HeroType.fromWire` check already accepts them):

```java
            if (HeroType.fromWire(v.heroType()) == null) {
                return "variant[" + i + "].hero_type must be one of people, object, typographic, scene, abstract_graphic";
            }
```

(The existing test asserts `contains("hero_type must be one of")` — still satisfied.)

- [ ] **Step 2: Treat `ABSTRACT_GRAPHIC` like `TYPOGRAPHIC` in the gate; `SCENE` keeps a subject**

In `OpenRouterPosterStyleValidationClient.validationPrompt(...)`, change the typographic branch condition and wording:

```java
        if (declaredHeroType == HeroType.TYPOGRAPHIC || declaredHeroType == HeroType.ABSTRACT_GRAPHIC) {
            sb.append("This poster has NO discrete pictorial hero subject (typography or an abstract ")
                    .append("graphic field IS the hero). Therefore report \"heroSubjectPresent\":true and ")
                    .append("judge only the medium and palette.\n");
        } else {
            sb.append("Expected hero subject — the poster should clearly feature one of these scenes:\n")
                    .append(bulletLines(card.heroSubjectsFor(declaredHeroType)))
                    .append("\nIf no such pictorial hero is present, report \"heroSubjectPresent\":false and reject.\n");
        }
```

`SCENE` falls into the `else` branch and uses `card.heroSubjectsFor(SCENE)` (its new pool), so it is checked like an object hero.

- [ ] **Step 3: Compile + run the touched tests**

Run: `./mvnw -q -o test -Dtest=AiEventDescriptionServiceTest+PosterStyleValidationServiceTest`
Expected: PASS (the style-validation service test uses the legacy `StyleCard` constructor and existing hero types; the new branch only adds `ABSTRACT_GRAPHIC`).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java src/main/java/com/imin/iminapi/service/poster/OpenRouterPosterStyleValidationClient.java
git commit -m "feat(poster): style gate handles scene/abstract_graphic modes"
```

---

## Task 9: Author plans for the 7 vibes that keep their existing pools

**Files (modify each):**
- `src/main/resources/vibes/style-cards/afro_amapiano.yaml`
- `src/main/resources/vibes/style-cards/dark_experimental.yaml`
- `src/main/resources/vibes/style-cards/hyperpop_club.yaml`
- `src/main/resources/vibes/style-cards/disco_italo.yaml`
- `src/main/resources/vibes/style-cards/dnb_jungle.yaml`
- `src/main/resources/vibes/style-cards/industrial_hard_groove.yaml`
- `src/main/resources/vibes/style-cards/berlin_minimal.yaml`

For each file, **append** the three top-level keys below (after the existing `example_prompts:` block, at column 0). Do not change any existing pool.

- [ ] **Step 1: `afro_amapiano.yaml`** — append:

```yaml
human_policy: required
human_style: photographic
variant_plan:
  - { mode: people }
  - { mode: object }
  - { mode: typographic }
```

- [ ] **Step 2: `dark_experimental.yaml`** — append:

```yaml
human_policy: required
human_style: abstracted
variant_plan:
  - { mode: people }
  - { mode: object }
  - { mode: typographic }
```

- [ ] **Step 3: `hyperpop_club.yaml`** — append:

```yaml
human_policy: required
human_style: photographic
variant_plan:
  - { mode: people }
  - { mode: object }
  - { mode: typographic }
```

- [ ] **Step 4: `disco_italo.yaml`** — append (three people slots; its `hero_subjects.people` pool already has ≥3 entries, required for the no-replacement draw):

```yaml
human_policy: required
human_style: photographic
variant_plan:
  - { mode: people }
  - { mode: people }
  - { mode: people }
```

- [ ] **Step 5: `dnb_jungle.yaml`** — append:

```yaml
human_policy: optional
human_style: abstracted
variant_plan:
  - { mode: object }
  - { mode: typographic }
  - { mode: people }
```

- [ ] **Step 6: `industrial_hard_groove.yaml`** — append:

```yaml
human_policy: optional
human_style: photographic
variant_plan:
  - { mode: object }
  - { mode: typographic }
  - { mode: people }
```

- [ ] **Step 7: `berlin_minimal.yaml`** — append (people pool stays — it is all ghosts/silhouettes; `abstracted` keeps them that way):

```yaml
human_policy: optional
human_style: abstracted
variant_plan:
  - { mode: typographic }
  - { mode: object }
  - { mode: people }
```

- [ ] **Step 8: Verify they parse**

Run: `./mvnw -q -o test -Dtest=StyleCardLibraryTest`
Expected: PASS (cards load with their plans; `classpath*:` picks up main resources on the test classpath).

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/vibes/style-cards/afro_amapiano.yaml src/main/resources/vibes/style-cards/dark_experimental.yaml src/main/resources/vibes/style-cards/hyperpop_club.yaml src/main/resources/vibes/style-cards/disco_italo.yaml src/main/resources/vibes/style-cards/dnb_jungle.yaml src/main/resources/vibes/style-cards/industrial_hard_groove.yaml src/main/resources/vibes/style-cards/berlin_minimal.yaml
git commit -m "data(poster): variant plans for the seven pool-stable vibes"
```

---

## Task 10: Re-author the three human-less / rare vibes (remove people pool)

**Files:**
- `src/main/resources/vibes/style-cards/liquid_melodic.yaml`
- `src/main/resources/vibes/style-cards/acid_rave_y2k.yaml`
- `src/main/resources/vibes/style-cards/brutalist_techno.yaml`

- [ ] **Step 1: `liquid_melodic.yaml`** — replace the entire `hero_subjects:` block with the version below (drops `people:`, keeps `object:`, adds `abstract_graphic:` split out from the two pure-field references), then append the plan keys.

Replace `hero_subjects:` … through the end of the `object:` list with:

```yaml
hero_subjects:
  object:
    - 'a glossy 3D-rendered iridescent beetle-dragonfly creature with mirrored wings and metallic body'
    - 'a fuzzy green pom-pom clover orb wrapped in silver chains, spikes, and a smiley sticker'
    - 'an x-ray-style cactus cluster with pink blooms enclosing a small machine, on near-black ground'
    - 'a cluster of chrome mushrooms and bubble spheres glowing under purple and green laser light'
  abstract_graphic:
    - 'a molten liquid-chrome blob with green and pink iridescent ripples flowing across a black field'
    - 'a thermal-camera fluid surface of amber, blue and teal blobs melting into one another'
    - 'an edge-to-edge liquid-glass field, soft-focused gradients bending like mercury under haze'
```

Append at column 0:

```yaml
human_policy: forbidden
variant_plan:
  - { mode: object }
  - { mode: abstract_graphic }
  - { mode: typographic }
```

- [ ] **Step 2: `acid_rave_y2k.yaml`** — drop `people:`, move the chrome sculpted head into `object:`, append the plan. Replace the `hero_subjects:` block with:

```yaml
hero_subjects:
  object:
    - 'A chrome liquid-metal sculpted human head tilted back, eyes closed, surface a mirror of holographic rainbow reflections, floating on flat black'
    - 'A descending chain of glossy chrome blobs / metaballs blurred in motion against an all-violet ground with faint wireframe grid'
    - 'A twisted polished liquid-metal knot or skeletal sculpture coiling on itself over hot-magenta concentric ripple rings'
    - 'A holographic iridescent liquid splash frozen mid-air, ribbons of oil-slick paint swirling across flat black'
    - 'A dark mirror-chrome orb or teardrop softly out of focus, reflecting blue light, buried in heavy film grain'
    - 'A melting acid-smiley face cast in liquid chrome, mouth drooping into a metallic drip'
```

Append at column 0:

```yaml
human_policy: rare
human_style: figure_as_object
variant_plan:
  - { mode: object }
  - { mode: object }
  - { mode: typographic }
```

- [ ] **Step 3: `brutalist_techno.yaml`** — drop `people:`, move the thermal-smoke object into a new `abstract_graphic:` pool, keep the remaining objects/architecture, append the plan. Replace the `hero_subjects:` block with:

```yaml
hero_subjects:
  object:
    - 'dramatic upward worm''s-eye view of a raw exposed-concrete brutalist tower''s corner, ribbed balconies vanishing into a grainy black sky'
    - 'engraving-style stippled astronomical specimen (ringed planet) floating in black, fine etched dot texture'
    - 'industrial steel stairwell and concrete soffit shot head-on, single harsh light raking the formwork, film grain'
    - 'vintage halftone black-and-white city street of old sedans and concrete blocks, scratched and marbled like a worn photocopy'
    - 'single bare hanging warehouse bulb burning a hot highlight into total black surrounding void'
  abstract_graphic:
    - 'abstract orange-and-teal thermal smoke cloud sliced into vertical strips, heavy paper grain'
    - 'a blown-out high-contrast posterized face reduced to two acid-green tones, used as flat graphic texture on black'
    - 'a field of marbled toner streaks and photocopy dust over matte black, no discrete subject'
```

Append at column 0:

```yaml
human_policy: rare
human_style: figure_as_object
variant_plan:
  - { mode: object }
  - { mode: typographic }
  - { mode: abstract_graphic }
```

- [ ] **Step 4: Verify parse + sampler over the new pools**

Run: `./mvnw -q -o test -Dtest=StyleCardLibraryTest+CreativeDirectionSamplerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/vibes/style-cards/liquid_melodic.yaml src/main/resources/vibes/style-cards/acid_rave_y2k.yaml src/main/resources/vibes/style-cards/brutalist_techno.yaml
git commit -m "data(poster): human-less plans for liquid_melodic, acid_rave_y2k, brutalist_techno"
```

---

## Task 11: Re-author the two vibes that gain a scene / abstract pool

**Files:**
- `src/main/resources/vibes/style-cards/open_air_festival.yaml`
- `src/main/resources/vibes/style-cards/psytrance_goa.yaml`

- [ ] **Step 1: `open_air_festival.yaml`** — split the scene-like entries out of `object:` into a new `scene:` pool (keep people; keep the truly object-like flags/palms/sun/mascot in `object:`), append the plan. Replace the `hero_subjects:` block with:

```yaml
hero_subjects:
  people:
    - 'a dense festival crowd shot from behind facing a lit outdoor stage at sunset, hundreds of raised hands and silhouetted heads against amber sky'
    - 'a tight group of summer day-party revelers crammed together outdoors, sunglasses and bucket hats, one shirtless guy with arms spread at the center, faces sun-flushed'
    - 'silhouetted dancers with arms in the air along a glowing horizon line, low sun flaring between their bodies'
    - 'a smiling crowd at a beach festival waist-deep in golden light, hands up, palm trees flanking the frame'
    - 'a single festival-goer on someone''s shoulders above the crowd, backlit by stage spotlights and sunset haze'
  object:
    - 'rows of colorful prayer/festival bunting flags strung in an arc across a sunset sky above a stage'
    - 'leaning palm trees framing the left and right edges of a glowing orange festival sky'
    - 'a blazing low sun bursting in lens flare over a wide flat horizon with scattered clouds'
    - 'a central festival mascot motif radiating sunbeams — a screaming sunglasses-wearing mouth made of flowers and speakers'
  scene:
    - 'an outdoor concert stage with truss, tall speaker stacks and bright LED screens lit against a dusk skyline and waterfront'
    - 'an aerial top-down view of a packed beach with umbrellas, bars, a ferris wheel and a beach ball scattered across pale sand'
    - 'a grassy city park in the foreground with a hazy skyline and a tall tower rising behind under blue sky'
    - 'a wide grassy festival field meeting a billboard-lined skyline under a chrome-titled sunset horizon'
```

Append at column 0:

```yaml
human_policy: optional
human_style: photographic
variant_plan:
  - { mode: scene }
  - { mode: object }
  - { mode: people }
```

- [ ] **Step 2: `psytrance_goa.yaml`** — move the two pure-abstract entries (neon bloom, mandala portal) out of `object:` into a new `abstract_graphic:` pool, keep people + the remaining objects, append the plan. Replace the `hero_subjects:` block with:

```yaml
hero_subjects:
  people:
    - 'a cross-legged figure in lotus meditation seen front-on, glowing third-eye gem at the forehead, arms folded, hair streaming upward into curling flame and smoke, eyes closed in shadow'
    - 'a gray-skinned tribal woman with fire-orange hair erupting into swirling tendrils, face calm and softly lit, neck ringed by metallic torcs, surrounded by hummingbirds and floating snails'
    - 'a seated meditator dissolving into a fractal mandala body, lower torso fading into crystalline shards and koi fish, framed by hibiscus blooms and glowing mushrooms'
    - 'silhouetted partygoers walking away down a jungle path toward distant neon towers, backlit so faces stay in shadow, rim-lit by magenta sky glow'
  object:
    - 'a giant ripe strawberry rendered as a textured orb with a single realistic human eye at its center, a ring of liquid fire orbiting it, syrup dripping down'
    - 'retro-futurist saucer-topped mushroom towers rising over an alien jungle, flying discs beaming light shafts under a giant ringed planet and starfield'
    - 'a faceted floating crystal sigil ringed in UV light, hovering above cupped hands amid vines and red flowers'
  abstract_graphic:
    - 'a glowing abstract bloom of fluid neon tendrils unfurling from a radiant white-hot core, like a melting jellyfish flower on a warm gradient'
    - 'a sacred-geometry mandala portal of nested triangles and dots glowing inside a lotus arch'
    - 'a full-bleed swirl of UV-glowing fractal energy radiating from a central white-hot point on a cosmic gradient'
```

Append at column 0:

```yaml
human_policy: optional
human_style: photographic
variant_plan:
  - { mode: people }
  - { mode: object }
  - { mode: abstract_graphic }
```

- [ ] **Step 3: Verify parse + sampler**

Run: `./mvnw -q -o test -Dtest=StyleCardLibraryTest+CreativeDirectionSamplerTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/vibes/style-cards/open_air_festival.yaml src/main/resources/vibes/style-cards/psytrance_goa.yaml
git commit -m "data(poster): scene plan for open_air_festival, abstract plan for psytrance_goa"
```

---

## Task 12: Cross-vibe consistency guard test

**Files:**
- Test: `src/test/java/com/imin/iminapi/service/poster/VibePlanConsistencyTest.java` (create)

This test loads the real production cards and asserts every plan is internally consistent with its policy and that every planned mode has a non-empty subject pool (except typographic).

- [ ] **Step 1: Write the test**

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.HumanPolicy;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.VariantSlot;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the authored production style-cards: every variant_plan is well-formed and consistent. */
class VibePlanConsistencyTest {

    private static final List<String> PRODUCTION_VIBES = List.of(
            "afro_amapiano", "dark_experimental", "hyperpop_club", "disco_italo",
            "dnb_jungle", "industrial_hard_groove", "berlin_minimal",
            "liquid_melodic", "acid_rave_y2k", "brutalist_techno",
            "open_air_festival", "psytrance_goa");

    private final StyleCardLibrary library = load();

    private static StyleCardLibrary load() {
        StyleCardLibrary lib = new StyleCardLibrary(
                new DefaultResourceLoader(), "classpath*:vibes/style-cards/*.yaml");
        lib.load();
        return lib;
    }

    @Test
    void everyProductionVibeHasAThreeSlotPlan() {
        for (String vibe : PRODUCTION_VIBES) {
            StyleCard card = library.get(vibe).orElseThrow(() -> new AssertionError("missing card: " + vibe));
            assertThat(card.variantPlan()).as("%s plan size", vibe).hasSize(3);
        }
    }

    @Test
    void forbiddenVibesHaveNoPeopleSlotAndOptionalRareRequiredAreConsistent() {
        for (String vibe : PRODUCTION_VIBES) {
            StyleCard card = library.get(vibe).orElseThrow();
            boolean hasPeople = card.variantPlan().stream().map(VariantSlot::mode).anyMatch(HeroType.PEOPLE::equals);
            if (card.humanPolicy() == HumanPolicy.FORBIDDEN) {
                assertThat(hasPeople).as("%s is forbidden ⇒ no people slot", vibe).isFalse();
            }
            if (card.humanPolicy() == HumanPolicy.REQUIRED) {
                assertThat(hasPeople).as("%s is required ⇒ has a people slot", vibe).isTrue();
            }
        }
    }

    @Test
    void everyNonTypographicPlannedModeHasEnoughDistinctSubjects() {
        // A pool must hold at least as many subjects as the plan uses that mode, so a repeated mode
        // (e.g. disco_italo people x3) can draw distinct subjects without replacement.
        for (String vibe : PRODUCTION_VIBES) {
            StyleCard card = library.get(vibe).orElseThrow();
            for (HeroType mode : List.of(HeroType.PEOPLE, HeroType.OBJECT, HeroType.SCENE, HeroType.ABSTRACT_GRAPHIC)) {
                long uses = card.variantPlan().stream().map(VariantSlot::mode).filter(mode::equals).count();
                if (uses == 0) continue;
                assertThat(card.heroSubjectsFor(mode).size())
                        .as("%s mode %s pool must have >= %d subjects", vibe, mode, uses)
                        .isGreaterThanOrEqualTo((int) uses);
            }
        }
    }
}
```

- [ ] **Step 2: Run it**

Run: `./mvnw -q -o test -Dtest=VibePlanConsistencyTest`
Expected: PASS. If a vibe fails `everyNonTypographicPlannedModeHasASubjectPool`, that card's plan references an empty pool — fix the YAML (Tasks 9-11), do not weaken the test.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/imin/iminapi/service/poster/VibePlanConsistencyTest.java
git commit -m "test(poster): guard production variant plans for consistency"
```

---

## Task 13: Full verification

- [ ] **Step 1: Run the entire suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 2: Build the package**

Run: `./mvnw -q -DskipTests package`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Sanity-grep that the old hard binding is gone**

Run: `grep -rn "HERO_TYPES\|ORDER\[i\]\|variant\[2\] (typographic)" src/main/java`
Expected: no `HERO_TYPES` constant; `HeroType.ORDER` appears only as the documented legacy fallback in `CreativeDirectionSampler.planModes`, `AiEventDescriptionService.planModes`, and `HeroType` itself.

- [ ] **Step 4: Final commit (if any stragglers)**

```bash
git status
# commit anything outstanding, then the branch is ready for review/PR.
```

---

## Notes for the implementer

- **Why no migration:** `poster_variants.variant_style` is a free `VARCHAR(32)` with no enum/CHECK constraint; `"abstract_graphic"` (16 chars) fits. New wire strings persist as-is.
- **Why existing tests survive:** every existing `new StyleCard(...)` call uses the legacy 10-arg constructor (→ default plan + REQUIRED), and `AiEventDescriptionServiceTest` points `StyleCardLibrary` at a nonexistent glob so `cardFor(...)` returns null (→ legacy plan). The behavior under the default plan is identical to today.
- **`figure_as_object` vibes** (`acid_rave_y2k`, `brutalist_techno`) have no `people` slot; their human read lives in the `object`/`abstract_graphic` pools, and `humanRule(FIGURE_AS_OBJECT)` adds the global "render any human as a material object" instruction. The `containsHumanHero` regex deliberately ignores `head`/`face`, so a "chrome sculpted head" object subject does not trip the `rare` cap.
- **Out of scope:** poster-lab harness, imin-webapp/imin-public, rendering speed, R2, the text-legibility gate.
