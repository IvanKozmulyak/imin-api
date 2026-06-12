# Per-event DJ photo on AI posters — design

**Date:** 2026-06-12
**Status:** Draft — pending review
**Repos affected:** `imin-api` (contract owner), `imin-webapp` (ships together per cross-repo rule)

## Summary

Organizers can upload one DJ photo per event. When the photo is present, all three
AI poster variants feature that DJ as the hero of the composition, rendered from the
photo via Ideogram V3 **character reference**, while the brand-book colour palette
continues to govern the colour story. When no photo is uploaded, generation is
unchanged.

## Goals

1. Per-event DJ photo upload/replace/remove (organizer dashboard).
2. When set, all 3 variants use the DJ as the main concept — likeness comes from the
   photo, not from prompt description.
3. Brand palette (lead + supporting accent colours) still visibly dominates each poster.
4. No regression for events without a DJ photo; the photo can never hard-fail a
   generation.

## Non-goals

- Multiple DJ photos / multi-artist posters (one photo, one hero).
- Client-side or Java2D compositing of the photo onto the poster — everything stays
  in-model (established direction: no overlays).
- DJ photo on the freeform `AiStudioPage` flow (`/events/new/ai`) — it has no event id.
- Public API (`/api/v1/public/...`) changes — none needed.
- Face detection / photo content moderation in v1.
- Per-variant Ideogram cost booking (`poster_variants.ideogram_cost_eur` is not
  populated by the existing flow either; cost tracking is a separate follow-up).

## Ideogram facts and the P0 probe

**Confirmed in the API reference** (developer.ideogram.ai):

- `POST /v1/ideogram-v3/generate` and `/remix` both accept
  `character_reference_images`: multipart file, **max 1 image**, JPEG/PNG/WebP,
  ≤10 MB total.
- `style_preset` exists on both endpoints.
- Character-reference pricing: TURBO $0.10, DEFAULT $0.15, QUALITY $0.20 per image
  (vs $0.03/$0.06/$0.09 base).

**Documented only in the product UI docs, NOT in the API reference:** with a character
reference active, Colour Palette, Seed, Negative Prompt, and Style Reference are
"unavailable". Whether the **API** rejects, ignores, or honours these params alongside
`character_reference_images` is undocumented.

**P0 probe (first implementation step, before any client code):** ad-hoc calls against
the real API — char ref alone, then + `color_palette`, + `seed`, + `style_preset`,
+ `style_reference_images`, one addition at a time. Decision matrix per param:

| Probe outcome | DJ-mode behaviour |
|---|---|
| 4xx rejected | omit the param (config default OFF) |
| accepted and visibly honoured | keep sending it (config default ON) |
| accepted but ignored | keep sending it (harmless), rely on prompt channel |

The client gates each param behind config flags (e.g.
`ideogram.character.color-palette`, `ideogram.character.seed`,
`ideogram.character.style-control`) whose defaults are set from the probe results, so a
later Ideogram behaviour change is an env tweak, not a deploy. The **prompt-channel
palette enforcement (§5) ships regardless of the probe outcome** — it is the guaranteed
channel.

## Approaches considered

**A. Character reference on generate + remix (chosen).** Likeness handled natively by
the model; composition, typography, and the text contract stay fully in-model,
consistent with the single-renderer pipeline and gates. Trade-off: `color_palette` and
curated style refs are possibly unavailable in DJ mode → palette fidelity rests on the
prompt block + the style gate as the metric.

**B. Remix with the DJ photo as base image (`image_weight` ~60–80).** Composition is
driven by the photo, which fights poster layout and the text contract; likeness is less
reliable than character reference. Rejected as the primary path; remix remains the
corrective mechanism it is today (with the character ref re-attached).

**C. Cut out the DJ and composite with Java2D (like the logo).** Keeps `color_palette`,
but produces a pasted-on look, breaks the in-model-everything direction, and the model
can't light/integrate the figure. Rejected.

## Design

### 1. Data model (`V39__event_dj_photo.sql` — V38 is the current latest)

```sql
ALTER TABLE events ADD COLUMN dj_photo_url TEXT;             -- nullable R2 URL
ALTER TABLE poster_generations ADD COLUMN dj_photo_url TEXT; -- snapshot at generation time
```

`poster_generations.dj_photo_url` records what the run actually used. Unlike
`brand_snapshot` (write-only audit today), this snapshot **is read back** by
`regenerate` (§3) so regeneration reproduces the original mode even if the photo was
replaced or removed in between. Brand resolution on regenerate stays live (existing
behaviour, unchanged).

### 2. Upload API — extend event media

Reuse the existing event-media surface: add `DJ_PHOTO("dj-photo")` to `MediaKind`
(today only `POSTER`, `VIDEO`; `fromWire` throws `IllegalArgumentException` → 500 for
unknown kinds — targeted improvement: map that to a 404/`FIELD_INVALID` while here).

- `POST /api/v1/events/{eventId}/media/dj-photo` — multipart part `file`.
  Validation in `MediaUploadService.validate()` (which today checks only size,
  content-type, magic bytes — the dimension logic lives in `OrgMediaService`; extract a
  shared dimension-check helper or replicate the `ImageIO` pattern):
  - ≤ 5 MB,
  - content types `image/png`, `image/jpeg` only (**no WebP**: JDK `ImageIO` cannot
    decode it without a new dependency; Ideogram is fine with JPEG/PNG),
  - magic bytes (PNG `89 50 4E 47`, JPEG `FF D8`), ImageIO decode must succeed,
  - short side ≥ 256 px (our quality bar — Ideogram documents no floor; product docs
    recommend a clear, well-lit, portrait-style face),
  - ownership via the existing `loadOwned` pattern (cross-org → `NOT_FOUND`).
  - Bytes stored as uploaded (no re-encode). R2 key
    `events/{eventId}/dj-photo-{sha256prefix8}.{ext}`; sets `events.dj_photo_url`.
  - Response: the existing `MediaUploadResponse` (`url`, `sizeBytes`, `contentType`) —
    no new DTO; the FE reads `url`, exactly like the poster tile.
- `DELETE /api/v1/events/{eventId}/media/dj-photo` — nulls the column, best-effort
  deletes the R2 object, 204. (Old `poster_generations.dj_photo_url` snapshots may then
  point at a deleted object — accepted, consistent with how deleted poster media
  behaves today.)
- `EventDto` gains nullable `djPhotoUrl` — update both the `summary()` and `detail()`
  static factories and their tests (Java record: every constructor site must change).

Errors use the standard envelope (`FIELD_INVALID` with a `fields.file` reason),
matching logo upload. Draft-status events are reachable via `findActive` (the poster
tile relies on this today), so wizard-stage uploads work unchanged.

### 3. Concept API — bind generation to the event

`ConceptRequest` gains optional `eventId: UUID`. **Java-record breakage is accepted**:
update every constructor call site — `ConceptStudioService.regenerate()` (builds a
fresh 12-arg request today) and tests. Same for `ConceptResponse` gaining
`djPhotoUsed: boolean` (call sites in `ConceptStudioService.run()` + tests).

- `ConceptStudioService` gains an `EventRepository` dependency (it has none today) and
  resolves `eventId` with the `loadOwned` pattern (cross-org → `NOT_FOUND`).
- If the owned event has `dj_photo_url`, the service downloads the bytes from R2
  **once, eagerly, before submitting the three variant futures** — that download is the
  consistency boundary against concurrent photo replace/delete mid-run. The result is a
  transient in-memory record `DjPhotoSnapshot(url, bytes, mimeType)` threaded through
  the render context alongside `BrandSnapshot`. Only the **URL string** is persisted
  (to `poster_generations.dj_photo_url`); bytes are never serialized into any JSON
  column.
- `regenerate(conceptId)`: read `dj_photo_url` from the most recent
  `poster_generations` row of that concept; if set, download and run in DJ mode (URL
  dead → degrade as below). This is **new** read-back behaviour, specified here — not
  an existing pattern.
- **Graceful degradation:** if the R2 download fails, generation proceeds in normal
  (no-DJ) mode, logs a Sentry warning, and the response carries
  `djPhotoUsed: false`. The photo can never break poster generation — same philosophy
  as `logo_composite_status`.

### 4. Render pipeline (`PosterOrchestrator` / `IdeogramV3Client`)

`IdeogramV3Client.generate(...)` and `.remix(...)` are **refactored**, not just
extended: today `generate(String prompt, long seed, List<StyleReferencePart> styleRefs,
String stylePreset, List<String> paletteHexes)` takes a primitive `seed` that cannot be
conditionally omitted. New shape: both methods accept a nullable
`CharacterRef(byte[] bytes, String filename, String mimeType)` and build the multipart
body conditionally:

- `characterRef != null` → send `character_reference_images`; include/omit
  `color_palette`, `seed`, and style controls (`style_reference_images` /
  `style_preset`) per the P0 probe config flags (§ Ideogram facts).
- `characterRef == null` → byte-identical baseline behaviour (regression-tested).
- Everything else unchanged: `aspect_ratio 4x5`, `magic_prompt OFF`,
  `rendering_speed` QUALITY (generate) / TURBO (remix). The existing asymmetry that
  remix doesn't send `enable_copyright_detection` is left as-is (out of scope).

Corrective remixes (text-gate failures) re-attach the same character reference so the
likeness survives correction passes. If the probe shows `seed` must be omitted,
DJ-mode runs lose seed determinism: `creative_seed` is still recorded for the sampler;
`poster_variants.seed` stores whatever Ideogram reports, or null.

### 5. Prompt changes (`AiEventDescriptionService`)

When the run has a DJ photo, insert a **FEATURED DJ — MANDATORY** block immediately
after the BRAND PALETTE block when that block is present, otherwise after the VIBE
block (BRAND PALETTE only renders when the org has accent colours):

```
FEATURED DJ — MANDATORY: a character reference photo of the headline DJ is attached.
Every variant's ideogram_prompt must make this DJ the dominant hero of the poster —
describe pose, framing, lighting, scale, and wardrobe, never facial features, hair,
ethnicity, or age (the reference image controls the face). The DJ must be a single,
clearly visible human figure occupying the visual centre of gravity. The vibe
contributes mood, texture, composition, and typography around the DJ. Brand colours
must dominate the lighting, wardrobe accents, and background grade of every variant.
```

**Variant plan in DJ mode:** the vibe's `variant_plan` (e.g.
`[people, object, typographic]`) is replaced by an effective plan of
`[people, people, people]` for the run, and **the same effective plan is fed to both
`CreativeDirectionSampler` and the `validate()` hero-type check** — `validate()`
enforces plan conformance as a hard rejection and bans human heroes on typographic
slots, so sampler and validator must see the same plan or every LLM attempt gets
rejected. The sampler overrides each variant's `heroSubject` with a distinct DJ
framing (e.g. "the featured DJ mid-set behind the decks", "monumental close-up
portrait of the featured DJ", "the featured DJ silhouetted against the crowd") so the
three variants stay compositionally distinct while sharing the hero.

The BRAND PALETTE block text is unchanged (it already forces exact hex values into
every `ideogram_prompt`'s colour section — in DJ mode this may be the only palette
channel). The EXACT TEXT CONTRACT is untouched — lineup names already flow into
allowed text.

### 6. Gates

- **Text gate (hard):** unchanged; corrective remix carries the character ref.
- **Style gate (soft):** in DJ mode the hero-presence check asks for "a single dominant
  human DJ figure" instead of the sampled hero subject; palette check unchanged.
  Best-effort acceptance on failure stays exactly as today — **no new render passes in
  v1.** A palette-corrective remix (one extra TURBO pass when the palette check fails
  in DJ mode) is the designated v1.1 lever if production palette pass rates are poor;
  it is deliberately cut from v1 because the probe may make it moot and the current
  `renderWithValidation()` control flow short-circuits on style failure (adding a pass
  is a structural change, not an if-block).

### 7. Logo compositing

Unchanged — it operates on the final PNG after generation and is orthogonal to the
character reference. Raw/final URL divergence and `logo_composite_status` behave as
today.

### 8. Webapp (`imin-webapp`)

**Upload tile (wizard).** `WizardStep2` gains a third media tile using the shared
`<Upload>` component: `aspect="4:5"`, `accept="image/jpeg,image/png"` (matches the
poster tile; no WebP per §2), `maxMb={5}`, with the poster tile's **retry-on-error
pattern** (not the video tile's error-less one). Helper copy: "Used by Poster Studio —
when set, your posters feature this DJ. A clear, well-lit photo of one person works
best." The wizard eagerly creates the event before step 2, so `draft.id` is always
non-empty when the tile renders.

**Draft state rules.** `EventDraftState` gains `djPhotoUrl: string | null`, hydrated in
`eventToDraft` and updated locally via `SET` dispatch on upload success and on clear —
but **excluded from `draftToPatch`** (unlike `posterUrl`/`videoUrl`): the column is
owned by the media endpoints, never the autosave PATCH. The clear handler must use
`apiFetch` (auth header) — and while here, fix the pre-existing `clearMedia` bug where
the poster/video DELETE uses bare `fetch` without the Bearer header.

**EventMediaTab (post-publish).** This tab deliberately uses raw
`<input type="file">` + ref-click + inline progress overlay, not `<Upload>` — the DJ
photo card follows that same in-tab pattern for consistency. Extend the tab's
`progress` and `deleting` state objects with a `djPhoto` key. After upload/delete the
existing `['events', id]` invalidation refetches `EventDto.djPhotoUrl`.

**Poster Studio wiring — four explicit sites:**
1. `types.ts`: `ConceptRequest` gains `eventId?: string`; `ConceptResponse` gains
   `djPhotoUsed: boolean`; the event types gain `djPhotoUrl?: string | null`.
2. `postersApi.ts`: `GeneratePostersInput` gains `eventId?: string`;
   `GeneratePostersResult` gains `djPhotoUsed: boolean` (today it discards everything
   but `conceptId`/`posters`).
3. `PosterStudioDialog.tsx`: `PosterStudioEvent` gains `djPhotoUrl?: string | null`;
   `runGeneration()` passes `event.id` as `eventId` (guarded: only when non-empty).
4. Call sites construct the event slice with `djPhotoUrl` — `WizardStep2` from
   `draft.djPhotoUrl`, `EventMediaTab` from `event.djPhotoUrl`.

**Studio UX.** Step 1 shows an inline notice "Posters will feature your DJ photo" only
when `djPhotoUrl` is truthy **and** an event id is present. `StoredStudio`
(localStorage) gains `djPhotoUrl: string | null` recording what the cached variants
were generated with; when it differs from the current event's value, the notice
becomes "Regenerate to apply your DJ photo change". If a response returns
`djPhotoUsed: false` while a photo is set, show a soft warning toast ("Couldn't use
the DJ photo this time — posters were generated without it") — kept in v1 because
silent degradation would otherwise be invisible (error-truth principle).

**Types sync.** After the API deploys, run `npm run api:sync` and reconcile
`types.ts` (sync pulls from production, so BE merges + deploys first — established
workflow).

### 9. Testing

`imin-api` (H2 + Mockito conventions as today):

- `MediaUploadServiceTest`: DJ photo size/type/magic-byte/short-side validation
  (real PNG/JPEG bytes via `ImageIO.write`), ownership, R2 key shape, delete behaviour,
  and the shared dimension-helper extraction not regressing logo validation.
- `EventMediaControllerTest`: multipart wiring for `dj-photo` kind, response shape,
  unknown-kind → clean error (not 500).
- `IdeogramV3Client` tests: with `CharacterRef` → request contains
  `character_reference_images` and includes/omits `color_palette`/`seed`/style controls
  per config flags; without → byte-identical baseline (regression).
- `PosterOrchestrator` tests: corrective remix carries the character ref; R2 download
  failure degrades to no-DJ mode with `djPhotoUsed=false`.
- `AiEventDescriptionService` tests: DJ-mode effective plan `[people×3]` accepted by
  `validate()`; FEATURED DJ block anchored correctly with and without brand colours.
- `ConceptStudioService` tests: `eventId` ownership (cross-org → `NOT_FOUND`), snapshot
  written, regenerate reads the snapshot not the live row, record call-site updates.
- Migration/persistence test for V39 (pattern: `V38BrandBookMigrationTest`).

`imin-webapp`: component tests for the tile state transitions and draft-state rules
(`djPhotoUrl` never in `draftToPatch`); manual verify of the studio notice, stale-cache
hint, and `djPhotoUsed=false` toast.

### 10. Rollout

1. **P0 probe** against the real Ideogram API; set the DJ-mode config flag defaults
   from its results.
2. Ship `imin-api` (V39, upload endpoint, concept changes, pipeline) → merge → deploy.
3. Watch the first DJ-mode generations in production (gate verdicts, style-gate palette
   pass rate — this metric decides whether the v1.1 palette-corrective remix is
   needed).
4. Ship `imin-webapp` (tiles, studio wiring, types), then `api:sync` reconcile.

Contract changes ship across both repos together (per the cross-repo rule), with the BE
deploy leading because `api:fetch` reads production.

## Risks & open questions

- **Palette fidelity in DJ mode** is the main quality risk if the probe confirms
  `color_palette` is unusable with character reference. Mitigations: mandatory hexes in
  every `ideogram_prompt`, palette wording in the FEATURED DJ block, style-gate palette
  check as the watched metric, palette-corrective remix as the v1.1 lever, and — last
  resort — a remix pass without the char ref but with `color_palette` at high
  `image_weight` (likeness-drift risk, deferred).
- **Likeness drift on stylized vibes** (illustration-heavy style cards): character
  reference is strongest near-photographic; `style_type: REALISTIC` is a tunable the
  client doesn't currently send. Left out of v1; revisit if likeness disappoints.
- **Multiple faces in the photo:** Ideogram documents single-character behaviour only;
  UI copy steers organizers to a one-person photo. No server-side face validation in v1.
- **Cost:** DJ-mode run ≈ $0.60 for 3 QUALITY variants (vs $0.27) plus $0.10 per
  corrective remix (vs $0.03). Ops awareness only — per-variant cost booking is out of
  scope (the column is unpopulated today).
- **Person-image privacy:** DJ photos land in the public R2 bucket like all event
  media; delete removes the object best-effort. Organizers are responsible for artist
  consent (same posture as uploaded event posters featuring people).
