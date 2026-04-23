# ADR-0001: Use Ideogram V3 directly, overlay only QR + address

Status: Accepted
Date: 2026-04-21

## Context

Our Phase 1 poster generator targets nightlife event flyers (techno, house, jazz, ambient). The visual target is the club-flyer look: chrome 3D typography, integrated script fonts, distressed serifs, neon outlines — all rendered as typography inside the image, not composited on top.

We originally built a three-layer pipeline on top of **Flux Schnell / Pro + DALL-E 3**, with a Java2D compositor responsible for drawing the event title, date, venue, and DJ name on top of a generated background. In practice this could not produce the target aesthetic:

1. Flux at 4–28 steps produces strong painterly/photographic backgrounds but renders typography poorly. Quoted strings in the prompt almost never survive in readable form, and when they do the typography treatment is flat and uninspired.
2. Java2D overlays look like overlays. Even with careful font selection, spacing, and blend modes, the text never feels *part of the image* — it feels pasted on. This is the exact opposite of the flyer aesthetic, where typography is the image.
3. The abstraction cost (three layers: VariantCore JSON → prompt assembler → per-model adapter) made iteration slow. Each prompt tweak required touching three files and re-validating the adapter contract.

## Decision

Replace the Flux + composite pipeline with **Ideogram V3 Turbo (direct)**. Use the canvas only for the two elements that must be character-perfect and cannot be left to a stochastic text-in-image model:

- **QR code** — encodes the RSVP URL. A single wrong bit = unscannable.
- **Address line** — the physical event location. One typo = people at the wrong door.

Everything else — event title, date, DJ name, tagline, hashtag, city — is rendered *by Ideogram inside the image*, explicitly wrapped in double quotes in the prompt. Ideogram V3 quotes survive at ~90–95% accuracy in our informal evals, which is acceptable given the title is already marketing copy and not life-critical.

### Architecture

```
EventCreatorRequest
  │
  ▼
AiEventDescriptionService  ─►  PosterConcept { subStyleTag, 3 × PosterVariant }
  │                                (one LLM call via OpenRouter / ChatClient)
  ▼
PosterOrchestrator  ──►  3 parallel IdeogramClient.generate() calls
  │                         (bounded by Semaphore(6) across requests,
  │                          fixed thread pool sized per-request)
  ▼
PosterImageStorage.download → OverlayCompositor.applyOverlays → writePng
  │   (raw PNG from Ideogram)   (zxing QR + address band only)
  ▼
OrchestrationResult (3 × GeneratedPoster)
```

### What is deliberately *not* in this design

- **No ImageGenAdapter abstraction.** A single `IdeogramClient` component. If we ever need a second provider, introducing an interface at that point is cheap; introducing it speculatively is more code to maintain.
- **No OCR auto-regeneration.** Ideogram misrenders ~5–10% of quoted strings. Phase 1 surfaces all 3 variants to the organizer so they can pick the one where the text came out right. Phase 2 will add OCR sanity + one auto-retry. Marker: `TODO(phase-2)` in `PosterOrchestrator.generateOne`.
- **No R2/object storage.** Phase 1 writes PNGs to local disk and serves them via `/images/{uuid}.png`. The seam is isolated to `PosterImageStorage`; swapping for R2 in Phase 2 touches that one class.
- **No SSE streaming.** The `/api/events/ai-create` endpoint returns synchronously after all 3 variants complete (or all fail). With turbo at ~8–12 s per variant running in parallel, p95 wall clock sits around 15 s — acceptable for a POST + spinner UX in Phase 1.
- **No cost ledger.** Cost tracking (`ai_usage` table) is deferred until we have real traffic to measure.
- **No Pro-subscription gating.** One tier for now.
- **No smart crop per aspect ratio.** Ideogram generates at the requested aspect ratio natively. If we want a single concept cropped to 1:1 / 3:4 / 4:5, that's a Phase 2 enhancement.

## Consequences

### Positive
- Typography quality jumps dramatically. Chrome lettering, integrated script, distressed serifs — all emerge from the model with prompt hints like "chrome 3D lettering" or "distressed serif".
- One AI call per event (concept) + 3 Ideogram calls (one per variant) replaces the old chain of `LLM → assemble → validate → Flux → composite`.
- Fewer moving parts, fewer places where a prompt tweak silently breaks the pipeline.
- Canvas code shrinks to ~150 LoC covering only QR + address band.

### Negative
- **Spelling risk on quoted strings.** ~5–10% of generations will misrender a quoted word. Mitigated in Phase 1 by delivering 3 variants; mitigated in Phase 2 by OCR + auto-retry.
- **Ideogram cost per image is higher than Flux Schnell** (~$0.03 vs $0.003). Three variants at $0.09 per generation is still well within our target envelope for a free-tier event creator given concept.
- **Style reference images require manual curation.** We seed one curated set per `sub_style_tag` (7 tags total) to anchor the aesthetic. See `poster-references.yaml`. Adding/changing style references is an ops task, not a code change.
- **Locked to Replicate's Ideogram endpoint** for Phase 1. Acceptable given the typography ceiling.

## Alternatives considered

- **Flux Pro with better prompts.** Tried. Text rendering on Flux Pro is better than Schnell but still ~40% failure on quoted strings. Not workable.
- **DALL-E 3.** Good text for short strings, but aspect-ratio options are limited (1024², 1024×1792, 1792×1024) and the aesthetic skews toward illustration rather than club flyer.
- **Keep compositor, use Ideogram only for backgrounds.** Rejected — defeats the reason to use Ideogram (integrated typography is the whole point).
- **Stable Diffusion XL + controlnet for typography.** Too fragile; controlnet typography setups are brittle and require reference renders.

## Rollback

If Ideogram becomes unavailable or its quality drops:
1. `IdeogramClient` is the single call site — a provider swap lives there.
2. `OverlayCompositor` is independent of Ideogram. It would still apply QR + address to whatever raw PNG we get.
3. The Flux code has been deleted, not deprecated. Rolling back would mean reintroducing a provider, not restoring old code.
