# Manual Style Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional manual style picker to the dev test UI so the developer can override the AI's auto-selected `subStyleTag` by choosing one of the 7 style references; default behavior (AI decides) is preserved.

**Architecture:** Additive optional field on `EventCreatorRequest`; service-layer override of the AI's `PosterConcept.subStyleTag()` when present; new lightweight REST endpoint to expose the reference catalog and stream PNG bytes; a new field in the existing static `index.html` that fetches the catalog and includes the chosen tag in the request body.

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring Security, Bean Validation (Jakarta), JUnit 5, Mockito, Spring MVC test, AssertJ; vanilla HTML/CSS/JS in `static/index.html`.

**Spec:** `docs/superpowers/specs/2026-04-22-manual-style-picker-design.md`

---

## File Structure

**Modified:**
- `src/main/java/com/imin/iminapi/dto/EventCreatorRequest.java` — add `subStyleTag` field + `@AssertTrue` cross-field validator.
- `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java` — change `VALID_SUB_STYLE_TAGS` from package-private to `public` so the DTO and the new controller can read it.
- `src/main/java/com/imin/iminapi/service/EventCreatorService.java` — apply override on returned `PosterConcept`.
- `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java` — add `loadBytes(tag, index)` and `tags()` accessors.
- `src/main/java/com/imin/iminapi/config/SecurityConfig.java` — permit `/api/posters/**`.
- `src/main/resources/static/index.html` — add Style picker field + fetch + submit wiring.
- `src/test/java/com/imin/iminapi/service/EventCreatorServiceTest.java` — assert override propagates.
- `src/test/java/com/imin/iminapi/controller/EventCreatorControllerTest.java` — assert validation rejects unknown tags.

**Created:**
- `src/main/java/com/imin/iminapi/dto/StyleReferenceSummary.java` — DTO for list endpoint.
- `src/main/java/com/imin/iminapi/controller/StyleReferenceController.java` — list + image endpoints.
- `src/test/java/com/imin/iminapi/controller/StyleReferenceControllerTest.java` — controller tests.
- `src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java` — `loadBytes` + `tags()` tests (only if no existing test for this class — check first; if one exists, append cases there).

---

## Task 1: Make `VALID_SUB_STYLE_TAGS` publicly accessible

**Why:** The DTO validator and the catalog endpoint both need this set; today it's package-private inside `AiEventDescriptionService`. Promoting visibility is the minimal change.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java:24-32`

- [ ] **Step 1: Change visibility**

In `AiEventDescriptionService.java`, change:
```java
    static final Set<String> VALID_SUB_STYLE_TAGS = Set.of(
```
to:
```java
    public static final Set<String> VALID_SUB_STYLE_TAGS = Set.of(
```
Keep the rest of the declaration identical.

- [ ] **Step 2: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java
git commit -m "refactor: make VALID_SUB_STYLE_TAGS public for cross-package use"
```

---

## Task 2: Add optional `subStyleTag` to `EventCreatorRequest` (failing test first)

**Files:**
- Modify: `src/test/java/com/imin/iminapi/controller/EventCreatorControllerTest.java`
- Modify: `src/main/java/com/imin/iminapi/dto/EventCreatorRequest.java`

- [ ] **Step 1: Add the failing test for unknown tag**

Append two tests to `EventCreatorControllerTest.java` (inside the existing class, before the closing `}`):

```java
    @Test
    void create_unknownSubStyleTag_returns400() throws Exception {
        String body = """
                {"vibe":"v","tone":"t","genre":"g","city":"c","date":"2026-06-14",
                 "platforms":["INSTAGRAM"],"subStyleTag":"made_up_tag"}
                """;

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.subStyleTagValid").exists());
    }

    @Test
    void create_knownSubStyleTag_passesValidation() throws Exception {
        String body = """
                {"vibe":"v","tone":"t","genre":"g","city":"c","date":"2026-06-14",
                 "platforms":["INSTAGRAM"],"subStyleTag":"neon_underground"}
                """;

        GeneratedPoster poster = new GeneratedPoster(
                UUID.randomUUID(), "atmospheric",
                "/images/raw.png", "/images/final.png",
                42L, "prompt", List.of(), Map.of(), "COMPLETE", null);
        EventCreatorResponse response = new EventCreatorResponse(
                UUID.randomUUID(), "COMPLETE",
                UUID.randomUUID(), "neon_underground",
                List.of(poster), LocalDateTime.now());
        when(eventCreatorService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./mvnw -q test -Dtest=EventCreatorControllerTest`
Expected: FAIL — `create_unknownSubStyleTag_returns400` (field doesn't exist or no validation), `create_knownSubStyleTag_passesValidation` (field doesn't exist on record so JSON deserialisation fails or field is silently ignored, but the existing constructor mismatch will compile-fail).

> Note: the new tests will not compile yet because `EventCreatorRequest` doesn't have the new field. Continue to Step 3 — the failing state is "won't compile", which is acceptable in TDD here.

- [ ] **Step 3: Add the field + validator**

Replace the entire contents of `src/main/java/com/imin/iminapi/dto/EventCreatorRequest.java` with:

```java
package com.imin.iminapi.dto;

import com.imin.iminapi.service.AiEventDescriptionService;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record EventCreatorRequest(
        @NotBlank String vibe,
        @NotBlank String tone,
        @NotBlank String genre,
        @NotBlank String city,
        @NotNull LocalDate date,
        @NotEmpty List<String> platforms,
        String djName,
        String location,
        String title,
        String accentColor,
        String address,
        String rsvpUrl,
        String subStyleTag
) {
    @AssertTrue(message = "subStyleTag must be one of the known style tags")
    public boolean isSubStyleTagValid() {
        return subStyleTag == null
                || subStyleTag.isBlank()
                || AiEventDescriptionService.VALID_SUB_STYLE_TAGS.contains(subStyleTag);
    }
}
```

- [ ] **Step 4: Update existing constructors**

Existing `new EventCreatorRequest(...)` calls now need a 13th argument. Update them all to pass `null` as the new last argument.

Files to edit:
- `src/test/java/com/imin/iminapi/service/EventCreatorServiceTest.java:43-49` (the `request()` helper) — append `, null` before the closing `)`.
- `src/test/java/com/imin/iminapi/controller/EventCreatorControllerTest.java:46-50` and `:91-95` (both `new EventCreatorRequest(...)` literals) — append `, null` before the closing `)` on each.

Search for any other instantiations:
```bash
grep -rn "new EventCreatorRequest(" src/
```
Add `, null` to any others found.

- [ ] **Step 5: Run tests**

Run: `./mvnw -q test -Dtest=EventCreatorControllerTest`
Expected: PASS for all four tests including the two new ones.

Run: `./mvnw -q test -Dtest=EventCreatorServiceTest`
Expected: PASS for all existing tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/EventCreatorRequest.java \
        src/test/java/com/imin/iminapi/controller/EventCreatorControllerTest.java \
        src/test/java/com/imin/iminapi/service/EventCreatorServiceTest.java
git commit -m "feat(api): accept optional subStyleTag override on event creator request"
```

---

## Task 3: Wire override into `EventCreatorService`

**Files:**
- Modify: `src/test/java/com/imin/iminapi/service/EventCreatorServiceTest.java`
- Modify: `src/main/java/com/imin/iminapi/service/EventCreatorService.java`

- [ ] **Step 1: Add the failing test**

Append to `EventCreatorServiceTest.java` (inside the class, before the final `}`):

```java
    @Test
    void create_withSubStyleTagOverride_passesOverriddenTagToOrchestrator() {
        EventCreatorRequest req = new EventCreatorRequest(
                "underground techno night", "edgy", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, "Void Sessions IV", null,
                "Kreuzberg 12, Berlin", "https://imin.wtf/e/abc",
                "chrome_tropical");

        // AI returns its own pick — the service must override it.
        when(aiEventDescriptionService.generateConcept(any())).thenReturn(concept());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        org.mockito.ArgumentCaptor<PosterConcept> conceptCaptor =
                org.mockito.ArgumentCaptor.forClass(PosterConcept.class);
        when(posterOrchestrator.run(any(), any(), conceptCaptor.capture()))
                .thenReturn(orchestrationResult());

        service.create(req);

        assertThat(conceptCaptor.getValue().subStyleTag()).isEqualTo("chrome_tropical");
    }
```

- [ ] **Step 2: Run test to verify failure**

Run: `./mvnw -q test -Dtest=EventCreatorServiceTest#create_withSubStyleTagOverride_passesOverriddenTagToOrchestrator`
Expected: FAIL — captured tag is `neon_underground` (the value from `concept()`), not `chrome_tropical`.

- [ ] **Step 3: Implement the override**

In `EventCreatorService.java`, change the body of the `try` block. Replace lines 37-38:

```java
            PosterConcept concept = aiEventDescriptionService.generateConcept(request);
            OrchestrationResult result = posterOrchestrator.run(event.getId(), request, concept);
```

with:

```java
            PosterConcept concept = aiEventDescriptionService.generateConcept(request);
            if (request.subStyleTag() != null && !request.subStyleTag().isBlank()) {
                concept = new PosterConcept(
                        request.subStyleTag(),
                        concept.colorPaletteDescription(),
                        concept.variants());
                log.info("Sub-style tag overridden by request: {}", concept.subStyleTag());
            }
            OrchestrationResult result = posterOrchestrator.run(event.getId(), request, concept);
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -q test -Dtest=EventCreatorServiceTest`
Expected: PASS for all tests including the new one.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/EventCreatorService.java \
        src/test/java/com/imin/iminapi/service/EventCreatorServiceTest.java
git commit -m "feat(api): override AI-chosen sub-style tag when supplied in request"
```

---

## Task 4: Expose `tags()` and `loadBytes(tag, index)` on `ReferenceImageLibrary`

**Files:**
- Create: `src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java` (only if no existing test file for this class)
- Modify: `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java`

- [ ] **Step 1: Check for existing test file**

Run: `ls src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java 2>/dev/null && echo EXISTS || echo MISSING`

If `EXISTS`, append the test cases below into the existing class. If `MISSING`, create the file with the contents in Step 2.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java` (or append cases to existing):

```java
package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ReferenceImageLibraryTest {

    @Autowired
    private ReferenceImageLibrary library;

    @Test
    void tags_returnsAllConfiguredTags() {
        List<String> tags = library.tags();
        assertThat(tags).contains(
                "neon_underground", "chrome_tropical", "sunset_silhouette",
                "flat_graphic", "aquatic_distressed", "industrial_minimal", "golden_editorial");
    }

    @Test
    void referenceCount_matchesYamlForKnownTag() {
        assertThat(library.referenceCount("neon_underground")).isEqualTo(3);
        assertThat(library.referenceCount("chrome_tropical")).isEqualTo(2);
        assertThat(library.referenceCount("nonexistent")).isZero();
    }

    @Test
    void loadBytes_validIndex_returnsPngBytes() {
        byte[] bytes = library.loadBytes("neon_underground", 0);
        assertThat(bytes).isNotEmpty();
        // PNG magic number: 89 50 4E 47
        assertThat(bytes[0] & 0xFF).isEqualTo(0x89);
        assertThat(bytes[1] & 0xFF).isEqualTo(0x50);
        assertThat(bytes[2] & 0xFF).isEqualTo(0x4E);
        assertThat(bytes[3] & 0xFF).isEqualTo(0x47);
    }

    @Test
    void loadBytes_unknownTag_throwsIllegalArgument() {
        assertThatThrownBy(() -> library.loadBytes("not_a_tag", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadBytes_indexOutOfRange_throwsIllegalArgument() {
        assertThatThrownBy(() -> library.loadBytes("neon_underground", 99))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: Run test to verify failure**

Run: `./mvnw -q test -Dtest=ReferenceImageLibraryTest`
Expected: FAIL — methods `tags()`, `referenceCount(String)`, `loadBytes(String, int)` do not exist.

- [ ] **Step 4: Implement the new methods**

In `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java`:

(a) Change the inner record from:
```java
    private record LoadedReference(String id, String urlOrDataUri) {}
```
to:
```java
    private record LoadedReference(String id, String urlOrDataUri, String sourceLocator) {}
```

(b) In `resolveOne(String entry)` (around lines 80-98), update both `return new LoadedReference(...)` calls to pass the source locator:

For the URL branch (around line 83):
```java
            return new LoadedReference(shortIdFromUrl(trimmed), trimmed, trimmed);
```

For the classpath branch (around line 97):
```java
        return new LoadedReference(id, dataUri, locator);
```

(c) Append three new public methods after the existing `hasTag(...)` method (before the `private record` declaration):

```java
    public List<String> tags() {
        return List.copyOf(byTag.keySet());
    }

    public int referenceCount(String subStyleTag) {
        return byTag.getOrDefault(subStyleTag, List.of()).size();
    }

    public byte[] loadBytes(String subStyleTag, int index) {
        List<LoadedReference> refs = byTag.get(subStyleTag);
        if (refs == null) {
            throw new IllegalArgumentException("Unknown sub-style tag: " + subStyleTag);
        }
        if (index < 0 || index >= refs.size()) {
            throw new IllegalArgumentException(
                    "Index " + index + " out of range for tag " + subStyleTag + " (size=" + refs.size() + ")");
        }
        String locator = refs.get(index).sourceLocator();
        if (locator.startsWith("http://") || locator.startsWith("https://") || locator.startsWith("data:")) {
            throw new IllegalArgumentException(
                    "Reference for tag " + subStyleTag + "[" + index + "] is a remote URL, not a classpath resource");
        }
        Resource r = resourceLoader.getResource(locator);
        if (!r.exists()) {
            throw new IllegalStateException("Reference resource gone: " + locator);
        }
        try (InputStream in = r.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read reference: " + locator, e);
        }
    }
```

Note: `List` and `IOException` and `InputStream` and `Resource` are already imported.

- [ ] **Step 5: Run tests**

Run: `./mvnw -q test -Dtest=ReferenceImageLibraryTest`
Expected: PASS — all 5 cases.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java \
        src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java
git commit -m "feat(poster): expose tags(), referenceCount(), and loadBytes() on ReferenceImageLibrary"
```

---

## Task 5: Create `StyleReferenceSummary` DTO

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/StyleReferenceSummary.java`

- [ ] **Step 1: Create the DTO**

Create `src/main/java/com/imin/iminapi/dto/StyleReferenceSummary.java`:

```java
package com.imin.iminapi.dto;

import java.util.List;

public record StyleReferenceSummary(
        String tag,
        String label,
        List<String> imageUrls
) {}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/StyleReferenceSummary.java
git commit -m "feat(api): add StyleReferenceSummary DTO"
```

---

## Task 6: Create `StyleReferenceController`

**Files:**
- Create: `src/test/java/com/imin/iminapi/controller/StyleReferenceControllerTest.java`
- Create: `src/main/java/com/imin/iminapi/controller/StyleReferenceController.java`

- [ ] **Step 1: Write the failing controller test**

Create `src/test/java/com/imin/iminapi/controller/StyleReferenceControllerTest.java`:

```java
package com.imin.iminapi.controller;

import com.imin.iminapi.config.SecurityConfig;
import com.imin.iminapi.service.poster.ReferenceImageLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = StyleReferenceController.class,
        excludeAutoConfiguration = Saml2RelyingPartyAutoConfiguration.class
)
@Import(SecurityConfig.class)
class StyleReferenceControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ReferenceImageLibrary library;

    @Test
    void list_returnsCatalogWithImageUrls() throws Exception {
        when(library.tags()).thenReturn(List.of("neon_underground", "chrome_tropical"));
        when(library.referenceCount("neon_underground")).thenReturn(3);
        when(library.referenceCount("chrome_tropical")).thenReturn(2);

        mockMvc.perform(get("/api/posters/style-references"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].tag").value("neon_underground"))
                .andExpect(jsonPath("$[0].label").value("Neon Underground"))
                .andExpect(jsonPath("$[0].imageUrls").isArray())
                .andExpect(jsonPath("$[0].imageUrls.length()").value(3))
                .andExpect(jsonPath("$[0].imageUrls[0]")
                        .value("/api/posters/style-references/neon_underground/0"))
                .andExpect(jsonPath("$[1].imageUrls.length()").value(2));
    }

    @Test
    void image_validTagAndIndex_returnsPngBytes() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        when(library.loadBytes("neon_underground", 0)).thenReturn(png);

        mockMvc.perform(get("/api/posters/style-references/neon_underground/0"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(png));
    }

    @Test
    void image_unknownTag_returns404() throws Exception {
        when(library.loadBytes("nope", 0))
                .thenThrow(new IllegalArgumentException("Unknown sub-style tag: nope"));

        mockMvc.perform(get("/api/posters/style-references/nope/0"))
                .andExpect(status().isNotFound());
    }

    @Test
    void image_indexOutOfRange_returns404() throws Exception {
        when(library.loadBytes("neon_underground", 99))
                .thenThrow(new IllegalArgumentException("Index 99 out of range"));

        mockMvc.perform(get("/api/posters/style-references/neon_underground/99"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=StyleReferenceControllerTest`
Expected: FAIL — `StyleReferenceController` does not exist (compile error).

- [ ] **Step 3: Implement the controller**

Create `src/main/java/com/imin/iminapi/controller/StyleReferenceController.java`:

```java
package com.imin.iminapi.controller;

import com.imin.iminapi.dto.StyleReferenceSummary;
import com.imin.iminapi.service.poster.ReferenceImageLibrary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/posters/style-references")
@RequiredArgsConstructor
public class StyleReferenceController {

    private final ReferenceImageLibrary library;

    @GetMapping
    public List<StyleReferenceSummary> list() {
        return library.tags().stream()
                .map(tag -> new StyleReferenceSummary(
                        tag,
                        humanize(tag),
                        IntStream.range(0, library.referenceCount(tag))
                                .mapToObj(i -> "/api/posters/style-references/" + tag + "/" + i)
                                .toList()))
                .toList();
    }

    @GetMapping("/{tag}/{index}")
    public ResponseEntity<byte[]> image(@PathVariable String tag, @PathVariable int index) {
        try {
            byte[] bytes = library.loadBytes(tag, index);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    static String humanize(String tag) {
        if (tag == null || tag.isBlank()) return "";
        StringBuilder out = new StringBuilder(tag.length());
        boolean capNext = true;
        for (char c : tag.toCharArray()) {
            if (c == '_' || c == '-') {
                out.append(' ');
                capNext = true;
            } else if (capNext) {
                out.append(Character.toUpperCase(c));
                capNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -q test -Dtest=StyleReferenceControllerTest`
Expected: PASS — all 4 cases.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/controller/StyleReferenceController.java \
        src/test/java/com/imin/iminapi/controller/StyleReferenceControllerTest.java
git commit -m "feat(api): expose style reference catalog and image bytes"
```

---

## Task 7: Permit `/api/posters/**` in `SecurityConfig`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/config/SecurityConfig.java`

- [ ] **Step 1: Add the matcher**

In `SecurityConfig.java`, replace the `requestMatchers` block (lines 16-21) with:

```java
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/images/**").permitAll()
                        .requestMatchers("/api/events/**").permitAll()
                        .requestMatchers("/api/posters/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated()
                )
```

- [ ] **Step 2: Re-run controller tests**

Run: `./mvnw -q test -Dtest=StyleReferenceControllerTest`
Expected: PASS (still — confirms SecurityConfig change didn't break anything).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/config/SecurityConfig.java
git commit -m "feat(security): permit /api/posters/** for the dev test UI"
```

---

## Task 8: Add the Style picker to `index.html`

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Add CSS for the picker**

Open `src/main/resources/static/index.html`. Inside the `<style>` block, just **before** the closing `</style>` (around line 188), append:

```css
    /* Style picker */
    .style-grid {
      display: flex; flex-wrap: wrap; gap: 10px; margin-top: 6px;
    }
    .style-card {
      width: 140px; padding: 8px; border-radius: 8px;
      border: 2px solid #2a2a2a; background: #0d0d0d; cursor: pointer;
      transition: all .15s; user-select: none; display: flex; flex-direction: column; gap: 6px;
    }
    .style-card:hover { border-color: #7c3aed; }
    .style-card.selected { border-color: #7c3aed; background: #1e1040; }
    .style-card .thumbs {
      display: flex; flex-direction: column; gap: 4px;
    }
    .style-card .thumbs img {
      width: 100%; aspect-ratio: 1 / 1; object-fit: cover; border-radius: 4px; display: block;
    }
    .style-card.ai-card .thumbs {
      align-items: center; justify-content: center;
      min-height: 80px; color: #7c3aed; font-size: 1.4rem;
    }
    .style-card .label {
      font-size: .7rem; color: #ccc; text-align: center; letter-spacing: .03em;
    }
    .style-card.selected .label { color: #a78bfa; font-weight: 600; }
    .style-error { font-size: .75rem; color: #f87171; margin-top: 6px; }
```

- [ ] **Step 2: Add the field markup in the Step 2 panel**

In `index.html`, find the existing "Platforms" field (around line 341–350). **Immediately before** the `<div class="field">` that contains the Platforms label, insert:

```html
    <div class="field">
      <label>Style <span style="color:#444">(optional — pick one to lock the look, or let AI decide)</span></label>
      <div class="style-grid" id="styleGrid">
        <div class="style-card ai-card selected" data-tag="" onclick="selectStyle(this)">
          <div class="thumbs">✨</div>
          <div class="label">Let AI decide</div>
        </div>
      </div>
      <div class="style-error" id="styleError" style="display:none"></div>
      <input type="hidden" id="subStyleTag" value="" />
    </div>
```

- [ ] **Step 3: Add the JS — fetch + render + select**

In `index.html`, find the line `renderPresets();` (currently at line 443). **Immediately after** that line, insert:

```javascript

  // ── Style picker ──────────────────────────────────────────────────────────

  async function loadStyles() {
    try {
      const res = await fetch('/api/posters/style-references');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const styles = await res.json();
      const grid = document.getElementById('styleGrid');
      styles.forEach(s => {
        const card = document.createElement('div');
        card.className = 'style-card';
        card.dataset.tag = s.tag;
        card.onclick = () => selectStyle(card);
        const thumbs = document.createElement('div');
        thumbs.className = 'thumbs';
        (s.imageUrls || []).forEach(url => {
          const img = document.createElement('img');
          img.src = url;
          img.alt = s.label;
          thumbs.appendChild(img);
        });
        const label = document.createElement('div');
        label.className = 'label';
        label.textContent = s.label;
        card.appendChild(thumbs);
        card.appendChild(label);
        grid.appendChild(card);
      });
    } catch (e) {
      const errEl = document.getElementById('styleError');
      errEl.textContent = 'Failed to load style references: ' + e.message;
      errEl.style.display = 'block';
    }
  }

  function selectStyle(card) {
    document.querySelectorAll('#styleGrid .style-card').forEach(c => c.classList.remove('selected'));
    card.classList.add('selected');
    setVal('subStyleTag', card.dataset.tag || '');
  }

  loadStyles();
```

- [ ] **Step 4: Wire `subStyleTag` into the request body**

In the same `index.html`, find the `body` object inside `generatePoster()` (around lines 583-596). Inside the existing object literal, add one more field. Change the closing of the `body` object from:

```javascript
      address:     nullable('address'),
      rsvpUrl:     nullable('rsvpUrl'),
    };
```

to:

```javascript
      address:     nullable('address'),
      rsvpUrl:     nullable('rsvpUrl'),
      subStyleTag: nullable('subStyleTag'),
    };
```

(`nullable()` already returns `null` for empty strings, which is exactly what we want for the AI-decides default.)

- [ ] **Step 5: Sanity check by serving the app**

Run: `./mvnw -q -DskipTests spring-boot:run` in one terminal.

In a second terminal, hit the catalog endpoint:
```bash
curl -s http://localhost:8080/api/posters/style-references | head -c 400
```
Expected: JSON array starting with `[{"tag":"...","label":"...","imageUrls":["/api/posters/style-references/.../0", ...]}, ...]`.

Verify a thumbnail downloads:
```bash
curl -sI http://localhost:8080/api/posters/style-references/neon_underground/0
```
Expected: `HTTP/1.1 200`, `Content-Type: image/png`, `Cache-Control: max-age=86400, public`.

Open `http://localhost:8080/` in a browser. Confirm:
- The Step 2 panel shows the "Style" field with "Let AI decide" + ~7 style cards each showing thumbnails.
- Clicking a card moves the purple highlight.
- Stop the dev server (Ctrl-C) when done.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat(ui): add style picker to dev test UI"
```

---

## Task 9: End-to-end smoke test (manual)

**Files:** none (manual verification)

- [ ] **Step 1: Start the app**

Run: `docker compose up -d` (if Postgres isn't running) then `./mvnw -q -DskipTests spring-boot:run`.

- [ ] **Step 2: Override path**

In the browser at `http://localhost:8080/`:
1. Pick a preset (e.g. "Berlin techno warehouse"), click Generate Content.
2. In Step 2, click a style card that does **not** match what the AI would naturally pick (e.g. pick `golden_editorial` for a techno prompt).
3. Click "Generate 3 Poster Variants".
4. Wait for the result.

Expected:
- The `style · <tag>` badge in the result area shows the **picked** tag (`golden_editorial`), not whatever the AI would have picked.
- The poster images visually reflect the chosen reference style.
- Open the Debug panel; `referenceImagesUsed` for each variant should reference files matching the chosen tag (e.g. `golden_editorial_01_good_vibes.png`).

- [ ] **Step 3: Default path (regression)**

Reload the page. Generate Content again. Leave the Style field on "Let AI decide" (default selection). Click Generate.

Expected:
- The `style · <tag>` badge shows whatever tag the AI chose — **not** an error, and the request body had no/null `subStyleTag`.

- [ ] **Step 4: Run the full test suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Final commit (only if anything was tweaked during smoke testing)**

If any small UI/CSS tweaks were made during smoke testing, commit them now:
```bash
git status
git add -p
git commit -m "fix(ui): style picker smoke-test tweaks"
```
Otherwise skip.

---

## Done criteria

- `./mvnw test` passes.
- Hitting `/api/posters/style-references` returns a JSON array with one entry per tag in `poster-references.yaml`, and each `imageUrls[i]` is downloadable and returns a valid PNG.
- Loading `http://localhost:8080/` shows the Style picker with thumbnails.
- Picking a non-default style and generating posters results in a response whose `subStyleTag` matches the picked value, and the rendered posters use that style's reference images.
- Leaving the picker on "Let AI decide" produces today's behavior (AI picks the tag).
