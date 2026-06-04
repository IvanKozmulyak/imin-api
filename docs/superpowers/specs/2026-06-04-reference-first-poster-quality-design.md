# Reference-First Poster Quality - Design

Date: 2026-06-04
Status: Approved direction; pending user review before implementation plan
Scope: `imin-api` + `imin-webapp`

## Problem

The AI poster flow has improved since the June 2 redesign, but the output still risks looking too simple, obvious, or generic compared with the curated reference flyers. The main product requirement is now explicit: optimize first for similarity to the reference flyers, even if generation becomes less predictable.

Current findings:

- `imin-webapp` now calls the real `/api/v1/ai/events/concept` path from `PosterStudioDialog`, but the vibe selector is still mostly name + palette + genres. It does not make the curated references or trained-style status visible, so the organizer cannot judge the art direction before spending a generation.
- `imin-api` has `ImageProvider.RECRAFT` and `RecraftClient`, but concept generation still defaults to Replicate/Ideogram because `poster.provider-routing.enabled` defaults to `false`.
- `vibes.yaml` still treats palette as a first-class vibe field and several descriptions are color-led. For reference-first generation, colors should come mostly from the trained/reference style, not from hand-authored color preferences.
- Prompting still gives the LLM too much art-direction authority. For maximum reference match, the LLM should describe only the event/motif/layout intent while the trained Recraft style carries the aesthetic.

## Decision

Use a **reference-first Recraft pipeline** as the default poster-generation path.

The system should behave as if the curated reference flyers and Recraft trained `style_id`s are the source of truth for aesthetics. Hand-authored vibe data remains useful for naming, genre matching, layout hints, avoid rules, and fallback behavior, but it must not overrule the references with fixed color preferences.

## Target Flow

```text
Organizer event data + selected vibe
        |
        v
Frontend Art Direction Cards
  - show vibe name, genre fit, preview language, reference/training status
  - no color-preference-first picker
        |
        v
POST /api/v1/ai/events/concept
  - sends vibeId + real event text
        |
        v
Backend resolves vibe
  - default provider: RECRAFT
  - prefer trained style_id for selected vibe
  - warn/fail-soft when a selected vibe is untrained
        |
        v
Reference-first prompt assembly
  - LLM supplies motif / scene / composition variation only
  - Recraft style_id carries the visual identity
  - no hand-authored color palette injection
        |
        v
3 poster variants
  - reference-style art first
  - deterministic text overlay remains available for real event text
```

## Frontend Design

Replace the current compact vibe picker presentation with **Art Direction Cards**.

Each card should read like a small poster-system choice, not a settings chip:

- Bold mini-poster preview or generated/static thumbnail that communicates the vibe.
- Vibe name and the strongest genre tags.
- Reference status: for example `17 refs`, `trained style`, `needs training`, or `preset only`.
- Optional warning for `textOnly` / no curated references so users understand why quality may be weaker.
- Primary action: selecting a card pins `vibeId`.

Remove palette-first UI. Palette swatches can stay only as a fallback visual skeleton if no thumbnail/reference preview exists, but they should not be the main selector affordance.

## Backend Design

Default poster generation to Recraft:

- `EventCreatorRequest.effectiveImageProvider()` should return `RECRAFT` when no provider is explicitly supplied.
- `ConceptStudioService.providerFor(...)` should return `RECRAFT` by default for concept generation.
- `poster.provider-routing.enabled` can remain as an advanced override, but the default production path should no longer silently route all vibes to Replicate/Ideogram.

Recraft should be reference-first:

- Prefer `VibeStyleTrainingService.resolveStyleId(vibeId, RECRAFT)` for every selected vibe.
- When `style_id` is missing, generation may still fall back to Recraft base style, but the response/logs should make the weaker conditioning visible.
- Existing Ideogram support stays as an emergency fallback, not the default quality path.

## Vibe Library Changes

Remove color preferences from vibe descriptions:

- Remove `palette` from backend prompt assembly.
- Remove color-led wording from `visual_style`, `composition`, and `avoid` where it acts like a fixed color recipe.
- Keep non-color aesthetic direction: material, texture, density, geometry, era, layout behavior, image treatment, and mood.
- If a `palette` field remains for UI compatibility, treat it as a display fallback only, not as prompt truth.

The frontend API can keep returning `palette` short term to avoid a breaking contract, but new UI should not lead with it.

## Quality Improvements Proposal

Near-term:

- Train Recraft styles for every curated vibe and store `style_id`s.
- Make untrained/text-only vibes visually marked in the selector.
- Reduce prompt verbosity and remove obvious poster cliches like "central DJ silhouette," "neon crowd," and "generic club flyer."
- Add negative constraints for generic/simple results: stock flyer layout, centered generic object, obvious music iconography, clipart, generic neon crowd, template poster, and bland gradient background.

Medium-term:

- Add reference cluster selection per vibe, e.g. `raw minimal`, `dense typographic`, `photographic`, `abstract object`.
- Add a quality gate that rejects outputs that are too simple or too far from reference density.
- Add one automatic regenerate for low-quality variants.
- Persist provider, style_id, reference ids, and prompt for every generated variant so bad outputs can be audited.

Later:

- Let organizers upload reference images or choose from saved brand/event art direction.
- Build an internal "poster critic" screen comparing generated variants against the reference set.
- Track accepted/rejected posters to learn which vibes and style ids produce usable results.

## Implementation Phases

1. **Backend defaults and prompt cleanup**
   - Default generation to Recraft.
   - Stop injecting palette into prompts.
   - Add stronger generic-output negative prompt language.
   - Add tests for default provider behavior and prompt contents.

2. **Vibe selector redesign**
   - Replace compact vibe presentation with Art Direction Cards in `PosterStudioDialog`.
   - Surface `textOnly` and trained/reference status.
   - De-emphasize palette swatches.

3. **Training/status support**
   - Expose trained-style status in the vibe catalog.
   - Ensure curated vibes have Recraft style ids populated.
   - Make missing style ids visible in logs and UI.

4. **Quality gate**
   - Add score/retry infrastructure once the Recraft default is live and there are real examples to evaluate.

## Non-Goals

- Do not build organizer reference uploads in this iteration.
- Do not remove Ideogram or OpenAI clients; keep them as fallback/experimentation providers.
- Do not redesign the full event creation wizard beyond the poster studio vibe selector.
- Do not guarantee exact reference copying; the goal is strong aesthetic similarity with original outputs.

## Implementation Defaults

- An untrained selected vibe should warn and use Recraft base style for this iteration. Blocking can come later once all curated vibes have trained `style_id`s.
- The vibe catalog should expose training status from the database when available, with `vibes.yaml` as fallback.
- Art Direction Cards should start with static hand-authored CSS previews. Real thumbnails from curated references can replace them after the first UX pass.
