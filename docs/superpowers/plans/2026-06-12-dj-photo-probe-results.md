# Ideogram character-reference probe — 2026-06-12

**Status: partially answered IN PRODUCTION (the scripted probe was never run —
`IDEOGRAM_API_KEY` unavailable in the implementation environment).**

## Confirmed

- **`color_palette` + character reference → HARD REJECT.** Production observation
  (2026-06-12, flag flipped on in Railway):

  ```
  400 Bad Request: {"error": "Character reference cannot be used with color palette."}
  ```

  The rejection happens before any render (no Ideogram charge). As of the same day the
  `ideogram.character.color-palette` flag is REMOVED from the code — `color_palette` is
  unconditionally omitted whenever `character_reference_images` is present. Brand colours
  in DJ mode ride the prompt channel (mandatory BRAND PALETTE block + FEATURED DJ brand
  suffix). Delete any `IDEOGRAM_CHARACTER_COLOR_PALETTE` env var; it is ignored.

## Still unverified at the API level

- `ideogram.character.seed` = `false` (default)
- `ideogram.character.style-control` = `false` (default)

To answer these, either run `/tmp/ideogram-probe.sh` (script in the plan, Task 0 Step 2)
with a production key, or flip ONE flag at a time in Railway and watch the next DJ-mode
generation — a hard reject surfaces as a 400 naming the offending param (cheap: fails
before any render), acceptance surfaces as a normal generation:

| Outcome | Action |
|---|---|
| 4xx rejected (names the param) | keep/return flag to `false` |
| accepted and visibly honoured | set flag `true` |
| accepted but ignored | `true` is harmless |
