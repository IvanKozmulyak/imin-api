# Per-Vibe Variant Plan — Design

**Date:** 2026-06-08
**Status:** Approved (pending spec review)
**Scope:** `imin-api` only (production poster generation). Not the `poster-lab` harness, not `imin-webapp`/`imin-public`.

## 1. Problem

Poster generation forces **every** vibe through one global hero binding:
`HeroType.ORDER = [PEOPLE, OBJECT, TYPOGRAPHIC]`. Exactly one of each is sampled per run,
always in that order, and validation **requires a human in variant 1** and **forbids a human in
variant 3**. So variant 1 is always a forced photorealistic person — even for vibes whose real
posters never show people.

A vision analysis of all 12 vibes' reference image sets (5 images each, 60 total) shows this is
wrong for **8 of 12 vibes**:

| Policy | Vibes | Count |
|---|---|---|
| **required** (human is the hero) | `afro_amapiano`, `dark_experimental`, `disco_italo`, `hyperpop_club` | 4 |
| **optional** (human recurs but isn't the rule) | `berlin_minimal`, `dnb_jungle`, `industrial_hard_groove`, `open_air_festival`, `psytrance_goa` | 5 |
| **rare** (human only as a rendered/graphic object) | `acid_rave_y2k`, `brutalist_techno` | 2 |
| **forbidden** (no people at all) | `liquid_melodic` | 1 |

Only the 4 `required` vibes fit today's rule. The analysis also surfaced three structural gaps:

1. **Three hero modes are too few.** The corpus needs two more: `scene` (open-air stages/landscapes,
   psytrance jungles) and `abstract_graphic` (liquid texture fields, berlin wireframes). Today these
   are crammed into "object," which mislabels them.
2. **"Human" is not binary — it needs a style.** `photographic` (disco, hyperpop) vs `abstracted`
   (dark_experimental, berlin ghosts) vs `figure_as_object` (acid's chrome head, dnb's wireframe
   head). Some *required* vibes must still never get a clean centered portrait.
3. **Typography is dominant or strong in all 60 reference images** — it is the one universally safe
   element. The fix should keep strong typography on every variant but stop forcing a type-**only**
   slot (off-style for disco/hyperpop, which are never type-only).

## 2. Goal & non-goals

**Goal:** Replace the global `[people, object, typographic] × 3` binding with a **per-vibe declared
variant plan** so that (a) human-less vibes never get a forced person, (b) hero composition reflects
each vibe's real identity, and (c) three visually distinct variants with strong typography are still
guaranteed.

**Non-goals:**
- No change to the rendering path (Ideogram V3 native), the text-legibility gate, R2 storage, or the
  request/response API surface. `hero_type` is an internal field, not part of a published contract.
- No change to variant **count** — always exactly 3 (the product surface shows 3 options).
- No port to `poster-lab` (the rendering decision is already settled on master; this is a production
  change, not a new experiment).

## 3. Chosen approach: declarative variant plan

Each vibe authors its own ordered 3-slot recipe into its style-card. The recipe *is* the diversity —
there is no runtime weighting or distinctness solver. With only 12 curated vibes, hand-authoring the
right recipe (seeded directly from the reference analysis) is deterministic, fully inspectable, and
matches the existing "diversity lives in data, not the model" philosophy.

Rejected alternatives:
- **Minimal unbind** (add only `human_policy`, keep 3 modes): leaves the scene/abstract gap and the
  collapse risk unmanaged.
- **Weighted hero sampler** (per-vibe mode→weight map, runtime distinct draw + type-floor +
  anti-collapse): adds weighting math and a distinctness solver to rediscover what 12 hand-authored
  recipes express directly.

## 4. Data model

### 4.1 Hero modes

`HeroType` expands from 3 to 5 values:

```
PEOPLE, OBJECT, TYPOGRAPHIC, SCENE, ABSTRACT_GRAPHIC
```

`wire()` / `fromWire()` cover the new tokens (`"scene"`, `"abstract_graphic"`). `HeroType.ORDER`
is **retained only as the legacy fallback** for a vibe that declares no `variant_plan`; it is no
longer the binding.

### 4.2 Style-card additions (`resources/vibes/style-cards/{vibe}.yaml`)

```yaml
# hero modes: people | object | typographic | scene | abstract_graphic
human_policy: forbidden        # required | optional | rare | forbidden
human_style:  photographic     # photographic | abstracted | figure_as_object
variant_plan:                  # exactly 3 ordered slots
  - { mode: object }
  - { mode: abstract_graphic }
  - { mode: typographic }
hero_subjects:
  people: [...]                # existing
  object: [...]                # existing
  scene: [...]                 # new — only where the plan uses a scene slot
  abstract_graphic: [...]      # new — only where the plan uses an abstract_graphic slot
```

- `human_policy` — governs validation (see §6). `forbidden` means no `people` slot AND no human hero
  allowed anywhere. `required` means ≥1 `people` slot. `rare` caps human heroes at ≤1 and pairs with
  `human_style: figure_as_object`. `optional` allows a `people` slot without forcing humans elsewhere.
- `human_style` — how a human, when present, must be rendered. Injected into the prompt so an
  `abstracted` / `figure_as_object` vibe is never told to produce a clean portrait. Vibe-level only
  (per-slot override is a deliberate non-goal — YAGNI).
- `variant_plan` — exactly 3 slots; each slot's `mode` is one of the 5 hero modes. A mode may repeat
  (e.g. `disco_italo` = people·people·people).

**Defaults for back-compat:** a card with no `variant_plan` → legacy `ORDER`; no `human_policy` →
`required` (today's behavior). Once all 12 cards are authored, defaults are never exercised.

## 5. Per-vibe plans (authored from the reference analysis)

| Vibe | `human_policy` | `human_style` | `variant_plan` |
|---|---|---|---|
| `liquid_melodic` | forbidden | — | object · abstract_graphic · typographic |
| `acid_rave_y2k` | rare | figure_as_object | object · object · typographic |
| `brutalist_techno` | rare | figure_as_object | object · typographic · abstract_graphic |
| `berlin_minimal` | optional | abstracted | typographic · object · people |
| `dnb_jungle` | optional | abstracted | object · typographic · people |
| `industrial_hard_groove` | optional | photographic | object · typographic · people |
| `open_air_festival` | optional | photographic | scene · object · people |
| `psytrance_goa` | optional | photographic | people · object · abstract_graphic |
| `afro_amapiano` | required | photographic | people · object · typographic |
| `dark_experimental` | required | abstracted | people · object · typographic |
| `hyperpop_club` | required | photographic | people · object · typographic |
| `disco_italo` | required | photographic | people · people · people |

Notes:
- **Three vibes lose `people` entirely** (`liquid_melodic`, `acid_rave_y2k`, `brutalist_techno`).
  Their "human" reads (chrome sculpted head, posterized face) become **object-mode subjects**, not a
  forced person. Their existing `hero_subjects.people` pools are pruned/removed and the weight moves
  into object/abstract pools.
- **`open_air_festival` gains a `scene` slot** — the first use of the new mode; stops a landscape
  being mislabeled "object."
- **`disco_italo` = people · people · people** (confirmed): three different neon framings drawn
  without replacement (chest-up partygoer / DJ behind decks / model in a neon ring), kept distinct by
  the Jaccard backstop.
- New subject pools to author: `scene` for `open_air_festival`; `abstract_graphic` for
  `liquid_melodic`, `psytrance_goa`, `berlin_minimal`, `brutalist_techno`.

## 6. Code changes

All in `imin-api`. Touchpoints:

1. **`dto/HeroType.java`** — add `SCENE`, `ABSTRACT_GRAPHIC`; extend `wire`/`fromWire`. Keep `ORDER`
   as fallback only, re-documented as such.
2. **`dto/StyleCard.java`** — add fields `humanPolicy` (enum), `humanStyle` (enum), `variantPlan`
   (`List<VariantSlot>` where `VariantSlot` = `{ HeroType mode }`), `heroSubjectsScene`,
   `heroSubjectsAbstract`. Extend `heroSubjectsFor(HeroType)`:
   `PEOPLE→people, OBJECT→object, SCENE→scene, ABSTRACT_GRAPHIC→abstract, TYPOGRAPHIC→empty`.
   New enums `HumanPolicy { REQUIRED, OPTIONAL, RARE, FORBIDDEN }` and
   `HumanStyle { PHOTOGRAPHIC, ABSTRACTED, FIGURE_AS_OBJECT }`.
3. **`service/poster/StyleCardLibrary.parse()`** — parse `human_policy`, `human_style`,
   `variant_plan` (list of `{mode}`), and `hero_subjects.scene` / `hero_subjects.abstract_graphic`.
   Apply the §4.2 defaults when keys are absent. A malformed/unknown mode logs and falls back to the
   legacy plan for that card (loading never throws — existing contract).
4. **`service/ai/CreativeDirectionSampler.sample()`** — iterate `card.variantPlan()` instead of
   `HeroType.ORDER`. For each slot draw `heroSubject` from **that slot's mode pool**. `composition`,
   `accent`, `paletteTwist`, `typeTreatment` stay drawn without replacement across the 3 slots.
   **New:** when a plan repeats a mode (e.g. people·people·people), draw those slots' subjects
   *without replacement* from the mode pool so repeats get distinct framings (group slots by mode,
   shuffle that mode's pool once, deal in order). Determinism preserved — same seed → same draws.
   `CreativeDirection` is unchanged (it already carries `heroType` + `heroSubject`).
5. **`service/AiEventDescriptionService`:**
   - `buildPrompt` / `variantBlock` — emit each variant's role from its plan mode: subject-bearing
     for people/object/scene/abstract_graphic; "typography is the image" for typographic. Inject
     `human_style` wording two ways: (a) `abstracted` conditions any **people-mode** slot ("render
     the figure abstracted — motion-blur / halftone / silhouette, never a clean centered portrait");
     (b) `figure_as_object` adds a **global** rule across all slots ("any human form must be a
     sculpted/rendered material object — chrome, wireframe, posterized — never a photographed
     person"), because these vibes (`acid_rave_y2k`, `brutalist_techno`) carry their human read as an
     object-pool subject rather than a people slot. The strict "people must be photorealistic" line
     applies only when `human_style: photographic`.
   - JSON contract line — `hero_type` enum becomes the 5 values, "exactly matching the per-variant
     role above" (drop "in the order people, object, typographic").
   - `validateRenderable` — `HeroType.fromWire` already accepts the 5 values once the enum grows;
     message updated to list all 5.
   - `validate(concept)` — the core change (see §7).

## 7. Validation: positional → plan/policy driven

`AiEventDescriptionService.validate(concept)` replaces its fixed positional rules:

| Old (hard-coded) | New (plan + policy driven) |
|---|---|
| `heroType[i]` must equal `HeroType.ORDER[i]` | `heroType[i]` must equal `variant_plan[i].mode` |
| variant[0] **must** contain a human (`mentionsHumanSubject`) | every **`people`-mode** slot must contain a human |
| variant[2] **must not** be person-led (`containsHumanHero`) | `forbidden` → no human hero in **any** slot; `rare` → ≤1 slot with a human hero; `optional`/`required` → no upper bound |
| (implicit: exactly one human, in slot 0) | `required` → ≥1 slot mentions a human (guaranteed by a `people` slot) |
| `PromptDistinctness.allPairwiseBelow(prompts, 0.45)` | **unchanged** — paraphrase-collapse backstop |
| aspect/style_type/word-count checks | **unchanged** |

The `HUMAN_NOUNS` / `HUMAN_SUBJECT` / `HUMAN_HERO` / `DESIGN_VOCAB` regexes are kept verbatim; only
their *application* moves from fixed positions to "iterate slots, branch on the slot's mode and the
vibe's `human_policy`." `validate()` therefore needs the `StyleCard` (or at least its policy + plan)
in scope — it already receives the concept; thread the resolved `StyleCard` through alongside it.

## 8. Vision style gate

`OpenRouterPosterStyleValidationClient.validationPrompt(card, declaredHeroType)` already adapts to the
declared hero type (`card.heroSubjectsFor(...)` for pictorial heroes; a "no pictorial hero" branch for
`TYPOGRAPHIC`). Two small changes:

- **`ABSTRACT_GRAPHIC`** joins the `TYPOGRAPHIC` branch — there is no discrete pictorial hero to
  demand (it's a texture/field), so the gate judges medium + palette only and reports
  `heroSubjectPresent:true`.
- **`SCENE`** is a subject-bearing mode like `OBJECT` — `heroSubjectsFor(SCENE)` returns the scene
  pool and the gate demands that pictorial hero.

No change to the gate's request/response shape.

## 9. Persistence & back-compat

- **No DB migration.** `poster_variants.variant_style` is a free `VARCHAR(32)` with no enum/CHECK
  constraint; `"abstract_graphic"` (16 chars) fits. The new wire strings persist as-is.
- `PosterVariantEntity`, audit logging, and `creative_direction_json` are unaffected beyond storing
  the new wire string.
- A style-card lacking the new keys still loads and behaves exactly as today (legacy `ORDER` plan +
  `required` policy), so the change is safe to land incrementally per vibe.

## 10. Risks & mitigations

- **All three variants collapse toward sameness.** With a declarative plan this is structurally
  prevented — the author writes the 3 modes. For repeated-mode plans (`disco_italo`, `acid_rave_y2k`)
  the without-replacement subject draw (§6.4) plus the unchanged Jaccard distinctness check keep the
  variants apart.
- **LLM ignores the plan and returns the wrong `hero_type` order.** Caught by the plan-equality check
  in `validate()`; on failure the existing reinforcement-retry loop (`MAX_ATTEMPTS = 2`) re-prompts,
  then best-effort renders — same degradation path as today.
- **A `forbidden` vibe still sneaks a person into an object slot.** Caught by the new "no human hero
  in any slot" assertion for `forbidden`, applied to all variants (not just slot 2).
- **Mis-authored plan / unknown mode in YAML.** `StyleCardLibrary` logs and falls back to the legacy
  plan for that card; loading never throws.

## 11. Testing

- `CreativeDirectionSamplerTest` — plan-driven iteration; per-mode subject pools; without-replacement
  draws for repeated modes; determinism for a fixed `(card, seed)`; null/empty-pool safety.
- `AiEventDescriptionServiceTest` seams — policy-driven `validate()` for each of the 4 policies
  (forbidden rejects any human; rare caps at 1; required needs ≥1; optional unconstrained); plan
  mismatch rejected; distinctness backstop intact.
- `StyleCardLibraryTest` — parsing of `human_policy`, `human_style`, `variant_plan`, and the two new
  hero-subject pools; defaults when keys are absent; fallback on a malformed plan.
- Style-card YAMLs — author the 12 plans from the §5 table, prune the three human-less vibes' people
  pools, and add the new `scene` / `abstract_graphic` pools.
- Existing render/gate tests stay green (no API/shape change).

## 12. Out of scope

- `poster-lab` harness (separate repo; not being ported).
- `imin-webapp` / `imin-public` (no contract surface change).
- Variant count, rendering speed, R2, text-legibility gate.
