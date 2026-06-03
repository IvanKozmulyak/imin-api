# AI Poster Studio Redesign — Overnight Status (for Ivan, morning of 2026-06-03)

Branch: **`feat/poster-studio-redesign`** (in `imin-api`). Nothing pushed; no PR. Your 142
staged reference images are **left staged and untouched** — I committed only my own files via
explicit pathspecs, so `git diff --cached` is still just your images.

## TL;DR

I reviewed the legacy flow, confirmed *why* it produces bad results, wrote a spec + plan, and
implemented the **safe, non-breaking Phase-1 foundation** with tests. The headline bug (the
entire reference-image system was silently dead) is fixed. I deliberately **did not** ship the
parts that need your decisions/keys or a separate-repo change — those are specified and waiting.

## What I found (verified)

The reference system was conditioning on **nothing**: `poster-references.yaml` pointed at
`reference-images/<f>.png` but the files had been moved to `reference-images/Simple/<f>.png`, so
every tag loaded an empty list → no `style_reference_images` sent, vision analyzer returned `""`,
prompt injected `(no descriptor available)`. The test suite only passed because it ran against a
**stale `target/classes`** that still held the old root-level copies — a clean build (CI/prod)
would have exposed it. (It also caused ~7 swallowed failing network calls to openrouter.ai at
every `@SpringBootTest` startup.) Other causes: gpt-4o-mini writes the whole prompt, no
temperature/JSON controls, always-Turbo tier, sequential generation, and — separately — the
in-product Poster Studio modal in `imin-webapp` is a **100% mock** (Canvas art + fake copy).

Full detail: `docs/superpowers/specs/2026-06-02-ai-poster-studio-redesign-design.md`.

## Decisions you already made (during brainstorming)

End-to-end **hybrid** rebuild: AI makes vibe-matched *art* (conditioned on the curated flyers),
the platform composites *real event text* as real fonts; decorative model-text allowed but never
factual data; **both** server + client rendering via one portable layout document; 12-vibe
library replacing the 7 tags; Recraft-style reusable per-vibe training as the target engine,
Ideogram as fallback.

## What shipped tonight (all green, committed)

1. `fix(poster)` — corrected the 10 reference paths to `Simple/`; gated startup vision analysis
   behind `poster.references.analyze-on-startup` (true prod / false test); guard test asserting
   every tag resolves ≥1 reference so this can't silently recur. **(the #1 fix)**
2. `feat(poster)` — concept LLM **temperature** (0.6); universal **negative_prompt** + configurable
   magic_prompt; **social aspect ratios** 4:5/1:1/9:16/16:9 (portrait default now 4:5).
3. `feat(poster)` — **VibeLibrary**: `vibes.yaml` with the 12 genre-mapped presets, genre→vibe
   auto-suggest + fallback, universal rules. Additive (not yet wired into the live pipeline).
4. `feat(ai)` — **`GET /api/v1/ai/vibes`** catalog for the picker + optional `vibeId` on
   ConceptRequest (validated against VibeLibrary).
5. `docs` — the spec + the full implementation plan.

## Continued 2026-06-03 (after your 4 decisions: keep 12 vibes / IP=conditioning-only / Recraft primary / Satori render)

6. `feat(poster)` — **glob folder references + merge curated vibe flyers**: `ReferenceImageLibrary`
   expands folder locators (capped, deterministic) and merges each curated vibe's flyers keyed by
   vibe id, so `forTag(vibeId)` resolves them. Boot now loads 12 tags / 30 refs.
7. `feat(ai)` — **vibe-driven generation, end-to-end**: a selected `vibeId` is pinned as the
   concept's style tag → the prompt gets the vibe's structured preset (palette/typography/
   composition/avoid) + universal negative + IP rule (no more `(no descriptor available)`), and the
   orchestrator feeds the vibe's curated flyers to Ideogram as `style_reference_images`. This is the
   working "posters that match the curated references" path via Ideogram. Recraft drops into the same
   `ImageProvider` seam once you add a key + train per-vibe styles.

Verification: each commit ran targeted tests green; final `./mvnw clean test` gates the lot
(665+ tests). The clean build is what proves the reference fix (no stale classpath).

## What's still deferred (and why)

- **Recraft provider (P3)** — your chosen primary engine; needs a Recraft API key + a one-time
  per-vibe style-training step (operator action). Today the vibe path runs through Ideogram.
- **Server text compositor + LayoutDocument (P2)** — Satori→SVG→resvg is a Node toolchain; likely
  lives in a Next.js route / sidecar, not imin-api (Java). Needs its own design + repo decision.
- **Frontend rebuild (P4)** — replace the Canvas mock; consume `GET /ai/vibes`, send `vibeId`, live
  text editing. Separate repo (`imin-webapp`); contract is already additive.
- **Variant parallelization** — Hibernate-session threading subtleties; wants a reviewed change +
  a real run with keys. (Today: ~30–60s sequential.)
- **Recraft provider, server text-compositor + LayoutDocument, the frontend rebuild** — P2–P5,
  need keys / a render-stack decision / the other repo. Outlined in the plan.

## Open questions waiting on you (defaults chosen, see spec §9)

1. Which of the 7 uncurated vibes to curate (3–5 flyers each) vs cut.
2. IP stance: OK to use the scraped flyers purely as style conditioning (not shipped/attributed)?
3. Recraft vs Ideogram-direct vs Nano Banana Pro as day-one primary (needs your keys).
4. Server render stack: extend Java2D vs Satori/SVG→resvg (Cyrillic/i18n).
5. Decorative model-text always Latin/abstract (real Cyrillic text = overlay)?

## How to review

```
cd imin-api && git checkout feat/poster-studio-redesign
git log --oneline master..HEAD      # the commits
git diff master..HEAD -- . ':!**/reference-images/**'   # code/doc diff, minus your images
./mvnw clean test                    # full suite
```

Design mockups (architecture + per-vibe layout templates) are saved under
`.superpowers/brainstorm/` at the workspace root.
