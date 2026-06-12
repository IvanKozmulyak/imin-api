# DJ Photo Posters (imin-api) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-event DJ photo upload; when present, all 3 AI poster variants render the DJ as hero via Ideogram V3 `character_reference_images`, with the brand palette enforced through the prompt channel.

**Architecture:** New `dj-photo` event-media kind (R2 storage, `events.dj_photo_url`). `ConceptRequest.eventId` binds generation to the event; a transient `DjPhotoSnapshot` (URL + bytes) threads from `ConceptStudioService` through `PosterOrchestrator` into `IdeogramV3Client`, which conditionally swaps `color_palette`/`seed`/style controls for `character_reference_images` behind probe-determined config flags. DJ mode forces an all-`people` variant plan fed to BOTH the sampler and the concept validator.

**Tech Stack:** Java 17, Spring Boot 4, Flyway, H2+Mockito tests (`./mvnw test`), Cloudflare R2 via `MediaStorage`.

**Spec:** `docs/superpowers/specs/2026-06-12-dj-photo-posters-design.md`

**Conventions for every task:** work on branch `feat/dj-photo-posters`; commit messages end with the standard `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` footer; run targeted tests before each commit.

---

### Task 0: Branch + P0 Ideogram probe

The probe decides the config-flag defaults in Task 4. It needs `IDEOGRAM_API_KEY` and costs ~$1 total. **If the key is unavailable, skip the calls, keep all flags defaulted to `false` (conservative: omit the params in DJ mode), and note that in the results file.**

**Files:**
- Create: `docs/superpowers/plans/2026-06-12-dj-photo-probe-results.md`
- Create (throwaway, not committed): `/tmp/ideogram-probe.sh`

- [ ] **Step 1: Create the branch**

```bash
cd /Users/ivan/imin/imin-api && git checkout -b feat/dj-photo-posters
```

- [ ] **Step 2: Write the probe script**

Needs any clear face photo as `/tmp/face.jpg` (≥256px short side; any portrait photo works).

```bash
cat > /tmp/ideogram-probe.sh <<'EOF'
#!/bin/bash
# Probes which params Ideogram v3 accepts alongside character_reference_images.
# Usage: IDEOGRAM_API_KEY=... ./ideogram-probe.sh /tmp/face.jpg
set -u
IMG="$1"
URL="https://api.ideogram.ai/v1/ideogram-v3/generate"
PROMPT="Nightlife event poster, the featured DJ behind the decks, bold typography reading TEST NIGHT, 4:5 vertical poster, dramatic magenta lighting"
PALETTE='{"members":[{"color_hex":"#ec4899","color_weight":1.0},{"color_hex":"#f6c04a","color_weight":0.5}]}'

run() {
  local label="$1"; shift
  echo "=== $label ==="
  curl -s -o /tmp/probe-out.json -w "HTTP %{http_code}\n" "$URL" \
    -H "Api-Key: $IDEOGRAM_API_KEY" \
    -F "prompt=$PROMPT" -F "aspect_ratio=4x5" -F "rendering_speed=TURBO" \
    -F "magic_prompt=OFF" \
    -F "character_reference_images=@$IMG" \
    "$@"
  head -c 600 /tmp/probe-out.json; echo; echo
}

run "A: char-ref alone"
run "B: + color_palette"  -F "color_palette=$PALETTE"
run "C: + seed"           -F "seed=12345"
run "D: + style_preset"   -F "style_preset=MONOCHROME"
EOF
chmod +x /tmp/ideogram-probe.sh
```

- [ ] **Step 3: Run it and interpret**

Run: `IDEOGRAM_API_KEY=<key> /tmp/ideogram-probe.sh /tmp/face.jpg`

Per the spec's decision matrix, for each of B/C/D: HTTP 4xx → flag stays `false`; HTTP 200 → open the returned image URL for B and check whether the magenta/amber palette is honoured (honoured or ignored → flag may be `true`; it is harmless if ignored). A is the baseline — if A itself fails, stop and investigate before continuing the plan.

- [ ] **Step 4: Record results**

Write `docs/superpowers/plans/2026-06-12-dj-photo-probe-results.md` with one line per probe (label, HTTP status, observed behaviour) and the resulting flag defaults, e.g.:

```markdown
# Ideogram character-reference probe — 2026-06-12
- A char-ref alone: 200 OK (baseline works)
- B + color_palette: <status> → ideogram.character.color-palette default <true|false>
- C + seed: <status> → ideogram.character.seed default <true|false>
- D + style_preset: <status> → ideogram.character.style-control default <true|false>
(or: "API key unavailable — all flags default false, revisit before prod rollout")
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/plans/2026-06-12-dj-photo-probe-results.md docs/superpowers/plans/2026-06-12-dj-photo-posters-api.md
git commit -m "docs: DJ photo posters plan + Ideogram character-reference probe results"
```

---

### Task 1: V39 migration + entity columns

**Files:**
- Create: `src/main/resources/db/migration/V39__event_dj_photo.sql`
- Modify: `src/main/java/com/imin/iminapi/model/Event.java` (field block, near `posterUrl` at ~line 68)
- Modify: `src/main/java/com/imin/iminapi/model/PosterGeneration.java` (after `brandSnapshot`, line 50)
- Test: `src/test/java/com/imin/iminapi/migration/V39DjPhotoMigrationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.imin.iminapi.migration;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;

/** V39 adds nullable dj_photo_url to events and poster_generations. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class V39DjPhotoMigrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void eventsHasDjPhotoUrlColumn() {
        assertThatCode(() -> jdbc.queryForList("SELECT dj_photo_url FROM events WHERE 1=0"))
                .doesNotThrowAnyException();
    }

    @Test
    void posterGenerationsHasDjPhotoUrlColumn() {
        assertThatCode(() -> jdbc.queryForList("SELECT dj_photo_url FROM poster_generations WHERE 1=0"))
                .doesNotThrowAnyException();
    }
}
```

(Mirror the imports/annotations of the existing `V38BrandBookMigrationTest` in the same package if they differ — e.g. if it doesn't import `TestRateLimitConfig`, drop it here too.)

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=V39DjPhotoMigrationTest`
Expected: FAIL — column `dj_photo_url` not found.

- [ ] **Step 3: Write the migration + entity fields**

`V39__event_dj_photo.sql`:

```sql
-- Per-event DJ photo (uploaded by the organizer; drives character-reference poster generation).
ALTER TABLE events ADD COLUMN dj_photo_url TEXT;
-- Snapshot of the DJ photo URL a generation actually used (read back by regenerate).
ALTER TABLE poster_generations ADD COLUMN dj_photo_url TEXT;
```

`Event.java` — add next to `posterUrl`/`videoUrl` (Lombok `@Getter`/`@Setter` on the class generates accessors):

```java
    @Column(name = "dj_photo_url", columnDefinition = "TEXT")
    private String djPhotoUrl;
```

`PosterGeneration.java` — add after `brandSnapshot`:

```java
    /** The DJ photo URL this generation rendered with, or NULL. Regenerate reads THIS, not the live event. */
    @Column(name = "dj_photo_url", columnDefinition = "TEXT")
    private String djPhotoUrl;
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw test -Dtest=V39DjPhotoMigrationTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V39__event_dj_photo.sql src/main/java/com/imin/iminapi/model/Event.java src/main/java/com/imin/iminapi/model/PosterGeneration.java src/test/java/com/imin/iminapi/migration/V39DjPhotoMigrationTest.java
git commit -m "feat: V39 migration — dj_photo_url on events and poster_generations"
```

---

### Task 2: `dj-photo` media kind — upload/delete + validation

**Files:**
- Modify: `src/main/java/com/imin/iminapi/model/MediaKind.java`
- Modify: `src/main/java/com/imin/iminapi/service/event/MediaUploadService.java`
- Test: `src/test/java/com/imin/iminapi/service/event/MediaUploadServiceTest.java` (extend)

- [ ] **Step 1: Write the failing tests**

Append to `MediaUploadServiceTest` (reuse the file's existing setup — it constructs `MediaUploadService` with a mocked `EventRepository`, a real `InMemoryMediaStorage`, and a mocked/real `VideoMetadata`; mirror exactly how the existing poster tests stub `events.findActive(...)` and build the `AuthPrincipal`). New test methods:

```java
    // --- DJ photo kind ---

    private static byte[] realPng(int w, int h) throws java.io.IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void djPhotoUploadStoresUrlOnEvent() throws Exception {
        byte[] png = realPng(600, 800);
        MediaUploadResponse res = service.upload(principal, eventId, MediaKind.DJ_PHOTO, png, "image/png", "dj.png");
        assertThat(res.url()).contains("/events/" + eventId + "/dj-photo-");
        assertThat(event.getDjPhotoUrl()).isEqualTo(res.url());
    }

    @Test
    void djPhotoRejectsTooSmallImage() throws Exception {
        byte[] png = realPng(200, 800); // short side 200 < 256
        assertThatThrownBy(() -> service.upload(principal, eventId, MediaKind.DJ_PHOTO, png, "image/png", "dj.png"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid file");
    }

    @Test
    void djPhotoRejectsOversize() {
        byte[] big = new byte[(int) (5 * 1024 * 1024) + 1];
        big[0] = (byte) 0x89; big[1] = 0x50; big[2] = 0x4E; big[3] = 0x47;
        assertThatThrownBy(() -> service.upload(principal, eventId, MediaKind.DJ_PHOTO, big, "image/png", "dj.png"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void djPhotoRejectsNonImageContentType() throws Exception {
        byte[] png = realPng(600, 800);
        assertThatThrownBy(() -> service.upload(principal, eventId, MediaKind.DJ_PHOTO, png, "image/webp", "dj.webp"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void djPhotoDeleteClearsUrl() throws Exception {
        byte[] png = realPng(600, 800);
        service.upload(principal, eventId, MediaKind.DJ_PHOTO, png, "image/png", "dj.png");
        service.delete(principal, eventId, MediaKind.DJ_PHOTO);
        assertThat(event.getDjPhotoUrl()).isNull();
    }

    @Test
    void mediaKindWireRoundTrip() {
        assertThat(MediaKind.fromWire("dj-photo")).isEqualTo(MediaKind.DJ_PHOTO);
        assertThat(MediaKind.DJ_PHOTO.wireValue()).isEqualTo("dj-photo");
    }
```

(Adapt `principal`/`eventId`/`event`/`service` identifiers to the names the file already uses.)

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest=MediaUploadServiceTest`
Expected: COMPILE FAILURE — `MediaKind.DJ_PHOTO` and `getDjPhotoUrl` exist only after Step 3 (the entity getter exists from Task 1; the enum constant does not).

- [ ] **Step 3: Implement**

`MediaKind.java` — the enum name contains an underscore, so `wireValue()` can no longer be `name().toLowerCase()`:

```java
package com.imin.iminapi.model;

public enum MediaKind {
    POSTER("poster"), VIDEO("video"), DJ_PHOTO("dj-photo");

    private final String wire;

    MediaKind(String wire) { this.wire = wire; }

    public String wireValue() { return wire; }

    public static MediaKind fromWire(String s) {
        return switch (s) {
            case "poster" -> POSTER;
            case "video" -> VIDEO;
            case "dj-photo" -> DJ_PHOTO;
            default -> throw new IllegalArgumentException("Unknown media kind: " + s);
        };
    }
}
```

`MediaUploadService.java` — four touch points:

1. `validate(...)` gains a `DJ_PHOTO` case and a dimension check (only DJ photos need one — a face needs resolution):

```java
            case DJ_PHOTO -> {
                if (size > 5 * MB) throw fieldErr("file", "must be ≤ 5 MB");
                if (!IMAGE_TYPES.contains(contentType)) throw fieldErr("file", "must be JPG or PNG");
            }
```

and at the end of `validate(...)`, after `verifyMagicBytes(kind, bytes, contentType);`:

```java
        if (kind == MediaKind.DJ_PHOTO) {
            verifyDjPhotoDimensions(bytes);
        }
```

with the new private method (pattern copied from `OrgMediaService.validate`, which owns the existing ImageIO dimension check for logos):

```java
    /** A face needs resolution for the character reference: decodable, short side ≥ 256 px. */
    private static void verifyDjPhotoDimensions(byte[] bytes) {
        java.awt.image.BufferedImage img;
        try {
            img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (java.io.IOException e) {
            throw fieldErr("file", "could not decode image");
        }
        if (img == null) throw fieldErr("file", "could not decode image");
        if (Math.min(img.getWidth(), img.getHeight()) < 256) {
            throw fieldErr("file", "must be at least 256px on the short side");
        }
    }
```

2. The `oldUrl` read switch in `upload(...)` gains: `case DJ_PHOTO -> e.getDjPhotoUrl();`
3. The setter switch in `upload(...)` gains: `case DJ_PHOTO -> e.setDjPhotoUrl(url);`
4. Both switches in `delete(...)` gain the same `DJ_PHOTO` arms (`e.getDjPhotoUrl()` / `e.setDjPhotoUrl(null)`).

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw test -Dtest=MediaUploadServiceTest`
Expected: PASS (all existing + 6 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/MediaKind.java src/main/java/com/imin/iminapi/service/event/MediaUploadService.java src/test/java/com/imin/iminapi/service/event/MediaUploadServiceTest.java
git commit -m "feat: dj-photo event media kind with upload validation (5MB, JPG/PNG, short side >= 256px)"
```

---

### Task 3: Controller wiring + clean unknown-kind error + `EventDto.djPhotoUrl`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/controller/event/EventMediaController.java`
- Modify: `src/main/java/com/imin/iminapi/dto/event/EventDto.java`
- Test: `src/test/java/com/imin/iminapi/controller/event/EventMediaControllerTest.java` (extend; find it with `ls src/test/java/com/imin/iminapi/controller/` if the package differs)

- [ ] **Step 1: Write the failing tests**

Append to `EventMediaControllerTest`, mirroring exactly how its existing poster-upload test builds the multipart request, the security context, and the `@MockitoBean MediaUploadService`:

```java
    @Test
    void uploadsDjPhotoKind() throws Exception {
        when(uploadService.upload(any(), eq(eventId), eq(MediaKind.DJ_PHOTO), any(), eq("image/png"), eq("dj.png")))
                .thenReturn(new MediaUploadResponse("https://cdn.example/dj.png", 123L, "image/png", null));
        mockMvc.perform(multipart("/api/v1/events/" + eventId + "/media/dj-photo")
                        .file(new MockMultipartFile("file", "dj.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://cdn.example/dj.png"));
    }

    @Test
    void unknownKindIsCleanNotFoundNot500() throws Exception {
        mockMvc.perform(multipart("/api/v1/events/" + eventId + "/media/banner")
                        .file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{1})))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest=EventMediaControllerTest`
Expected: `uploadsDjPhotoKind` passes already only if Task 2 shipped (it did) — but `unknownKindIsCleanNotFoundNot500` FAILS with a 500 (the raw `IllegalArgumentException` from `fromWire` hits the catch-all handler).

- [ ] **Step 3: Implement the clean 404 + DTO field**

`EventMediaController.java` — wrap `fromWire` in both endpoints:

```java
    private static MediaKind kindOr404(String kind) {
        try {
            return MediaKind.fromWire(kind);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Media kind");
        }
    }
```

(add `import com.imin.iminapi.security.ApiException;`), then replace both `MediaKind.fromWire(kind)` call sites with `kindOr404(kind)`.

`EventDto.java` — add `String djPhotoUrl` to the record header directly after `String videoUrl`, and thread it through both factories:

- in `summary(Event e)`: `e.getDescription(), e.getPosterUrl(), e.getVideoUrl(), e.getDjPhotoUrl(),`
- in `detail(...)`: `base.description, base.posterUrl, base.videoUrl, base.djPhotoUrl,`

Then run `./mvnw test-compile 2>&1 | grep -A2 "EventDto"` — any other `new EventDto(...)` constructor call sites (tests included) need the extra `null`/value argument; fix each one the compiler reports.

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw test -Dtest=EventMediaControllerTest` then `./mvnw test -Dtest='Event*Test'`
Expected: PASS — controller tests green, and every EventDto-constructing test compiles and passes.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: dj-photo media endpoints, EventDto.djPhotoUrl, clean 404 for unknown media kinds"
```

---

### Task 4: `IdeogramV3Client` — character reference with probe-gated params

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/IdeogramV3Client.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/IdeogramV3ClientTest.java` (extend)

Approach: extract package-private `buildGenerateParts(...)`/`buildRemixParts(...)` so param composition is testable without HTTP. `generate()`/`remix()` gain a trailing nullable `StyleReferencePart characterRef` parameter (reusing the existing multipart-part record — no new type needed at the client layer).

- [ ] **Step 1: Write the failing tests**

Append to `IdeogramV3ClientTest` (the new builder methods make these pure unit tests; construct the client with `new IdeogramV3Client(null, "QUALITY", "TURBO", true)` — the RestClient is unused by the builders. If the existing test file already constructs a client instance, reuse its pattern):

```java
    private static final StyleReferencePart CHAR_REF =
            new StyleReferencePart(new byte[]{1, 2, 3}, "dj-photo.jpg", "image/jpeg");
    private static final List<String> PALETTE = List.of("#ec4899", "#f6c04a");

    private IdeogramV3Client clientWithFlags(boolean palette, boolean seed, boolean style) {
        return new IdeogramV3Client(null, "QUALITY", "TURBO", true, palette, seed, style);
    }

    @Test
    void generateWithoutCharacterRefIsUnchangedBaseline() {
        var parts = clientWithFlags(false, false, false)
                .buildGenerateParts("prompt", 42L, List.of(), "MONOCHROME", PALETTE, null);
        assertThat(parts.containsKey("character_reference_images")).isFalse();
        assertThat(parts.getFirst("seed")).isEqualTo("42");
        assertThat(parts.containsKey("color_palette")).isTrue();
        assertThat(parts.getFirst("style_preset")).isEqualTo("MONOCHROME");
    }

    @Test
    void characterRefOmitsGatedParamsWhenFlagsOff() {
        var parts = clientWithFlags(false, false, false)
                .buildGenerateParts("prompt", 42L, List.of(), "MONOCHROME", PALETTE, CHAR_REF);
        assertThat(parts.containsKey("character_reference_images")).isTrue();
        assertThat(parts.containsKey("seed")).isFalse();
        assertThat(parts.containsKey("color_palette")).isFalse();
        assertThat(parts.containsKey("style_preset")).isFalse();
        assertThat(parts.containsKey("style_reference_images")).isFalse();
    }

    @Test
    void characterRefKeepsGatedParamsWhenFlagsOn() {
        var parts = clientWithFlags(true, true, true)
                .buildGenerateParts("prompt", 42L, List.of(), "MONOCHROME", PALETTE, CHAR_REF);
        assertThat(parts.containsKey("character_reference_images")).isTrue();
        assertThat(parts.getFirst("seed")).isEqualTo("42");
        assertThat(parts.containsKey("color_palette")).isTrue();
        assertThat(parts.getFirst("style_preset")).isEqualTo("MONOCHROME");
    }

    @Test
    void remixCarriesCharacterRefToo() {
        var parts = clientWithFlags(false, false, false)
                .buildRemixParts(new byte[]{9}, "fix", 70, 42L, List.of(), null, PALETTE, CHAR_REF);
        assertThat(parts.containsKey("character_reference_images")).isTrue();
        assertThat(parts.containsKey("image")).isTrue();
        assertThat(parts.containsKey("seed")).isFalse();
        assertThat(parts.containsKey("color_palette")).isFalse();
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest=IdeogramV3ClientTest`
Expected: COMPILE FAILURE — the 7-arg constructor and builder methods don't exist yet.

- [ ] **Step 3: Implement**

`IdeogramV3Client.java`:

1. Constructor gains three flag params (after `copyrightDetection`), stored in final fields:

```java
            @Value("${ideogram.character.color-palette:false}") boolean characterColorPalette,
            @Value("${ideogram.character.seed:false}") boolean characterSeed,
            @Value("${ideogram.character.style-control:false}") boolean characterStyleControl) {
```

(Defaults reflect the conservative posture; flip them in `application.yaml`/env only per the Task 0 probe results.)

2. Extract the part-building from `generate()` into a package-private builder; `generate()` becomes a thin wrapper. The full new shape of the two public methods plus builders:

```java
    /** Text-to-image generate (QUALITY tier). {@code characterRef} non-null switches DJ mode. */
    public IdeogramResult generate(String prompt, long seed, List<StyleReferencePart> styleRefs,
                                   String stylePreset, List<String> paletteHexes,
                                   StyleReferencePart characterRef) {
        MultiValueMap<String, Object> parts =
                buildGenerateParts(prompt, seed, styleRefs, stylePreset, paletteHexes, characterRef);
        log.info("[ideogram v3 generate] promptLen={} speed={} {} palette={} seed={} charRef={}",
                prompt.length(), generateSpeed, styleLabel(styleRefs, stylePreset),
                paletteHexes == null ? 0 : paletteHexes.size(), seed, characterRef != null);
        return new IdeogramResult(post(GENERATE_PATH, parts), seed);
    }

    MultiValueMap<String, Object> buildGenerateParts(String prompt, long seed,
            List<StyleReferencePart> styleRefs, String stylePreset, List<String> paletteHexes,
            StyleReferencePart characterRef) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("prompt", prompt);
        parts.add("aspect_ratio", ASPECT_RATIO);
        parts.add("rendering_speed", generateSpeed);
        parts.add("magic_prompt", MAGIC_PROMPT);
        parts.add("enable_copyright_detection", String.valueOf(copyrightDetection));
        applyConditional(parts, seed, styleRefs, stylePreset, paletteHexes, characterRef);
        return parts;
    }

    /** Image-conditioned remix (TURBO tier). {@code characterRef} keeps the DJ likeness through corrections. */
    public IdeogramResult remix(byte[] image, String prompt, int imageWeight, long seed,
                                List<StyleReferencePart> styleRefs, String stylePreset,
                                List<String> paletteHexes, StyleReferencePart characterRef) {
        MultiValueMap<String, Object> parts =
                buildRemixParts(image, prompt, imageWeight, seed, styleRefs, stylePreset, paletteHexes, characterRef);
        log.info("[ideogram v3 remix] promptLen={} weight={} {} palette={} seed={} charRef={}",
                prompt.length(), imageWeight, styleLabel(styleRefs, stylePreset),
                paletteHexes == null ? 0 : paletteHexes.size(), seed, characterRef != null);
        return new IdeogramResult(post(REMIX_PATH, parts), seed);
    }

    MultiValueMap<String, Object> buildRemixParts(byte[] image, String prompt, int imageWeight, long seed,
            List<StyleReferencePart> styleRefs, String stylePreset, List<String> paletteHexes,
            StyleReferencePart characterRef) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", filePart(new StyleReferencePart(image, "source.png", "image/png")));
        parts.add("prompt", prompt);
        parts.add("image_weight", String.valueOf(imageWeight));
        parts.add("aspect_ratio", ASPECT_RATIO);
        parts.add("rendering_speed", remixSpeed);
        parts.add("magic_prompt", MAGIC_PROMPT);
        applyConditional(parts, seed, styleRefs, stylePreset, paletteHexes, characterRef);
        return parts;
    }

    /**
     * The DJ-mode switch. Ideogram's product docs say Color Palette / Seed / Style Reference are
     * unavailable with a character reference; the API reference doesn't confirm it, so each param
     * is gated behind a probe-determined flag (see the 2026-06-12 probe results doc). Without a
     * character ref this emits the exact baseline params.
     */
    private void applyConditional(MultiValueMap<String, Object> parts, long seed,
            List<StyleReferencePart> styleRefs, String stylePreset, List<String> paletteHexes,
            StyleReferencePart characterRef) {
        if (characterRef == null) {
            parts.add("seed", String.valueOf(seed));
            applyStyleControl(parts, styleRefs, stylePreset);
            applyColorPalette(parts, paletteHexes);
            return;
        }
        parts.add("character_reference_images", filePart(characterRef));
        if (characterSeed) parts.add("seed", String.valueOf(seed));
        if (characterStyleControl) applyStyleControl(parts, styleRefs, stylePreset);
        if (characterColorPalette) applyColorPalette(parts, paletteHexes);
    }
```

3. Update the two existing callers in `PosterOrchestrator.renderWithValidation` (lines 252–256) to pass `null` as the new trailing argument **for now** (Task 5 threads the real value), and fix any compile errors in existing tests the same way.

- [ ] **Step 4: Run to verify**

Run: `./mvnw test -Dtest=IdeogramV3ClientTest && ./mvnw test -Dtest=PosterOrchestratorTest`
Expected: PASS — new tests green, orchestrator tests still green (baseline behaviour byte-identical).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/IdeogramV3Client.java src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java src/test/java/com/imin/iminapi/service/poster/IdeogramV3ClientTest.java
git commit -m "feat: Ideogram client accepts a character reference, gating palette/seed/style behind probe flags"
```

---

### Task 5: `DjPhotoSnapshot` + orchestrator threading

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/poster/DjPhotoSnapshot.java`
- Modify: `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java` (extend)

- [ ] **Step 1: Create the snapshot record**

```java
package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.StyleReferencePart;

/**
 * The resolved per-event DJ photo for ONE generation run: bytes are downloaded once, eagerly,
 * before the variant futures are submitted (the consistency boundary against a concurrent photo
 * replace/delete). Transient — only {@code url} is persisted (poster_generations.dj_photo_url);
 * bytes never enter any JSON column.
 */
public record DjPhotoSnapshot(String url, byte[] bytes, String mimeType) {

    /** The multipart part sent as Ideogram's character_reference_images. */
    public StyleReferencePart toPart() {
        String ext = "image/png".equals(mimeType) ? "png" : "jpg";
        return new StyleReferencePart(bytes, "dj-photo." + ext, mimeType);
    }
}
```

- [ ] **Step 2: Write the failing tests**

Append to `PosterOrchestratorTest` (mirror its existing mock setup — it mocks `IdeogramV3Client`, the validation services, `PosterImageStorage`, etc.; reuse its helpers for a passing text gate and a minimal `PosterConcept`):

```java
    private static final DjPhotoSnapshot DJ =
            new DjPhotoSnapshot("https://cdn.example/dj.jpg", new byte[]{1, 2}, "image/jpeg");

    @Test
    void djModePassesCharacterRefToGenerate() {
        // arrange: text gate accepts on first attempt (reuse the file's standard stubbing)
        orchestrator.run(genEventId, request, concept, 7L, List.of(), null, DJ);
        ArgumentCaptor<StyleReferencePart> ref = ArgumentCaptor.forClass(StyleReferencePart.class);
        verify(ideogramClient, atLeastOnce()).generate(anyString(), anyLong(), anyList(), any(), anyList(), ref.capture());
        assertThat(ref.getValue()).isNotNull();
        assertThat(ref.getValue().filename()).isEqualTo("dj-photo.jpg");
    }

    @Test
    void djModeCorrectiveRemixCarriesCharacterRef() {
        // arrange: text gate fails once then accepts (reuse the file's existing remix-path stubbing)
        orchestrator.run(genEventId, request, concept, 7L, List.of(), null, DJ);
        verify(ideogramClient, atLeastOnce()).remix(any(), anyString(), anyInt(), anyLong(), anyList(), any(), anyList(),
                argThat(r -> r != null && "dj-photo.jpg".equals(r.filename())));
    }

    @Test
    void djPhotoUrlIsSnapshottedOnGeneration() {
        orchestrator.run(genEventId, request, concept, 7L, List.of(), null, DJ);
        ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(generationRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getDjPhotoUrl()).isEqualTo("https://cdn.example/dj.jpg");
    }

    @Test
    void nullDjPhotoKeepsBaselineNullCharacterRef() {
        orchestrator.run(genEventId, request, concept, 7L, List.of(), null, null);
        verify(ideogramClient, atLeastOnce()).generate(anyString(), anyLong(), anyList(), any(), anyList(), isNull());
    }
```

- [ ] **Step 3: Run to verify they fail**

Run: `./mvnw test -Dtest=PosterOrchestratorTest`
Expected: COMPILE FAILURE — no 7-arg `run` overload.

- [ ] **Step 4: Implement the threading**

`PosterOrchestrator.java`:

1. The existing 6-arg `run(..., BrandSnapshot brand)` delegates: `return run(generatedEventId, request, concept, creativeSeed, directions, brand, null);`
2. New full overload:

```java
    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept,
                                   long creativeSeed, List<CreativeDirection> directions, BrandSnapshot brand,
                                   DjPhotoSnapshot djPhoto) {
```

   In its body (the current 6-arg body moves here): after `generation.setBrandSnapshot(...)` add

```java
        if (djPhoto != null) {
            generation.setDjPhotoUrl(djPhoto.url());
        }
```

   and compute the character ref once, before the variant loop:

```java
        final StyleReferencePart characterRef = djPhoto == null ? null : djPhoto.toPart();
```

   then pass it through the future submission: `generateOne(gen, v, dir, seed, request, ctx, brandFinal, characterRef)`.

3. `generateOne(...)` and `renderWithValidation(...)` each gain a trailing `StyleReferencePart characterRef` parameter; `renderWithValidation` passes it as the new last argument of both `ideogramClient.generate(...)` and `ideogramClient.remix(...)` (replacing the `null` stubs from Task 4).

- [ ] **Step 5: Run to verify they pass**

Run: `./mvnw test -Dtest=PosterOrchestratorTest`
Expected: PASS (all existing + 4 new).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/poster/DjPhotoSnapshot.java src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java
git commit -m "feat: thread DjPhotoSnapshot through the poster orchestrator into both render paths"
```

---

### Task 6: DJ-mode concept generation (sampler, prompt, validation)

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/ai/CreativeDirectionSampler.java`
- Modify: `src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java`
- Test: `src/test/java/com/imin/iminapi/service/ai/CreativeDirectionSamplerTest.java` (extend or create in that package)
- Test: `src/test/java/com/imin/iminapi/service/AiEventDescriptionServiceTest.java` (extend; locate the existing test for this service first — `grep -rl "AiEventDescriptionService" src/test/java`)

The three DJ-mode rules (from the spec): effective variant plan = `[people, people, people]` fed to **both** sampler and validator; FEATURED DJ prompt block anchored after BRAND PALETTE (else after VIBE); human-policy caps and the typographic exclusion must not fire (the all-people plan plus a policy bypass handles this — without the bypass, vibes with `HumanPolicy.FORBIDDEN`/`RARE` would reject every DJ concept).

- [ ] **Step 1: Write the failing tests**

`CreativeDirectionSamplerTest` additions:

```java
    @Test
    void djModeForcesThreePeopleVariantsWithDistinctDjSubjects() {
        CreativeDirectionSampler sampler = new CreativeDirectionSampler();
        var run = sampler.sample(null, 42L, true); // even a null card yields the DJ plan
        assertThat(run.directions()).hasSize(3);
        assertThat(run.directions()).allMatch(d -> d.heroType() == HeroType.PEOPLE);
        var subjects = run.directions().stream().map(CreativeDirection::heroSubject).toList();
        assertThat(subjects).doesNotContainNull();
        assertThat(subjects).allMatch(s -> s.contains("DJ"));
        assertThat(new java.util.HashSet<>(subjects)).hasSize(3); // pairwise distinct
    }

    @Test
    void djModeIsDeterministicInSeed() {
        CreativeDirectionSampler sampler = new CreativeDirectionSampler();
        assertThat(sampler.sample(null, 42L, true).directions())
                .isEqualTo(sampler.sample(null, 42L, true).directions());
    }
```

`AiEventDescriptionServiceTest` additions (instantiate the service the way the existing tests do — `@RequiredArgsConstructor` means `new AiEventDescriptionService(chatClient, vibeLibrary, textSpecFactory, styleCardLibrary, sampler)` with mocks; reuse the file's existing helpers for a valid concept/request/card):

```java
    @Test
    void djModeValidateAcceptsThreePeopleVariantsRegardlessOfCardPlanAndPolicy() {
        // a concept whose 3 variants are all hero_type "people" with DJ-led prompts,
        // against a card whose variant_plan is [people, object, typographic] and humanPolicy RARE
        String error = service.validate(djConcept, request, cardWithMixedPlanAndRarePolicy, true);
        assertThat(error).isNull();
    }

    @Test
    void nonDjModeStillEnforcesTheCardPlan() {
        String error = service.validate(djConcept, request, cardWithMixedPlanAndRarePolicy, false);
        assertThat(error).isNotNull(); // variant[1] hero_type mismatch
    }

    @Test
    void djModePromptContainsFeaturedDjBlockAfterBrandPalette() {
        String prompt = service.buildPrompt(requestWithBrand, vibe, sampledDjRun, null, true);
        int brandIdx = prompt.indexOf("BRAND PALETTE");
        int djIdx = prompt.indexOf("FEATURED DJ — MANDATORY");
        assertThat(brandIdx).isPositive();
        assertThat(djIdx).isGreaterThan(brandIdx);
        assertThat(prompt).contains("never facial features");
    }

    @Test
    void djModePromptWithoutBrandStillContainsFeaturedDjBlock() {
        String prompt = service.buildPrompt(requestWithoutBrand, vibe, sampledDjRun, null, true);
        assertThat(prompt).doesNotContain("BRAND PALETTE");
        assertThat(prompt).contains("FEATURED DJ — MANDATORY");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest='CreativeDirectionSamplerTest,AiEventDescriptionServiceTest'`
Expected: COMPILE FAILURE — no 3-arg `sample`, no 4/5-arg `validate`/`buildPrompt`.

- [ ] **Step 3: Implement**

`CreativeDirectionSampler.java`:

```java
    /** DJ-hero framings, drawn 3-of-N without replacement so DJ runs still vary. */
    static final List<String> DJ_HERO_SUBJECTS = List.of(
            "the featured DJ mid-set behind the decks, hands on the mixer",
            "a monumental close-up portrait of the featured DJ, head and shoulders dominating the frame",
            "the featured DJ silhouetted against a packed crowd, arms raised",
            "the featured DJ in sharp profile under a single hard spotlight",
            "a low-angle hero shot of the featured DJ towering over the dancefloor",
            "the featured DJ centered in a wide club tableau, the unmistakable focal point");

    public SampledRun sample(StyleCard card, long seed) {
        return sample(card, seed, false);
    }

    /**
     * @param djMode when true the run ignores the vibe's variant_plan: all three variants are
     *               PEOPLE with a distinct DJ-hero subject (the attached character reference is
     *               the face); composition/accent/palette/type still come from the card pools.
     */
    public SampledRun sample(StyleCard card, long seed, boolean djMode) {
        if (!djMode) {
            return sampleStandard(card, seed); // rename of the existing body
        }
        Random random = new Random(seed);
        int count = 3;
        List<String> djSubjects = drawWithoutReplacement(DJ_HERO_SUBJECTS, count, random);
        List<String> compositions = drawWithoutReplacement(card == null ? null : card.compositions(), count, random);
        List<String> accents = drawWithoutReplacement(card == null ? null : card.accents(), count, random);
        List<String> paletteTwists = drawWithoutReplacement(card == null ? null : card.paletteTwists(), count, random);
        List<String> typeTreatments = drawWithoutReplacement(card == null ? null : card.typeTreatments(), count, random);
        List<CreativeDirection> directions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            directions.add(new CreativeDirection(HeroType.PEOPLE, djSubjects.get(i),
                    compositions.get(i), accents.get(i), paletteTwists.get(i), typeTreatments.get(i)));
        }
        String examplePrompt = card == null ? null : pickOne(card.examplePrompts(), random);
        return new SampledRun(List.copyOf(directions), examplePrompt);
    }
```

(Rename the current `sample(StyleCard, long)` body to a private `sampleStandard(StyleCard, long)`.)

`AiEventDescriptionService.java`:

1. The DJ-mode plan and block as constants:

```java
    private static final List<HeroType> DJ_PLAN = List.of(HeroType.PEOPLE, HeroType.PEOPLE, HeroType.PEOPLE);

    private static final String FEATURED_DJ_BLOCK = """
            FEATURED DJ — MANDATORY: a character reference photo of the headline DJ is attached.
            Every variant's ideogram_prompt must make this DJ the dominant hero of the poster — \
            describe pose, framing, lighting, scale, and wardrobe, never facial features, hair, \
            ethnicity, or age (the reference image controls the face). The DJ must be a single, \
            clearly visible human figure occupying the visual centre of gravity. The vibe \
            contributes mood, texture, composition, and typography around the DJ. Brand colours \
            must dominate the lighting, wardrobe accents, and background grade of every variant.

            """;

    private static final String DJ_HUMAN_RULE =
            "- Render the featured DJ as one clear, dominant human figure — the attached character "
            + "reference controls the face; never describe facial features, hair, ethnicity, or age";
```

2. `generateConcept`: keep the 2-arg as a delegate to a new `generateConcept(EventCreatorRequest request, long seed, boolean djMode)`; inside, replace `creativeDirectionSampler.sample(card, seed)` with `sample(card, seed, djMode)`, `buildPrompt(request, vibe, sampled, reinforcement)` with the 5-arg form, and `validate(concept, request, card)` with the 4-arg form.

3. `buildPrompt`: keep the 4-arg as a delegate to `buildPrompt(request, vibe, sampled, reinforcement, false)`. In the 5-arg form, immediately after the closing brace of the `if (notBlank(brandAccent)) { ... }` BRAND PALETTE block, add:

```java
        if (djMode) {
            sb.append(FEATURED_DJ_BLOCK);
        }
```

   (This lands after BRAND PALETTE when present, else after the VIBE block — the spec's anchor rule, for free.) And in the STRICT RULES section replace `.append(humanRule(humanStyle))` with `.append(djMode ? DJ_HUMAN_RULE : humanRule(humanStyle))`.

4. `validate`: keep existing arities delegating with `false`. The plan-aware core becomes:

```java
    String validate(PosterConcept concept, StyleCard card, boolean djMode) {
        ...
        List<HeroType> plan = djMode ? DJ_PLAN : planModes(card);
        ...
        // Policy caps on human heroes across the whole run — skipped in DJ mode, where
        // three DJ-hero variants are the entire point and the reference controls rendering.
        if (!djMode) {
            ... existing FORBIDDEN / RARE / REQUIRED block unchanged ...
        }
        ...
    }
```

   and `validate(PosterConcept, EventCreatorRequest, StyleCard, boolean djMode)` delegates its shape check to the 3-arg form above. (The per-slot TYPOGRAPHIC exclusion never fires in DJ mode because `DJ_PLAN` has no typographic slot.)

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw test -Dtest='CreativeDirectionSamplerTest,AiEventDescriptionServiceTest'`
Expected: PASS (existing + new).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/ai/CreativeDirectionSampler.java src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java src/test/java
git commit -m "feat: DJ-mode concept generation — all-people plan, FEATURED DJ prompt block, policy bypass"
```

---

### Task 7: `ConceptStudioService` — eventId binding, snapshot read-back, `djPhotoUsed`

**Files:**
- Modify: `src/main/java/com/imin/iminapi/dto/ai/ConceptRequest.java` (append `UUID eventId`)
- Modify: `src/main/java/com/imin/iminapi/dto/ai/ConceptResponse.java` (append `boolean djPhotoUsed`)
- Modify: `src/main/java/com/imin/iminapi/repository/PosterGenerationRepository.java`
- Modify: `src/main/java/com/imin/iminapi/service/ai/ConceptStudioService.java`
- Test: `src/test/java/com/imin/iminapi/service/ai/ConceptStudioServiceDjPhotoTest.java` (create)

- [ ] **Step 1: DTO + repository changes (mechanical, compiler-driven)**

`ConceptRequest`: append `, UUID eventId` as the final record component (after `logoOnPosters`), with javadoc:

```java
        // Optional: bind this generation to an owned event. When the event has a DJ photo, all
        // three variants render that DJ via Ideogram character reference. Cross-org → NOT_FOUND.
        UUID eventId) {}
```

`ConceptResponse`: append `, boolean djPhotoUsed` as the final component (true only when the character reference was actually sent).

`PosterGenerationRepository`: add the derived query

```java
    Optional<PosterGeneration> findTopByGeneratedEventIdOrderByCreatedAtDesc(UUID generatedEventId);
```

Then run `./mvnw test-compile` and fix every constructor call site the compiler reports (at minimum: `ConceptStudioService.regenerate(...)` gets `/* eventId */ null` — replaced properly in Step 3 — and `ConceptStudioService.run(...)`'s `new ConceptResponse(...)` gets the new flag; plus any tests constructing these records).

- [ ] **Step 2: Write the failing tests**

Create `ConceptStudioServiceDjPhotoTest` — pure-Mockito unit test of the new resolution logic:

```java
package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.ai.ConceptRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PosterGenerationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.AiEventDescriptionService;
import com.imin.iminapi.service.PricingService;
import com.imin.iminapi.service.poster.DjPhotoSnapshot;
import com.imin.iminapi.service.poster.PosterImageStorage;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import com.imin.iminapi.service.poster.VibeLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConceptStudioServiceDjPhotoTest {

    private final EventRepository events = mock(EventRepository.class);
    private final PosterImageStorage storage = mock(PosterImageStorage.class);
    private final PosterGenerationRepository generations = mock(PosterGenerationRepository.class);
    private ConceptStudioService service;
    private final UUID orgId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final AuthPrincipal principal = principalFor(orgId); // build the record the way other tests do

    @BeforeEach
    void setUp() {
        service = new ConceptStudioService(
                mock(AiEventDescriptionService.class), mock(PosterOrchestrator.class),
                mock(PricingService.class), mock(ConceptOverviewLlm.class),
                mock(GeneratedEventRepository.class), mock(OrganizationRepository.class),
                mock(VibeLibrary.class), events, storage, generations);
    }

    private Event ownedEventWithPhoto() {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setDjPhotoUrl("https://cdn.example/events/x/dj-photo-abc.jpg");
        return e;
    }

    @Test
    void resolvesSnapshotForOwnedEventWithPhoto() {
        when(events.findActive(eventId)).thenReturn(Optional.of(ownedEventWithPhoto()));
        when(storage.download("https://cdn.example/events/x/dj-photo-abc.jpg")).thenReturn(new byte[]{1});
        DjPhotoSnapshot snap = service.resolveDjPhotoFromEvent(principal, eventId);
        assertThat(snap).isNotNull();
        assertThat(snap.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void crossOrgEventIsNotFoundNotForbidden() {
        Event foreign = ownedEventWithPhoto();
        foreign.setOrgId(UUID.randomUUID());
        when(events.findActive(eventId)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.resolveDjPhotoFromEvent(principal, eventId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void nullEventIdOrNoPhotoYieldsNull() {
        assertThat(service.resolveDjPhotoFromEvent(principal, null)).isNull();
        Event noPhoto = ownedEventWithPhoto();
        noPhoto.setDjPhotoUrl(null);
        when(events.findActive(eventId)).thenReturn(Optional.of(noPhoto));
        assertThat(service.resolveDjPhotoFromEvent(principal, eventId)).isNull();
    }

    @Test
    void downloadFailureDegradesToNullNotThrow() {
        when(events.findActive(eventId)).thenReturn(Optional.of(ownedEventWithPhoto()));
        when(storage.download(anyString())).thenThrow(new RuntimeException("R2 down"));
        assertThat(service.resolveDjPhotoFromEvent(principal, eventId)).isNull();
    }
}
```

(`principalFor`: copy how an `AuthPrincipal` is built in any existing service test — it is a record, so `new AuthPrincipal(...)` with the org id in the `orgId` slot.)

- [ ] **Step 3: Run to verify they fail, then implement**

Run: `./mvnw test -Dtest=ConceptStudioServiceDjPhotoTest` — COMPILE FAILURE (10-arg constructor, `resolveDjPhotoFromEvent`).

`ConceptStudioService.java` changes:

1. Three new constructor-injected finals: `EventRepository eventRepo`, `PosterImageStorage posterStorage`, `PosterGenerationRepository generationRepo` (appended to the constructor).
2. Resolution helpers:

```java
    /**
     * Resolve the DJ photo for a generation bound to an owned event. Null when: no eventId, the
     * event has no photo, or the R2 download fails (the photo must never break generation — the
     * response then reports djPhotoUsed=false). Cross-org access is NOT_FOUND, never FORBIDDEN.
     */
    DjPhotoSnapshot resolveDjPhotoFromEvent(AuthPrincipal p, UUID eventId) {
        if (eventId == null) return null;
        Event e = eventRepo.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return resolveDjPhotoFromUrl(e.getDjPhotoUrl());
    }

    private DjPhotoSnapshot resolveDjPhotoFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            byte[] bytes = posterStorage.download(url);
            String mime = url.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
            return new DjPhotoSnapshot(url, bytes, mime);
        } catch (Exception ex) {
            log.warn("DJ photo download failed; generating without character reference: {}", ex.getMessage());
            return null;
        }
    }
```

   (imports: `com.imin.iminapi.model.Event`, `com.imin.iminapi.repository.EventRepository`, `com.imin.iminapi.repository.PosterGenerationRepository`, `com.imin.iminapi.service.poster.DjPhotoSnapshot`, `com.imin.iminapi.service.poster.PosterImageStorage`.)

3. `create(...)`: `return run(p, req, resolveDjPhotoFromEvent(p, req.eventId()));`
4. `regenerate(...)`: read the snapshot back, not the live event (per spec §3) — after loading `prior`, before `run`:

```java
        DjPhotoSnapshot djPhoto = generationRepo.findTopByGeneratedEventIdOrderByCreatedAtDesc(conceptId)
                .map(PosterGeneration::getDjPhotoUrl)
                .map(this::resolveDjPhotoFromUrl)
                .orElse(null);
```

   and pass it: `return run(p, req, djPhoto);` — also append `/* eventId */ null` to the `ConceptRequest` it builds. (import `com.imin.iminapi.model.PosterGeneration`.)
5. `run(...)` becomes `private ConceptResponse run(AuthPrincipal p, ConceptRequest req, DjPhotoSnapshot djPhoto)`:
   - `descService.generateConcept(legacy, creativeSeed)` → `descService.generateConcept(legacy, creativeSeed, djPhoto != null)`
   - `orchestrator.run(staging.getId(), legacy, poster, creativeSeed, generated.directions(), brand)` → same + `, djPhoto`
   - the final `new ConceptResponse(...)` gains `, djPhoto != null` as its last argument.

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: PASS — everything compiles, all suites green (this is the integration point; fix any remaining record-arity call sites the build surfaces).

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: concept generation binds to an owned event's DJ photo; regenerate reads the snapshot back"
```

---

### Task 8: Final verification

- [ ] **Step 1: Full build + tests**

Run: `./mvnw clean package`
Expected: BUILD SUCCESS, zero test failures.

- [ ] **Step 2: Manual smoke (only if local env is configured)**

With `IDEOGRAM_API_KEY`/`OPENROUTER_API_KEY` set and `MEDIA_ENABLED=false`: start the app (`./mvnw spring-boot:run`), upload a DJ photo to an event (`POST /api/v1/events/{id}/media/dj-photo`), then `POST /api/v1/ai/events/concept` with that `eventId` and confirm the response has `djPhotoUsed: true` and the Ideogram request log lines show `charRef=true`. Skip silently if the env isn't set up; the unit suites cover the logic.

- [ ] **Step 3: Done — hand off**

Merge/PR per the `superpowers:finishing-a-development-branch` skill. **Deploy order matters:** this repo merges and deploys to production BEFORE the webapp plan's `api:sync` step runs (the FE type-regen pulls the production OpenAPI).
