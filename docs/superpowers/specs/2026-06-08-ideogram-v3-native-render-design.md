# Ideogram V3 native render flow — design

- **Date:** 2026-06-08
- **Status:** Approved (brainstorming complete)
- **Component:** `imin-api` poster generation
- **Supersedes (render layer):** `2026-06-04-reference-first-poster-quality-design.md` (Recraft reference-first path)

## Goal

Turn structured event data into a finished poster image with the event's text **baked
in by the image model**, in a chosen visual "vibe," reliably and on-brand — using the
native **Ideogram V3** API as the sole renderer.

One-line shape:

```
event data
  → exact-text contract + seeded creative direction
  → Sonnet 4.6 art director (3 hero-typed variants)
  → render each via NATIVE Ideogram V3 generate (prompt verbatim + vibe reference
    images, ONE style control, 4:5, Turbo, magic_prompt OFF, copyright ON, seed)
  → download
  → vision text gate (HARD) + style gate (SOFT)
  → on text-gate fail: corrective REMIX of the failing image (prompt = what's wrong)
  → accept / accept best-effort
  → persist
```

## Context: what already exists

imin-api already implements most of this flow; this is a **render-layer replacement**,
not a rebuild.

Already present and **kept**:

- **Stage-1 art director** — `AiEventDescriptionService` calls Claude Sonnet 4.6 via
  OpenRouter (`openrouter.model=anthropic/claude-sonnet-4.6`, temp 0.6), emits 3
  variants in `people` / `object` / `typographic` order with the v2 prompt anatomy,
  and validates the concept (hero order, distinctness, human-noun rule, required text).
- **Seeded creative-direction sampler** — `CreativeDirectionSampler` + `StyleCardLibrary`
  (`vibes/style-cards/*.yaml`: compositions / accents / type_treatments / palette_twists /
  hero_subjects / example_prompts).
- **Exact-text contract** — `PosterTextSpecFactory` / `PosterTextSpec` (`required` vs
  `allowed`, date formatted `d MMM yyyy` upper-cased). One source of truth for the
  prompt block and the text-gate checklist.
- **Vision gates** — `PosterTextValidationService` + `OpenRouterPosterTextValidationClient`
  (hard), `PosterStyleValidationService` + `OpenRouterPosterStyleValidationClient` (soft).
  Both currently **disabled** by default.
- **Vibe library + reference images** — `VibeLibrary` (`vibes.yaml`), `ReferenceImageLibrary`
  (curated flyers under `resources/reference-images/<vibe>/`), `ReferenceImageAnalyzer`
  (vision → NL style descriptor injected into the art-director prompt).
- **Persistence** — `poster_generations` / `poster_variants`, `PosterImageStorage`
  (Cloudflare R2 with local-disk fallback).

The render layer is the gap: today `PosterOrchestrator` renders via **Recraft** (default),
**Replicate→Ideogram** (`IdeogramClient` over `ReplicateClient`), or **OpenAI**, then
composites a QR + address band (`OverlayCompositor`) and an optional Satori text layer
(`PosterTextCompositorClient`).

## Decisions (locked)

1. **Sole renderer = native Ideogram V3.** Remove the Recraft, Replicate→Ideogram, and
   OpenAI image paths and their config. No provider switch.
2. **All text on the model.** Remove the QR + address band overlay and the Satori
   compositor. The downloaded Ideogram PNG is the final image.
3. **Vision gates on.** Text gate **hard** (default on), style gate **soft** (default on),
   validator `openai/gpt-4o-mini` via OpenRouter.
4. **Text-gate failure → corrective remix**, not reseed-from-scratch. Feed the failing
   image back to Ideogram's remix endpoint with a prompt describing exactly what is wrong
   (derived from the gate's `missingRequired` / `extraText`), preserving the composition.

## Architecture

### Request flow (after change)

```
POST /api/v1/ai/events/concept  (ConceptController → ConceptStudioService)
  → AiEventDescriptionService.generateConcept()      [Sonnet 4.6 → PosterConcept, 3 variants]   (unchanged)
  → PosterOrchestrator.run()
      for each variant (parallel, Semaphore-bounded):
        attempt 0: IdeogramV3Client.generate(prompt, styleControl, seed)   → image₀
        text gate (HARD) on image₀
          pass → style gate (SOFT) → accept (or accept best-effort if style fails)
          fail → build correction prompt from gate output
                 IdeogramV3Client.remix(failingImage, correctionPrompt, styleControl, imageWeight, nextSeed) → imageₙ
                 re-validate; loop up to poster.validation.max-regenerations
                 on exhaustion → accept best-effort (verdict BEST_EFFORT)
      persist verdict + per-attempt journal
  → ConceptResponse (poster URLs)   (response shape UNCHANGED)
```

No QR / address / Satori step. `final_url == raw_url` (the render output).

### New component — `IdeogramV3Client`

Replaces the Replicate-based `IdeogramClient`. Built on a dedicated `RestClient`
(`IdeogramImageConfig`, base `https://api.ideogram.ai`, `Api-Key` header — mirrors
`RecraftImageConfig`'s fail-fast-on-missing-key pattern).

**`generate(...)`** → `POST /v1/ideogram-v3/generate`, `multipart/form-data`:

| part | value |
|---|---|
| `prompt` | the variant's render prompt, **sent unchanged** |
| `aspect_ratio` | `4x5` (native uses `NxM`, not `N:M`) |
| `rendering_speed` | `TURBO` (configurable) |
| `magic_prompt` | `OFF` (art director already wrote the prompt) |
| `enable_copyright_detection` | `true` |
| `seed` | per-variant seed |
| style control | **exactly one** — see below |

**`remix(...)`** → `POST /v1/ideogram-v3/remix`, `multipart/form-data`: same fields plus
- `image` (binary, ≤10 MB, JPG/PNG/WebP) — the failing image to fix
- `image_weight` (0–100, default config ~70) — hold composition, free the text
- (no `enable_copyright_detection`; not part of the remix contract)

Response (both): `data[0].url` → **download bytes immediately** (link expires).
No `negative_prompt` is sent (the art-director rules carry the "avoid" list; diffusion
negatives were observed to make the model drop people).

### Style control — exactly one channel

Ideogram V3 accepts **`style_reference_images` OR `style_preset` OR `style_codes`,
never combined** (400 otherwise).

- **Reference images win.** If the vibe has curated flyers → attach **1–3** as repeated
  `style_reference_images` binary parts, **≤10 MB total**, JPG/PNG/WebP.
- **Preset is the fallback** — only when a vibe has no reference images. `VibeLibrary`
  exposes a per-vibe `ideogramStylePreset(id)` parsed from a new `ideogram_style_preset`
  key in `vibes.yaml` (ported from poster-lab, e.g. `brutalist_techno → HIGH_CONTRAST`).
  Kept off the `Vibe` record to avoid touching unrelated `new Vibe(...)` call sites. All
  12 current vibes have references, so the preset is a safety net.

`ReferenceImageLibrary` gains an accessor returning the **top-3 references as raw parts**
(`{bytes, mime, filename}`), sorted by filename, capped at 10 MB total.

### Corrective remix loop (text gate)

The text gate already returns structured `{accepted, missingRequired[], extraText[]}`.
On failure the orchestrator assembles a **correction prompt**:

```
<original render prompt>

CORRECTION — the previous render had text errors. Render these exact strings,
correctly spelled and clearly legible: "<missing/required …>".
Remove these invented or garbled words: "<extraText …>".
Keep the composition, hero, colors, and layout identical — only fix the text.
```

It calls `IdeogramV3Client.remix(failingImageBytes, correctionPrompt, styleControl,
imageWeight, nextSeed(seed))`, re-validates (text hard → style soft), and loops up to
`poster.validation.max-regenerations` (default 2). On exhaustion the latest image is
accepted **best-effort** and recorded as such. The **style gate stays soft**: on failure
it accepts best-effort with a recorded reason (no remix).

### Removals (render-path only)

Scope is bounded to the **render path**. Investigation found that `ImageProvider` also
backs a separate Recraft **style-training** subsystem off the render path, and the
orchestrator selects a provider only via `request.effectiveImageProvider()`. So once the
orchestrator stops branching on provider, the provider/training code can be left
**dormant** — no contract change, minimal blast radius.

**Delete** (used only by the render path being replaced):

- old `IdeogramClient` (Replicate) + `ReplicateClient` (image predictions; only the old
  `IdeogramClient` uses it).
- `OpenAiImageClient` + `OpenAiImageConfig`.
- `OverlayCompositor`, `PosterTextCompositorClient`, `PosterCompositorConfig`.
- Their tests: `IdeogramClientTest`, `OpenAiImageClientTest`, `OverlayCompositorTest`,
  `PosterTextCompositorClientTest`.

**Keep dormant** (not on the render path; full purge is a documented follow-up that would
also need to retire the `POST /api/v1/ai/vibes/{vibeId}/train-style` endpoint — the webapp
does not call it):

- `ImageProvider` enum, `VibeStyle` + `VibeStyleRepository`, `VibeStyleTrainingService` +
  `VibeStyleTrainingController`, `RecraftClient` + `RecraftImageConfig`.
- `EventCreatorRequest.imageProvider` / `effectiveImageProvider()` and
  `ConceptStudioService.providerFor()` — still compile; the rewired orchestrator simply
  never reads the provider. `poster.provider-routing.*` becomes a no-op for rendering.
- The `Vibe` record is unchanged (Recraft-only fields stay dormant). The Ideogram preset
  fallback is added as a `vibes.yaml` `ideogram_style_preset` key + a `VibeLibrary`
  accessor, not a record component.

### Config (`application.yaml`)

**Add**

```yaml
ideogram:
  api-key: ${IDEOGRAM_API_KEY:}
  base-url: ${IDEOGRAM_BASE_URL:https://api.ideogram.ai}
  rendering-speed: ${IDEOGRAM_RENDERING_SPEED:TURBO}
  copyright-detection: ${IDEOGRAM_COPYRIGHT_DETECTION:true}
  style-mode: ${IDEOGRAM_STYLE_MODE:refs}      # refs | preset
  max-references: ${IDEOGRAM_MAX_REFERENCES:3}
  remix:
    image-weight: ${IDEOGRAM_REMIX_IMAGE_WEIGHT:70}
```

**Flip**

```yaml
poster:
  text-validation:  { enabled: ${POSTER_TEXT_VALIDATION_ENABLED:true} }
  style-validation: { enabled: ${POSTER_STYLE_VALIDATION_ENABLED:true} }
```

**Remove** (now-unused render-path keys): `replicate.models.ideogram-*`, `poster.overlay.*`,
`poster.compositor.*`, `poster.generation.magic-prompt`, `poster.generation.negative-prompt`.

**Keep**: `poster.validation.max-regenerations`; `replicate.image.storage-dir` (used by
`PosterImageStorage` local fallback + `WebConfig`) and `replicate.max-concurrent` (the
render semaphore — read with an added `poster.render.max-concurrent` alias); `recraft.*`
and `poster.provider-routing.*` (dormant, feed the kept training subsystem / no-op).

### Persistence

`poster_generations` / `poster_variants` already store the render prompt
(`ideogram_prompt`), seed, `reference_images_used`, `raw_url`, `final_url`, status,
`ideogram_cost_eur`, `failure_reason`. **Add migration `V37__poster_validation_verdict.sql`:**

- `poster_variants.validation_verdict` VARCHAR(16) NULL — `ACCEPTED` | `BEST_EFFORT`
- `poster_variants.validation_attempts_json` TEXT NULL — per-attempt journal: `[{attempt,
  seed, mode: generate|remix, text:{accepted,reason}, style:{accepted,reason}}]`

`final_url` is set to the render output (no overlay).

## Contract / cross-repo impact

- **No contract change at all.** `ConceptResponse` / `GeneratedPoster` (poster URLs +
  label) are identical, and the request DTO is unchanged (`imageProvider` is kept dormant).
  `imin-public` and `imin-webapp` need no change and no `api:sync` is required.

## Testing

TDD. External calls mocked (`MockRestServiceServer` / WireMock; OpenRouter + Ideogram
stubbed) per project test conventions.

- `IdeogramV3Client`: multipart field names + values (`magic_prompt=OFF`, `aspect_ratio=4x5`,
  `Api-Key` header); one-style-control branching (refs vs preset, never both; >3 refs and
  >10 MB capped); `generate` vs `remix` (image part present, `image_weight`); response
  `data[0].url` download; expired-URL/error handling.
- `PosterOrchestrator`: text-gate fail → remix with correction prompt built from gate
  output → re-validate → accept; budget exhaustion → `BEST_EFFORT` + journal persisted;
  style-gate soft accept; refs-empty vibe → preset path.
- `ReferenceImageLibrary`: top-3 parts, 10 MB cap, mime/filename.

## Rollout

- New env `IDEOGRAM_API_KEY` (local `.env` + Railway). App warns at startup if unset and
  fails the render with a clear message (matches `RecraftImageConfig`).
- Single backend deploy; no frontend coordination required (response unchanged).

## Out of scope

- The optional **critic vision pass** (legibility/balance scoring + prompt patches) — a
  seam is left in the orchestrator; not built now.
- Reintroducing any post-render compositor (QR / address / real-font).
