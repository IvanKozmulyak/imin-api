# AI Poster Studio — Redesign Design

Date: 2026-06-02
Status: Draft (approved direction; open product/vendor questions pending — see end)
Authors: Ivan + Claude (brainstorming session)
Scope: `imin-api` (primary) + `imin-webapp` (Poster Studio frontend)

---

## 1. Problem

The AI poster generator "is fully legacy and doesn't generate good results." A deep
investigation (verified against the source + filesystem) found the output is poor for
concrete, compounding reasons — not vibes-and-feelings:

1. **The entire reference-image system is silently dead.** `poster-references.yaml`
   points every locator at `reference-images/<file>.png`, but those files actually live
   one folder deeper, in `reference-images/Simple/<file>.png`. `ReferenceImageLibrary`
   catches the per-file load failure as a *warning* and registers each style tag with an
   **empty** reference list. Consequences at runtime: (a) **zero** `style_reference_images`
   are sent to the image model for any tag; (b) the vision analyzer is called with an empty
   list, returns `""`, and the concept prompt injects the literal string
   `"(no descriptor available)"`. Both the image-conditioning path *and* the
   prompt-seeding path are dead. The system *looks* fully wired but conditions on nothing.
2. **A cheap model does all the art direction.** `gpt-4o-mini` writes the *entire* verbatim
   image prompt — there is no deterministic prompt template wrapping it. Small/cheap model →
   generic, templated prose → bland posters, even if references worked.
3. **No LLM generation controls.** `OpenRouterConfig` sets only the model — no temperature
   (defaults high → noisy/inconsistent), no JSON `response_format`, no seed.
4. **User intent is erased before generation.** `ConceptStudioService.toLegacyRequest`
   hard-codes `tone=energetic, genre=Techno, city=Berlin, platforms=[instagram]` and discards
   `title/venue/date/price`. The model invents a fictional event and bakes fictional text in.
5. **Lowest tier, sequential, no safety net.** Every poster uses Ideogram V3 **Turbo**
   (the `quality` tier method exists but has zero callers); the 3 variants render
   sequentially on the request thread (the `Semaphore(6)` is inert because single-threaded);
   `style_type` is silently downgraded to `Auto` whenever refs are present; `magic_prompt`
   is `Off` and no `negative_prompt` is sent; the ADR's promised OCR-retry was never built.
6. **The in-product Poster Studio is a 100% mock.** `imin-webapp`'s
   `postersApi.generatePosters` sleeps ~1.3s and returns fixed style keys; posters are drawn
   entirely client-side with a deterministic Canvas-2D renderer using fake placeholder copy
   (`Subterrane / 200 CAP / LA STATION`). It never calls the backend. Part of "bad results"
   is users judging a mock.
7. **The library taxonomy is mismatched.** The 7 *code* tags are aesthetics, but the **132
   curated flyers on disk** are organized by *music genre* (`Berlin Minimal`,
   `Brutalist Techno`, `Acid Rave - Y2K`, `Industrial - Hard Groove`, `Psytrance - Goa`,
   `Simple`) and are referenced by **nothing**. 5 of 7 tags also had only a single reference
   image even before the path bug.

## 2. Decisions (this session)

- **Hybrid two-layer architecture.** The image model generates *vibe-matched art*; the
  platform composites *real event text* (title, date, venue, lineup, price, QR, address) as
  real fonts. This reverses ADR-0001's "typography is the image" and eliminates the
  ~5–10% misspelled-text rate. The model **may** render *decorative/atmospheric* lettering
  (repeated word-echoes, sideways labels, code blocks) — never the factual event data.
  (User decisions: hybrid = yes; decorative art-text allowed = yes.)
- **Reference images + structured presets, together.** Each vibe is both a hand-authored
  structured preset *and* backed by curated reference images used to condition the model
  (e.g. a Recraft reusable trained style, or Ideogram `style_reference_images`).
  (User decision: "Both: presets + image conditioning.")
- **Model choice optimized for "match our flyers."** Primary engine = a reusable
  per-vibe trained style (Recraft V3 `style_id`); Ideogram 3.0 retained as fallback; a
  SOTA model (Nano Banana Pro / Recraft) for premium finals. (User decision.)
- **12-vibe library** mapped to music genres with auto-suggest + fallback, replacing the
  7 broken aesthetic tags. (From Ivan's "AI Studio Poster Style Base" draft.)
- **End-to-end rebuild** including a real frontend flow replacing the Canvas mock.
  (User decision.)
- **Both server-side and client-side text rendering**, sharing a single **portable layout
  document** (a small JSON scene-graph). (User decision.)

## 3. Target architecture

```
Organizer brief (title, date, venue, lineup, price, QR/RSVP) + chosen Vibe
        │  (Vibe auto-suggested from music genre; overridable)
        ▼
[2] Prompt assembly (deterministic, server-side)
     • a strong LLM emits ONLY structured scene fields (subject/motif, layout intent,
       color emphasis) — NOT the whole prompt
     • server assembles the final art prompt from the Vibe preset
       (visualStyle · palette · typography-hints · composition · mood · avoid)
       + universal negative prompt + IP guardrail + "leave safe zones / no factual text"
        ▼
[3] ART LAYER  (stochastic — matches the curated reference flyers)
     • primary: Recraft V3 with the Vibe's reusable trained style_id
     • fallback: Ideogram 3.0 (style_reference_images) ; premium: Nano Banana Pro
     • output: vibe-matched art (decorative lettering allowed), in the 4 aspect ratios,
       with factual-text safe zones kept clear
        ▼
[4] TEXT LAYER  (deterministic — guaranteed-correct, real fonts)
     • a per-Vibe LAYOUT TEMPLATE places title/date/venue/lineup/price + QR + address
     • rendered from a portable LAYOUT DOCUMENT (JSON scene-graph) by BOTH:
         – server renderer (canonical export asset)
         – client renderer (live editing in the webapp)
     • i18n-safe (Ukrainian/Cyrillic), brand fonts, exact spelling
        ▼
[5] Output: composited posters in 4:5 · 1:1 · 9:16 · 1.91:1 → R2/object storage → CDN
```

Cross-cutting: async generation job + progress (variants in parallel); pinned model
versions; cost ledger (built but unused today); IP guardrail + universal negative prompt
on every call.

### 3.1 Component map (backend)

| Concern | Today | Target |
|---|---|---|
| Vibe definitions | `AiEventDescriptionService.VALID_SUB_STYLE_TAGS` (7 hard-coded) + `poster-references.yaml` | **`VibeLibrary`** component reading **`vibes.yaml`** (12 structured presets + genre map + universal rules) |
| Reference images | `ReferenceImageLibrary` (paths broken; analyzes at boot) | `ReferenceImageLibrary` fixed; analysis opt-in/async + test-safe; refs wired to curated genre folders |
| Prompt building | `AiEventDescriptionService.buildPrompt` (LLM writes whole prompt) | LLM emits structured scene fields; **deterministic assembler** builds final prompt from Vibe preset |
| Image generation | `IdeogramClient` (turbo only, style_type forced Auto) + `OpenAiImageClient` | `ImageProvider` seam + **`RecraftClient`** (primary) + Ideogram (fallback, quality tier, Design) |
| Overlay | `OverlayCompositor` (Java2D, QR + address only) | **`PosterCompositor`** renders full text layer from a layout document; QR+address remain pixel-exact |
| Orchestration | `PosterOrchestrator` (sequential) | parallel variants; async job + status; (optional) OCR/quality gate |
| Storage | `PosterImageStorage` (local disk) | R2 via existing `imin.media.*` seam |

## 4. The Vibe Library

Source of truth: a new `src/main/resources/vibes.yaml`, loaded by `VibeLibrary`.

Each **Vibe** =:

```yaml
id: brutalist_techno
name: "Brutalist Techno"
genres: [techno, hard techno, warehouse, peak-time]
visual_style: "raw exposed concrete, industrial warehouse, harsh single-source lighting…"
palette: ["#0A0A0A", "#1C1C1C", "#E8E8E8", "#FF2D00"]
typography: "oversized condensed grotesk, all caps, tight tracking, brutalist grid"
composition: "giant centered/top headline, lots of empty space, single dominant texture"
mood_tags: [raw, severe, monolithic, cold, dark]
avoid: [color gradients, soft lighting, decoration, warmth]
model_route: recraft          # routing hint
references: [reference-images/Brutalist Techno]   # folder or explicit files
style_id: null                # Recraft trained style id, filled once trained
layout_template: brutalist    # which text-layout template to use
```

The 12 vibes (from Ivan's draft): `brutalist_techno`, `berlin_minimal`, `acid_rave_y2k`,
`psytrance_goa`, `industrial_hard_groove`, `liquid_melodic`, `hyperpop_club`, `dnb_jungle`,
`afro_amapiano`, `open_air_festival`, `disco_italo`, `dark_experimental`.

- **Genre → Vibe auto-suggest map** (and fallback `liquid_melodic` when no match).
- **Universal rules** (config): 4 aspect ratios `[4:5, 1:1, 9:16, 1.91:1]`; the universal
  negative prompt; the hard IP rule (never "in the style of \<real artist/label/club\>",
  no real venues/brands/characters; keep descriptions generic).
- **Reference coverage today:** 5 of 12 vibes map to existing curated folders
  (`brutalist_techno`, `berlin_minimal`, `acid_rave_y2k`, `industrial_hard_groove`,
  `psytrance_goa`, 17–37 imgs each). The remaining 7 ship with full structured presets but
  **no reference images yet** — they run text-only until curated (tracked as an open item;
  see §9). `VibeLibrary` marks these explicitly so the startup guard does not treat empty
  references as a bug for them.

## 5. Text layer — portable layout document

A vibe's `layout_template` resolves to a **LayoutDocument**: a small, renderer-agnostic
JSON scene-graph describing each text slot:

```jsonc
{
  "aspect": "4:5",
  "elements": [
    { "role": "title",  "anchor": "top-right", "box": [0.40,0.09,0.55,0.24],
      "font": "vibe.display", "case": "upper", "color": "vibe.accent", "align": "right" },
    { "role": "meta",   "anchor": "bottom-left", "box": [0.05,0.78,0.52,0.13],
      "lines": ["date","venue","lineup"], "font": "vibe.mono", "color": "vibe.fg" },
    { "role": "qr",      "anchor": "bottom-right", "box": [0.78,0.80,0.17,0.14] },
    { "role": "address", "anchor": "bottom",       "box": [0.05,0.94,0.90,0.05] }
  ]
}
```

- **Per-vibe, per-element** decision of art vs overlay is encoded here (e.g. Psytrance's
  warped title is left to the art layer; its lineup band is an overlay).
- **Two renderers, one document:**
  - **Server**: produces the canonical export. Decision pending (§9) — extend the existing
    Java2D `OverlayCompositor`, or adopt an HTML/CSS→SVG→raster stack (Satori + resvg) for
    richer layout/kerning/i18n. Recommendation: SVG stack for Cyrillic + rich layout.
  - **Client**: the webapp renders the same document for **live editing** (port the
    existing competent Canvas-2D renderer, currently driven by fake copy, to consume the
    real LayoutDocument + event data).
- QR + address keep their pixel-exact treatment (zxing QR; truncated address band).

## 6. API contract (backward-compatible evolution)

- `ConceptRequest` today: `{ vibe (free text 10–500), genre?, city?, capacity? }`.
- Add optional `vibeId` (one of the 12). When present it selects the preset directly; when
  absent we keep auto-suggest from `genre`, then fall back to free-text `vibe`. **No breaking
  change** to the existing field; the frontend migrates to send `vibeId` once the picker
  ships. (Cross-repo contract change is additive — honoring the synchronized-changes rule.)
- New (later phase): async surface — `POST /ai/events/concept` returns a job id +
  `GET …/status` (SSE/poll), so variants parallelize and a quality step can be inserted.
  Today's synchronous shape is retained until the frontend is ready.
- A new endpoint to fetch the **vibe catalog** for the picker:
  `GET /api/v1/ai/vibes` → `[{id, name, genres, swatch, sampleUrl?}]`.

## 7. Data model

- `style_reference_analysis` table: reused; descriptor becomes optional enrichment.
- New: persist the chosen `vibe_id` and the **LayoutDocument** JSON on the generation /
  variant rows so re-issue and client editing are reproducible. (Flyway forward migration.)
- `ideogram_cost_eur` ledger column exists but is unpopulated — wire cost tracking when the
  provider is finalized.

## 8. Phasing (see the implementation plan for task detail)

- **P1 — Foundation (non-breaking, internal, tested).** VibeLibrary + `vibes.yaml` + genre
  map + universal rules; fix reference wiring + make startup analysis test-safe/async + add a
  guard test; LLM controls (temperature + JSON mode) + deterministic prompt assembly from
  presets + feed real event data; stop forcing `style_type=Auto`; send negative prompt; route
  the quality tier; 4 aspect ratios; parallelize variants.
- **P2 — Text layer.** LayoutDocument schema + per-vibe templates; server renderer; QR +
  address migrated onto it; persist the document.
- **P3 — Model engine.** `RecraftClient` behind the `ImageProvider` seam; per-vibe style
  training workflow; provider routing from `model_route`; cost ledger; pinned versions.
- **P4 — Frontend.** Replace the Canvas mock: real vibe picker (catalog endpoint),
  generate→progress, live text editing via the shared LayoutDocument, export, upload.
- **P5 — Quality + async.** Async job + progress; optional art-quality/NSFW gate; re-issue.

## 9. Open questions (defaults chosen so work can proceed; confirm in the morning)

1. **Reference-library curation for the 7 vibes with no images.** Default: ship them
   text-only (structured preset, empty references) and curate ~3–5 reference flyers per vibe
   later. Alternative: drop vibes we won't curate. *Decision needed: which of the 7 to curate
   vs cut.*
2. **IP stance vs. scraped references.** Your draft's IP rule forbids "in the style of \<real
   artist\>", but the curated flyers are scraped real designs. Default: use them only as
   *style conditioning* (Recraft trained style / style refs), never name sources, keep prompts
   generic — treated as internal style anchors, not shipped or attributed. *Confirm acceptable.*
2b. **Recraft vs Ideogram-direct vs Nano Banana Pro as the day-one primary.** Default:
   stand up `RecraftClient` behind the seam (reusable per-vibe style is the best
   "looks-like-our-refs" mechanism) but keep Ideogram as the default until a Recraft key +
   trained styles exist. *Needs your account/keys to go live.*
3. **Server text-render stack:** extend Java2D vs Satori/SVG→resvg. Default: SVG stack
   (better Cyrillic + layout). *Confirm; adds a dependency.*
4. **Async vs sync generation.** Default: keep sync for P1; move to async job in P5.
5. **Decorative model-text language.** Should the model's decorative lettering ever be in
   Ukrainian, or always Latin/abstract? Default: Latin/abstract decorative only; all
   real/Cyrillic text is overlay.
6. **Variant count & cost envelope** per generation (today 3). Default: keep 3.

## 9b. Decisions resolved (2026-06-03)

- **Q1 Coverage:** keep all 12 vibes; the 7 without curated flyers ship text-only (preset-driven)
  and get reference images curated over time. No vibe is hidden.
- **Q2 IP:** curated flyers are used **only** as private style conditioning (trained style /
  style refs); outputs are original; sources are never named, shipped, or attributed.
- **Q2b Engine:** **Recraft V3** (reusable per-vibe trained `style_id`) is the day-one primary;
  Ideogram 3.0 is the fallback. Requires a Recraft API key + a one-time per-vibe training step
  (operator action). Until trained, the Ideogram fallback conditions via `style_reference_images`.
- **Q3 Render stack:** **Satori → SVG → resvg** server-side for the text layer (rich layout +
  Cyrillic/i18n), sharing markup with the client renderer.

Still open: Q5 (decorative model-text language) — see below / asked separately.

## 10. Non-goals

- Organizer-uploaded brand kits / custom reference images (future).
- Replacing the QR/address exactness model (kept as-is).
- Migrating the legacy `/api/events/ai-create` demo endpoint (left in place; new work targets
  `/api/v1/ai/...`).
- Per-aspect smart-crop beyond native multi-ratio generation.

## 11. Verified-safe vs. needs-input (for overnight autonomy)

- **Safe to build now (no keys, non-breaking, tested):** VibeLibrary + config + genre map;
  reference wiring fix + test-safe startup + guard; LLM controls + deterministic prompt
  assembly + real-event-data; aspect ratios; parallelization; vibe catalog endpoint.
- **Needs your input/keys (scaffolded + planned, not shipped live tonight):** RecraftClient
  live calls (account/keys), per-vibe style training, the server SVG render stack choice,
  the frontend rebuild (separate repo, contract follows), and the 7 uncurated vibes' images.
