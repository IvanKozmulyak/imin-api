# AI Poster Studio Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the AI poster generator into a hybrid pipeline — the image model produces vibe-matched art conditioned on curated reference flyers; the platform composites real event text as real fonts — driven by a 12-vibe library.

**Architecture:** Two layers. (1) Art layer: deterministic prompt assembled from a structured Vibe preset + curated reference images, sent to a reference-conditioned image model. (2) Text layer: a portable LayoutDocument rendered by both server (canonical) and client (live edit). Phase 1 (this plan) delivers the non-breaking backend foundation; P2–P5 follow once vendor/render-stack/frontend decisions land.

**Tech Stack:** Java 17 · Spring Boot 4 · Spring AI ChatClient (OpenRouter) · Replicate/Ideogram · zxing · Flyway/H2 · JUnit 5 + Mockito + MockMvc.

Spec: `docs/superpowers/specs/2026-06-02-ai-poster-studio-redesign-design.md`

---

## Conventions for this plan

- Run a single test class: `./mvnw test -Dtest=ClassName`; one method: `./mvnw test -Dtest=ClassName#method`.
- Full suite: `./mvnw test`. Tests use H2 + profile `test`; **all external services must be mocked**.
- Work on branch `feat/poster-studio-redesign`. Commit per task. Do **not** push or open PRs.
- The curated reference images are **staged but uncommitted** on `master`; leave them as-is.

---

## File Structure (Phase 1)

| File | Responsibility | Action |
|---|---|---|
| `src/main/resources/vibes.yaml` | 12 vibe presets + genre→vibe map + universal rules | Create |
| `dto/Vibe.java` | One vibe preset (immutable record) | Create |
| `dto/UniversalRules.java` | aspect ratios, negative prompt, IP rule | Create |
| `service/poster/VibeLibrary.java` | Load `vibes.yaml`; lookup by id; genre→vibe; universal rules; reference globs | Create |
| `service/poster/ReferenceImageLibrary.java` | Add glob/folder resolution; gate startup analysis behind a flag | Modify |
| `src/main/resources/poster-references.yaml` | Fix the broken `Simple/` paths | Modify |
| `config/OpenRouterConfig.java` | temperature + JSON response format | Modify |
| `service/AiEventDescriptionService.java` | Deterministic preset injection; drop `(no descriptor available)` degradation; 4 aspect ratios | Modify |
| `service/ai/ConceptStudioService.java` | Stop discarding real event data; pass `vibeId` | Modify |
| `service/poster/IdeogramClient.java` | negative_prompt; correct style_type; quality tier routing | Modify |
| `service/poster/PosterOrchestrator.java` | Parallel variant rendering | Modify |
| `controller/ai/VibeCatalogController.java` | `GET /api/v1/ai/vibes` | Create |
| `dto/ai/ConceptRequest.java` | optional `vibeId` | Modify |
| `src/main/resources/application.yaml` + `src/test/resources/application.yaml` | `poster.references.analyze-on-startup` | Modify |

---

## Task 1: Make startup descriptor analysis test-safe (do this FIRST)

Fixing the reference paths (Task 3) will make `ReferenceImageLibrary.loadDescriptors()` call the real vision LLM at boot — including inside `@SpringBootTest`, which would hit the network with a dummy key. Gate it behind a flag, default true in prod, **false** in test.

**Files:**
- Modify: `service/poster/ReferenceImageLibrary.java`
- Modify: `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java`

- [ ] **Step 1: Failing test** — with `analyzeOnStartup=false`, the analyzer is never called even when a tag has references.

```java
@Test
void skips_analysis_when_disabled() {
    // build library with analyzeOnStartup=false and a tag that has refs
    // verify(analyzer, never()).analyze(anyString(), anyList());
}
```

- [ ] **Step 2:** Run `./mvnw test -Dtest=ReferenceImageLibraryTest#skips_analysis_when_disabled` → FAIL.
- [ ] **Step 3:** Add constructor `@Value("${poster.references.analyze-on-startup:true}") boolean analyzeOnStartup`; in `load()`, only call `loadDescriptors()` when `analyzeOnStartup`. Keep the `reloadDescriptors()` test hook unconditional.
- [ ] **Step 4:** Add to `src/test/resources/application.yaml` under `poster:` → `references: { analyze-on-startup: false }`. Add the same key (value `true`) to main `application.yaml` for documentation.
- [ ] **Step 5:** Run the test → PASS. Run `./mvnw test -Dtest=ReferenceImageLibraryTest` → PASS.
- [ ] **Step 6:** Commit `chore(poster): gate startup style analysis behind a flag (test-safe)`.

---

## Task 2: Add glob/folder reference resolution to ReferenceImageLibrary

The curated folders hold many hash-named files; we want a vibe to reference a *folder* (capped) rather than listing 37 hashes.

**Files:**
- Modify: `service/poster/ReferenceImageLibrary.java`
- Test: `ReferenceImageLibraryTest`

- [ ] **Step 1: Failing test** — a locator ending in `/*` or naming a directory expands to the image files inside (jpg/jpeg/png/webp), capped at `poster.references.max-per-tag` (default 4), deterministically ordered by filename.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** In `resolveAll`, detect directory/glob entries; resolve via `PathMatchingResourcePatternResolver` on `classpath*:<dir>/*`; filter image extensions; sort by URI; cap; then feed each through `resolveOne`. Add `@Value("${poster.references.max-per-tag:4}")`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(poster): support folder/glob reference locators (capped)`.

---

## Task 3: Fix the broken reference paths + guard test

The verified #1 bug. `poster-references.yaml` points at `reference-images/<f>.png`; files live in `reference-images/Simple/<f>.png`.

**Files:**
- Modify: `src/main/resources/poster-references.yaml` (prefix the 10 entries with `Simple/`)
- Test: `ReferenceImageLibraryTest`

- [ ] **Step 1: Failing test** — `forTag("neon_underground").referenceUrls()` is non-empty; every key in `poster-references.yaml` resolves ≥1 reference (a guard so silent breakage can't recur).
- [ ] **Step 2:** Run → FAIL (empty today).
- [ ] **Step 3:** Edit `poster-references.yaml`: each `reference-images/<f>.png` → `reference-images/Simple/<f>.png`.
- [ ] **Step 4:** Run → PASS. Run `./mvnw test -Dtest=ReferenceImageLibraryTest` → PASS.
- [ ] **Step 5:** Commit `fix(poster): correct reference-image paths (re-activates style conditioning)`.

---

## Task 4: VibeLibrary + vibes.yaml + genre map + universal rules

The new core. Additive — does not yet replace the live pipeline.

**Files:**
- Create: `src/main/resources/vibes.yaml` (12 vibes from the spec/draft; `references:` pointing at curated folders for the 5 covered vibes; empty + `text_only: true` for the other 7)
- Create: `dto/Vibe.java`, `dto/UniversalRules.java`
- Create: `service/poster/VibeLibrary.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/VibeLibraryTest.java`

- [ ] **Step 1: Failing test** — loads exactly 12 vibes; `byId("brutalist_techno")` has palette/typography/avoid; `suggestForGenre("techno")` → `brutalist_techno`; unknown genre → fallback `liquid_melodic`; `universalRules().aspectRatios()` == `[4:5,1:1,9:16,1.91:1]`; negative prompt non-blank.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement `Vibe` record (id, name, genres, visualStyle, palette, typography, composition, moodTags, avoid, modelRoute, references, styleId, layoutTemplate, textOnly); `UniversalRules` record; `VibeLibrary` `@Component` `@PostConstruct` loading `vibes.yaml` via SnakeYAML (mirror `ReferenceImageLibrary` style), building an id→Vibe map and a genre→id map.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(poster): add VibeLibrary (12 genre-mapped vibe presets + universal rules)`.

---

## Task 5: LLM generation controls (temperature + JSON mode)

**Files:**
- Modify: `config/OpenRouterConfig.java`
- Test: `src/test/java/com/imin/iminapi/config/OpenRouterConfigTest.java` (new) — assert the built `OpenAiChatOptions` carries the configured temperature; keep `normalizeOpenRouterBaseUrl` tests.

- [ ] **Step 1:** Failing test asserting temperature is applied from `openrouter.temperature` (default 0.6).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Add `@Value("${openrouter.temperature:0.6}")`; set `.temperature(...)` on `OpenAiChatOptions`. (JSON response_format is enforced per-call via Spring AI `.entity()`, which already requests structured output; leave model-level alone to avoid provider incompatibility.)
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(ai): set concept LLM temperature`.

---

## Task 6: Deterministic preset injection + stop the degradation + feed real event data

Replace `(no descriptor available)` with the structured Vibe preset; pass real event fields through `ConceptStudioService` so quoted typography is the real title/venue/date, not invented.

**Files:**
- Modify: `service/AiEventDescriptionService.java` (inject Vibe preset fields + universal negative + IP rule into `buildPrompt`; fall back to preset, never the placeholder)
- Modify: `service/ai/ConceptStudioService.java` (`toLegacyRequest` keeps `title/location/date/genre/city`; map `vibeId`→subStyleTag/vibe)
- Test: `AiEventDescriptionServiceTest` (assert the prompt contains the preset palette/typography and the real title when provided; assert no `(no descriptor available)` when a vibe preset exists)

- [ ] **Step 1:** Failing tests for the above assertions.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Inject `VibeLibrary` into `AiEventDescriptionService`; in `buildPrompt`, when a vibe/tag preset exists, emit `visual_style / palette / typography / composition / avoid` + universal negative + IP rule; keep the reference descriptor as optional enrichment. In `ConceptStudioService.toLegacyRequest`, stop nulling `title/location`; pass through `req`-derived fields.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(ai): deterministic vibe-preset prompt + real event data (no more degraded placeholder)`.

---

## Task 7: IdeogramClient — negative prompt, correct style_type, quality tier

**Files:**
- Modify: `service/poster/IdeogramClient.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/IdeogramClientTest.java` (new; mock `ReplicateClient`, capture the `input` map)

- [ ] **Step 1:** Failing test — `input` contains `negative_prompt`; when refs present, `style_type` is the configured value (verify against current Ideogram V3 API: if "Design" is now allowed with refs, send it; else keep "Auto" but log). Quality tier method reachable via a `tier` arg.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Add `negativePrompt` param threaded from `UniversalRules`; add a `tier` (TURBO/QUALITY) param to `generate`; set `magic_prompt_option` configurable (default `Auto`). Keep behavior config-driven.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(poster): send negative prompt + tier/style_type controls to Ideogram`.

---

## Task 8: 4 aspect ratios

**Files:**
- Modify: `service/AiEventDescriptionService.java` (`VALID_ASPECTS` → `4:5,1:1,9:16,1.91:1`; default 4:5)
- Modify: `service/poster/OpenAiImageClient.java` (`mapAspectRatio` for the new ratios)
- Test: `AiEventDescriptionServiceTest`

- [ ] Steps 1–5 (TDD): assert the new aspect set validates; commit `feat(poster): support 4 aspect ratios (4:5/1:1/9:16/1.91:1)`.

---

## Task 9: Parallelize variant rendering

**Files:**
- Modify: `service/poster/PosterOrchestrator.java` (render variants on a bounded executor, gathering results; keep per-variant failure isolation; keep the `Semaphore` as the cross-request cap)
- Test: `PosterOrchestratorTest` (verify all 3 variants attempted; failure of one does not drop the others)

- [ ] Steps 1–5 (TDD): commit `perf(poster): render variants in parallel`.

---

## Task 10: Vibe catalog endpoint

**Files:**
- Create: `controller/ai/VibeCatalogController.java` (`GET /api/v1/ai/vibes`)
- Modify: `dto/ai/ConceptRequest.java` (add optional `String vibeId`)
- Test: `src/test/java/com/imin/iminapi/controller/ai/VibeCatalogControllerTest.java`

- [ ] Steps 1–5 (TDD): returns 12 vibe summaries `{id,name,genres,swatch}`; `vibeId` accepted (optional, validated against VibeLibrary). Commit `feat(ai): vibe catalog endpoint + optional vibeId on ConceptRequest`.

---

## Phase 1 self-review gate

- [ ] `./mvnw test` fully green.
- [ ] No `(no descriptor available)` reachable when a vibe preset exists.
- [ ] Every `poster-references.yaml` key resolves ≥1 reference (guard test).
- [ ] Startup makes no network calls under profile `test`.

---

## P2–P5 (outline — separate plans once open questions resolved)

- **P2 Text layer:** `LayoutDocument` JSON schema + per-vibe templates; server renderer (Java2D vs Satori/SVG — **open Q3**); migrate QR+address onto it; persist the document (Flyway). 
- **P3 Model engine:** `RecraftClient` behind `ImageProvider`; per-vibe style training; routing from `model_route`; cost ledger; pinned versions. **Needs keys (open Q2b).**
- **P4 Frontend (`imin-webapp`):** replace the Canvas mock — real vibe picker (consumes `GET /ai/vibes`), generate→progress, live text edit via shared LayoutDocument, export+upload. Contract change is additive (`vibeId`).
- **P5 Quality + async:** async job + progress (state machine exists); optional art-quality/NSFW gate; re-issue.

Open questions are tracked in the spec §9; defaults chosen there so P1 can proceed without blocking.
