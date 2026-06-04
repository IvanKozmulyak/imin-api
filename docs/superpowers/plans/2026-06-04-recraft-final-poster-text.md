# Recraft Final Poster Text Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Recraft generate the complete poster, including visible event title/date/venue/lineup typography, then validate the rendered text before accepting the poster.

**Architecture:** Recraft owns the final typographic composition because the reference posters are typography-led. `imin-api` assembles exact required text into every Recraft prompt, validates generated image text with an OCR/vision gate, and only applies QR/address overlays after validation. The Satori text compositor remains available for fallback/experiments, but it is no longer the main path for Recraft final posters.

**Tech Stack:** Java 17, Spring Boot 4, Spring AI/OpenRouter or OpenAI-compatible vision endpoint, Recraft V3 image generation, JUnit 5, Mockito, R2 media storage.

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `src/main/java/com/imin/iminapi/dto/PosterTextSpec.java` | Exact text contract for a poster generation request | Create |
| `src/main/java/com/imin/iminapi/service/poster/PosterTextSpecFactory.java` | Build the exact required/optional text set from `EventCreatorRequest` | Create |
| `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java` | Prompt the LLM to produce Recraft final-poster prompts with exact text | Modify |
| `src/main/java/com/imin/iminapi/service/poster/PosterTextValidationService.java` | Validate generated poster text before accepting a variant | Create |
| `src/main/java/com/imin/iminapi/service/poster/PosterTextValidationClient.java` | Interface for OCR/vision validation | Create |
| `src/main/java/com/imin/iminapi/service/poster/OpenRouterPosterTextValidationClient.java` | OpenRouter vision implementation of text validation | Create |
| `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java` | For Recraft, validate raw poster and apply QR/address-only overlay; skip Satori title compositor | Modify |
| `src/main/java/com/imin/iminapi/service/poster/PosterTextCompositorClient.java` | Keep as non-Recraft fallback only | Modify comments/tests if needed |
| `src/main/resources/application.yaml` | Text-validation config, retry count, validation model | Modify |
| `src/test/java/...` | Unit tests for prompt, text spec, validation, orchestration | Add/modify |

---

## Task 1: Exact Poster Text Spec

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/PosterTextSpec.java`
- Create: `src/main/java/com/imin/iminapi/service/poster/PosterTextSpecFactory.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/PosterTextSpecFactoryTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PosterTextSpecFactoryTest {
    PosterTextSpecFactory factory = new PosterTextSpecFactory();

    @Test
    void fromRequest_buildsRequiredAndAllowedText() {
        EventCreatorRequest r = new EventCreatorRequest(
                "warehouse rave", "energetic", "techno", "Berlin",
                LocalDate.of(2026, 6, 7), List.of("instagram"),
                "DJ A, DJ B", "RSO", "BIG NIGHT - BERLIN", null,
                "Schnellerstrasse 137", "https://imin.wtf/e/big-night",
                "berlin_minimal", null);

        var spec = factory.from(r);

        assertThat(spec.required()).containsExactly("BIG NIGHT - BERLIN", "7 JUN 2026", "RSO");
        assertThat(spec.allowed()).contains("DJ A", "DJ B", "BERLIN");
        assertThat(spec.forPrompt()).contains("\"BIG NIGHT - BERLIN\"");
        assertThat(spec.forPrompt()).contains("No other words, filler text, lorem ipsum, fake letters, pseudo-text, logos, watermarks, or invented copy.");
    }
}
```

- [ ] **Step 2: Run red test**

Run: `./mvnw test -Dtest=PosterTextSpecFactoryTest`

Expected: FAIL because the classes do not exist.

- [ ] **Step 3: Add `PosterTextSpec`**

```java
package com.imin.iminapi.dto;

import java.util.List;

public record PosterTextSpec(
        List<String> required,
        List<String> allowed,
        String promptBlock
) {
    public String forPrompt() {
        return promptBlock;
    }
}
```

- [ ] **Step 4: Add `PosterTextSpecFactory`**

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.PosterTextSpec;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class PosterTextSpecFactory {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public PosterTextSpec from(EventCreatorRequest r) {
        List<String> required = new ArrayList<>();
        if (has(r.title())) required.add(r.title().trim());
        if (r.date() != null) required.add(r.date().format(DATE).toUpperCase(Locale.ENGLISH));
        if (has(r.location())) required.add(r.location().trim());

        List<String> allowed = new ArrayList<>(required);
        if (has(r.city())) allowed.add(r.city().trim().toUpperCase(Locale.ENGLISH));
        if (has(r.djName())) {
            Arrays.stream(r.djName().split("[,·/&]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(allowed::add);
        }

        String prompt = """
                EXACT TEXT CONTRACT:
                Render the following required text exactly as written, as the visible poster typography:
                %s

                You may also use these optional text elements if they fit the composition:
                %s

                No other words, filler text, lorem ipsum, fake letters, pseudo-text, logos, watermarks, or invented copy.
                If a text element is too long, reduce font size or change layout. Do not misspell it.
                """.formatted(quoteLines(required), quoteLines(allowed));

        return new PosterTextSpec(List.copyOf(required), List.copyOf(allowed), prompt);
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String quoteLines(List<String> values) {
        if (values.isEmpty()) return "- none";
        return values.stream().map(v -> "- \"" + v + "\"").reduce((a, b) -> a + "\n" + b).orElse("- none");
    }
}
```

- [ ] **Step 5: Run green test**

Run: `./mvnw test -Dtest=PosterTextSpecFactoryTest`

Expected: PASS.

---

## Task 2: Recraft Final-Poster Prompting

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java`
- Test: `src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java`

- [ ] **Step 1: Write failing prompt assertions**

Update `buildPrompt_pinnedVibe_injectsStructuredPresetAndUniversalRules` to use a request with real title/date/venue and assert:

```java
assertThat(prompt).contains("Create a finished event poster");
assertThat(prompt).contains("Render the following required text exactly as written");
assertThat(prompt).contains("\"BIG NIGHT - BERLIN\"");
assertThat(prompt).contains("\"7 JUN 2026\"");
assertThat(prompt).doesNotContain("EMPTY typographic surfaces");
assertThat(prompt).doesNotContain("No letters, no glyphs");
```

- [ ] **Step 2: Run red test**

Run: `./mvnw test -Dtest=AiEventDescriptionServiceTest#buildPrompt_pinnedVibe_injectsStructuredPresetAndUniversalRules`

Expected: FAIL because current prompt asks for blank text surfaces.

- [ ] **Step 3: Inject `PosterTextSpecFactory`**

Change constructor/fields:

```java
private final ChatClient chatClient;
private final VibeLibrary vibeLibrary;
private final PosterTextSpecFactory posterTextSpecFactory;
```

Use Lombok required args as today.

- [ ] **Step 4: Replace blank-surface rules**

Inside `buildPrompt`, build:

```java
PosterTextSpec textSpec = posterTextSpecFactory.from(request);
```

Replace the current `ideogram_prompt` and strict rules copy with:

```java
.append("    - ideogram_prompt: a COMPLETE self-contained Recraft prompt, 45-180 words, for a FINISHED event poster where typography is the main visual composition\n")
...
.append("STRICT RULES for each ideogram_prompt:\n")
.append("- Create a finished event poster, not a background plate and not a mockup\n")
.append("- The required event text must be integrated into the poster composition as native typography, not added as a caption\n")
.append("- Use exactly the required text elements and only optional text elements listed below\n")
.append("- No filler text, lorem ipsum, fake letters, pseudo-text, paragraphs, logos, watermarks, or invented words\n")
.append("- If text is long, change layout or scale; never misspell, abbreviate, translate, or replace it\n\n")
.append(textSpec.forPrompt())
.append("\n");
```

- [ ] **Step 5: Run green test**

Run: `./mvnw test -Dtest=AiEventDescriptionServiceTest`

Expected: PASS.

---

## Task 3: Recraft Text Validation Gate

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/poster/PosterTextValidationClient.java`
- Create: `src/main/java/com/imin/iminapi/service/poster/PosterTextValidationService.java`
- Create: `src/main/java/com/imin/iminapi/service/poster/OpenRouterPosterTextValidationClient.java`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/com/imin/iminapi/service/poster/PosterTextValidationServiceTest.java`

- [ ] **Step 1: Write failing validation service tests**

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.PosterTextSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PosterTextValidationServiceTest {
    PosterTextValidationClient client = mock(PosterTextValidationClient.class);

    @Test
    void accepts_whenAllRequiredTextPresentAndNoExtraText() {
        PosterTextValidationService service = new PosterTextValidationService(client, true);
        PosterTextSpec spec = new PosterTextSpec(List.of("BIG NIGHT - BERLIN", "7 JUN 2026"), List.of("BIG NIGHT - BERLIN", "7 JUN 2026", "BERLIN"), "prompt");
        when(client.validate(any(), eq(spec))).thenReturn(new PosterTextValidationClient.ValidationResult(true, List.of(), List.of()));

        assertThat(service.validateOrExplain(new byte[]{1}, spec).accepted()).isTrue();
    }

    @Test
    void rejects_whenRequiredTextMissing() {
        PosterTextValidationService service = new PosterTextValidationService(client, true);
        PosterTextSpec spec = new PosterTextSpec(List.of("BIG NIGHT - BERLIN"), List.of("BIG NIGHT - BERLIN"), "prompt");
        when(client.validate(any(), eq(spec))).thenReturn(new PosterTextValidationClient.ValidationResult(false, List.of("BIG NIGHT - BERLIN"), List.of("BAG NIGKT")));

        var result = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains("missing required text");
    }

    @Test
    void disabledValidationAcceptsWithoutCallingClient() {
        PosterTextValidationService service = new PosterTextValidationService(client, false);
        PosterTextSpec spec = new PosterTextSpec(List.of("A"), List.of("A"), "prompt");

        assertThat(service.validateOrExplain(new byte[]{1}, spec).accepted()).isTrue();
        verifyNoInteractions(client);
    }
}
```

- [ ] **Step 2: Run red test**

Run: `./mvnw test -Dtest=PosterTextValidationServiceTest`

Expected: FAIL because classes do not exist.

- [ ] **Step 3: Add validation interface**

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.PosterTextSpec;

import java.util.List;

public interface PosterTextValidationClient {
    ValidationResult validate(byte[] imageBytes, PosterTextSpec spec);

    record ValidationResult(boolean accepted, List<String> missingRequired, List<String> extraText) {}
}
```

- [ ] **Step 4: Add validation service**

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.PosterTextSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PosterTextValidationService {
    private final PosterTextValidationClient client;
    private final boolean enabled;

    public PosterTextValidationService(
            PosterTextValidationClient client,
            @Value("${poster.text-validation.enabled:true}") boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    public ValidationDecision validateOrExplain(byte[] imageBytes, PosterTextSpec spec) {
        if (!enabled || spec.required().isEmpty()) {
            return ValidationDecision.accepted();
        }
        PosterTextValidationClient.ValidationResult result = client.validate(imageBytes, spec);
        if (result.accepted()) return ValidationDecision.accepted();
        return new ValidationDecision(false,
                "missing required text: " + result.missingRequired() + "; extra text: " + result.extraText());
    }

    public record ValidationDecision(boolean accepted, String reason) {
        static ValidationDecision accepted() {
            return new ValidationDecision(true, null);
        }
    }
}
```

- [ ] **Step 5: Add OpenRouter vision implementation**

Implement `OpenRouterPosterTextValidationClient` with `RestClient` against OpenRouter chat completions. Use `poster.text-validation.model` default `openai/gpt-4o-mini`, send the generated image as `data:image/png;base64,...`, and ask for JSON:

```json
{"accepted":true,"missingRequired":[],"extraText":[]}
```

The validation prompt must say:

```text
You are checking an event poster. Required text must appear exactly or near-exactly.
Ignore QR codes. Reject if required title/date/venue is missing or badly misspelled.
Reject if there are obvious large invented words outside the allowed text list.
Return JSON only.
```

- [ ] **Step 6: Add config**

In `application.yaml`:

```yaml
poster:
  text-validation:
    enabled: ${POSTER_TEXT_VALIDATION_ENABLED:true}
    model: ${POSTER_TEXT_VALIDATION_MODEL:openai/gpt-4o-mini}
    max-extra-text-items: ${POSTER_TEXT_VALIDATION_MAX_EXTRA_TEXT_ITEMS:2}
```

In `src/test/resources/application.yaml`:

```yaml
poster:
  text-validation:
    enabled: false
```

- [ ] **Step 7: Run green test**

Run: `./mvnw test -Dtest=PosterTextValidationServiceTest`

Expected: PASS.

---

## Task 4: Orchestrator Uses Recraft Final Poster Path

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java`

- [ ] **Step 1: Write failing orchestrator tests**

Add tests:

```java
@Mock PosterTextValidationService textValidation;
@Mock PosterTextSpecFactory textSpecFactory;

@Test
void run_withRecraftProvider_validatesRawPosterAndSkipsTextCompositor() {
    // arrange Recraft raw image, text spec, validation accepted
    // assert textValidation.validateOrExplain(rawBytes, spec) called
    // assert textCompositor.composite never called
    // assert overlayCompositor.applyOverlays called for QR/address only
}

@Test
void run_withRecraftProvider_validationRejected_marksVariantFailed() {
    // arrange validation decision false with reason "missing required text"
    // assert result variant FAILED and failureReason contains that reason
}
```

- [ ] **Step 2: Run red test**

Run: `./mvnw test -Dtest=PosterOrchestratorTest`

Expected: FAIL because constructor and behavior do not exist.

- [ ] **Step 3: Inject new collaborators**

Add fields:

```java
private final PosterTextSpecFactory textSpecFactory;
private final PosterTextValidationService textValidation;
```

Add them to the constructor and tests.

- [ ] **Step 4: Split text handling by provider**

In `generateOne`, after `rawBytes` is written and before final storage:

```java
byte[] finalBytes = provider == ImageProvider.RECRAFT
        ? validateAndApplyQrOnly(rawBytes, request)
        : applyTextLayer(rawBytes, refs, request);
```

Add:

```java
private byte[] validateAndApplyQrOnly(byte[] rawBytes, EventCreatorRequest request) {
    PosterTextSpec spec = textSpecFactory.from(request);
    PosterTextValidationService.ValidationDecision decision =
            textValidation.validateOrExplain(rawBytes, spec);
    if (!decision.accepted()) {
        throw new IllegalStateException("Poster text validation failed: " + decision.reason());
    }
    return overlayCompositor.applyOverlays(new OverlayCompositor.Input(
            rawBytes, request.rsvpUrl(), null));
}
```

This keeps QR exact, but does not overlay event title/date/venue because Recraft owns the final typography.

- [ ] **Step 5: Keep Satori compositor only for non-Recraft fallback**

Leave `applyTextLayer` in place for `OPENAI` / `REPLICATE` fallback paths. Add a comment that Recraft bypasses it by design.

- [ ] **Step 6: Run green tests**

Run: `./mvnw test -Dtest=PosterOrchestratorTest,PosterTextValidationServiceTest`

Expected: PASS.

---

## Task 5: Recraft Prompt Retry On Text Validation Failure

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java`

- [ ] **Step 1: Write failing retry test**

```java
@Test
void run_withRecraftProvider_retriesOnceWhenTextValidationFails() {
    // first recraftClient.generate returns bytes {1}
    // validation rejects bytes {1}
    // second recraftClient.generate returns bytes {2}
    // validation accepts bytes {2}
    // assert two Recraft calls and COMPLETE result
}
```

- [ ] **Step 2: Run red test**

Run: `./mvnw test -Dtest=PosterOrchestratorTest#run_withRecraftProvider_retriesOnceWhenTextValidationFails`

Expected: FAIL because validation retry is not implemented.

- [ ] **Step 3: Add retry config**

Constructor value:

```java
@Value("${poster.text-validation.max-regenerations:1}") int maxTextValidationRegenerations
```

- [ ] **Step 4: Implement one bounded retry**

For `ImageProvider.RECRAFT`, wrap render/validate in a loop:

```java
for (int attempt = 0; attempt <= maxTextValidationRegenerations; attempt++) {
    byte[] rawBytes = renderVariant(...);
    try {
        byte[] finalBytes = validateAndApplyQrOnly(rawBytes, request);
        ...
        return toDto(entity);
    } catch (IllegalStateException e) {
        if (attempt == maxTextValidationRegenerations) throw e;
        log.warn("Recraft poster text validation failed; regenerating once: {}", e.getMessage());
    }
}
```

Do not retry OpenAI/Ideogram paths in this task.

- [ ] **Step 5: Run green tests**

Run: `./mvnw test -Dtest=PosterOrchestratorTest`

Expected: PASS.

---

## Task 6: Frontend/Operator Messaging

**Files:**
- Modify: `imin-webapp/src/features/events/PosterStudioDialog.tsx` if backend exposes validation errors cleanly in existing API error body.
- Otherwise no frontend change.

- [ ] **Step 1: Check current behavior**

Generate a poster with a forced validation failure in backend tests or dev. Confirm frontend shows existing `ApiError` toast. If it already says `Poster generation service unavailable`, defer frontend changes.

- [ ] **Step 2: Optional copy change**

If the backend returns a specific validation message, display:

```text
The poster text was not readable enough. Try regenerating.
```

Use the existing `toast.error` path in `PosterStudioDialog`.

---

## Task 7: Verification

**Files:** all changed files.

- [ ] **Step 1: Backend focused tests**

Run:

```bash
cd /Users/ivan/imin/imin-api
./mvnw test -Dtest=PosterTextSpecFactoryTest,AiEventDescriptionServiceTest,PosterTextValidationServiceTest,PosterOrchestratorTest,RecraftClientTest
```

Expected: PASS.

- [ ] **Step 2: Full backend tests if local JDK is available**

Run:

```bash
./mvnw test
```

Expected: PASS.

- [ ] **Step 3: Frontend typecheck only if frontend changed**

Run:

```bash
cd /Users/ivan/imin/imin-webapp
npm run typecheck
```

Expected: PASS.

---

## Self-Review

Spec coverage:

- Recraft owns final poster typography: Tasks 1, 2, and 4.
- Exact event title/date/venue integrated in Recraft prompt: Tasks 1 and 2.
- QR remains deterministic after generation: Task 4.
- OCR/vision acceptance gate: Task 3.
- Retry once on text validation failure: Task 5.
- Satori compositor no longer fights Recraft text: Task 4.

Known operational requirement:

- `POSTER_TEXT_VALIDATION_ENABLED=true` requires a vision-capable OpenRouter model and API key. If disabled, the system cannot guarantee text accuracy.

No placeholders are intentionally left in this plan.
