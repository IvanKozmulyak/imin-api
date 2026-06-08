# Ideogram V3 Native Render Flow — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace imin-api's poster renderer with the native Ideogram V3 API — render the art-director's prompt with the event text baked in by the model, validate it with vision gates (text hard, style soft), and on a text-gate failure feed the image back to Ideogram's remix endpoint with a correction prompt instead of re-rolling.

**Architecture:** The Sonnet-4.6 art-director stage, seeded sampler, style cards, exact-text contract, and the two vision-gate clients already exist and are kept. `PosterOrchestrator` is rewired to a single render path: `IdeogramV3Client.generate()` → text gate (hard) → style gate (soft) → on text fail, `IdeogramV3Client.remix(failingImage, correctionPrompt)` up to a budget, then accept best-effort. No QR/address/Satori overlay; the downloaded PNG is final. The Recraft/OpenAI/Replicate render code and overlay compositors are deleted; the (off-render-path) `ImageProvider` enum and Recraft style-training subsystem are left dormant.

**Tech Stack:** Java 17, Spring Boot 4, Spring `RestClient` + multipart (`MultiValueMap`/`ByteArrayResource`), `MockRestServiceServer` for HTTP tests, JUnit 5 + AssertJ + Mockito, Flyway (PostgreSQL; H2 PG-compat in tests), Lombok.

**Reference:** Spec at `docs/superpowers/specs/2026-06-08-ideogram-v3-native-render-design.md`. The Node reference implementation lives at `~/Claude Cowork/OUTPUTS/IMIN/poster-lab/src/clients/ideogram.js`.

**Conventions:** Build/test with `./mvnw`. Run a single test class with `./mvnw test -Dtest=ClassName`, a single method with `./mvnw test -Dtest=ClassName#method`. External services (OpenRouter, Ideogram) are always mocked in tests. Commit after each green task.

---

## File map

**Create**
- `src/main/java/com/imin/iminapi/dto/StyleReferencePart.java` — a reference image as multipart bytes + filename + mime.
- `src/main/java/com/imin/iminapi/config/IdeogramImageConfig.java` — `ideogramRestClient` bean (Api-Key header).
- `src/main/java/com/imin/iminapi/service/poster/IdeogramV3Client.java` — native generate + remix.
- `src/main/resources/db/migration/V37__poster_validation_verdict.sql` — verdict columns.
- `src/test/java/com/imin/iminapi/service/poster/IdeogramV3ClientTest.java`
- `src/test/java/com/imin/iminapi/model/PosterVariantVerdictPersistenceTest.java`

**Modify**
- `src/main/java/com/imin/iminapi/model/PosterVariantEntity.java` — add `validationVerdict`, `validationAttemptsJson`.
- `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java` — add `topReferenceParts(...)`.
- `src/main/java/com/imin/iminapi/service/poster/PosterTextValidationService.java` — `ValidationDecision` carries `missingRequired`/`extraText`.
- `src/main/java/com/imin/iminapi/service/poster/VibeLibrary.java` — load + expose `ideogramStylePreset(id)`.
- `src/main/resources/vibes.yaml` — add `ideogram_style_preset` per vibe.
- `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java` — full rewrite to the Ideogram path.
- `src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java` — rewrite.
- `src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java` — add a case for the new accessor.
- `src/test/java/com/imin/iminapi/service/poster/PosterTextValidationServiceTest.java` — assert the new lists.
- `src/main/resources/application.yaml` — add `ideogram.*`, flip gates on, drop dead render keys.

**Delete**
- `src/main/java/com/imin/iminapi/service/poster/IdeogramClient.java` (Replicate-based) + `ReplicateClient.java`
- `src/main/java/com/imin/iminapi/service/poster/OpenAiImageClient.java` + `src/main/java/com/imin/iminapi/config/OpenAiImageConfig.java`
- `src/main/java/com/imin/iminapi/service/poster/OverlayCompositor.java`, `PosterTextCompositorClient.java`, `src/main/java/com/imin/iminapi/config/PosterCompositorConfig.java`
- Their tests: `IdeogramClientTest.java`, `OpenAiImageClientTest.java`, `OverlayCompositorTest.java`, `PosterTextCompositorClientTest.java`

---

## Task 1: Validation-verdict persistence (migration + entity)

**Files:**
- Create: `src/main/resources/db/migration/V37__poster_validation_verdict.sql`
- Modify: `src/main/java/com/imin/iminapi/model/PosterVariantEntity.java`
- Test: `src/test/java/com/imin/iminapi/model/PosterVariantVerdictPersistenceTest.java`

- [ ] **Step 1: Write the failing test** (mirrors the existing `@DataJpaTest` persistence tests, e.g. `TicketTierRepositoryPersistenceTest`)

```java
package com.imin.iminapi.model;

import com.imin.iminapi.repository.PosterGenerationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PosterVariantVerdictPersistenceTest {

    @Autowired
    PosterGenerationRepository repo;

    @Test
    void persistsValidationVerdictAndAttemptsJson() {
        PosterGeneration g = new PosterGeneration();
        g.setGeneratedEventId(UUID.randomUUID());
        g.setStatus(PosterGenerationStatus.COMPLETE);
        g.setSubStyleTag("brutalist_techno");
        g.setCreativeSeed(1L);

        PosterVariantEntity v = new PosterVariantEntity();
        v.setPosterGeneration(g);
        v.setVariantStyle("people");
        v.setIdeogramPrompt("p");
        v.setStatus(PosterVariantStatus.COMPLETE);
        v.setValidationVerdict("BEST_EFFORT");
        v.setValidationAttemptsJson("[{\"attempt\":0,\"mode\":\"generate\"}]");
        g.getVariants().add(v);

        UUID id = repo.saveAndFlush(g).getId();
        repo.flush();

        PosterGeneration reloaded = repo.findById(id).orElseThrow();
        PosterVariantEntity rv = reloaded.getVariants().get(0);
        assertThat(rv.getValidationVerdict()).isEqualTo("BEST_EFFORT");
        assertThat(rv.getValidationAttemptsJson()).contains("generate");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile** (the setters don't exist yet)

Run: `./mvnw test -Dtest=PosterVariantVerdictPersistenceTest`
Expected: compilation failure — `cannot find symbol method setValidationVerdict`.

- [ ] **Step 3: Add the two columns to the entity**

In `PosterVariantEntity.java`, add these fields after `failureReason` (the class is `@Getter @Setter`, so accessors are generated):

```java
    /** Final gate outcome: ACCEPTED (both gates passed) or BEST_EFFORT (accepted despite a gate). */
    @Column(name = "validation_verdict", length = 16)
    private String validationVerdict;

    /** Per-attempt validation journal as JSON: [{attempt,seed,mode,text,style}]. */
    @Column(name = "validation_attempts_json", columnDefinition = "TEXT")
    private String validationAttemptsJson;
```

- [ ] **Step 4: Write the migration**

Create `V37__poster_validation_verdict.sql`:

```sql
-- Record the post-render vision-gate outcome per variant (Ideogram V3 render flow).
ALTER TABLE poster_variants
    ADD COLUMN validation_verdict VARCHAR(16),
    ADD COLUMN validation_attempts_json TEXT;
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=PosterVariantVerdictPersistenceTest`
Expected: PASS (Flyway applies V37 on the H2 test DB; the round-trip succeeds).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V37__poster_validation_verdict.sql \
        src/main/java/com/imin/iminapi/model/PosterVariantEntity.java \
        src/test/java/com/imin/iminapi/model/PosterVariantVerdictPersistenceTest.java
git commit -m "feat(poster): persist vision-gate validation verdict per variant"
```

---

## Task 2: `StyleReferencePart` + `ReferenceImageLibrary.topReferenceParts`

Provides the top-N reference images as raw multipart parts for Ideogram's `style_reference_images` (≤3 files, ≤10 MB total).

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/StyleReferencePart.java`
- Modify: `src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java`

- [ ] **Step 1: Create the DTO**

```java
package com.imin.iminapi.dto;

/** One curated reference image as a multipart part: raw bytes + filename + MIME type. */
public record StyleReferencePart(byte[] bytes, String filename, String mimeType) {}
```

- [ ] **Step 2: Write the failing test**

Add to `ReferenceImageLibraryTest.java` (it already loads the real classpath references for the seeded vibes; `brutalist_techno` has curated flyers):

```java
    @Test
    void topReferenceParts_capsCountAndCarriesMime() {
        ReferenceImageLibrary lib = loadedLibrary(); // existing helper that @PostConstruct-loads the library

        java.util.List<com.imin.iminapi.dto.StyleReferencePart> parts =
                lib.topReferenceParts("brutalist_techno", 3, 10L * 1024 * 1024);

        assertThat(parts).isNotEmpty();
        assertThat(parts.size()).isLessThanOrEqualTo(3);
        assertThat(parts).allSatisfy(p -> {
            assertThat(p.bytes()).isNotEmpty();
            assertThat(p.filename()).isNotBlank();
            assertThat(p.mimeType()).startsWith("image/");
        });
    }

    @Test
    void topReferenceParts_unknownTagIsEmpty() {
        assertThat(loadedLibrary().topReferenceParts("does_not_exist", 3, 10L * 1024 * 1024)).isEmpty();
    }
```

> If `ReferenceImageLibraryTest` builds the library differently (check its existing setup), reuse that exact construction instead of `loadedLibrary()`; the assertions stay the same.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ReferenceImageLibraryTest#topReferenceParts_capsCountAndCarriesMime`
Expected: FAIL — `cannot find symbol method topReferenceParts`.

- [ ] **Step 4: Implement the accessor**

Add to `ReferenceImageLibrary.java` (uses the existing private `bytesFor`, `guessMime`, and `LoadedReference`):

```java
    /**
     * Up to {@code maxRefs} curated references for a tag as raw multipart parts, in load order,
     * capped at {@code maxTotalBytes} across all parts. Remote/data-URI references are skipped
     * (Ideogram uploads file bytes). Used for Ideogram V3 {@code style_reference_images}.
     */
    public List<com.imin.iminapi.dto.StyleReferencePart> topReferenceParts(
            String subStyleTag, int maxRefs, long maxTotalBytes) {
        List<LoadedReference> refs = byTag.getOrDefault(subStyleTag, List.of());
        List<com.imin.iminapi.dto.StyleReferencePart> out = new ArrayList<>();
        long total = 0;
        for (LoadedReference ref : refs) {
            if (out.size() >= maxRefs) break;
            String loc = ref.sourceLocator();
            if (loc.startsWith("http://") || loc.startsWith("https://") || loc.startsWith("data:")) {
                continue;
            }
            byte[] bytes;
            try {
                bytes = bytesFor(ref);
            } catch (Exception e) {
                log.warn("Skipping reference {} for tag {}: {}", ref.id(), subStyleTag, e.getMessage());
                continue;
            }
            if (bytes == null || bytes.length == 0) continue;
            if (total + bytes.length > maxTotalBytes) {
                log.warn("[ideogram-refs] {}: dropping '{}' — exceeds {} byte cap", subStyleTag, ref.id(), maxTotalBytes);
                continue;
            }
            total += bytes.length;
            out.add(new com.imin.iminapi.dto.StyleReferencePart(bytes, ref.id(), guessMime(ref.id())));
        }
        return out;
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ReferenceImageLibraryTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/StyleReferencePart.java \
        src/main/java/com/imin/iminapi/service/poster/ReferenceImageLibrary.java \
        src/test/java/com/imin/iminapi/service/poster/ReferenceImageLibraryTest.java
git commit -m "feat(poster): expose top-N reference images as multipart parts"
```

---

## Task 3: `IdeogramImageConfig` RestClient bean

**Files:**
- Create: `src/main/java/com/imin/iminapi/config/IdeogramImageConfig.java`

- [ ] **Step 1: Create the config** (mirrors `RecraftImageConfig`, but the native Ideogram API authenticates with an `Api-Key` header, not bearer)

```java
package com.imin.iminapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the native Ideogram V3 API (generate + remix). Authenticates with the
 * {@code Api-Key} header. Fails fast with a clear message when the key is missing so a
 * misconfigured deploy surfaces a config error rather than an opaque 401.
 */
@Configuration
public class IdeogramImageConfig {

    private static final Logger log = LoggerFactory.getLogger(IdeogramImageConfig.class);

    @Value("${ideogram.api-key:${IDEOGRAM_API_KEY:}}")
    private String apiKey;

    @Value("${ideogram.base-url:https://api.ideogram.ai}")
    private String baseUrl;

    @Bean
    public RestClient ideogramRestClient() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("IDEOGRAM_API_KEY is not set — poster rendering will fail with 401. "
                    + "Set the environment variable and restart the app.");
        }
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException(
                                "IDEOGRAM_API_KEY is not configured. Set the environment variable and "
                                + "restart the app before generating posters.");
                    }
                    request.getHeaders().set("Api-Key", apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/config/IdeogramImageConfig.java
git commit -m "feat(poster): add native Ideogram RestClient (Api-Key auth)"
```

---

## Task 4: `IdeogramV3Client.generate()`

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/poster/IdeogramV3Client.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/IdeogramV3ClientTest.java`

- [ ] **Step 1: Write the failing test** (multipart asserts via `content().string(containsString(...))`, the same approach as `RecraftClientTest.createStyle_*`; the generate POST returns a CDN URL, then the client GETs that URL for bytes — two ordered expectations)

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.StyleReferencePart;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdeogramV3ClientTest {

    private record Harness(IdeogramV3Client client, MockRestServiceServer server) {}

    private Harness harness() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.ideogram.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IdeogramV3Client client = new IdeogramV3Client(builder.build(), "QUALITY", "TURBO", true);
        return new Harness(client, server);
    }

    @Test
    void generate_withRefs_sendsMultipartFieldsAndDownloadsImage() {
        Harness h = harness();
        // 1) generate POST returns a CDN url
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(Matchers.containsString("name=\"prompt\"")))
                .andExpect(content().string(Matchers.containsString("a \"VOID\" poster")))
                .andExpect(content().string(Matchers.containsString("name=\"aspect_ratio\"")))
                .andExpect(content().string(Matchers.containsString("4x5")))
                .andExpect(content().string(Matchers.containsString("name=\"rendering_speed\"")))
                .andExpect(content().string(Matchers.containsString("QUALITY")))
                .andExpect(content().string(Matchers.containsString("name=\"magic_prompt\"")))
                .andExpect(content().string(Matchers.containsString("OFF")))
                .andExpect(content().string(Matchers.containsString("name=\"enable_copyright_detection\"")))
                .andExpect(content().string(Matchers.containsString("name=\"seed\"")))
                .andExpect(content().string(Matchers.containsString("name=\"style_reference_images\"")))
                .andExpect(content().string(Matchers.containsString("filename=\"ref0.png\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("style_preset"))))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/x.png\"}]}",
                        MediaType.APPLICATION_JSON));
        // 2) client downloads the url
        h.server().expect(requestTo("https://cdn.ideogram.ai/x.png"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new byte[]{1, 2, 3, 4}, MediaType.IMAGE_PNG));

        IdeogramV3Client.IdeogramResult r = h.client().generate(
                "a \"VOID\" poster", 42L,
                List.of(new StyleReferencePart(new byte[]{9, 9}, "ref0.png", "image/png")),
                "HIGH_CONTRAST"); // preset present but refs win

        assertThat(r.imageBytes()).containsExactly(1, 2, 3, 4);
        assertThat(r.seed()).isEqualTo(42L);
        h.server().verify();
    }

    @Test
    void generate_noRefs_fallsBackToStylePreset() {
        Harness h = harness();
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/generate"))
                .andExpect(content().string(Matchers.containsString("name=\"style_preset\"")))
                .andExpect(content().string(Matchers.containsString("HIGH_CONTRAST")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("style_reference_images"))))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/y.png\"}]}",
                        MediaType.APPLICATION_JSON));
        h.server().expect(requestTo("https://cdn.ideogram.ai/y.png"))
                .andRespond(withSuccess(new byte[]{5, 6}, MediaType.IMAGE_PNG));

        IdeogramV3Client.IdeogramResult r = h.client().generate("p", 7L, List.of(), "HIGH_CONTRAST");

        assertThat(r.imageBytes()).containsExactly(5, 6);
        h.server().verify();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=IdeogramV3ClientTest`
Expected: FAIL — `IdeogramV3Client` does not exist.

- [ ] **Step 3: Implement the client** (generate only; remix added in Task 5)

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.StyleReferencePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Native Ideogram V3 client (direct API, not Replicate). Auth is the {@code Api-Key} header
 * (see {@link com.imin.iminapi.config.IdeogramImageConfig}). The art director already wrote the
 * prompt, so {@code magic_prompt=OFF}. Ideogram V3 accepts EXACTLY ONE style control, so curated
 * reference images win when present and the per-vibe {@code style_preset} is the no-refs fallback.
 * The response carries a temporary image URL, which is downloaded immediately (the link expires).
 */
@Component
public class IdeogramV3Client {

    private static final Logger log = LoggerFactory.getLogger(IdeogramV3Client.class);

    static final String GENERATE_PATH = "/v1/ideogram-v3/generate";
    static final String REMIX_PATH = "/v1/ideogram-v3/remix";
    static final String ASPECT_RATIO = "4x5";   // native uses NxM
    static final String MAGIC_PROMPT = "OFF";

    private final RestClient ideogramRestClient;
    private final String generateSpeed;
    private final String remixSpeed;
    private final boolean copyrightDetection;

    public IdeogramV3Client(
            RestClient ideogramRestClient,
            @Value("${ideogram.generate.rendering-speed:QUALITY}") String generateSpeed,
            @Value("${ideogram.remix.rendering-speed:TURBO}") String remixSpeed,
            @Value("${ideogram.copyright-detection:true}") boolean copyrightDetection) {
        this.ideogramRestClient = ideogramRestClient;
        this.generateSpeed = (generateSpeed == null || generateSpeed.isBlank()) ? "QUALITY" : generateSpeed;
        this.remixSpeed = (remixSpeed == null || remixSpeed.isBlank()) ? "TURBO" : remixSpeed;
        this.copyrightDetection = copyrightDetection;
    }

    public record IdeogramResult(byte[] imageBytes, long seed) {}

    /** Text-to-image generate. */
    public IdeogramResult generate(String prompt, long seed, List<StyleReferencePart> styleRefs, String stylePreset) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("prompt", prompt);
        parts.add("aspect_ratio", ASPECT_RATIO);
        parts.add("rendering_speed", generateSpeed);
        parts.add("magic_prompt", MAGIC_PROMPT);
        parts.add("enable_copyright_detection", String.valueOf(copyrightDetection));
        parts.add("seed", String.valueOf(seed));
        applyStyleControl(parts, styleRefs, stylePreset);
        log.info("[ideogram v3 generate] promptLen={} speed={} {} seed={}",
                prompt.length(), generateSpeed, styleLabel(styleRefs, stylePreset), seed);
        return new IdeogramResult(post(GENERATE_PATH, parts), seed);
    }

    /** Apply exactly one style control: reference images win; style_preset is the no-refs fallback. */
    private void applyStyleControl(MultiValueMap<String, Object> parts,
                                   List<StyleReferencePart> styleRefs, String stylePreset) {
        boolean hasRefs = styleRefs != null && !styleRefs.isEmpty();
        if (hasRefs) {
            for (StyleReferencePart ref : styleRefs) {
                parts.add("style_reference_images", filePart(ref));
            }
        } else if (stylePreset != null && !stylePreset.isBlank()) {
            parts.add("style_preset", stylePreset);
        }
    }

    private static HttpEntity<ByteArrayResource> filePart(StyleReferencePart ref) {
        final String filename = ref.filename();
        ByteArrayResource resource = new ByteArrayResource(ref.bytes()) {
            @Override public String getFilename() { return filename; }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                ref.mimeType() == null || ref.mimeType().isBlank() ? "image/png" : ref.mimeType()));
        return new HttpEntity<>(resource, headers);
    }

    private static String styleLabel(List<StyleReferencePart> refs, String preset) {
        if (refs != null && !refs.isEmpty()) return "refs=" + refs.size();
        if (preset != null && !preset.isBlank()) return "preset=" + preset;
        return "style=none";
    }

    /** POST the multipart body, then download the returned image URL (it expires quickly). */
    private byte[] post(String path, MultiValueMap<String, Object> parts) {
        Instant start = Instant.now();
        GenerateResponse resp = ideogramRestClient.post()
                .uri(path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(GenerateResponse.class);
        String url = resp == null || resp.data() == null || resp.data().isEmpty() ? null : resp.data().get(0).url();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Ideogram returned no image URL");
        }
        byte[] bytes = ideogramRestClient.get().uri(URI.create(url)).retrieve().body(byte[].class);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Ideogram image download was empty: " + url);
        }
        log.info("[ideogram v3] {} -> {} KB in {} ms", path, bytes.length / 1024,
                Duration.between(start, Instant.now()).toMillis());
        return bytes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateResponse(List<Datum> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Datum(String url) {}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=IdeogramV3ClientTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/IdeogramV3Client.java \
        src/test/java/com/imin/iminapi/service/poster/IdeogramV3ClientTest.java
git commit -m "feat(poster): native Ideogram V3 generate (multipart, one style control)"
```

---

## Task 5: `IdeogramV3Client.remix()`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/IdeogramV3Client.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/IdeogramV3ClientTest.java`

- [ ] **Step 1: Write the failing test** (add to `IdeogramV3ClientTest`)

```java
    @Test
    void remix_sendsImagePartAndImageWeightAndPrompt() {
        Harness h = harness();
        h.server().expect(requestTo("https://api.ideogram.ai/v1/ideogram-v3/remix"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"image\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"image_weight\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("70")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"rendering_speed\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TURBO")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CORRECTION")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"magic_prompt\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("OFF")))
                .andRespond(withSuccess("{\"data\":[{\"url\":\"https://cdn.ideogram.ai/z.png\"}]}",
                        MediaType.APPLICATION_JSON));
        h.server().expect(requestTo("https://cdn.ideogram.ai/z.png"))
                .andRespond(withSuccess(new byte[]{7, 8}, MediaType.IMAGE_PNG));

        IdeogramV3Client.IdeogramResult r = h.client().remix(
                new byte[]{1, 1, 1, 1}, "p\n\nCORRECTION — fix text", 70, 99L, List.of(), "HIGH_CONTRAST");

        assertThat(r.imageBytes()).containsExactly(7, 8);
        assertThat(r.seed()).isEqualTo(99L);
        h.server().verify();
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=IdeogramV3ClientTest#remix_sendsImagePartAndImageWeightAndPrompt`
Expected: FAIL — `cannot find symbol method remix`.

- [ ] **Step 3: Add the `remix` method** (insert after `generate` in `IdeogramV3Client`)

```java
    /** Image-conditioned remix: feed a failing image back with a corrective prompt to fix the text. */
    public IdeogramResult remix(byte[] image, String prompt, int imageWeight, long seed,
                                List<StyleReferencePart> styleRefs, String stylePreset) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", filePart(new StyleReferencePart(image, "source.png", "image/png")));
        parts.add("prompt", prompt);
        parts.add("image_weight", String.valueOf(imageWeight));
        parts.add("aspect_ratio", ASPECT_RATIO);
        parts.add("rendering_speed", remixSpeed);
        parts.add("magic_prompt", MAGIC_PROMPT);
        parts.add("seed", String.valueOf(seed));
        applyStyleControl(parts, styleRefs, stylePreset);
        log.info("[ideogram v3 remix] promptLen={} weight={} {} seed={}",
                prompt.length(), imageWeight, styleLabel(styleRefs, stylePreset), seed);
        return new IdeogramResult(post(REMIX_PATH, parts), seed);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=IdeogramV3ClientTest`
Expected: PASS (all generate + remix tests green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/IdeogramV3Client.java \
        src/test/java/com/imin/iminapi/service/poster/IdeogramV3ClientTest.java
git commit -m "feat(poster): native Ideogram V3 remix for corrective text re-render"
```

---

## Task 6: Text gate exposes `missingRequired` / `extraText`

The correction prompt needs the structured gate output. Extend the decision record without changing the reason string (the existing test only reads `.accepted()`/`.reason()`).

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/PosterTextValidationService.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/PosterTextValidationServiceTest.java`

- [ ] **Step 1: Write the failing test** (add to `PosterTextValidationServiceTest`; reuse the existing test's rejection setup that already yields a non-empty `missingRequired`/`extraText` — copy its `client` stubbing for the failing case)

```java
    @Test
    void decision_carriesStructuredMissingAndExtra() {
        // Arrange the client to reject with structured lists (same stub shape as the existing
        // "rejects when required text missing" test in this file).
        when(client.validate(any(), any())).thenReturn(
                new PosterTextValidationClient.ValidationResult(
                        false, java.util.List.of("BIG NIGHT - BERLIN"), java.util.List.of("BAG NIGKT")));

        PosterTextValidationService.ValidationDecision decision =
                service(true).validateOrExplain(new byte[]{1}, specWithRequired());

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.missingRequired()).containsExactly("BIG NIGHT - BERLIN");
        assertThat(decision.extraText()).containsExactly("BAG NIGKT");
    }
```

> Match the field/helper names already used in `PosterTextValidationServiceTest` (e.g. how it builds the service and a `PosterTextSpec` with required text). If the test uses inline construction instead of `service(true)`/`specWithRequired()`, reuse that exact form.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=PosterTextValidationServiceTest#decision_carriesStructuredMissingAndExtra`
Expected: FAIL — `cannot find symbol method missingRequired`.

- [ ] **Step 3: Extend the record and populate it**

Replace the body of `PosterTextValidationService` (keep the class/constructor) with:

```java
    public ValidationDecision validateOrExplain(byte[] imageBytes, PosterTextSpec spec) {
        if (!enabled || spec.required().isEmpty()) {
            return ValidationDecision.accepted();
        }

        PosterTextValidationClient.ValidationResult result = client.validate(imageBytes, spec);
        if (result.accepted()) {
            return ValidationDecision.accepted();
        }

        return new ValidationDecision(false,
                "missing required text: " + result.missingRequired() + "; extra text: " + result.extraText(),
                result.missingRequired() == null ? java.util.List.of() : result.missingRequired(),
                result.extraText() == null ? java.util.List.of() : result.extraText());
    }

    public record ValidationDecision(
            boolean accepted, String reason,
            java.util.List<String> missingRequired, java.util.List<String> extraText) {
        public static ValidationDecision accepted() {
            return new ValidationDecision(true, null, java.util.List.of(), java.util.List.of());
        }
    }
```

- [ ] **Step 4: Run the text-validation tests to verify they pass**

Run: `./mvnw test -Dtest=PosterTextValidationServiceTest`
Expected: PASS (existing reason-string assertions unchanged; new structured assertions green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/PosterTextValidationService.java \
        src/test/java/com/imin/iminapi/service/poster/PosterTextValidationServiceTest.java
git commit -m "feat(poster): text gate exposes missing/extra text for correction prompts"
```

---

## Task 7: Per-vibe Ideogram style preset in `VibeLibrary`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/VibeLibrary.java`
- Modify: `src/main/resources/vibes.yaml`
- Test: `src/test/java/com/imin/iminapi/service/poster/VibeLibraryTest.java`

- [ ] **Step 1: Write the failing test** (add to `VibeLibraryTest`, which already loads the real `vibes.yaml`)

```java
    @Test
    void exposesIdeogramStylePresetPerVibe() {
        VibeLibrary lib = loadedLibrary(); // reuse this test's existing loaded-library helper

        assertThat(lib.ideogramStylePreset("brutalist_techno")).isEqualTo("HIGH_CONTRAST");
        assertThat(lib.ideogramStylePreset("berlin_minimal")).isEqualTo("MONOCHROME");
        assertThat(lib.ideogramStylePreset("nope")).isNull();
    }
```

> Use whatever construction `VibeLibraryTest` already uses to get a loaded `VibeLibrary`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=VibeLibraryTest#exposesIdeogramStylePresetPerVibe`
Expected: FAIL — `cannot find symbol method ideogramStylePreset`.

- [ ] **Step 3: Load and expose the preset map** in `VibeLibrary`

Add the field (near `byId`):

```java
    private Map<String, String> ideogramPresetById = Collections.emptyMap();
```

In `load()`, inside the `if (vibesRaw instanceof List<?> list)` loop, after `Vibe v = toVibe(...); ids.put(v.id(), v);`, also collect the preset. Replace the loop body with:

```java
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) m;
                    Vibe v = toVibe(mm);
                    ids.put(v.id(), v);
                    String preset = str(mm, "ideogram_style_preset");
                    if (preset != null && !preset.isBlank()) presets.put(v.id(), preset);
                    for (String g : v.genres()) {
                        genres.putIfAbsent(g.toLowerCase(), v.id());
                    }
                }
```

Declare `Map<String, String> presets = new LinkedHashMap<>();` alongside `ids`/`genres`, and after the loop add `ideogramPresetById = presets;`. Then add the accessor:

```java
    /** The vibe's Ideogram V3 {@code style_preset} fallback (used only when it has no reference images). */
    public String ideogramStylePreset(String vibeId) {
        return vibeId == null ? null : ideogramPresetById.get(vibeId);
    }
```

- [ ] **Step 4: Add the presets to `vibes.yaml`**

For each vibe entry, add an `ideogram_style_preset:` key with the value from this table (ported from poster-lab):

```
acid_rave_y2k:          90S_NOSTALGIA
afro_amapiano:          BRIGHT_ART
berlin_minimal:         MONOCHROME
brutalist_techno:       HIGH_CONTRAST
dark_experimental:      DARK_AURA
disco_italo:            RIVIERA_POP
dnb_jungle:             GRAFFITI_I
hyperpop_club:          POP_ART
industrial_hard_groove: BLUEPRINT
liquid_melodic:         ETHEREAL_PARTY
open_air_festival:      GOLDEN_HOUR
psytrance_goa:          SURREAL_COLLAGE
```

Example — add the line to the `brutalist_techno` block:

```yaml
  brutalist_techno:
    id: brutalist_techno
    name: "Brutalist Techno"
    # ...existing keys...
    ideogram_style_preset: HIGH_CONTRAST
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=VibeLibraryTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/VibeLibrary.java \
        src/main/resources/vibes.yaml \
        src/test/java/com/imin/iminapi/service/poster/VibeLibraryTest.java
git commit -m "feat(poster): per-vibe Ideogram style_preset fallback"
```

---

## Task 8: Rewrite `PosterOrchestrator` to the Ideogram render flow

The big one. Single render path; text gate hard with corrective remix; style gate soft; verdict persisted; no overlays.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java` (full rewrite)
- Test: `src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java` (rewrite)

- [ ] **Step 1: Rewrite the test** to the new collaborators and behaviors

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterTextSpec;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.StyleReferencePart;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.repository.PosterGenerationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class PosterOrchestratorTest {

    private IdeogramV3Client ideogram;
    private VibeLibrary vibeLibrary;
    private StyleCardLibrary styleCardLibrary;
    private ReferenceImageLibrary referenceLibrary;
    private PosterTextSpecFactory textSpecFactory;
    private PosterTextValidationService textValidation;
    private PosterStyleValidationService styleValidation;
    private PosterImageStorage storage;
    private PosterGenerationRepository repo;

    @BeforeEach
    void setUp() {
        ideogram = mock(IdeogramV3Client.class);
        vibeLibrary = mock(VibeLibrary.class);
        styleCardLibrary = mock(StyleCardLibrary.class);
        referenceLibrary = mock(ReferenceImageLibrary.class);
        textSpecFactory = mock(PosterTextSpecFactory.class);
        textValidation = mock(PosterTextValidationService.class);
        styleValidation = mock(PosterStyleValidationService.class);
        storage = mock(PosterImageStorage.class);
        repo = mock(PosterGenerationRepository.class);

        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.writePng(any())).thenReturn("https://img/x.png");
        when(textSpecFactory.from(any())).thenReturn(new PosterTextSpec(List.of("TITLE"), List.of("TITLE")));
        when(referenceLibrary.topReferenceParts(any(), anyInt(), anyLong()))
                .thenReturn(List.of(new StyleReferencePart(new byte[]{1}, "r0.png", "image/png")));
        when(vibeLibrary.byId(any())).thenReturn(Optional.of(brutalist()));
        when(vibeLibrary.ideogramStylePreset(any())).thenReturn("HIGH_CONTRAST");
        when(styleCardLibrary.get(any())).thenReturn(Optional.of(mock(StyleCard.class)));
    }

    private PosterOrchestrator orchestrator() {
        return new PosterOrchestrator(ideogram, vibeLibrary, styleCardLibrary, referenceLibrary,
                textSpecFactory, textValidation, styleValidation, storage, repo,
                /*maxRegenerations*/ 2, /*remixImageWeight*/ 70, /*maxReferences*/ 3, /*maxConcurrent*/ 6);
    }

    private static Vibe brutalist() {
        return new Vibe("brutalist_techno", "Brutalist Techno", List.of("techno"), "vs",
                List.of("#000"), "typo", "comp", List.of(), List.of(), null, List.of(), null,
                null, false, "subj", null, null);
    }

    private static EventCreatorRequest req() {
        return new EventCreatorRequest("v", "energetic", "techno", "Berlin", LocalDate.now(),
                List.of("instagram"), null, null, "Big Night", null, null, null, "brutalist_techno", null);
    }

    private static PosterConcept concept() {
        List<PosterVariant> vs = List.of(
                new PosterVariant("people", "people prompt with TITLE", "4:5", "Design"),
                new PosterVariant("object", "object prompt with TITLE", "4:5", "Design"),
                new PosterVariant("typographic", "typographic prompt with TITLE", "4:5", "Design"));
        return new PosterConcept("brutalist_techno", "colors", vs);
    }

    private static PosterTextValidationService.ValidationDecision textOk() {
        return PosterTextValidationService.ValidationDecision.accepted();
    }

    private static PosterTextValidationService.ValidationDecision textFail() {
        return new PosterTextValidationService.ValidationDecision(false, "missing", List.of("TITLE"), List.of("BLRG"));
    }

    private static PosterStyleValidationService.ValidationDecision styleOk() {
        return new PosterStyleValidationService.ValidationDecision(true, null);
    }

    private static PosterStyleValidationService.ValidationDecision styleFail() {
        return new PosterStyleValidationService.ValidationDecision(false, "wrong hero");
    }

    @Test
    void happyPath_generateOnce_textAndStylePass_acceptedVerdict() {
        when(ideogram.generate(any(), anyLong(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        assertThat(r.posters()).hasSize(3);
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void textFails_thenRemixCorrects_remixPromptCarriesMissingText() {
        when(ideogram.generate(any(), anyLong(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 2L));
        // first call per variant fails text, second passes; style always passes
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textFail(), textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        verify(ideogram, times(3)).remix(any(), any(), eq(70), anyLong(), any(), any());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ideogram, times(3)).remix(any(), prompt.capture(), anyInt(), anyLong(), any(), any());
        assertThat(prompt.getValue()).contains("CORRECTION").contains("TITLE");
    }

    @Test
    void textNeverPasses_acceptsBestEffortWithJournal() {
        when(ideogram.generate(any(), anyLong(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 2L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textFail());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        // 1 generate + 2 remixes (maxRegenerations=2) per variant, then best-effort COMPLETE
        verify(ideogram, times(6)).remix(any(), any(), anyInt(), anyLong(), any(), any());
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        ArgumentCaptor<com.imin.iminapi.model.PosterGeneration> saved =
                ArgumentCaptor.forClass(com.imin.iminapi.model.PosterGeneration.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        com.imin.iminapi.model.PosterVariantEntity v = saved.getValue().getVariants().get(0);
        assertThat(v.getValidationVerdict()).isEqualTo("BEST_EFFORT");
        assertThat(v.getValidationAttemptsJson()).contains("remix");
    }

    @Test
    void textPasses_styleSoftFails_acceptsBestEffort_noRemix() {
        when(ideogram.generate(any(), anyLong(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleFail());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any());
    }
}
```

> Confirm the real `PosterVariant`, `PosterConcept`, and `PosterTextSpec` constructor shapes match (check those record sources); adjust the test constructors if the field order differs. The behavior asserted does not change.

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./mvnw test -Dtest=PosterOrchestratorTest`
Expected: compilation failure — the new constructor and collaborators don't exist yet.

- [ ] **Step 3: Replace `PosterOrchestrator.java` with the Ideogram flow**

```java
package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterTextSpec;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.StyleReferencePart;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.model.PosterGeneration;
import com.imin.iminapi.model.PosterGenerationStatus;
import com.imin.iminapi.model.PosterVariantEntity;
import com.imin.iminapi.model.PosterVariantStatus;
import com.imin.iminapi.repository.PosterGenerationRepository;
import com.imin.iminapi.service.ai.CreativeDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders the art director's 3 concept variants via the native Ideogram V3 API, validates each
 * with the vision gates, and persists the result.
 *
 * <p>Per variant: generate once; run the HARD text gate; on failure feed the failing image back to
 * Ideogram's remix endpoint with a correction prompt built from the gate's missing/extra text, up to
 * {@code poster.validation.max-regenerations} times, then accept best-effort. The SOFT style gate is
 * advisory — a style-only failure is accepted best-effort without another render. Text is fully baked
 * by the model; there is no QR/address/Satori overlay.
 */
@Service
public class PosterOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PosterOrchestrator.class);

    private static final int VARIANT_POOL_SIZE = 3;
    private static final long MAX_REF_TOTAL_BYTES = 10L * 1024 * 1024; // Ideogram cap
    private static final String VERDICT_ACCEPTED = "ACCEPTED";
    private static final String VERDICT_BEST_EFFORT = "BEST_EFFORT";
    private static final AtomicInteger POOL_THREAD_SEQ = new AtomicInteger();

    private final IdeogramV3Client ideogramClient;
    private final VibeLibrary vibeLibrary;
    private final StyleCardLibrary styleCardLibrary;
    private final ReferenceImageLibrary referenceLibrary;
    private final PosterTextSpecFactory textSpecFactory;
    private final PosterTextValidationService textValidation;
    private final PosterStyleValidationService styleValidation;
    private final PosterImageStorage storage;
    private final PosterGenerationRepository generationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRegenerations;
    private final int remixImageWeight;
    private final int maxReferences;
    private final Semaphore renderCap;
    private final ExecutorService variantPool;

    public PosterOrchestrator(
            IdeogramV3Client ideogramClient,
            VibeLibrary vibeLibrary,
            StyleCardLibrary styleCardLibrary,
            ReferenceImageLibrary referenceLibrary,
            PosterTextSpecFactory textSpecFactory,
            PosterTextValidationService textValidation,
            PosterStyleValidationService styleValidation,
            PosterImageStorage storage,
            PosterGenerationRepository generationRepository,
            @Value("${poster.validation.max-regenerations:2}") int maxRegenerations,
            @Value("${ideogram.remix.image-weight:70}") int remixImageWeight,
            @Value("${ideogram.max-references:3}") int maxReferences,
            @Value("${poster.render.max-concurrent:${replicate.max-concurrent:6}}") int maxConcurrent) {
        this.ideogramClient = ideogramClient;
        this.vibeLibrary = vibeLibrary;
        this.styleCardLibrary = styleCardLibrary;
        this.referenceLibrary = referenceLibrary;
        this.textSpecFactory = textSpecFactory;
        this.textValidation = textValidation;
        this.styleValidation = styleValidation;
        this.storage = storage;
        this.generationRepository = generationRepository;
        this.maxRegenerations = Math.max(0, maxRegenerations);
        this.remixImageWeight = remixImageWeight;
        this.maxReferences = Math.max(0, maxReferences);
        this.renderCap = new Semaphore(maxConcurrent, true);
        this.variantPool = Executors.newFixedThreadPool(
                Math.min(VARIANT_POOL_SIZE, Math.max(1, maxConcurrent)),
                r -> {
                    Thread t = new Thread(r, "poster-variant-" + POOL_THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    public record OrchestrationResult(UUID generationId, String subStyleTag, List<GeneratedPoster> posters) {}

    /** Style control resolved once per generation: curated parts (win) + the preset fallback. */
    private record StyleControl(List<StyleReferencePart> parts, String preset) {
        boolean hasRefs() { return parts != null && !parts.isEmpty(); }
        List<String> ids() { return hasRefs() ? parts.stream().map(StyleReferencePart::filename).toList() : List.of(); }
    }

    private record RenderContext(Vibe vibe, StyleCard card, StyleControl style) {}

    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept) {
        return run(generatedEventId, request, concept, deriveSeed(generatedEventId), List.of());
    }

    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept,
                                   long creativeSeed, List<CreativeDirection> directions) {
        PosterGeneration generation = new PosterGeneration();
        generation.setGeneratedEventId(generatedEventId);
        generation.setStatus(PosterGenerationStatus.PENDING);
        generation.setSubStyleTag(concept.subStyleTag());
        generation.setCreativeSeed(creativeSeed);
        generation = generationRepository.save(generation);

        String tag = concept.subStyleTag();
        List<StyleReferencePart> parts = referenceLibrary.topReferenceParts(tag, maxReferences, MAX_REF_TOTAL_BYTES);
        String preset = vibeLibrary.ideogramStylePreset(tag);
        if (parts.isEmpty() && (preset == null || preset.isBlank())) {
            log.warn("Vibe '{}' has no reference images and no ideogram_style_preset — rendering with no style control", tag);
        }
        RenderContext ctx = new RenderContext(
                vibeLibrary.byId(tag).orElse(null),
                styleCardLibrary.get(tag).orElse(null),
                new StyleControl(parts, preset));

        List<PosterVariant> variants = concept.variants();
        final PosterGeneration gen = generation;
        List<Future<GeneratedPoster>> futures = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            final PosterVariant v = variants.get(i);
            final CreativeDirection dir = i < directions.size() ? directions.get(i) : null;
            final long seed = deriveSeed(creativeSeed, i);
            futures.add(variantPool.submit(() -> generateOne(gen, v, dir, seed, request, ctx)));
        }

        List<GeneratedPoster> results = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            PosterVariant v = variants.get(i);
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("Variant task threw unexpectedly", cause);
                results.add(failedPoster(null, v.heroType(), cause.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while awaiting variant task", e);
                results.add(failedPoster(null, v.heroType(), "interrupted while awaiting variant"));
            }
        }

        boolean allFailed = results.stream().allMatch(p -> "FAILED".equals(p.status()));
        generation.setRawReadyAt(LocalDateTime.now());
        generation.setCompletedAt(LocalDateTime.now());
        generation.setStatus(allFailed ? PosterGenerationStatus.FAILED : PosterGenerationStatus.COMPLETE);
        generationRepository.save(generation);

        if (allFailed) {
            throw new IllegalStateException("All 3 poster variants failed — check upstream logs. "
                    + "First failure: " + results.get(0).failureReason());
        }
        return new OrchestrationResult(generation.getId(), tag, results);
    }

    private GeneratedPoster generateOne(
            PosterGeneration generation, PosterVariant variant, CreativeDirection direction,
            long seed, EventCreatorRequest request, RenderContext ctx) {
        PosterVariantEntity entity = new PosterVariantEntity();
        entity.setPosterGeneration(generation);
        entity.setVariantStyle(variant.heroType());
        entity.setIdeogramPrompt(variant.ideogramPrompt());
        entity.setReferenceImagesUsed(String.join(",", ctx.style().ids()));
        entity.setSeed(seed);
        entity.setCreativeDirectionJson(serialize(direction));
        entity.setStatus(PosterVariantStatus.PENDING);
        synchronized (generation) {
            generation.getVariants().add(entity);
        }

        try {
            renderCap.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason("interrupted while waiting for render slot");
            return toDto(entity);
        }
        try {
            return renderWithValidation(entity, variant, seed, request, ctx);
        } catch (RuntimeException e) {
            log.error("Variant generation failed: hero_type={}, seed={}", variant.heroType(), seed, e);
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason(e.getMessage());
            return toDto(entity);
        } finally {
            renderCap.release();
        }
    }

    /** Generate → text gate (hard, corrective remix) → style gate (soft). */
    private GeneratedPoster renderWithValidation(
            PosterVariantEntity entity, PosterVariant variant, long baseSeed,
            EventCreatorRequest request, RenderContext ctx) {
        PosterTextSpec spec = textSpecFactory.from(request);
        HeroType heroType = HeroType.fromWire(variant.heroType());
        StyleControl style = ctx.style();
        List<Map<String, Object>> attempts = new ArrayList<>();

        long seed = baseSeed;
        byte[] image = null;
        String url = null;
        String correction = null;

        for (int attempt = 0; attempt <= maxRegenerations; attempt++) {
            boolean last = attempt == maxRegenerations;
            entity.setSeed(seed);

            IdeogramV3Client.IdeogramResult render = (attempt == 0)
                    ? ideogramClient.generate(variant.ideogramPrompt(), seed, style.parts(), style.preset())
                    : ideogramClient.remix(image, correction, remixImageWeight, seed, style.parts(), style.preset());
            image = render.imageBytes();
            url = storage.writePng(image);
            entity.setRawUrl(url);
            entity.setStatus(PosterVariantStatus.RAW_READY);

            PosterTextValidationService.ValidationDecision text = textValidation.validateOrExplain(image, spec);
            if (!text.accepted()) {
                attempts.add(attemptJson(attempt, seed, attempt == 0 ? "generate" : "remix", text, null));
                if (last) {
                    log.warn("Text gate still failing after {} regenerations; accepting best-effort: {}",
                            maxRegenerations, text.reason());
                    return accept(entity, url, VERDICT_BEST_EFFORT, attempts);
                }
                correction = buildCorrectionPrompt(variant.ideogramPrompt(), text);
                seed = nextSeed(seed);
                continue;
            }

            PosterStyleValidationService.ValidationDecision styleDecision =
                    styleValidation.validateOrExplain(image, ctx.card(), heroType);
            attempts.add(attemptJson(attempt, seed, attempt == 0 ? "generate" : "remix", text, styleDecision));
            if (styleDecision.accepted()) {
                return accept(entity, url, VERDICT_ACCEPTED, attempts);
            }
            // Text is correct; style is soft — accept best-effort without spending more renders.
            log.warn("Style gate soft-failed (text OK); accepting best-effort: {}", styleDecision.reason());
            return accept(entity, url, VERDICT_BEST_EFFORT, attempts);
        }
        throw new IllegalStateException("render-with-validation loop exhausted");
    }

    /** No overlay: the downloaded image is final. */
    private GeneratedPoster accept(PosterVariantEntity entity, String url, String verdict,
                                   List<Map<String, Object>> attempts) {
        entity.setFinalUrl(url);
        entity.setValidationVerdict(verdict);
        entity.setValidationAttemptsJson(serialize(attempts));
        entity.setStatus(PosterVariantStatus.COMPLETE);
        return toDto(entity);
    }

    static String buildCorrectionPrompt(String originalPrompt,
                                        PosterTextValidationService.ValidationDecision text) {
        StringBuilder c = new StringBuilder(originalPrompt);
        c.append("\n\nCORRECTION — the previous render had text errors. ");
        if (text.missingRequired() != null && !text.missingRequired().isEmpty()) {
            c.append("Render these exact strings, correctly spelled and clearly legible: ")
             .append(quoteJoin(text.missingRequired())).append(". ");
        }
        if (text.extraText() != null && !text.extraText().isEmpty()) {
            c.append("Remove these invented or garbled words: ")
             .append(quoteJoin(text.extraText())).append(". ");
        }
        c.append("Keep the composition, hero, colors, and layout identical — only fix the text.");
        return c.toString();
    }

    private static String quoteJoin(List<String> items) {
        return String.join(", ", items.stream().map(s -> "\"" + s + "\"").toList());
    }

    private Map<String, Object> attemptJson(int attempt, long seed, String mode,
                                            PosterTextValidationService.ValidationDecision text,
                                            PosterStyleValidationService.ValidationDecision style) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempt", attempt);
        m.put("seed", seed);
        m.put("mode", mode);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("accepted", text.accepted());
        t.put("reason", text.reason() == null ? "" : text.reason());
        m.put("text", t);
        Map<String, Object> s = new LinkedHashMap<>();
        if (style == null) {
            s.put("skipped", true);
        } else {
            s.put("accepted", style.accepted());
            s.put("reason", style.reason() == null ? "" : style.reason());
        }
        m.put("style", s);
        return m;
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialize {}: {}", value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static long deriveSeed(long creativeSeed, int index) {
        long s = creativeSeed * 1_000_003L + index;
        return Math.floorMod(s, 1_000_000_000L) + 1L;
    }

    private static long deriveSeed(UUID id) {
        return id == null ? 1L : Math.abs(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
    }

    private static long nextSeed(long seed) {
        long s = seed * 6364136223846793005L + 1442695040888963407L;
        return Math.floorMod(s, 1_000_000_000L) + 1L;
    }

    private GeneratedPoster toDto(PosterVariantEntity e) {
        Map<String, Object> overlays = new HashMap<>();
        overlays.put("qr_code", false);
        overlays.put("address", false);
        List<String> refs = e.getReferenceImagesUsed() == null || e.getReferenceImagesUsed().isBlank()
                ? List.of() : List.of(e.getReferenceImagesUsed().split(","));
        return new GeneratedPoster(
                e.getId(), e.getVariantStyle(), e.getRawUrl(), e.getFinalUrl(),
                e.getSeed() != null ? e.getSeed() : 0L, e.getIdeogramPrompt(), refs, overlays,
                e.getStatus().name(), e.getFailureReason());
    }

    private GeneratedPoster failedPoster(UUID id, String style, String reason) {
        return new GeneratedPoster(id, style, null, null, 0L, "", List.of(), Map.of(), "FAILED", reason);
    }
}
```

- [ ] **Step 4: Run the orchestrator test to verify it passes**

Run: `./mvnw test -Dtest=PosterOrchestratorTest`
Expected: PASS (all four scenarios).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java \
        src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java
git commit -m "feat(poster): render via native Ideogram V3 with corrective-remix text gate"
```

---

## Task 9: Configuration — add `ideogram.*`, enable gates, drop dead render keys

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Add the `ideogram` block** (top-level, near the former `replicate`/`recraft` blocks)

```yaml
ideogram:
  api-key: ${IDEOGRAM_API_KEY:}
  base-url: ${IDEOGRAM_BASE_URL:https://api.ideogram.ai}
  copyright-detection: ${IDEOGRAM_COPYRIGHT_DETECTION:true}
  max-references: ${IDEOGRAM_MAX_REFERENCES:3}
  generate:
    rendering-speed: ${IDEOGRAM_GENERATE_RENDERING_SPEED:QUALITY}
  remix:
    rendering-speed: ${IDEOGRAM_REMIX_RENDERING_SPEED:TURBO}
    image-weight: ${IDEOGRAM_REMIX_IMAGE_WEIGHT:70}
```

- [ ] **Step 2: Enable the gates** — flip the two defaults under `poster:`

```yaml
  text-validation:
    enabled: ${POSTER_TEXT_VALIDATION_ENABLED:true}
    model: ${POSTER_TEXT_VALIDATION_MODEL:openai/gpt-4o-mini}
    max-extra-text-items: ${POSTER_TEXT_VALIDATION_MAX_EXTRA_TEXT_ITEMS:2}
  style-validation:
    enabled: ${POSTER_STYLE_VALIDATION_ENABLED:true}
    model: ${POSTER_STYLE_VALIDATION_MODEL:openai/gpt-4o-mini}
```

- [ ] **Step 3: Add the render-concurrency alias** (keeps the old env working)

```yaml
  render:
    max-concurrent: ${POSTER_RENDER_MAX_CONCURRENT:${REPLICATE_MAX_CONCURRENT:6}}
```

- [ ] **Step 4: Delete the now-dead render keys** from `application.yaml`:
  - under `replicate.models`: `ideogram-turbo`, `ideogram-quality`
  - the whole `poster.overlay` block
  - the whole `poster.compositor` block
  - `poster.generation.magic-prompt` and `poster.generation.negative-prompt`

  Keep: `replicate.image.storage-dir`, `replicate.max-concurrent`, `recraft.*`, `poster.provider-routing.*`, `poster.validation.max-regenerations`, `poster.references.*`, `poster.style-cards.*`.

- [ ] **Step 5: Verify the app context still loads**

Run: `./mvnw test -Dtest=*ApplicationTests` (or the project's context-load test, e.g. `IminApiApplicationTests`)
Expected: PASS — context loads with the new config and no missing-bean errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "config(poster): wire native Ideogram, enable vision gates, drop overlay/recraft-render keys"
```

---

## Task 10: Delete the replaced render-path code + tests; full green build

By now nothing references these classes (the orchestrator was rewired in Task 8; the training subsystem keeps `RecraftClient`/`ImageProvider`).

**Files (delete):**
- `src/main/java/com/imin/iminapi/service/poster/IdeogramClient.java`
- `src/main/java/com/imin/iminapi/service/poster/ReplicateClient.java`
- `src/main/java/com/imin/iminapi/service/poster/OpenAiImageClient.java`
- `src/main/java/com/imin/iminapi/config/OpenAiImageConfig.java`
- `src/main/java/com/imin/iminapi/service/poster/OverlayCompositor.java`
- `src/main/java/com/imin/iminapi/service/poster/PosterTextCompositorClient.java`
- `src/main/java/com/imin/iminapi/config/PosterCompositorConfig.java`
- `src/test/java/com/imin/iminapi/service/poster/IdeogramClientTest.java`
- `src/test/java/com/imin/iminapi/service/poster/OpenAiImageClientTest.java`
- `src/test/java/com/imin/iminapi/service/poster/OverlayCompositorTest.java`
- `src/test/java/com/imin/iminapi/service/poster/PosterTextCompositorClientTest.java`

- [ ] **Step 1: Delete the files**

```bash
cd /Users/ivan/imin/imin-api
git rm src/main/java/com/imin/iminapi/service/poster/IdeogramClient.java \
       src/main/java/com/imin/iminapi/service/poster/ReplicateClient.java \
       src/main/java/com/imin/iminapi/service/poster/OpenAiImageClient.java \
       src/main/java/com/imin/iminapi/config/OpenAiImageConfig.java \
       src/main/java/com/imin/iminapi/service/poster/OverlayCompositor.java \
       src/main/java/com/imin/iminapi/service/poster/PosterTextCompositorClient.java \
       src/main/java/com/imin/iminapi/config/PosterCompositorConfig.java \
       src/test/java/com/imin/iminapi/service/poster/IdeogramClientTest.java \
       src/test/java/com/imin/iminapi/service/poster/OpenAiImageClientTest.java \
       src/test/java/com/imin/iminapi/service/poster/OverlayCompositorTest.java \
       src/test/java/com/imin/iminapi/service/poster/PosterTextCompositorClientTest.java
```

- [ ] **Step 2: Confirm no dangling references**

Run: `grep -rn "OverlayCompositor\|PosterTextCompositorClient\|OpenAiImageClient\|ReplicateClient\|new IdeogramClient\|IdeogramClient\." src/main src/test`
Expected: no matches in `src/main`; in `src/test`, no matches except unrelated. If anything remains (e.g. a stray import), remove it.

- [ ] **Step 3: Full build + test**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS — all tests green. (The kept Recraft training tests `RecraftClientTest`, `VibeStyleTrainingServiceTest`, `VibeStyleTrainingControllerTest`, and `ConceptStudioServiceTest` still pass; they never touched the render path being replaced.)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore(poster): remove replaced Recraft/OpenAI/Replicate render path + overlay compositors"
```

---

## Self-review

**Spec coverage:**
- Native Ideogram generate (multipart, one style control, 4x5, Turbo, magic_prompt OFF, copyright on, seed) → Tasks 3–4.
- Reference images as ≤3 binary parts ≤10 MB → Task 2 + `MAX_REF_TOTAL_BYTES`/`maxReferences` in Task 8.
- Style-preset fallback → Tasks 7, 8.
- Corrective remix on text-gate failure with a "what's wrong" prompt → Tasks 5, 6, 8.
- Text gate hard, style gate soft, best-effort + journal → Tasks 6, 8.
- No overlays (text fully model-baked) → Task 8 (`accept` sets `finalUrl=url`) + Task 10 (delete compositors).
- Persistence of verdict + attempts → Task 1, 8.
- Config: add ideogram, enable gates, drop dead keys → Task 9.
- Sole renderer / remove render-path code → Task 10. (Provider enum + Recraft training subsystem intentionally kept dormant per spec.)
- No contract change → response DTO untouched; request DTO untouched.

**Placeholder scan:** none — every code step shows complete code; commands have expected output.

**Type consistency:** `IdeogramV3Client.IdeogramResult(byte[],long)`, `generate(String,long,List<StyleReferencePart>,String)`, `remix(byte[],String,int,long,List<StyleReferencePart>,String)`, `StyleReferencePart(byte[],String,String)`, `ValidationDecision(boolean,String,List<String>,List<String>)` + `.accepted()`, `ReferenceImageLibrary.topReferenceParts(String,int,long)`, `VibeLibrary.ideogramStylePreset(String)`, and the new `PosterOrchestrator` constructor are used identically across tasks.

**Note for the executor:** Two tasks reuse existing test helpers (`ReferenceImageLibraryTest`, `VibeLibraryTest`, `PosterTextValidationServiceTest`) — open each test first and match its real construction/helper names; the assertions in this plan stay the same. Confirm the `PosterVariant` / `PosterConcept` / `PosterTextSpec` record shapes when writing the orchestrator test.
