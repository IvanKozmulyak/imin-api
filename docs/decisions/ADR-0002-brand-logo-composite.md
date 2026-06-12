# ADR-0002: Composite the brand logo onto accepted posters (carve-out from the no-overlay decision)

Status: Accepted
Date: 2026-06-12

## Context

ADR-0001 and the 2026-06-08 native-Ideogram-V3 spec locked a "no post-render overlay"
decision: the QR-code, address band, and Satori real-font text compositors were removed, and
"the downloaded Ideogram PNG is the final image." The scope of that decision is explicit and
narrow: **typography stays model-native.** All event text — title, date, venue, lineup — is
rendered by Ideogram inside the image and verified by the vision text gate.

The Brand Book feature (spec 2026-06-11) introduces an organizer-uploaded logo that should
appear on generated posters, with a per-org toggle and a per-generation override. This raises
the question: does compositing a logo violate the no-overlay decision?

## Decision

**A brand logo is composited deterministically onto accepted posters via pure Java2D. This is a
scoped carve-out from ADR-0001's no-overlay decision, not a reversal of it.**

The carve-out is justified on three grounds:

1. **A logo is not text.** The no-overlay decision's purpose is that *typography is the image* —
   event copy must be model-native so it reads as part of the flyer, not pasted on. A logo is a
   fixed brand mark, not typography the model should invent.

2. **A diffusion model provably cannot reproduce an exact mark.** Ideogram V3 has no
   logo-placement, vector-aware, or brand-mark feature; style references do style transfer only
   (they extract palette/mood, not the mark). Passing a logo as a style reference would corrupt
   both the vibe aesthetic and the mark. Deterministic compositing is the only faithful option.

3. **It restores nothing the decision removed.** The removed compositors drew *text*. This draws
   a single raster mark in one fixed corner. The text pipeline stays 100% model-native.

### Mechanism

- Pure Java2D (`BufferedImage` / `Graphics2D` / `AlphaComposite.SrcOver`) — the same toolkit
  `QrImageRenderer` already uses. No new dependency (`pom.xml` unchanged).
- **Seam:** inside `PosterOrchestrator.accept()` — the single funnel that sets `final_url`, so all
  three acceptance paths (text-gate best-effort, accepted, style soft-fail) get the logo.
- **Placement:** bottom-right; margin 4% of poster width; logo scaled to max 18% of poster width,
  aspect preserved.
- **Legibility scrim (required):** the destination corner's mean luminance is sampled and a
  contrasting translucent rounded scrim is drawn behind the logo, so a white mark on a light
  corner (or dark-on-dark) stays legible — Ideogram backgrounds are unpredictable per generation.
- **Storage:** the composited PNG is a **second** `PosterImageStorage.writePng` call →
  `final_url` = composited URL; `raw_url` keeps the un-composited render. `final_url` is canonical
  for display/download; `raw_url` is the re-edit/remix input.

### Failure isolation (hard rule)

The composite is wrapped in try/catch. Any failure (logo URL 404, decode error, OOM) logs, emits
a Sentry **warning**, sets `logo_composite_status = 'FAILED'`, and ships the un-composited poster
(`final_url = raw_url`). **Generation never fails over a decoration.** Toggle off / no logo →
`SKIPPED`.

## Consequences

### Positive
- Faithful brand mark on every accepted poster with zero extra API calls (CPU-only, in-process).
- The no-text-overlay architecture is untouched; the boundary is now documented, not implied.
- `brand_snapshot` makes "why does this poster (not) have a logo?" auditable, and corrective
  remixes read the snapshot, not live org state.

### Negative
- One extra storage object per accepted variant (the composited PNG). `raw_url` and `final_url`
  now genuinely differ for branded generations; every reader was audited (gallery/download use
  `final_url`; remix re-edit uses the raw render bytes).
- The scrim is a heuristic; a busy corner can still reduce contrast. Acceptable for Phase 1; a
  `logo_placement` enum can refine placement later.

## Alternatives considered
- **Inject the logo as an Ideogram style reference.** Rejected — style transfer cannot reproduce a
  mark and would displace the vibe's curated references (Ideogram accepts exactly one style control).
- **Composite client-side in the browser.** Rejected — loses the single durable R2 URL and makes
  downloads/share inconsistent across surfaces.
- **Bake the logo into the prompt as text.** Rejected — a logo is not text; this is exactly what
  the no-overlay decision keeps model-native, and a mark is not reproducible as typography.
