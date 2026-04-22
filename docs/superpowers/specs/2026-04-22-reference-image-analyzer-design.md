# Reference Image Analyzer + Persisted Style Descriptors

**Date:** 2026-04-22
**Status:** Approved
**Related spec:** `2026-04-22-manual-style-picker-design.md` (the picker that lets users pin a `subStyleTag`)

## Problem

Today, Ideogram poster generation gets steered by `style_reference_images`
(a small set of PNGs per `subStyleTag`) plus an `ideogramPrompt` written by
the LLM in `AiEventDescriptionService`. The prompt does not mention what the
references actually look like — so the LLM has no way to weave the
visual style into the prompt itself, and Ideogram's reliance on the
reference images alone leaves the aesthetic loose.

We want each `ideogramPrompt` to explicitly describe the style (palette,
typography, mood, composition cues) that the corresponding reference
images convey. Repeating that analysis on every request is wasteful — the
references are static — so we cache the descriptors in the database and
re-analyze only when the underlying images change.

## Non-Goals

- Admin endpoints for triggering re-analysis (delete the row + restart, or
  change YAML, is enough).
- Versioning the descriptor history (one row per tag, overwritten on
  change).
- Background re-analysis on YAML hot-reload (restart-only).
- Per-image (vs. per-tag) descriptors. One descriptor per tag, blending all
  references for that tag in a single vision call.
- Surfacing descriptors in the dev UI (could be added later as a free
  follow-up; not blocking).

## Design

### New DB table: `style_reference_analysis`

```
CREATE TABLE style_reference_analysis (
    sub_style_tag    VARCHAR(64)  PRIMARY KEY,
    descriptor       TEXT         NOT NULL,
    image_signature  VARCHAR(64)  NOT NULL,    -- SHA-256 hex of sorted (refId + sha256(bytes))
    model_id         VARCHAR(128) NOT NULL,
    analyzed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

Created via a new Flyway migration under `src/main/resources/db/migration/`
named `V4__create_style_reference_analysis.sql` (current migrations stop
at `V3__poster_generations.sql`).

Row count is small and bounded by the number of tags in
`poster-references.yaml` (currently 7). No additional indexes needed.

### JPA entity & repository

`StyleReferenceAnalysis` (entity) + `StyleReferenceAnalysisRepository extends
JpaRepository<StyleReferenceAnalysis, String>`. The repository only needs
`findById(tag)` (which JPA gives free) and `save(...)`.

### Image signature

Order-independent SHA-256:

1. For each loaded reference for the tag, compute `sha256(bytes)`.
2. Build the string list `[ "<id>:<sha256>", ... ]` and **sort** it
   alphabetically (so reordering entries in YAML does not change the
   signature).
3. Concatenate with a single `\n` separator.
4. SHA-256 the concatenated string and hex-encode it.

The signature changes when a reference's bytes change, when references are
added, or when references are removed. It does **not** change on YAML
reorder. The signature implementation lives in a new private helper inside
`ReferenceImageLibrary` (small enough not to warrant its own class).

### `ReferenceImageAnalyzer` (new service)

```java
@Component
public class ReferenceImageAnalyzer {
    String analyze(String subStyleTag, List<String> referenceUrlsOrDataUris);
}
```

- Builds a single multimodal prompt: a short text instruction asking for a
  2–4 sentence style descriptor (palette, typography, mood, composition),
  followed by all reference images attached as `Media`.
- Calls the existing `ChatClient` (Spring AI / OpenRouter).
- Returns the raw response string. No JSON, no parsing — the prompt is
  prose.
- On `RuntimeException`, the caller logs and treats the descriptor as empty
  for this run. The analyzer itself rethrows.
- The vision model identifier (used as `model_id` in the cache row) comes
  from configuration — `${imin.reference-analyzer.model-id}` with a sane
  default — so swapping models is a one-line config change and triggers
  re-analysis on next boot.

### `ReferenceImageLibrary` — extended responsibilities

After the existing YAML-load loop, add a second phase that:

1. For each loaded tag, computes the current `image_signature` (above).
2. Looks up `style_reference_analysis` by tag.
3. Decides:
   - **Hit** — row exists, `image_signature` matches, `model_id` matches
     the configured analyzer model. Use the stored descriptor.
   - **Miss / stale** — row missing OR signature differs OR model_id
     differs. Call `ReferenceImageAnalyzer`. On success, upsert the row
     (`save(new StyleReferenceAnalysis(tag, descriptor, signature,
     modelId, now))`). On failure, log warn, leave descriptor empty in
     memory, do **not** write a row — next restart retries.
4. Stores the result in an in-memory `Map<String, String>` and exposes
   `String descriptor(String subStyleTag)` returning that map's value (or
   empty string if missing).

Per-tag analysis runs in **parallel** using `CompletableFuture.supplyAsync`
on the common ForkJoinPool, then `allOf(...).join()` to wait for all. First
boot pays N parallel analyzer calls (~3–5s); subsequent boots pay zero
unless something changed.

### `AiEventDescriptionService.buildPrompt` changes

The existing line:

```
- sub_style_tag: one of <comma list>
```

becomes a per-tag style guide pulled from `ReferenceImageLibrary.descriptor(...)`:

```
- sub_style_tag: pick one and weave its style notes into every variant
    neon_underground — <descriptor or "(no descriptor available)">
    chrome_tropical  — <descriptor>
    ...
```

When the call is made with a **pinned tag** (i.e. `request.subStyleTag()` is
non-null/non-blank), the style-guide block is replaced by a single
imperative line:

```
- sub_style_tag is pre-selected as <tag>. Use the following style notes in every variant:
    <descriptor or "(no descriptor available)">
```

### `AiEventDescriptionService.generateConcept` signature

Today it takes only `EventCreatorRequest`. It already has access to
`request.subStyleTag()`, so no signature change is required; `buildPrompt`
just reads from the request directly. The new dependency is on
`ReferenceImageLibrary` (inject it).

### `EventCreatorService` interaction

When the AI is told the tag is pre-selected, it will return a concept with
that tag. The post-call override in `EventCreatorService` (added in the
manual-style-picker work) becomes a safety net — it should only fire if the
AI somehow returned a different tag than requested. Behavior unchanged
either way.

## Failure Handling Summary

| Failure | Behavior |
|---|---|
| DB down at startup | App fails fast (existing JPA wiring) |
| Vision call fails for one tag | Warn, leave descriptor empty for that tag, other tags still work; next restart retries |
| Vision call fails for ALL tags on first boot | App still starts; concept generation falls back to "(no descriptor available)" placeholders, prompts will be less stylistically anchored but not broken |
| YAML edited without restart | Old descriptors used until next restart (per spec — restart-only invalidation) |
| Reference file deleted from disk | Existing YAML-load behavior already warns; signature recomputed without the missing entry; descriptor refreshed if other refs remain, else descriptor row left as-is |

## Testing

- **Signature unit test:** assert order-independence (same set, different
  YAML order → same signature) and content-sensitivity (replace one image
  → different signature).
- **`ReferenceImageAnalyzer` test:** stub the `ChatClient` to return a
  fixed string; assert the request includes a multimodal user message with
  the expected number of `Media` attachments and that the response is
  returned verbatim.
- **`ReferenceImageLibrary` integration test (Spring Boot):**
  - Hit case: pre-seed the DB with a row whose signature matches the
    classpath references → assert the analyzer is **not** called and the
    descriptor matches the row.
  - Miss case: empty DB → assert the analyzer is called and the row is
    written.
  - Stale case: pre-seed with a mismatched signature → assert the
    analyzer is called and the row is overwritten.
- **`AiEventDescriptionService.buildPrompt` test:**
  - With no pinned tag and known descriptors: assert each tag appears in
    the style-guide block alongside its descriptor.
  - With a pinned tag: assert the single imperative line is present and the
    full guide block is absent.

## Files

**Created**
- `src/main/resources/db/migration/V4__create_style_reference_analysis.sql`
- `src/main/java/com/imin/iminapi/model/StyleReferenceAnalysis.java`
- `src/main/java/com/imin/iminapi/repository/StyleReferenceAnalysisRepository.java`
- `src/main/java/com/imin/iminapi/service/poster/ReferenceImageAnalyzer.java`
- Test files mirroring the above.

**Modified**
- `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java`
  — image-signature helper, second-phase load with cache check, descriptor map, `descriptor(tag)` accessor.
- `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java`
  — inject `ReferenceImageLibrary`; rewrite `buildPrompt` style-guide block; handle pinned-tag path.
- `src/main/resources/application.yaml` — add `imin.reference-analyzer.model-id` (default to a vision-capable OpenRouter model).
- Existing `AiEventDescriptionServiceTest` (if present) — update to provide a `ReferenceImageLibrary` mock or stub.
