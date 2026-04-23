# Reference Image Analyzer + Persisted Style Descriptors — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Analyze each style's reference images once with a vision model, cache the resulting style descriptor in PostgreSQL keyed by an order-independent image-content signature, and inject the descriptor into the AI's poster-concept prompt so each `ideogramPrompt` explicitly anchors the visual style.

**Architecture:** A new Flyway-managed table `style_reference_analysis` stores `(sub_style_tag → descriptor, image_signature, model_id)`. At startup, `ReferenceImageLibrary` computes the current per-tag signature, looks up the cached row, and only calls the new `ReferenceImageAnalyzer` (Spring AI multimodal `ChatClient`) when the row is missing/stale or the configured model changed. Descriptors are kept in memory and read by `AiEventDescriptionService.buildPrompt`, which renders a per-tag style guide (or a pinned-tag imperative when the request supplies `subStyleTag`).

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring Data JPA, Flyway, Spring AI (`ChatClient` + `Media`), PostgreSQL, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-04-22-reference-image-analyzer-design.md`

---

## File Structure

**Created**
- `src/main/resources/db/migration/V4__create_style_reference_analysis.sql` — Flyway migration.
- `src/main/java/com/imin/iminapi/model/StyleReferenceAnalysis.java` — JPA entity.
- `src/main/java/com/imin/iminapi/repository/StyleReferenceAnalysisRepository.java` — Spring Data repository.
- `src/main/java/com/imin/iminapi/service/poster/ReferenceImageAnalyzer.java` — vision-call service.
- `src/test/java/com/imin/iminapi/service/poster/ReferenceImageAnalyzerTest.java` — unit test.
- `src/test/java/com/imin/iminapi/service/poster/ReferenceImageSignatureTest.java` — unit test for the signature helper (kept separate so it doesn't need Spring context).

**Modified**
- `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java` — add `imageSignature()`, second-phase load with cache check, descriptor map, `descriptor(tag)` accessor.
- `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java` — inject `ReferenceImageLibrary`; rewrite the `sub_style_tag` block in `buildPrompt`.
- `src/main/resources/application.yaml` — add `imin.reference-analyzer.model-id`.
- `src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java` — extend with cache hit/miss/stale cases.
- (Possibly) `src/test/resources/application.yaml` — add `imin.reference-analyzer.model-id` if Spring fails to start the test context without it.

---

## Task 1: Flyway migration + JPA entity + repository

**Files:**
- Create: `src/main/resources/db/migration/V4__create_style_reference_analysis.sql`
- Create: `src/main/java/com/imin/iminapi/model/StyleReferenceAnalysis.java`
- Create: `src/main/java/com/imin/iminapi/repository/StyleReferenceAnalysisRepository.java`

This is plumbing — no behavior to test directly. The follow-up tasks exercise the schema via JPA.

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V4__create_style_reference_analysis.sql`:

```sql
CREATE TABLE style_reference_analysis (
    sub_style_tag    VARCHAR(64)  PRIMARY KEY,
    descriptor       TEXT         NOT NULL,
    image_signature  VARCHAR(64)  NOT NULL,
    model_id         VARCHAR(128) NOT NULL,
    analyzed_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

(Following the V3 file's style: `TIMESTAMP` not `TIMESTAMPTZ`, since Hibernate is configured with `jdbc.time_zone: UTC` and the existing tables use `TIMESTAMP`.)

- [ ] **Step 2: Write the JPA entity**

Create `src/main/java/com/imin/iminapi/model/StyleReferenceAnalysis.java`:

```java
package com.imin.iminapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "style_reference_analysis")
@Getter
@Setter
public class StyleReferenceAnalysis {

    @Id
    @Column(name = "sub_style_tag", length = 64)
    private String subStyleTag;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String descriptor;

    @Column(name = "image_signature", nullable = false, length = 64)
    private String imageSignature;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;

    @PrePersist
    void prePersist() {
        if (analyzedAt == null) {
            analyzedAt = LocalDateTime.now();
        }
    }
}
```

- [ ] **Step 3: Write the repository**

Create `src/main/java/com/imin/iminapi/repository/StyleReferenceAnalysisRepository.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.StyleReferenceAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StyleReferenceAnalysisRepository
        extends JpaRepository<StyleReferenceAnalysis, String> {}
```

- [ ] **Step 4: Verify the schema applies and compiles**

Make sure Postgres is running:
```bash
docker compose up -d
```

Run:
```bash
./mvnw -q test -Dtest=IminApiApplicationTests
```
Expected: PASS — Spring boots, Flyway runs `V4`, no schema errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V4__create_style_reference_analysis.sql \
        src/main/java/com/imin/iminapi/model/StyleReferenceAnalysis.java \
        src/main/java/com/imin/iminapi/repository/StyleReferenceAnalysisRepository.java
git commit -m "feat(db): add style_reference_analysis table + entity + repo"
```

---

## Task 2: Image-signature helper (TDD)

**Files:**
- Create: `src/test/java/com/imin/iminapi/service/poster/ReferenceImageSignatureTest.java`
- Modify: `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java`

The signature must be order-independent and content-sensitive. Implement it as a private `static` helper inside `ReferenceImageLibrary` with package-private visibility for testability.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/imin/iminapi/service/poster/ReferenceImageSignatureTest.java`:

```java
package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceImageSignatureTest {

    @Test
    void signature_isOrderIndependent() {
        byte[] a = new byte[]{1, 2, 3};
        byte[] b = new byte[]{4, 5, 6};
        String sig1 = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img_a.png", a),
                new ReferenceImageLibrary.SignatureInput("img_b.png", b)));
        String sig2 = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img_b.png", b),
                new ReferenceImageLibrary.SignatureInput("img_a.png", a)));
        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    void signature_changesWhenContentChanges() {
        byte[] orig    = new byte[]{1, 2, 3};
        byte[] changed = new byte[]{1, 2, 4};
        String sigOrig = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img.png", orig)));
        String sigChanged = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img.png", changed)));
        assertThat(sigOrig).isNotEqualTo(sigChanged);
    }

    @Test
    void signature_changesWhenReferenceAdded() {
        byte[] a = new byte[]{1, 2, 3};
        byte[] b = new byte[]{4, 5, 6};
        String sig1 = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img_a.png", a)));
        String sig2 = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img_a.png", a),
                new ReferenceImageLibrary.SignatureInput("img_b.png", b)));
        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    void signature_isHexEncoded64Chars() {
        String sig = ReferenceImageLibrary.imageSignature(List.of(
                new ReferenceImageLibrary.SignatureInput("img.png", new byte[]{0})));
        assertThat(sig).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void signature_emptyListIsValid() {
        String sig = ReferenceImageLibrary.imageSignature(List.of());
        assertThat(sig).hasSize(64);
    }
}
```

- [ ] **Step 2: Verify it fails (compile error)**

Run: `./mvnw -q test -Dtest=ReferenceImageSignatureTest`
Expected: compile error — `ReferenceImageLibrary.imageSignature` and the `SignatureInput` record don't exist yet.

- [ ] **Step 3: Add the helper**

In `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java`:

(a) Add imports near the top (alongside the existing ones):

```java
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
```

(b) Append a public nested record and a package-private static method (place them just above the existing `private record LoadedReference(...)` declaration so all type/method declarations sit together):

```java
    public record SignatureInput(String referenceId, byte[] bytes) {}

    static String imageSignature(List<SignatureInput> inputs) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            HexFormat hex = HexFormat.of();
            List<String> entries = inputs.stream()
                    .map(in -> in.referenceId() + ":" + hex.formatHex(sha.digest(in.bytes())))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            String joined = String.join("\n", entries);
            return hex.formatHex(sha.digest(joined.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
```

(`MessageDigest` is **not** thread-safe, but each call here creates a fresh instance and reuses it across all entries within the same call — that's fine because the call itself is single-threaded.)

- [ ] **Step 4: Run tests**

Run: `./mvnw -q test -Dtest=ReferenceImageSignatureTest`
Expected: PASS — all 5 cases.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java \
        src/test/java/com/imin/iminapi/service/poster/ReferenceImageSignatureTest.java
git commit -m "feat(poster): add order-independent image-signature helper"
```

---

## Task 3: `ReferenceImageAnalyzer` service (TDD)

**Files:**
- Create: `src/test/java/com/imin/iminapi/service/poster/ReferenceImageAnalyzerTest.java`
- Create: `src/main/java/com/imin/iminapi/service/poster/ReferenceImageAnalyzer.java`

The analyzer takes a tag + reference URLs (or `data:` URIs) and returns a 2–4 sentence style descriptor by calling `ChatClient` with multimodal input.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/imin/iminapi/service/poster/ReferenceImageAnalyzerTest.java`:

```java
package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceImageAnalyzerTest {

    @Test
    void analyze_buildsMultimodalPromptAndReturnsResponse() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(
                "Black and magenta neon palette with distressed serif title type. " +
                "Smoky underground atmosphere, asymmetric composition with strong rule-of-thirds.");

        ReferenceImageAnalyzer analyzer = new ReferenceImageAnalyzer(chatClient, "openai/gpt-4o-mini");

        String descriptor = analyzer.analyze("neon_underground", List.of(
                "data:image/png;base64,AAAA",
                "data:image/png;base64,BBBB"));

        assertThat(descriptor).contains("magenta neon").contains("distressed");
        verify(chatClient).prompt();
        verify(requestSpec).user(any(Consumer.class));
        verify(requestSpec).call();
    }

    @Test
    void analyze_emptyReferenceList_returnsEmptyDescriptorWithoutCallingClient() {
        ChatClient chatClient = mock(ChatClient.class);
        ReferenceImageAnalyzer analyzer = new ReferenceImageAnalyzer(chatClient, "openai/gpt-4o-mini");

        String descriptor = analyzer.analyze("nonexistent", List.of());

        assertThat(descriptor).isEmpty();
        verify(chatClient, org.mockito.Mockito.never()).prompt();
    }
}
```

- [ ] **Step 2: Verify it fails (compile error)**

Run: `./mvnw -q test -Dtest=ReferenceImageAnalyzerTest`
Expected: compile error — class doesn't exist yet.

- [ ] **Step 3: Implement the analyzer**

Create `src/main/java/com/imin/iminapi/service/poster/ReferenceImageAnalyzer.java`:

```java
package com.imin.iminapi.service.poster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.Base64;
import java.util.List;

@Component
public class ReferenceImageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImageAnalyzer.class);

    private static final String SYSTEM_INSTRUCTION = """
            You are a senior art director. Look at the supplied poster reference images and write
            a SINGLE 2-4 sentence descriptor capturing the shared visual style:
            palette (specific colours, contrast), typography (weight, treatment, family character),
            mood/atmosphere, and composition cues (layout patterns, focal points, framing).
            No bullet points, no headings, no preamble. Plain prose only. Be concrete and concise.
            """;

    private final ChatClient chatClient;
    private final String modelId;

    public ReferenceImageAnalyzer(
            ChatClient chatClient,
            @Value("${imin.reference-analyzer.model-id:openai/gpt-4o-mini}") String modelId) {
        this.chatClient = chatClient;
        this.modelId = modelId;
    }

    public String modelId() {
        return modelId;
    }

    public String analyze(String subStyleTag, List<String> referenceUrls) {
        if (referenceUrls == null || referenceUrls.isEmpty()) {
            log.warn("No reference images for tag '{}' — returning empty descriptor", subStyleTag);
            return "";
        }
        log.info("Analyzing {} reference image(s) for tag '{}'", referenceUrls.size(), subStyleTag);
        return chatClient.prompt()
                .user(u -> {
                    u.text(SYSTEM_INSTRUCTION);
                    for (String ref : referenceUrls) {
                        u.media(toMedia(ref));
                    }
                })
                .call()
                .content();
    }

    private Media toMedia(String urlOrDataUri) {
        if (urlOrDataUri.startsWith("data:")) {
            int comma = urlOrDataUri.indexOf(',');
            int semicolon = urlOrDataUri.indexOf(';');
            String mime = urlOrDataUri.substring(5, semicolon);
            byte[] bytes = Base64.getDecoder().decode(urlOrDataUri.substring(comma + 1));
            return Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType(mime))
                    .data(new ByteArrayResource(bytes))
                    .build();
        }
        try {
            return Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(java.net.URI.create(urlOrDataUri).toURL())
                    .build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to wrap reference URL as Media: " + urlOrDataUri, e);
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -q test -Dtest=ReferenceImageAnalyzerTest`
Expected: PASS for both cases.

If the `Media` builder API differs in your Spring AI version, the test will still pass (it never instantiates `Media`, only stubs `ChatClient`), but the production wiring will fail at runtime in Task 4. If that happens, fix `toMedia(...)` to match the available API surface (search Spring AI docs for the matching `Media.builder()` or `Media.from*(...)` factory).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/ReferenceImageAnalyzer.java \
        src/test/java/com/imin/iminapi/service/poster/ReferenceImageAnalyzerTest.java
git commit -m "feat(poster): add multimodal ReferenceImageAnalyzer service"
```

---

## Task 4: Wire DB cache + analyzer into `ReferenceImageLibrary`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java`
- Modify: `src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java`
- Modify (if missing field): `src/main/resources/application.yaml`

After the existing YAML load, run a second phase that fills the descriptor cache from the DB or by calling the analyzer.

- [ ] **Step 1: Add config to `application.yaml`**

In `src/main/resources/application.yaml`, add a new top-level block (or extend the existing `imin:` block if one exists; otherwise create it):

```yaml
imin:
  reference-analyzer:
    model-id: ${IMIN_REFERENCE_ANALYZER_MODEL:openai/gpt-4o-mini}
```

(This is the same default the analyzer uses; making it explicit at config keeps deployment knob discoverability.)

If `src/test/resources/application.yaml` exists and is used during tests, mirror the same block there so Spring Boot tests don't fail on missing properties.

- [ ] **Step 2: Add the failing tests**

In `src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java`, append three new methods inside the existing class:

```java
    @Autowired private com.imin.iminapi.repository.StyleReferenceAnalysisRepository repo;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ReferenceImageAnalyzer analyzer;

    @org.junit.jupiter.api.BeforeEach
    void resetCache() {
        repo.deleteAll();
    }

    @Test
    void descriptor_cacheMiss_callsAnalyzerAndPersists() {
        org.mockito.Mockito.when(analyzer.modelId()).thenReturn("test-model");
        org.mockito.Mockito.when(analyzer.analyze(org.mockito.ArgumentMatchers.eq("neon_underground"),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("test descriptor for neon");

        // Force a re-load so the second-phase cache pass runs against the (cleared) DB.
        library.reloadDescriptors();

        assertThat(library.descriptor("neon_underground")).isEqualTo("test descriptor for neon");
        assertThat(repo.findById("neon_underground")).isPresent();
    }

    @Test
    void descriptor_cacheHit_skipsAnalyzer() {
        org.mockito.Mockito.when(analyzer.modelId()).thenReturn("test-model");
        // Pre-seed: signature must match what the library will compute for this tag.
        String currentSignature = library.computeCurrentSignatureFor("neon_underground");
        com.imin.iminapi.model.StyleReferenceAnalysis row = new com.imin.iminapi.model.StyleReferenceAnalysis();
        row.setSubStyleTag("neon_underground");
        row.setDescriptor("cached descriptor");
        row.setImageSignature(currentSignature);
        row.setModelId("test-model");
        repo.save(row);

        library.reloadDescriptors();

        assertThat(library.descriptor("neon_underground")).isEqualTo("cached descriptor");
        org.mockito.Mockito.verify(analyzer, org.mockito.Mockito.never())
                .analyze(org.mockito.ArgumentMatchers.eq("neon_underground"),
                         org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void descriptor_signatureStale_reanalyzesAndOverwrites() {
        org.mockito.Mockito.when(analyzer.modelId()).thenReturn("test-model");
        com.imin.iminapi.model.StyleReferenceAnalysis row = new com.imin.iminapi.model.StyleReferenceAnalysis();
        row.setSubStyleTag("neon_underground");
        row.setDescriptor("old descriptor");
        row.setImageSignature("0000000000000000000000000000000000000000000000000000000000000000");
        row.setModelId("test-model");
        repo.save(row);

        org.mockito.Mockito.when(analyzer.analyze(org.mockito.ArgumentMatchers.eq("neon_underground"),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("fresh descriptor");

        library.reloadDescriptors();

        assertThat(library.descriptor("neon_underground")).isEqualTo("fresh descriptor");
        assertThat(repo.findById("neon_underground")).get()
                .extracting(com.imin.iminapi.model.StyleReferenceAnalysis::getDescriptor)
                .isEqualTo("fresh descriptor");
    }

    @Test
    void descriptor_unknownTag_returnsEmpty() {
        assertThat(library.descriptor("not_a_real_tag")).isEmpty();
    }
```

- [ ] **Step 3: Verify they fail to compile**

Run: `./mvnw -q test -Dtest=ReferenceImageLibraryTest`
Expected: compile error — `descriptor()`, `reloadDescriptors()`, and `computeCurrentSignatureFor()` don't exist yet.

- [ ] **Step 4: Modify `ReferenceImageLibrary` to do the cache pass**

In `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java`:

(a) Add imports:

```java
import com.imin.iminapi.model.StyleReferenceAnalysis;
import com.imin.iminapi.repository.StyleReferenceAnalysisRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
```

(b) Add two new constructor-injected fields and update the constructor:

```java
    private final StyleReferenceAnalysisRepository analysisRepo;
    private final ReferenceImageAnalyzer analyzer;
    private final Map<String, String> descriptors = new HashMap<>();

    public ReferenceImageLibrary(
            ResourceLoader resourceLoader,
            StyleReferenceAnalysisRepository analysisRepo,
            ReferenceImageAnalyzer analyzer,
            @Value("${poster.references.config-file:classpath:poster-references.yaml}") String configFile) {
        this.resourceLoader = resourceLoader;
        this.analysisRepo = analysisRepo;
        this.analyzer = analyzer;
        this.configFile = configFile;
    }
```

(c) At the bottom of the existing `@PostConstruct void load()` method, append:

```java
        loadDescriptors();
```

(d) Add three new methods after the existing `loadBytes(...)` method:

```java
    public String descriptor(String subStyleTag) {
        return descriptors.getOrDefault(subStyleTag, "");
    }

    /** Test hook — re-runs the cache check pass without reloading the YAML. */
    public void reloadDescriptors() {
        loadDescriptors();
    }

    /** Test hook — exposes the signature this library computes for a tag right now. */
    public String computeCurrentSignatureFor(String subStyleTag) {
        return imageSignature(toSignatureInputs(subStyleTag));
    }

    private void loadDescriptors() {
        descriptors.clear();
        String currentModel = analyzer.modelId();

        List<CompletableFuture<Map.Entry<String, String>>> futures = byTag.keySet().stream()
                .map(tag -> CompletableFuture.supplyAsync(() ->
                        Map.entry(tag, resolveDescriptor(tag, currentModel))))
                .toList();

        for (CompletableFuture<Map.Entry<String, String>> f : futures) {
            try {
                Map.Entry<String, String> entry = f.join();
                if (!entry.getValue().isEmpty()) {
                    descriptors.put(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                log.warn("Descriptor task failed: {}", e.getMessage());
            }
        }
        log.info("Style descriptors loaded for {} of {} tags", descriptors.size(), byTag.size());
    }

    private String resolveDescriptor(String tag, String currentModel) {
        try {
            String signature = imageSignature(toSignatureInputs(tag));
            Optional<StyleReferenceAnalysis> existing = analysisRepo.findById(tag);
            if (existing.isPresent()
                    && signature.equals(existing.get().getImageSignature())
                    && currentModel.equals(existing.get().getModelId())) {
                return existing.get().getDescriptor();
            }

            ReferenceImageSet refs = forTag(tag);
            String descriptor = analyzer.analyze(tag, refs.referenceUrls());
            if (descriptor == null || descriptor.isBlank()) {
                log.warn("Empty descriptor for tag '{}' — not persisting", tag);
                return "";
            }

            StyleReferenceAnalysis row = existing.orElseGet(StyleReferenceAnalysis::new);
            row.setSubStyleTag(tag);
            row.setDescriptor(descriptor);
            row.setImageSignature(signature);
            row.setModelId(currentModel);
            row.setAnalyzedAt(LocalDateTime.now());
            analysisRepo.save(row);
            return descriptor;
        } catch (RuntimeException e) {
            log.warn("Failed to analyze references for tag '{}': {}", tag, e.getMessage());
            return "";
        }
    }

    private List<SignatureInput> toSignatureInputs(String tag) {
        List<LoadedReference> refs = byTag.getOrDefault(tag, List.of());
        List<SignatureInput> inputs = new java.util.ArrayList<>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            try {
                inputs.add(new SignatureInput(refs.get(i).id(), bytesFor(refs.get(i))));
            } catch (Exception e) {
                log.warn("Could not read bytes for {}/{}: {}", tag, i, e.getMessage());
            }
        }
        return inputs;
    }

    private byte[] bytesFor(LoadedReference ref) throws IOException {
        String locator = ref.sourceLocator();
        if (locator.startsWith("http://") || locator.startsWith("https://") || locator.startsWith("data:")) {
            // Remote/data URIs are sent to Ideogram as-is; for signature purposes use the URL string bytes.
            return locator.getBytes();
        }
        Resource r = resourceLoader.getResource(locator);
        try (InputStream in = r.getInputStream()) {
            return in.readAllBytes();
        }
    }
```

- [ ] **Step 5: Run the new tests**

Run: `./mvnw -q test -Dtest=ReferenceImageLibraryTest`
Expected: PASS — original 5 + new 4 = 9 cases all green.

If a Spring context fails to start because the new constructor params don't have a bean, double-check that `StyleReferenceAnalysisRepository` (Task 1) and `ReferenceImageAnalyzer` (Task 3) are both committed before this task runs.

- [ ] **Step 6: Run full suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java \
        src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java \
        src/main/resources/application.yaml
# Plus src/test/resources/application.yaml if you edited it
git commit -m "feat(poster): cache style descriptors in DB, regenerate only on change"
```

---

## Task 5: Inject descriptors into the AI prompt

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java`
- Add or modify: `src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java`

`buildPrompt` currently emits `- sub_style_tag: one of <list>`. Replace with the per-tag style guide block (or, if a tag is pinned via the request, an imperative single-tag line).

- [ ] **Step 1: Check whether a test for `buildPrompt` exists**

Run: `ls src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java 2>/dev/null && echo EXISTS || echo MISSING`

If MISSING, create the file in Step 2 with the contents shown. If EXISTS, append the methods inside the existing class and add `ReferenceImageLibrary` mocking if not already present.

- [ ] **Step 2: Write the failing test**

Create or extend `src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java`:

```java
package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.service.poster.ReferenceImageLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiEventDescriptionServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ReferenceImageLibrary library;

    private AiEventDescriptionService service;

    @BeforeEach
    void setUp() {
        service = new AiEventDescriptionService(chatClient, library);
        when(library.tags()).thenReturn(List.of("neon_underground", "chrome_tropical"));
        when(library.descriptor("neon_underground")).thenReturn("Magenta neon and black void.");
        when(library.descriptor("chrome_tropical")).thenReturn("Chrome 3D type, sunset gradient.");
    }

    private EventCreatorRequest req(String pinnedTag) {
        return new EventCreatorRequest(
                "vibe", "tone", "genre", "city",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, null, null, null, null,
                pinnedTag);
    }

    @Test
    void buildPrompt_noPinnedTag_includesStyleGuideForEveryTag() {
        String prompt = service.buildPrompt(req(null), null);

        assertThat(prompt).contains("neon_underground — Magenta neon and black void.");
        assertThat(prompt).contains("chrome_tropical — Chrome 3D type, sunset gradient.");
        assertThat(prompt).contains("pick one and weave its style notes");
    }

    @Test
    void buildPrompt_pinnedTag_emitsSingleImperativeLine() {
        String prompt = service.buildPrompt(req("chrome_tropical"), null);

        assertThat(prompt).contains("sub_style_tag is pre-selected as chrome_tropical");
        assertThat(prompt).contains("Chrome 3D type, sunset gradient.");
        assertThat(prompt).doesNotContain("neon_underground —");
    }

    @Test
    void buildPrompt_descriptorMissing_emitsPlaceholder() {
        when(library.descriptor("neon_underground")).thenReturn("");

        String prompt = service.buildPrompt(req(null), null);

        assertThat(prompt).contains("neon_underground — (no descriptor available)");
    }
}
```

This test references `service.buildPrompt(...)` directly. Today `buildPrompt` is `private`. We promote it to **package-private** in Step 4 to make it testable without spinning a full Spring context.

This test also references `library.tags()` — added in the manual-style-picker work.

- [ ] **Step 3: Verify it fails (compile error)**

Run: `./mvnw -q test -Dtest=AiEventDescriptionServiceTest`
Expected: compile error — the constructor of `AiEventDescriptionService` doesn't take a `ReferenceImageLibrary`, and `buildPrompt` is not visible here.

- [ ] **Step 4: Modify `AiEventDescriptionService`**

Open `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java`. Make four changes:

(a) Add `import com.imin.iminapi.service.poster.ReferenceImageLibrary;` near the other imports.

(b) Update the field block + constructor to inject the library:

```java
    private final ChatClient chatClient;
    private final ReferenceImageLibrary referenceLibrary;

    public AiEventDescriptionService(ChatClient chatClient, ReferenceImageLibrary referenceLibrary) {
        this.chatClient = chatClient;
        this.referenceLibrary = referenceLibrary;
    }
```

(Remove `@RequiredArgsConstructor` from the class annotations if it's there, OR leave it — Lombok will produce the same constructor. Either is fine; explicit constructor is clearer for testability.)

(c) Change `buildPrompt`'s visibility from `private` to package-private (drop the `private` keyword).

(d) Inside `buildPrompt`, replace the existing `sub_style_tag: one of ...` line with the style-guide / pinned-tag block.

Find this block in the current code:

```java
          .append("Return a JSON object with exactly these fields:\n")
          .append("- sub_style_tag: one of ")
          .append(String.join(", ", VALID_SUB_STYLE_TAGS))
          .append("\n- color_palette_description: a brief human-readable description of the dominant colors\n")
```

Replace with:

```java
          .append("Return a JSON object with exactly these fields:\n");
        String pinned = request.subStyleTag();
        if (pinned != null && !pinned.isBlank()) {
            String descriptor = nonBlankOrPlaceholder(referenceLibrary.descriptor(pinned));
            sb.append("- sub_style_tag is pre-selected as ").append(pinned)
              .append(". Use the following style notes in every variant:\n    ")
              .append(descriptor).append("\n");
        } else {
            sb.append("- sub_style_tag: pick one and weave its style notes into every variant\n");
            for (String tag : referenceLibrary.tags()) {
                String descriptor = nonBlankOrPlaceholder(referenceLibrary.descriptor(tag));
                sb.append("    ").append(tag).append(" — ").append(descriptor).append("\n");
            }
        }
        sb.append("- color_palette_description: a brief human-readable description of the dominant colors\n");
```

(e) Add a helper at the bottom of the class (just before the closing `}`):

```java
    private static String nonBlankOrPlaceholder(String s) {
        return (s == null || s.isBlank()) ? "(no descriptor available)" : s;
    }
```

- [ ] **Step 5: Run the new test**

Run: `./mvnw -q test -Dtest=AiEventDescriptionServiceTest`
Expected: PASS — all 3 cases.

- [ ] **Step 6: Run full suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS, no regressions. (`EventCreatorServiceTest` mocks `AiEventDescriptionService` via `@Mock`, so its constructor change doesn't break it. `IminApiApplicationTests` boots the full context, which now requires `ReferenceImageLibrary` to wire in — fine because Task 4 added the constructor params.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java \
        src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java
git commit -m "feat(ai): inject per-tag style descriptors into poster-concept prompt"
```

---

## Task 6: End-to-end smoke test

**Files:** none (manual verification + repo state cleanup)

- [ ] **Step 1: Run the full test suite one more time**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Boot the app**

Make sure Postgres is up:
```bash
docker compose up -d
```

Set required env vars (at minimum `OPENROUTER_API_KEY`; `IMIN_REFERENCE_ANALYZER_MODEL` only if you want to override the default):
```bash
export OPENROUTER_API_KEY=...
./mvnw -q -DskipTests spring-boot:run
```

- [ ] **Step 3: Verify first-boot behavior**

In the application logs, look for:
- `Style descriptors loaded for N of N tags` (info log from `loadDescriptors()`).
- For each tag: an `Analyzing K reference image(s) for tag '...'` log (from `ReferenceImageAnalyzer.analyze`).

Then in another terminal:
```bash
docker compose exec postgres psql -U myuser -d mydatabase \
  -c "SELECT sub_style_tag, length(descriptor) AS len, model_id FROM style_reference_analysis;"
```
Expected: one row per loaded tag (currently 7), each `descriptor` non-empty, `model_id` matches `imin.reference-analyzer.model-id`.

- [ ] **Step 4: Verify second-boot uses cache**

Stop the app (Ctrl-C). Restart it (`./mvnw -q -DskipTests spring-boot:run`).

In the logs:
- The `Analyzing K reference image(s)` messages should NOT appear (cache hit on every tag).
- The `Style descriptors loaded for N of N tags` line should still appear.

- [ ] **Step 5: Verify the prompt actually contains descriptors**

Open `http://localhost:8080/`, generate content + a poster (any preset works). Open the **Debug · Concept + Variant prompts** panel.

Each variant's `ideogramPrompt` should now contain language reflecting the chosen tag's descriptor (e.g. for `neon_underground` you should see neon/magenta/distressed cues). Confirm at least one such cue is present.

- [ ] **Step 6: Verify pin-and-override path**

In the test UI, pick a non-default style (e.g. `chrome_tropical`) and generate. The variants should now reflect chrome/tropical cues, and the response's `subStyleTag` should be `chrome_tropical`.

- [ ] **Step 7: Verify invalidation**

Stop the app. Replace one of the reference PNGs (e.g. overwrite `neon_underground_01_friday_night.png` with another image). Restart.

In the logs, you should see exactly **one** `Analyzing 3 reference image(s) for tag 'neon_underground'` message — only the changed tag re-analyzes. Verify in the DB that the corresponding row's `descriptor` and `analyzed_at` updated.

Restore the original image after testing.

- [ ] **Step 8: Final commit (only if smoke testing required tweaks)**

If you adjusted anything during smoke testing, commit it now with a focused message. Otherwise skip.

---

## Done criteria

- `./mvnw test` is green.
- Booting the app for the first time creates one row per tag in `style_reference_analysis` and logs an `Analyzing ...` line per tag.
- Booting again reuses the cache (no `Analyzing ...` lines).
- Replacing a single reference image triggers re-analysis for that tag only.
- `ideogramPrompt`s in the response/debug panel contain visual cues from the chosen tag's descriptor.
- Pre-selecting a tag in the UI causes the AI to receive the pinned-tag instruction and emit prompts in that style.
