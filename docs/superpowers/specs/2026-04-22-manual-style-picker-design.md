# Manual Style Picker in Test UI

**Date:** 2026-04-22
**Status:** Approved
**Scope:** Test UI (`src/main/resources/static/index.html`) + minimal backend support

## Problem

The poster `subStyleTag` (one of 7 hard-coded styles: `neon_underground`,
`chrome_tropical`, `sunset_silhouette`, `flat_graphic`, `aquatic_distressed`,
`industrial_minimal`, `golden_editorial`) is currently always picked by the AI
in `AiEventDescriptionService`. There is no way to preview what each style
looks like, nor to override the AI's pick from the test UI.

Goal: let the developer browse the reference images for each style and
optionally lock in a specific style before clicking "Generate Poster Variants".
If no style is picked, AI picks (today's behavior).

## Non-Goals

- Changing how the AI picks styles when no override is provided.
- Persisting the user's style preference across sessions.
- Adding/removing/uploading reference images from the UI.
- Exposing the picker outside the dev test UI (no public API contract change
  beyond an additive optional field).

## Backend Changes

### `EventCreatorRequest` (DTO)

Add one optional field:

```java
String subStyleTag   // null → AI picks (current behavior)
```

No `@NotNull` / `@NotBlank` — null means "let AI decide".

### Validation

In `EventCreatorService` (or wherever the request is first handled), if
`subStyleTag` is non-null and non-blank:
- Validate it against `AiEventDescriptionService.VALID_SUB_STYLE_TAGS`.
- On invalid value, return HTTP 400 with a clear message listing valid tags.

### Override wiring

In `AiEventDescriptionService` (or its caller in `EventCreatorService`),
after the AI returns a `PosterConcept`:
- If the request specified a `subStyleTag`, replace the concept's
  `subStyleTag` with the requested one (use `PosterConcept` builder / a
  `withSubStyleTag(...)` helper, or rebuild the record).
- Everything downstream — `PosterOrchestrator`, `ReferenceImageLibrary.forTag`,
  Ideogram prompt building — already keys off `concept.subStyleTag()` and
  needs no further changes.

Open implementation choice (defer to plan): we may also short-circuit the AI
call entirely when an override is supplied, since picking a style is one of
the things the AI does. The plan should evaluate whether the AI call still
adds value (variants, prompt phrasing) when the tag is fixed. Default
assumption: keep the AI call; just override the tag field on its output.

### New REST controller for style references

A new lightweight controller (e.g. `StyleReferenceController`) under
`/api/posters/style-references`:

**`GET /api/posters/style-references`** — returns the catalog:

```json
[
  {
    "tag": "neon_underground",
    "label": "Neon Underground",
    "imageUrls": [
      "/api/posters/style-references/neon_underground/0",
      "/api/posters/style-references/neon_underground/1",
      "/api/posters/style-references/neon_underground/2"
    ]
  },
  ...
]
```

- Tags come from `ReferenceImageLibrary` (whatever is loaded from
  `poster-references.yaml` at startup).
- `label` is a humanised version of the tag (`neon_underground` → `Neon
  Underground`). Computed in the controller; no config change needed.
- `imageUrls` count matches the number of references the library has for
  that tag.

**`GET /api/posters/style-references/{tag}/{index}`** — streams the PNG bytes
of reference image `index` for `tag`. 404 on unknown tag or out-of-range
index. `Content-Type: image/png`. Cache headers: `Cache-Control: public,
max-age=86400` is fine — these images don't change at runtime.

To serve bytes, `ReferenceImageLibrary` needs a tiny addition: an internal
accessor (or a new public method like `loadBytes(tag, index)`) that returns
the raw bytes for a given (tag, index). The library already has the resolved
data URIs in memory; the new method either decodes the base64 back to bytes
or — cleaner — re-resolves the source resource on demand. Either is
acceptable; the implementation plan should pick one.

### Security

The `/api/posters/**` paths must be readable by the same actors that can hit
the existing event creator endpoints. Adjust `SecurityConfig` if needed (the
existing creator endpoints are presumably already public for the dev UI).

## UI Changes (`index.html`)

### New field in Step 2 panel

Add a new `.field` block, placed **above** the existing "Platforms" field
(so it sits naturally alongside other style/visual decisions):

```
Style  [optional — pick one to lock the look, or leave on AI]
┌───────────┐ ┌───────────┐ ┌───────────┐ ...
│ AI decides│ │ [thumbs]  │ │ [thumbs]  │
│           │ │ neon_und. │ │ chrome_t. │
└───────────┘ └───────────┘ └───────────┘
```

Layout details:
- Wrap-flex grid of cards, ~140px wide, gap 10px.
- Each style card stacks **all** its reference thumbnails vertically (or in a
  small inner row if there are 2–3) so the developer can judge the vibe from
  more than one image. Thumbnails ~120px wide, rounded corners.
- Below the thumbnails: the human-readable label.
- Selected card gets a 2px purple border (matching the existing
  `.accent-swatch.selected` style) and a checkmark.
- A leading "Let AI decide" card (no thumbnails, just a label) is selected by
  default.
- Hidden input `#subStyleTag` holds the selected value (`""` for AI).

### Page-load behaviour

On page load, the existing inline `<script>` makes one `fetch`:
```js
GET /api/posters/style-references
```
and renders the cards. If the fetch fails, render only the "Let AI decide"
card and a small inline error (so the rest of the UI still works).

### Submit behaviour

In `generatePoster()`, include `subStyleTag` in the request body **only when
a real style is picked** (not when "Let AI decide" is selected). Send `null`
or omit the field otherwise — both work given the optional DTO field.

### Result display

When the response comes back, the existing `.sub-style-tag` badge in
`renderPosters()` already shows which tag was used — that's enough feedback,
no extra UI needed to confirm the override.

## Data Flow

```
Page load:
  UI ──GET /api/posters/style-references──> StyleReferenceController
                                              └─> ReferenceImageLibrary (cached at startup)
  UI renders thumbnail grid

User picks a style (or leaves on "AI decides"):
  UI stores tag in hidden input

User clicks Generate Poster:
  UI ──POST /api/events/ai-create { ..., subStyleTag }──> EventCreatorService
       (validate → call AI → if override present, replace concept.subStyleTag
        → PosterOrchestrator → ReferenceImageLibrary.forTag → Ideogram)
```

## Error Handling

- Unknown tag in request → 400 with valid-tags list.
- Style references endpoint failure on page load → UI degrades gracefully
  (only "AI decides" available; small error message).
- Missing image file (tag in YAML but file gone) → image route returns 404;
  thumbnail in UI shows broken-image placeholder. Acceptable for a dev UI.

## Testing

- Unit test: `EventCreatorRequest` validation accepts null and known tags;
  rejects unknown tags via service-layer check (existing controller test
  pattern).
- Unit test: when request has `subStyleTag`, the resulting concept passed to
  the orchestrator carries that tag regardless of what the AI returned.
- Integration / controller test for `StyleReferenceController`:
  - List endpoint returns one entry per loaded tag.
  - Image endpoint returns 200 + `image/png` for valid (tag, index).
  - Image endpoint returns 404 for unknown tag and out-of-range index.
- Manual UI smoke test: pick each style, generate, confirm the response's
  `subStyleTag` matches the picked tag and the references used in debug panel
  match the chosen style's images.

## Files Touched

- `src/main/java/com/imin/iminapi/dto/EventCreatorRequest.java` — add field.
- `src/main/java/com/imin/iminapi/service/EventCreatorService.java` — validate
  + propagate override.
- `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java` —
  apply override on returned concept (or expose a hook for the service layer).
- `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java` —
  add bytes accessor.
- `src/main/java/com/imin/iminapi/controller/StyleReferenceController.java`
  — **new**.
- `src/main/java/com/imin/iminapi/config/SecurityConfig.java` — permit the
  new paths if not already covered.
- `src/main/resources/static/index.html` — new field, fetch, render, wire to
  request body.
- Test files mirroring the above.
