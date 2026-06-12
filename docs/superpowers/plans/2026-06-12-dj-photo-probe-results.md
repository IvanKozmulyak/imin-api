# Ideogram character-reference probe — 2026-06-12

**Status: NOT RUN — `IDEOGRAM_API_KEY` unavailable in the implementation environment.**

Per the plan's conservative posture, all DJ-mode parameter flags default to `false`
(the params are OMITTED when `character_reference_images` is present):

- `ideogram.character.color-palette` = `false`
- `ideogram.character.seed` = `false`
- `ideogram.character.style-control` = `false`

Palette enforcement in DJ mode therefore rests entirely on the prompt channel
(the mandatory BRAND PALETTE block + the FEATURED DJ block), which ships regardless.

**Revisit before/at prod rollout:** run `/tmp/ideogram-probe.sh` (script in the plan,
Task 0 Step 2) against the real API with a production key. If `color_palette` /
`seed` / `style_preset` turn out to be accepted alongside the character reference,
flip the matching env flags ON in Railway — no deploy needed:

| Probe outcome | Action |
|---|---|
| 4xx rejected | keep flag `false` |
| accepted and visibly honoured | set flag `true` |
| accepted but ignored | set flag `true` (harmless; prompt channel still active) |
