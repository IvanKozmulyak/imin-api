# Reference-First Poster Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make AI poster generation reference-first by defaulting to Recraft, removing color-led prompt preferences, and redesigning the frontend vibe selector as Art Direction Cards.

**Architecture:** The backend keeps the existing concept endpoint and provider clients, but changes the default route to `ImageProvider.RECRAFT` and treats vibe palettes as UI fallback data only. The frontend keeps `PosterStudioDialog` as the entry point, but the vibe picker becomes a card grid that shows art-direction preview and reference/training status instead of compact palette chips.

**Tech Stack:** Java 17, Spring Boot 4, JUnit 5, Mockito, Vite 8, React 19, TypeScript, CSS Modules.

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `src/main/java/com/imin/iminapi/dto/EventCreatorRequest.java` | Legacy/common image-provider default | Modify default provider to `RECRAFT` |
| `src/main/java/com/imin/iminapi/service/ai/ConceptStudioService.java` | Concept endpoint provider routing | Modify default provider behavior and comments |
| `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java` | Poster prompt assembly | Remove palette injection and add generic-output avoid language |
| `src/main/resources/vibes.yaml` | Vibe library source | Remove color-led prompt fields where they are prescriptive |
| `src/test/java/com/imin/iminapi/service/ai/ConceptStudioServiceTest.java` | Provider behavior tests | Update/add tests |
| `src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java` | Prompt tests | Update/add tests |
| `src/features/events/PosterStudioDialog.tsx` | Poster studio UI | Replace compact vibe cards with Art Direction Cards |
| `src/features/events/PosterStudioDialog.module.css` | Poster studio styling | Add art-card styles |
| `src/shared/api/types.ts` | FE vibe catalog type | Add optional training/reference metadata if backend exposes it in this slice |

No commits during execution unless Ivan explicitly asks for them.

---

## Task 1: Backend Defaults To Recraft

**Files:**
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/dto/EventCreatorRequest.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/service/ai/ConceptStudioService.java`
- Test: `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/service/ai/ConceptStudioServiceTest.java`

- [ ] **Step 1: Write the failing provider-default test**

Add/replace the default assertion:

```java
@Test
void providerFor_defaultsToRecraftForReferenceFirstGeneration() {
    Vibe recraftVibe = vibe("recraft");

    assertThat(sut.providerFor(recraftVibe)).isEqualTo(ImageProvider.RECRAFT);
    assertThat(sut.providerFor(vibe("sdxl"))).isEqualTo(ImageProvider.RECRAFT);
    assertThat(sut.providerFor(null)).isEqualTo(ImageProvider.RECRAFT);
}
```

Add a direct request default assertion if no test exists:

```java
@Test
void eventCreatorRequest_defaultProviderIsRecraft() {
    EventCreatorRequest request = new EventCreatorRequest(
            "vibe", "tone", "techno", "Berlin",
            LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
            null, null, "Void", null, null, null,
            "brutalist_techno", null);

    assertThat(request.effectiveImageProvider()).isEqualTo(ImageProvider.RECRAFT);
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
cd /Users/ivan/imin/imin-api
./mvnw test -Dtest=ConceptStudioServiceTest#providerFor_defaultsToRecraftForReferenceFirstGeneration
```

Expected: FAIL because `providerFor` currently returns `REPLICATE` by default.

- [ ] **Step 3: Implement the minimal backend default change**

Change `EventCreatorRequest.effectiveImageProvider()` to:

```java
public ImageProvider effectiveImageProvider() {
    return imageProvider != null ? imageProvider : ImageProvider.RECRAFT;
}
```

Change `ConceptStudioService.providerFor(...)` so routing disabled means `RECRAFT`, not `REPLICATE`:

```java
ImageProvider providerFor(Vibe vibe) {
    if (!providerRoutingEnabled || vibe == null || vibe.modelRoute() == null) {
        return ImageProvider.RECRAFT;
    }
    return switch (vibe.modelRoute().toLowerCase()) {
        case "openai", "gpt-image" -> ImageProvider.OPENAI;
        case "replicate", "ideogram" -> ImageProvider.REPLICATE;
        default -> ImageProvider.RECRAFT;
    };
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./mvnw test -Dtest=ConceptStudioServiceTest
```

Expected: PASS.

---

## Task 2: Prompt Cleanup Removes Palette Authority

**Files:**
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/resources/vibes.yaml`
- Test: `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java`

- [ ] **Step 1: Write the failing prompt test**

Update `buildPrompt_pinnedVibe_injectsStructuredPresetAndUniversalRules`:

```java
assertThat(prompt).doesNotContain("Palette:");
assertThat(prompt).doesNotContain("#0A0A0A");
assertThat(prompt).contains("Visual style: raw exposed concrete");
assertThat(prompt).contains("Typography: oversized condensed grotesk");
```

Add an assertion for generic-output avoid language:

```java
assertThat(prompt).contains("stock flyer layout");
assertThat(prompt).contains("template poster");
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
cd /Users/ivan/imin/imin-api
./mvnw test -Dtest=AiEventDescriptionServiceTest#buildPrompt_pinnedVibe_injectsStructuredPresetAndUniversalRules
```

Expected: FAIL because the current prompt includes `Palette:`.

- [ ] **Step 3: Remove palette from prompt assembly**

In `vibeStyleBlock`, remove the block that appends `Palette:`. Keep visual style, typography, composition, and avoid.

Extend universal negative prompt handling so the final avoid text includes:

```text
stock flyer layout, centered generic object, obvious music iconography, clipart, generic neon crowd, template poster, bland gradient background
```

- [ ] **Step 4: Remove color-led vibe description language**

Edit `vibes.yaml` so `visual_style`, `composition`, and `avoid` avoid fixed color recipes. Keep the `palette` arrays as compatibility fallback only.

Example direction:

```yaml
visual_style: "raw exposed concrete, industrial warehouse, harsh single-source lighting, heavy film grain, high-contrast photography, oversized negative space, stark and severe"
avoid: [soft lighting, decoration, warmth, generic flyer tropes]
```

- [ ] **Step 5: Run tests**

Run:

```bash
./mvnw test -Dtest=AiEventDescriptionServiceTest
```

Expected: PASS.

---

## Task 3: Art Direction Cards In Poster Studio

**Files:**
- Modify: `/Users/ivan/imin/imin-webapp/src/features/events/PosterStudioDialog.tsx`
- Modify: `/Users/ivan/imin/imin-webapp/src/features/events/PosterStudioDialog.module.css`
- Modify: `/Users/ivan/imin/imin-webapp/src/shared/api/types.ts`

- [ ] **Step 1: Add UI-only card metadata helper**

Inside `PosterStudioDialog.tsx`, add a local helper:

```ts
function vibePreview(vibeId: string): {
  tone: string;
  refs: string;
  className: string;
} {
  switch (vibeId) {
    case 'brutalist_techno':
      return { tone: 'raw warehouse pressure', refs: 'curated references', className: styles.previewBrutalist };
    case 'berlin_minimal':
      return { tone: 'austere negative space', refs: 'curated references', className: styles.previewMinimal };
    case 'acid_rave_y2k':
      return { tone: 'glossy rave artifacts', refs: 'curated references', className: styles.previewAcid };
    case 'psytrance_goa':
      return { tone: 'dense hypnotic geometry', refs: 'curated references', className: styles.previewPsy };
    case 'industrial_hard_groove':
      return { tone: 'distressed machinery', refs: 'curated references', className: styles.previewIndustrial };
    default:
      return { tone: 'preset direction', refs: 'preset only', className: styles.previewFallback };
  }
}
```

- [ ] **Step 2: Replace the current vibe button markup**

Replace the `.vibeGrid` button body with an Art Direction Card:

```tsx
const preview = vibePreview(v.id);
const status = v.textOnly ? 'Preset only' : preview.refs;

return (
  <button
    key={v.id}
    type="button"
    className={`${styles.vibeCard} ${active ? styles.vibeCardSelected : ''}`}
    aria-pressed={active}
    onClick={() => setVibeId(v.id)}
    disabled={loading || busy}
  >
    <span className={`${styles.vibePreview} ${preview.className}`} aria-hidden>
      <span className={styles.vibePreviewTitle}>{v.name}</span>
    </span>
    <span className={styles.vibeCardBody}>
      <span className={styles.vibeCardTopline}>{status}</span>
      <span className={styles.vibeName}>{v.name}</span>
      <span className={styles.vibeTone}>{preview.tone}</span>
      <span className={styles.vibeGenres}>{v.genres.slice(0, 3).join(' / ')}</span>
    </span>
  </button>
);
```

- [ ] **Step 3: Add CSS card styles**

Add CSS classes for `.vibeCard`, `.vibeCardSelected`, `.vibePreview`, `.vibeCardBody`, `.vibeCardTopline`, `.vibeTone`, and the preview variants.

- [ ] **Step 4: Run TypeScript check**

Run:

```bash
cd /Users/ivan/imin/imin-webapp
npm run typecheck
```

Expected: PASS.

---

## Task 4: Focused Verification

**Files:** all changed files.

- [ ] **Step 1: Backend focused tests**

Run:

```bash
cd /Users/ivan/imin/imin-api
./mvnw test -Dtest=ConceptStudioServiceTest,AiEventDescriptionServiceTest,PosterOrchestratorTest,VibeStyleTrainingServiceTest,VibeStyleTrainingControllerTest
```

Expected: PASS.

- [ ] **Step 2: Frontend typecheck**

Run:

```bash
cd /Users/ivan/imin/imin-webapp
npm run typecheck
```

Expected: PASS.

- [ ] **Step 3: Lints**

Use IDE diagnostics for changed files. Fix issues introduced by this change.

---

## Self-Review

Spec coverage:

- Recraft default: Task 1.
- Remove color preferences from vibe descriptions/prompt authority: Task 2.
- Art Direction Cards: Task 3.
- Training/status support: partially present through `textOnly` and static metadata; richer DB-backed training status remains the next slice.
- Quality gate: documented as later; no implementation in this slice.

No placeholders are intentionally left for this slice. The plan avoids commits because the user has not requested commits.
