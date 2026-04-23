# File Uploads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Depends on:** `2026-04-23-foundation.md`, `2026-04-23-auth.md`, `2026-04-23-events-core.md`.

**Goal:** Upload poster, video, and cover assets for an event to Cloudflare R2 (S3-compatible) with permanent, publicly-readable URLs. Implement DELETE per kind. Endpoints: `POST/DELETE /api/v1/events/:id/media/{poster|video|cover}`. Matches contract §8.

**Architecture:** A `MediaStorage` interface defined as the swap seam (so tests use an in-memory impl, prod uses R2). The R2 implementation uses the AWS SDK v2 `S3Client` configured with R2's endpoint. Uploaded objects are keyed `events/{eventId}/{kind}.{ext}` and the bucket is policy-public-read so the returned URL is permanent. Validation (size, MIME, video duration) happens in `MediaUploadService` before bytes are sent to R2. The corresponding URL field on the `Event` row (`posterUrl` / `videoUrl` / `coverUrl`) is updated atomically.

**Tech Stack:** Java 17, Spring Boot 4.0.5, AWS SDK v2 (`s3` 2.27.x), Cloudflare R2 endpoint override, Jakarta Servlet multipart, JCodec for video duration extraction (or `mediainfo` shell — see Task 5), JUnit 5, MockMvc, Mockito.

---

## Decisions locked

- **Backend:** Cloudflare R2 with public-read bucket and stable URL `https://<custom-domain>/<key>` (or `https://<account>.r2.cloudflarestorage.com/<bucket>/<key>` if no custom domain).
- **URL semantics:** permanent. No `expiresAt` returned.
- **Video duration:** parsed server-side. JCodec via Maven for header-only metadata read; if unavailable in pure Java, fall back to invoking the bundled `mediainfo` binary in Task 5 (decision deferred to runtime — implementer picks).
- **Quotas (server-enforced, FE pre-validates):** poster ≤ 5 MB JPG/PNG, video ≤ 50 MB MP4 ≤ 30 s, cover ≤ 5 MB JPG/PNG.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `pom.xml` | Modify | Add `software.amazon.awssdk:s3` (2.27.21), `software.amazon.awssdk:url-connection-client`, optionally `org.jcodec:jcodec` (0.2.5) |
| `src/main/resources/application.yaml` | Modify | R2 config (bucket, endpoint, access keys, public URL prefix) |
| `src/main/resources/application-dev.yaml` | Modify | Defaults pointing at R2 dev account or empty if disabled |
| `src/main/java/com/imin/iminapi/config/R2Config.java` | Create | `S3Client` bean for R2 |
| `src/main/java/com/imin/iminapi/storage/MediaStorage.java` | Create | Interface: `Stored put(String key, byte[] bytes, String contentType)`, `void delete(String key)` |
| `src/main/java/com/imin/iminapi/storage/R2MediaStorage.java` | Create | R2-backed impl |
| `src/main/java/com/imin/iminapi/storage/InMemoryMediaStorage.java` | Create (test) | Map-backed impl returning fake URL |
| `src/main/java/com/imin/iminapi/dto/event/MediaUploadResponse.java` | Create | `{url, sizeBytes, contentType, durationSec?}` |
| `src/main/java/com/imin/iminapi/model/MediaKind.java` | Create | enum POSTER/VIDEO/COVER |
| `src/main/java/com/imin/iminapi/service/event/MediaUploadService.java` | Create | validate, store, update Event |
| `src/main/java/com/imin/iminapi/service/event/VideoMetadata.java` | Create | helper: `Duration probeMp4Duration(byte[])` |
| `src/main/java/com/imin/iminapi/controller/event/EventMediaController.java` | Create | endpoints |
| `src/test/java/com/imin/iminapi/service/event/MediaUploadServiceTest.java` | Create | unit tests |
| `src/test/java/com/imin/iminapi/controller/event/EventMediaControllerTest.java` | Create | MockMvc with multipart |

---

## Task 1: Maven dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add AWS SDK v2**

Inside `<dependencies>`:

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.27.21</version>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>url-connection-client</artifactId>
    <version>2.27.21</version>
</dependency>
<dependency>
    <groupId>org.jcodec</groupId>
    <artifactId>jcodec</artifactId>
    <version>0.2.5</version>
</dependency>
```

- [ ] **Step 2: Verify compile**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "deps: AWS S3 SDK and JCodec for R2 media uploads"
```

---

## Task 2: R2 application config

**Files:**
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/resources/application.yaml`

- [ ] **Step 1: Append to main application.yaml**

```yaml
imin:
  media:
    enabled: ${MEDIA_ENABLED:true}
    bucket: ${R2_BUCKET:imin-media-dev}
    endpoint: ${R2_ENDPOINT:https://example.r2.cloudflarestorage.com}
    region: ${R2_REGION:auto}
    access-key-id: ${R2_ACCESS_KEY_ID:}
    secret-access-key: ${R2_SECRET_ACCESS_KEY:}
    public-url-prefix: ${R2_PUBLIC_URL_PREFIX:https://media-dev.imin.example/}
```

- [ ] **Step 2: Append to test application.yaml**

```yaml
imin:
  media:
    enabled: false
    bucket: test
    endpoint: https://test.invalid
    region: auto
    access-key-id: test
    secret-access-key: test
    public-url-prefix: https://test-media.invalid/
```

`enabled: false` makes `R2Config` skip wiring the real `S3Client` — tests provide an in-memory `MediaStorage` bean.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yaml src/test/resources/application.yaml
git commit -m "config: R2 media credentials and public URL prefix"
```

---

## Task 3: MediaStorage interface + InMemory impl + R2 impl

**Files:**
- Create: `src/main/java/com/imin/iminapi/storage/MediaStorage.java`
- Create: `src/main/java/com/imin/iminapi/storage/R2MediaStorage.java`
- Create: `src/main/java/com/imin/iminapi/storage/InMemoryMediaStorage.java`
- Create: `src/main/java/com/imin/iminapi/config/R2Config.java`

- [ ] **Step 1: Define MediaStorage interface**

```java
package com.imin.iminapi.storage;

public interface MediaStorage {

    /**
     * Store {@code bytes} at {@code key} (e.g. "events/uuid/poster.png").
     * Returns the publicly-readable URL.
     */
    Stored put(String key, byte[] bytes, String contentType);

    void delete(String key);

    record Stored(String url, long sizeBytes, String contentType) {}
}
```

- [ ] **Step 2: InMemoryMediaStorage**

```java
package com.imin.iminapi.storage;

import java.util.HashMap;
import java.util.Map;

public class InMemoryMediaStorage implements MediaStorage {

    private final Map<String, byte[]> blobs = new HashMap<>();
    private final String publicPrefix;

    public InMemoryMediaStorage(String publicPrefix) {
        this.publicPrefix = publicPrefix.endsWith("/") ? publicPrefix : publicPrefix + "/";
    }

    @Override
    public Stored put(String key, byte[] bytes, String contentType) {
        blobs.put(key, bytes);
        return new Stored(publicPrefix + key, bytes.length, contentType);
    }

    @Override
    public void delete(String key) { blobs.remove(key); }

    public Map<String, byte[]> blobs() { return blobs; }
}
```

- [ ] **Step 3: R2Config (skipped when disabled)**

```java
package com.imin.iminapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "imin.media.enabled", havingValue = "true")
public class R2Config {

    @Bean
    public S3Client s3Client(@Value("${imin.media.endpoint}") String endpoint,
                             @Value("${imin.media.region}") String region,
                             @Value("${imin.media.access-key-id}") String accessKeyId,
                             @Value("${imin.media.secret-access-key}") String secret) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secret)))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
```

- [ ] **Step 4: R2MediaStorage**

```java
package com.imin.iminapi.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@ConditionalOnProperty(name = "imin.media.enabled", havingValue = "true")
public class R2MediaStorage implements MediaStorage {

    private final S3Client s3;
    private final String bucket;
    private final String publicPrefix;

    public R2MediaStorage(S3Client s3,
                          @Value("${imin.media.bucket}") String bucket,
                          @Value("${imin.media.public-url-prefix}") String publicPrefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.publicPrefix = publicPrefix.endsWith("/") ? publicPrefix : publicPrefix + "/";
    }

    @Override
    public Stored put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .contentType(contentType)
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(bytes));
        return new Stored(publicPrefix + key, bytes.length, contentType);
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
```

- [ ] **Step 5: Test bean for the in-memory storage**

Create `src/test/java/com/imin/iminapi/config/TestMediaStorageConfig.java`:

```java
package com.imin.iminapi.config;

import com.imin.iminapi.storage.InMemoryMediaStorage;
import com.imin.iminapi.storage.MediaStorage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestMediaStorageConfig {

    @Bean @Primary
    public MediaStorage inMemoryMediaStorage() {
        return new InMemoryMediaStorage("https://test-media.invalid/");
    }
}
```

- [ ] **Step 6: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/config/R2Config.java src/main/java/com/imin/iminapi/storage/ src/test/java/com/imin/iminapi/config/TestMediaStorageConfig.java
git commit -m "storage: MediaStorage seam with R2 and in-memory implementations"
```

---

## Task 4: MediaKind enum + MediaUploadResponse DTO

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/MediaKind.java`
- Create: `src/main/java/com/imin/iminapi/dto/event/MediaUploadResponse.java`

- [ ] **Step 1: MediaKind**

```java
package com.imin.iminapi.model;

public enum MediaKind {
    POSTER, VIDEO, COVER;

    public String wireValue() { return name().toLowerCase(); }

    public static MediaKind fromWire(String s) {
        return switch (s) {
            case "poster" -> POSTER;
            case "video" -> VIDEO;
            case "cover" -> COVER;
            default -> throw new IllegalArgumentException("Unknown media kind: " + s);
        };
    }
}
```

- [ ] **Step 2: MediaUploadResponse**

```java
package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaUploadResponse(String url, long sizeBytes, String contentType, Integer durationSec) {}
```

- [ ] **Step 3: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/model/MediaKind.java src/main/java/com/imin/iminapi/dto/event/MediaUploadResponse.java
git commit -m "media: MediaKind enum + upload response DTO"
```

---

## Task 5: VideoMetadata helper

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/event/VideoMetadata.java`

- [ ] **Step 1: Implement using JCodec**

```java
package com.imin.iminapi.service.event;

import org.jcodec.containers.mp4.MP4Util;
import org.jcodec.containers.mp4.boxes.MovieBox;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.io.NIOUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;

@Component
public class VideoMetadata {

    /**
     * Returns the playback duration of an MP4 in seconds (rounded up).
     * Returns null if the bytes cannot be parsed (caller treats as "unknown").
     */
    public Integer probeMp4DurationSec(byte[] bytes) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            MovieBox mov = MP4Util.parseMovie(NIOUtils.readableChannel(bb));
            if (mov == null || mov.getDuration() == 0 || mov.getTimescale() == 0) return null;
            double seconds = (double) mov.getDuration() / mov.getTimescale();
            return (int) Math.ceil(seconds);
        } catch (Exception e) {
            return null;
        }
    }
}
```

> Note: `NIOUtils.readableChannel(ByteBuffer)` is JCodec's helper. If the API changed in 0.2.5, the alternative is to write the bytes to a temp file and use `MP4Util.parseMovie(NIOUtils.readableChannel(tempFile))`. Implementer should adjust if compile fails.

- [ ] **Step 2: Compile**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS. If JCodec API mismatch, switch to temp-file variant or replace with shelling out to `mediainfo` (via `ProcessBuilder`) — record the choice in a code comment on the class.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/event/VideoMetadata.java
git commit -m "media: VideoMetadata helper using JCodec for MP4 duration"
```

---

## Task 6: MediaUploadService — validate, store, link to event

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/event/MediaUploadService.java`
- Create: `src/test/java/com/imin/iminapi/service/event/MediaUploadServiceTest.java`

- [ ] **Step 1: Write the test**

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MediaUploadServiceTest {

    EventRepository events = mock(EventRepository.class);
    InMemoryMediaStorage storage = new InMemoryMediaStorage("https://media.test/");
    VideoMetadata video = new VideoMetadata();
    MediaUploadService sut = new MediaUploadService(events, storage, video);

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private Event ev(UUID orgId) {
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(orgId);
        e.setName("X"); e.setSlug("x");
        return e;
    }

    @Test
    void poster_png_under_5mb_uploads_and_sets_url() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] png = pngBytes(1024);
        MediaUploadResponse r = sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, png, "image/png", "poster.png");

        assertThat(r.url()).startsWith("https://media.test/events/").endsWith("/poster.png");
        assertThat(r.contentType()).isEqualTo("image/png");
        assertThat(r.durationSec()).isNull();
        assertThat(e.getPosterUrl()).isEqualTo(r.url());
    }

    @Test
    void poster_above_5mb_returns_FIELD_INVALID() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        byte[] big = new byte[6 * 1024 * 1024];
        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, big, "image/png", "p.png"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void poster_with_bad_mime_returns_FIELD_INVALID() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, new byte[10], "image/gif", "x.gif"))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
    }

    @Test
    void other_org_event_returns_NOT_FOUND() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(UUID.randomUUID()); // different org
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> sut.upload(owner(orgId), e.getId(), MediaKind.POSTER, pngBytes(10), "image/png", "p.png"))
                .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
    }

    @Test
    void delete_clears_url_field_and_blob() {
        UUID orgId = UUID.randomUUID();
        Event e = ev(orgId);
        e.setPosterUrl("https://media.test/events/" + e.getId() + "/poster.png");
        storage.put("events/" + e.getId() + "/poster.png", new byte[1], "image/png");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        sut.delete(owner(orgId), e.getId(), MediaKind.POSTER);

        assertThat(e.getPosterUrl()).isNull();
        assertThat(storage.blobs()).isEmpty();
    }

    private static byte[] pngBytes(int size) {
        byte[] b = new byte[size];
        b[0] = (byte) 0x89; b[1] = (byte) 0x50; b[2] = (byte) 0x4E; b[3] = (byte) 0x47;
        b[4] = (byte) 0x0D; b[5] = (byte) 0x0A; b[6] = (byte) 0x1A; b[7] = (byte) 0x0A;
        return b;
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=MediaUploadServiceTest`
Expected: FAIL — service doesn't exist.

- [ ] **Step 3: Implement MediaUploadService**

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.storage.MediaStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaUploadService {

    private static final long MB = 1024L * 1024L;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/jpg");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4");

    private final EventRepository events;
    private final MediaStorage storage;
    private final VideoMetadata videoMetadata;

    public MediaUploadService(EventRepository events, MediaStorage storage, VideoMetadata videoMetadata) {
        this.events = events;
        this.storage = storage;
        this.videoMetadata = videoMetadata;
    }

    @Transactional
    public MediaUploadResponse upload(AuthPrincipal p, UUID eventId, MediaKind kind,
                                      byte[] bytes, String contentType, String originalFilename) {
        Event e = loadOwned(p, eventId);
        validate(kind, bytes, contentType);
        Integer durationSec = null;
        if (kind == MediaKind.VIDEO) {
            durationSec = videoMetadata.probeMp4DurationSec(bytes);
            if (durationSec != null && durationSec > 30) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                        "Video must be ≤ 30 seconds", Map.of("file", "duration > 30s"));
            }
        }
        String key = "events/" + e.getId() + "/" + kind.wireValue() + "." + extensionFor(contentType, originalFilename);
        MediaStorage.Stored stored = storage.put(key, bytes, contentType);

        switch (kind) {
            case POSTER -> e.setPosterUrl(stored.url());
            case VIDEO -> e.setVideoUrl(stored.url());
            case COVER -> e.setCoverUrl(stored.url());
        }
        events.save(e);
        return new MediaUploadResponse(stored.url(), stored.sizeBytes(), stored.contentType(), durationSec);
    }

    @Transactional
    public void delete(AuthPrincipal p, UUID eventId, MediaKind kind) {
        Event e = loadOwned(p, eventId);
        String url = switch (kind) {
            case POSTER -> e.getPosterUrl();
            case VIDEO -> e.getVideoUrl();
            case COVER -> e.getCoverUrl();
        };
        if (url == null) return;
        // Reverse-derive key from public URL (everything after the prefix).
        String key = "events/" + e.getId() + "/" + kind.wireValue() + "." + url.substring(url.lastIndexOf('.') + 1);
        try { storage.delete(key); } catch (Exception ignored) {}
        switch (kind) {
            case POSTER -> e.setPosterUrl(null);
            case VIDEO -> e.setVideoUrl(null);
            case COVER -> e.setCoverUrl(null);
        }
        events.save(e);
    }

    private Event loadOwned(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    private static void validate(MediaKind kind, byte[] bytes, String contentType) {
        long size = bytes.length;
        switch (kind) {
            case POSTER, COVER -> {
                if (size > 5 * MB) throw fieldErr("file", "must be ≤ 5 MB");
                if (!IMAGE_TYPES.contains(contentType)) throw fieldErr("file", "must be JPG or PNG");
            }
            case VIDEO -> {
                if (size > 50 * MB) throw fieldErr("file", "must be ≤ 50 MB");
                if (!VIDEO_TYPES.contains(contentType)) throw fieldErr("file", "must be MP4");
            }
        }
    }

    private static ApiException fieldErr(String field, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                "Invalid file", Map.of(field, msg));
    }

    private static String extensionFor(String contentType, String originalFilename) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "video/mp4" -> "mp4";
            default -> {
                int dot = originalFilename.lastIndexOf('.');
                yield dot >= 0 ? originalFilename.substring(dot + 1).toLowerCase() : "bin";
            }
        };
    }
}
```

- [ ] **Step 4: Re-run test, expect pass**

Run: `./mvnw -q test -Dtest=MediaUploadServiceTest`
Expected: 5 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/event/MediaUploadService.java src/test/java/com/imin/iminapi/service/event/MediaUploadServiceTest.java
git commit -m "media: MediaUploadService validates and persists per-kind uploads"
```

---

## Task 7: EventMediaController + multipart limits

**Files:**
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/imin/iminapi/controller/event/EventMediaController.java`
- Create: `src/test/java/com/imin/iminapi/controller/event/EventMediaControllerTest.java`

- [ ] **Step 1: Bump multipart limits**

Append to `src/main/resources/application.yaml`:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 60MB
      max-request-size: 60MB
```

> 60 MB envelope to allow the 50 MB video plus headers.

- [ ] **Step 2: Write the MockMvc test**

```java
package com.imin.iminapi.controller.event;

import com.imin.iminapi.config.TestMediaStorageConfig;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.event.MediaUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestRateLimitConfig.class, TestMediaStorageConfig.class})
class EventMediaControllerTest {

    @Autowired MockMvc mvc;
    @MockBean MediaUploadService uploadService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubUser {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Test
    @WithStubUser
    void post_poster_returns_url() throws Exception {
        UUID id = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "p.png", "image/png", new byte[]{(byte) 0x89, 'P','N','G'});
        when(uploadService.upload(any(), eq(id), eq(MediaKind.POSTER), any(), eq("image/png"), eq("p.png")))
                .thenReturn(new MediaUploadResponse("https://media.test/events/" + id + "/poster.png", 4, "image/png", null));

        mvc.perform(multipart("/api/v1/events/" + id + "/media/poster").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.durationSec").doesNotExist());
    }

    @Test
    @WithStubUser
    void post_video_returns_url_and_duration() throws Exception {
        UUID id = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "v.mp4", "video/mp4", new byte[]{0,0,0,0});
        when(uploadService.upload(any(), eq(id), eq(MediaKind.VIDEO), any(), eq("video/mp4"), eq("v.mp4")))
                .thenReturn(new MediaUploadResponse("https://media.test/events/" + id + "/video.mp4", 4, "video/mp4", 12));

        mvc.perform(multipart("/api/v1/events/" + id + "/media/video").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationSec").value(12));
    }

    @Test
    @WithStubUser
    void delete_returns_204() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/api/v1/events/" + id + "/media/poster"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 3: Run, expect failure**

Run: `./mvnw -q test -Dtest=EventMediaControllerTest`
Expected: FAIL.

- [ ] **Step 4: Implement EventMediaController**

```java
package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.MediaUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/media")
public class EventMediaController {

    private final MediaUploadService uploadService;

    public EventMediaController(MediaUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(path = "/{kind}", consumes = "multipart/form-data")
    public MediaUploadResponse upload(@CurrentUser AuthPrincipal p,
                                      @PathVariable UUID eventId,
                                      @PathVariable String kind,
                                      @RequestPart("file") MultipartFile file) throws IOException {
        MediaKind k = MediaKind.fromWire(kind);
        return uploadService.upload(p, eventId, k, file.getBytes(),
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename());
    }

    @DeleteMapping("/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthPrincipal p,
                       @PathVariable UUID eventId,
                       @PathVariable String kind) {
        uploadService.delete(p, eventId, MediaKind.fromWire(kind));
    }
}
```

- [ ] **Step 5: Re-run test, expect pass**

Run: `./mvnw -q test -Dtest=EventMediaControllerTest`
Expected: 3 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.yaml src/main/java/com/imin/iminapi/controller/event/EventMediaController.java src/test/java/com/imin/iminapi/controller/event/EventMediaControllerTest.java
git commit -m "media: EventMediaController with multipart upload + delete"
```

---

## Task 8: End-to-end smoke (requires real R2 credentials)

**Files:** none

> Skip this task if you don't have R2 creds yet. The unit + MockMvc tests cover correctness; this task verifies the R2 wiring.

- [ ] **Step 1: Set R2 env vars**

```bash
export R2_BUCKET=imin-media-dev
export R2_ENDPOINT=https://<account>.r2.cloudflarestorage.com
export R2_ACCESS_KEY_ID=...
export R2_SECRET_ACCESS_KEY=...
export R2_PUBLIC_URL_PREFIX=https://media-dev.imin.example/
export MEDIA_ENABLED=true
```

- [ ] **Step 2: Boot the app**

Run: `./mvnw -q -DskipTests spring-boot:run`

- [ ] **Step 3: Get token + create event (see events-core plan)**

```bash
TOKEN=$(curl -s -X POST http://localhost:8085/api/v1/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"media-smoke@example.com","password":"lovelace12","orgName":"X","country":"GB"}' | jq -r .token)
EID=$(curl -s -X POST http://localhost:8085/api/v1/events -H "Authorization: Bearer $TOKEN" -d '{}' | jq -r .id)
```

- [ ] **Step 4: Upload poster**

```bash
curl -s -X POST "http://localhost:8085/api/v1/events/$EID/media/poster" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./tiny.png;type=image/png" | jq
```
Expected: a JSON `{url, sizeBytes, contentType}` and the URL is reachable in a browser.

- [ ] **Step 5: Confirm Event row was updated**

```bash
curl -s "http://localhost:8085/api/v1/events/$EID" -H "Authorization: Bearer $TOKEN" | jq .posterUrl
```
Expected: matches the URL from step 4.

- [ ] **Step 6: Delete poster**

```bash
curl -s -i -X DELETE "http://localhost:8085/api/v1/events/$EID/media/poster" -H "Authorization: Bearer $TOKEN"
```
Expected: HTTP/1.1 204.

---

## Self-Review

- **Spec coverage (§8):** all three POST media endpoints + DELETE per kind. ✓
- **Size and format limits:** poster/cover ≤ 5 MB JPG/PNG; video ≤ 50 MB MP4 ≤ 30 s. ✓
- **URL semantics:** permanent (R2 public-read bucket). No `expiresAt` returned, matching the chosen variant.
- **Placeholder scan:** none. The `extensionFor` fallback path is deterministic, not a TODO.
- **Type consistency:** `MediaKind` wire form ("poster"/"video"/"cover") matches contract paths; `MediaUploadResponse.durationSec` is `Integer` so it's omitted from JSON for non-video uploads.
- **Gap:** the bucket and CORS configuration in R2 itself is operational (not code) — record in deployment notes that the bucket needs `Allow public read` and CORS rules permitting the FE origin.
