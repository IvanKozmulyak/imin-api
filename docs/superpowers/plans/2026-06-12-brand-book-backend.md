# Brand Book Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give organizers an org-level brand book (display name, PNG logo, up to 3 ordered accent colours, logo-on-posters toggle) that persists on `organizations`, exposes a JWT-scoped CRUD + logo-upload API, and feeds AI poster generation — accent colours injected into the Ideogram prompt and the logo composited bottom-right onto accepted posters via pure Java2D, with failure never breaking generation.

**Architecture:** Two backend PRs in `imin-api` (Java 17, Spring Boot 4). PR 1 is purely additive data + contract (V38 migration, `Organization` fields, `OrgBrandController`/`OrgBrandService`, `OrgMediaService.uploadLogo`, a `MaxUploadSizeExceededException` → 413 handler) — generation untouched, so empty brand columns ⇒ today's behaviour byte-for-byte. PR 2 wires brand data into generation (ConceptRequest field, prompt colour packing, a `brand_snapshot` provenance stamp, and a `BrandLogoCompositor` invoked inside the single `accept()` funnel in `PosterOrchestrator`). PR 2 must be deployed only after PR 1 is live (the FE `api:sync` pulls prod OpenAPI; see §7 of the spec).

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring Data JPA + Flyway, H2 (PG-compat) for tests, Lombok, Jackson, Cloudflare R2 behind the `MediaStorage` seam, JUnit 5 + Mockito + AssertJ, pure `java.awt` (BufferedImage/Graphics2D/ImageIO) for compositing — no new dependency.

---

## File structure

### PR 1 — `feat/brand-book-data`

| File | Created/Modified | Responsibility |
|---|---|---|
| `src/main/resources/db/migration/V38__org_brand_book.sql` | Create | Adds brand columns to `organizations`, `poster_generations.brand_snapshot`, `poster_variants.logo_composite_status`. |
| `src/main/java/com/imin/iminapi/model/Organization.java` | Modify | Adds `brandName`, `brandLogoUrl`, `brandAccentColors` (StringListJsonConverter), `brandLogoOnPosters` fields. |
| `src/main/java/com/imin/iminapi/dto/org/BrandBookDto.java` | Create | GET/PUT response record `{brandName, logoUrl, accentColors, logoOnPosters}`. |
| `src/main/java/com/imin/iminapi/dto/org/BrandUpdateRequest.java` | Create | PUT request record `{brandName, accentColors, logoOnPosters}` (full replace). |
| `src/main/java/com/imin/iminapi/dto/org/LogoUploadResponse.java` | Create | POST logo response record `{logoUrl}`. |
| `src/main/java/com/imin/iminapi/service/org/OrgBrandService.java` | Create | Validation (hex regex, normalize, dedupe, max-3, name trim) + GET/PUT + logo url clear. |
| `src/main/java/com/imin/iminapi/service/org/OrgMediaService.java` | Create | `uploadLogo` / `deleteLogo`: PNG magic bytes, ≤2 MB, dimensions/aspect, R2 put + old-object cleanup. |
| `src/main/java/com/imin/iminapi/controller/org/OrgBrandController.java` | Create | GET/PUT `/api/v1/org/brand`, POST/DELETE `/api/v1/org/brand/logo`. |
| `src/main/java/com/imin/iminapi/security/GlobalExceptionHandler.java` | Modify | Adds `MaxUploadSizeExceededException` → 413 handler. |
| `src/test/java/com/imin/iminapi/service/org/OrgBrandServiceTest.java` | Create | Hex matrix, max-3, dedupe, order, clear-via-[], per-index keys, name trim. |
| `src/test/java/com/imin/iminapi/service/org/OrgMediaServiceTest.java` | Create | PNG magic, size/dimension/aspect rejects, key shape, delete-old-after-put. |
| `src/test/java/com/imin/iminapi/controller/org/OrgBrandControllerTest.java` | Create | Auth, org-scoping, error envelope, multipart, 413. |
| `src/test/java/com/imin/iminapi/migration/V38BrandBookMigrationTest.java` | Create | Flyway V38 applies; defaults correct on an existing row; non-empty ordered accent list round-trips through `StringListJsonConverter`. |

### PR 2 — `feat/brand-book-generation`

| File | Created/Modified | Responsibility |
|---|---|---|
| `src/main/java/com/imin/iminapi/dto/ai/ConceptRequest.java` | Modify | Adds optional `Boolean logoOnPosters` (12th field). |
| `src/main/java/com/imin/iminapi/service/ai/ConceptStudioService.java` | Modify | Resolves org brand (failure-isolated), packs accent string into `accentColor`, stamps `brand_snapshot`, threads `logoOnPosters`. |
| `src/main/java/com/imin/iminapi/service/poster/BrandSnapshot.java` | Create | Record `{colors, logoUrl, logoOn}` + JSON (de)serialize helpers. |
| `src/main/java/com/imin/iminapi/service/poster/BrandLogoCompositor.java` | Create | Pure Java2D bottom-right composite with luminance scrim + per-org decoded-logo cache. |
| `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java` | Modify | Threads `BrandSnapshot` into `run(...)`; composite seam inside `accept()`; stamps `logo_composite_status`; second `writePng`. |
| `src/main/java/com/imin/iminapi/model/PosterGeneration.java` | Modify | Adds `brandSnapshot` (TEXT) field. |
| `src/main/java/com/imin/iminapi/model/PosterVariantEntity.java` | Modify | Adds `logoCompositeStatus` (VARCHAR(16)) field. |
| `docs/decisions/ADR-0002-brand-logo-composite.md` | Create | Records the logo-composite carve-out from ADR-0001's no-overlay decision. |
| `src/test/java/com/imin/iminapi/service/ai/ConceptStudioServiceTest.java` | Modify | Colours→packed string; empty→null; precedence; malformed→brandless; snapshot stamped. |
| `src/test/java/com/imin/iminapi/service/poster/BrandLogoCompositorTest.java` | Create | Golden-image: dims unchanged, corner differs, scrim present, fallback path. |
| `src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java` | Modify | Composite applied/skipped/failed; second writePng; final_url vs raw_url. |

---

# PR 1 — Brand data + contract (`feat/brand-book-data`)

**Deploy gate:** This PR is purely additive. When merged and deployed, generation behaves exactly as today (brand columns are empty/default). After deploy, the FE runs `api:sync` (it pulls the production OpenAPI). PR 2 must NOT be merged until this PR is live in production.

## Task 1.0 — Create the branch

- [ ] Create the feature branch off the current default branch:
  ```bash
  cd /Users/ivan/imin/imin-api && git checkout -b feat/brand-book-data
  ```
  Expected output:
  ```
  Switched to a new branch 'feat/brand-book-data'
  ```

## Task 1.1 — V38 migration

**Files:**
- Create: `src/main/resources/db/migration/V38__org_brand_book.sql`
- Test: `src/test/java/com/imin/iminapi/migration/V38BrandBookMigrationTest.java`

- [ ] Confirm V37 is still the high-water mark (never reuse a number):
  ```bash
  cd /Users/ivan/imin/imin-api && ls src/main/resources/db/migration/ | sort -V | tail -3
  ```
  Expected output (the last line must be `V37`; if a `V38` already exists, STOP and renumber):
  ```
  V35__drop_style_reference_analysis.sql
  V36__poster_creative_direction.sql
  V37__poster_validation_verdict.sql
  ```

- [ ] Write the failing migration test first. Create `src/test/java/com/imin/iminapi/migration/V38BrandBookMigrationTest.java`:
  ```java
  package com.imin.iminapi.migration;

  import com.imin.iminapi.model.Organization;
  import com.imin.iminapi.repository.OrganizationRepository;
  import jakarta.persistence.EntityManager;
  import jakarta.persistence.PersistenceContext;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.transaction.annotation.Transactional;

  import java.util.List;
  import java.util.UUID;

  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest
  @Transactional
  class V38BrandBookMigrationTest {

      @Autowired OrganizationRepository orgs;
      @PersistenceContext EntityManager em;

      @Test
      void v38_columns_apply_with_correct_defaults() {
          // An org saved with no brand fields touched must read back the SQL defaults:
          // empty accent list and logoOnPosters = true (the column default).
          Organization o = new Organization();
          o.setName("Defaults Org");
          o.setSlug("defaults-org-" + System.nanoTime());
          o.setContactEmail("a@b.com");
          o.setCountry("DE");
          Organization saved = orgs.saveAndFlush(o);
          orgs.flush();

          Organization reloaded = orgs.findById(saved.getId()).orElseThrow();
          assertThat(reloaded.getBrandName()).isNull();
          assertThat(reloaded.getBrandLogoUrl()).isNull();
          assertThat(reloaded.getBrandAccentColors()).isEqualTo(List.of());
          assertThat(reloaded.isBrandLogoOnPosters()).isTrue();
      }

      @Test
      void populated_accent_list_round_trips_through_StringListJsonConverter_in_order() {
          // The OrgBrandService unit test mocks the repository, so it never exercises the real
          // StringListJsonConverter ↔ TEXT-column round-trip. This is the only test that does it for a
          // NON-EMPTY ordered palette — exactly as V33's stripe_requirements_* lists are stored as
          // JSON-in-TEXT and read back via the same converter (mind the H2/PG JSON-as-TEXT note).
          Organization o = new Organization();
          o.setName("Populated Org");
          o.setSlug("populated-org-" + System.nanoTime());
          o.setContactEmail("a@b.com");
          o.setCountry("DE");
          o.setBrandName("Tortuga Collective");
          o.setBrandLogoUrl("https://cdn.example/orgs/x/brand/logo-ab12cd34.png");
          o.setBrandAccentColors(new java.util.ArrayList<>(List.of("#ec4899", "#f6c04a")));
          o.setBrandLogoOnPosters(false);
          Organization saved = orgs.saveAndFlush(o);

          // Evict so the read comes from the DB column through the converter, not the persistence cache.
          orgs.flush();
          em.clear();

          Organization reloaded = orgs.findById(saved.getId()).orElseThrow();
          // Order is priority (index 0 = primary) — must survive the JSON-array TEXT round-trip exactly.
          assertThat(reloaded.getBrandAccentColors()).containsExactly("#ec4899", "#f6c04a");
          assertThat(reloaded.getBrandName()).isEqualTo("Tortuga Collective");
          assertThat(reloaded.getBrandLogoUrl()).isEqualTo("https://cdn.example/orgs/x/brand/logo-ab12cd34.png");
          assertThat(reloaded.isBrandLogoOnPosters()).isFalse();

          // And the []-default still holds for a sibling row that never touched the column.
          Organization bare = new Organization();
          bare.setName("Bare Org");
          bare.setSlug("bare-org-" + System.nanoTime());
          bare.setContactEmail("c@d.com");
          bare.setCountry("DE");
          UUID bareId = orgs.saveAndFlush(bare).getId();
          em.clear();
          assertThat(orgs.findById(bareId).orElseThrow().getBrandAccentColors()).isEqualTo(List.of());
      }
  }
  ```

- [ ] Run it — expected FAIL (the getters don't exist yet, so it won't compile):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=V38BrandBookMigrationTest -q
  ```
  Expected output contains:
  ```
  COMPILATION ERROR
  ... cannot find symbol ... method getBrandName()
  BUILD FAILURE
  ```

- [ ] Write the migration. Create `src/main/resources/db/migration/V38__org_brand_book.sql`:
  ```sql
  -- Brand book: org-level brand identity consumed by AI poster generation.
  -- Columns on organizations (consistent with every prior org extension: slug V15, Stripe V18/V33).
  ALTER TABLE organizations ADD COLUMN brand_name VARCHAR(120);
  ALTER TABLE organizations ADD COLUMN brand_logo_url TEXT;
  -- TEXT (not JSONB) for cross-engine compatibility: PG and H2 both accept TEXT, and
  -- StringListJsonConverter (de)serializes the JSON array manually (same rationale as V33's
  -- stripe_requirements_* lists). Order is priority: index 0 = primary accent.
  ALTER TABLE organizations ADD COLUMN brand_accent_colors TEXT NOT NULL DEFAULT '[]';
  ALTER TABLE organizations ADD COLUMN brand_logo_on_posters BOOLEAN NOT NULL DEFAULT TRUE;

  -- Generation provenance: stamp the resolved brand onto the generation row at creation, so a
  -- corrective remix (and any audit) reads the SNAPSHOT, not live org state, and "why no logo?"
  -- is answerable. NULL when the generation was brandless.
  ALTER TABLE poster_generations ADD COLUMN brand_snapshot TEXT;

  -- Per-variant logo outcome, beside validation_verdict: NULL | 'APPLIED' | 'SKIPPED' | 'FAILED'.
  -- failure_reason stays reserved for real generation failures.
  ALTER TABLE poster_variants ADD COLUMN logo_composite_status VARCHAR(16);
  ```

- [ ] Add the entity fields. Edit `src/main/java/com/imin/iminapi/model/Organization.java`. After the `currency` field (line 46, before the `stripeAccountId` javadoc at line 48), insert:
  ```java
      // ----- Brand book (V38) -----

      @Column(name = "brand_name", length = 120)
      private String brandName;

      @Column(name = "brand_logo_url", columnDefinition = "TEXT")
      private String brandLogoUrl;

      /** Up to 3 ordered accent colours, lowercase hex (#rrggbb). Index 0 = primary ("AI leads with the first"). */
      @Convert(converter = StringListJsonConverter.class)
      @Column(name = "brand_accent_colors", nullable = false, columnDefinition = "TEXT")
      private List<String> brandAccentColors = new ArrayList<>();

      @Column(name = "brand_logo_on_posters", nullable = false)
      private boolean brandLogoOnPosters = true;
  ```
  (`@Convert`, `@Column`, `List`, `ArrayList` are already imported in this file — confirmed: `jakarta.persistence.*`, `java.util.ArrayList`, `java.util.List`.)

- [ ] Run the migration test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=V38BrandBookMigrationTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/resources/db/migration/V38__org_brand_book.sql src/main/java/com/imin/iminapi/model/Organization.java src/test/java/com/imin/iminapi/migration/V38BrandBookMigrationTest.java && git commit -m "$(cat <<'EOF'
Add V38 brand-book columns + Organization fields

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 1.2 — Brand DTOs

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/org/BrandBookDto.java`
- Create: `src/main/java/com/imin/iminapi/dto/org/BrandUpdateRequest.java`
- Create: `src/main/java/com/imin/iminapi/dto/org/LogoUploadResponse.java`

(No test for plain records — they are exercised by the service/controller tests below. House style: DTOs are records, no `@Operation`/`@Schema`.)

- [ ] Create `src/main/java/com/imin/iminapi/dto/org/BrandBookDto.java`:
  ```java
  package com.imin.iminapi.dto.org;

  import com.imin.iminapi.model.Organization;

  import java.util.List;

  /** GET /api/v1/org/brand and PUT response. logoUrl is null when no logo; accentColors is [] when none. */
  public record BrandBookDto(
          String brandName,
          String logoUrl,
          List<String> accentColors,
          boolean logoOnPosters
  ) {
      public static BrandBookDto from(Organization o) {
          return new BrandBookDto(
                  o.getBrandName(),
                  o.getBrandLogoUrl(),
                  o.getBrandAccentColors() == null ? List.of() : List.copyOf(o.getBrandAccentColors()),
                  o.isBrandLogoOnPosters());
      }
  }
  ```

- [ ] Create `src/main/java/com/imin/iminapi/dto/org/BrandUpdateRequest.java`:
  ```java
  package com.imin.iminapi.dto.org;

  import java.util.List;

  /**
   * PUT /api/v1/org/brand — full replace of the three scalar fields. All fields are required;
   * the FE always sends its full controlled state. accentColors == [] clears the palette.
   * No bean-validation annotations: the per-index hex/count rules are validated manually in
   * OrgBrandService so we can emit indexed field keys (accentColors[1]) the FE can highlight.
   */
  public record BrandUpdateRequest(
          String brandName,
          List<String> accentColors,
          Boolean logoOnPosters
  ) {}
  ```

- [ ] Create `src/main/java/com/imin/iminapi/dto/org/LogoUploadResponse.java`:
  ```java
  package com.imin.iminapi.dto.org;

  /** POST /api/v1/org/brand/logo response. */
  public record LogoUploadResponse(String logoUrl) {}
  ```

- [ ] Compile to confirm the records are well-formed:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw -q compile
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/dto/org/BrandBookDto.java src/main/java/com/imin/iminapi/dto/org/BrandUpdateRequest.java src/main/java/com/imin/iminapi/dto/org/LogoUploadResponse.java && git commit -m "$(cat <<'EOF'
Add brand-book DTOs (BrandBookDto, BrandUpdateRequest, LogoUploadResponse)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 1.3 — OrgBrandService (validation + GET/PUT)

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/org/OrgBrandService.java`
- Test: `src/test/java/com/imin/iminapi/service/org/OrgBrandServiceTest.java`

- [ ] Write the failing service test. Create `src/test/java/com/imin/iminapi/service/org/OrgBrandServiceTest.java`:
  ```java
  package com.imin.iminapi.service.org;

  import com.imin.iminapi.dto.org.BrandBookDto;
  import com.imin.iminapi.dto.org.BrandUpdateRequest;
  import com.imin.iminapi.model.Organization;
  import com.imin.iminapi.model.UserRole;
  import com.imin.iminapi.repository.OrganizationRepository;
  import com.imin.iminapi.security.ApiException;
  import com.imin.iminapi.security.AuthPrincipal;
  import com.imin.iminapi.security.ErrorCode;
  import org.junit.jupiter.api.Test;

  import java.util.List;
  import java.util.Optional;
  import java.util.UUID;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;
  import static org.assertj.core.api.InstanceOfAssertFactories.map;
  import static org.mockito.ArgumentMatchers.any;
  import static org.mockito.Mockito.*;

  class OrgBrandServiceTest {

      OrganizationRepository orgs = mock(OrganizationRepository.class);
      OrgBrandService sut = new OrgBrandService(orgs);

      private AuthPrincipal owner(UUID orgId) {
          return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
      }

      private Organization org(UUID id) {
          Organization o = new Organization();
          o.setId(id); o.setName("Org"); o.setContactEmail("a@b.com"); o.setCountry("DE");
          return o;
      }

      private void stub(Organization o) {
          when(orgs.findById(o.getId())).thenReturn(Optional.of(o));
          when(orgs.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
      }

      @Test
      void get_returns_current_brand() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          o.setBrandName("Tortuga Collective");
          o.setBrandAccentColors(new java.util.ArrayList<>(List.of("#ec4899")));
          o.setBrandLogoOnPosters(false);
          when(orgs.findById(id)).thenReturn(Optional.of(o));

          BrandBookDto dto = sut.get(owner(id));
          assertThat(dto.brandName()).isEqualTo("Tortuga Collective");
          assertThat(dto.accentColors()).containsExactly("#ec4899");
          assertThat(dto.logoOnPosters()).isFalse();
      }

      @Test
      void put_replaces_fields_and_normalizes_hex_to_lowercase() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          stub(o);

          BrandBookDto dto = sut.put(owner(id),
                  new BrandUpdateRequest("  Tortuga Collective  ",
                          List.of("#EC4899", "#F6C04A", "#A78BFA"), true));

          assertThat(dto.brandName()).isEqualTo("Tortuga Collective"); // trimmed
          assertThat(dto.accentColors()).containsExactly("#ec4899", "#f6c04a", "#a78bfa"); // lowercased, order kept
          assertThat(dto.logoOnPosters()).isTrue();
      }

      @Test
      void put_blank_name_becomes_null() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          stub(o);

          BrandBookDto dto = sut.put(owner(id), new BrandUpdateRequest("   ", List.of(), false));
          assertThat(dto.brandName()).isNull();
      }

      @Test
      void put_empty_list_clears_palette() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          o.setBrandAccentColors(new java.util.ArrayList<>(List.of("#ec4899")));
          stub(o);

          BrandBookDto dto = sut.put(owner(id), new BrandUpdateRequest(null, List.of(), true));
          assertThat(dto.accentColors()).isEmpty();
      }

      @Test
      void put_case_insensitive_dedupe_keeps_first_occurrence() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          stub(o);

          BrandBookDto dto = sut.put(owner(id),
                  new BrandUpdateRequest(null, List.of("#EC4899", "#ec4899", "#a78bfa"), true));
          assertThat(dto.accentColors()).containsExactly("#ec4899", "#a78bfa");
      }

      @Test
      void put_more_than_three_colours_throws_indexed_field_error() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          when(orgs.findById(id)).thenReturn(Optional.of(o));

          assertThatThrownBy(() -> sut.put(owner(id),
                  new BrandUpdateRequest(null,
                          List.of("#111111", "#222222", "#333333", "#444444"), true)))
                  .isInstanceOf(ApiException.class)
                  .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID)
                  .extracting("fields").asInstanceOf(map(String.class, String.class))
                  .containsKey("accentColors");
          verify(orgs, never()).save(any(Organization.class));
      }

      @Test
      void put_invalid_hex_throws_per_index_field_key() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          when(orgs.findById(id)).thenReturn(Optional.of(o));

          assertThatThrownBy(() -> sut.put(owner(id),
                  new BrandUpdateRequest(null, List.of("#ec4899", "ec4899", "#a78bfa"), true)))
                  .isInstanceOf(ApiException.class)
                  .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID)
                  .extracting("fields").asInstanceOf(map(String.class, String.class))
                  .containsKey("accentColors[1]");
          verify(orgs, never()).save(any(Organization.class));
      }

      @Test
      void put_rejects_three_digit_and_named_and_rgb() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          when(orgs.findById(id)).thenReturn(Optional.of(o));

          assertThatThrownBy(() -> sut.put(owner(id),
                  new BrandUpdateRequest(null, List.of("#fff"), true)))
                  .isInstanceOf(ApiException.class);
          assertThatThrownBy(() -> sut.put(owner(id),
                  new BrandUpdateRequest(null, List.of("red"), true)))
                  .isInstanceOf(ApiException.class);
          assertThatThrownBy(() -> sut.put(owner(id),
                  new BrandUpdateRequest(null, List.of("rgb(0,0,0)"), true)))
                  .isInstanceOf(ApiException.class);
      }

      @Test
      void put_null_logoOnPosters_defaults_true() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          stub(o);

          BrandBookDto dto = sut.put(owner(id), new BrandUpdateRequest(null, List.of(), null));
          assertThat(dto.logoOnPosters()).isTrue();
      }

      @Test
      void put_name_over_120_throws_field_error() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          when(orgs.findById(id)).thenReturn(Optional.of(o));

          String tooLong = "x".repeat(121);
          assertThatThrownBy(() -> sut.put(owner(id), new BrandUpdateRequest(tooLong, List.of(), true)))
                  .isInstanceOf(ApiException.class)
                  .extracting("fields").asInstanceOf(map(String.class, String.class))
                  .containsKey("brandName");
      }

      @Test
      void clearLogoUrl_nulls_the_logo_only() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          o.setBrandLogoUrl("https://cdn/logo.png");
          o.setBrandLogoOnPosters(true);
          stub(o);

          sut.clearLogoUrl(owner(id));
          assertThat(o.getBrandLogoUrl()).isNull();
          assertThat(o.isBrandLogoOnPosters()).isTrue(); // toggle untouched
      }

      @Test
      void setLogoUrl_persists_url() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          stub(o);

          sut.setLogoUrl(owner(id), "https://cdn/logo-ab12cd34.png");
          assertThat(o.getBrandLogoUrl()).isEqualTo("https://cdn/logo-ab12cd34.png");
      }
  }
  ```

- [ ] Run it — expected FAIL (class doesn't exist):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=OrgBrandServiceTest -q
  ```
  Expected output contains:
  ```
  COMPILATION ERROR
  ... package com.imin.iminapi.service.org ... OrgBrandService ... does not exist
  BUILD FAILURE
  ```

- [ ] Implement the service. Create `src/main/java/com/imin/iminapi/service/org/OrgBrandService.java`:
  ```java
  package com.imin.iminapi.service.org;

  import com.imin.iminapi.dto.org.BrandBookDto;
  import com.imin.iminapi.dto.org.BrandUpdateRequest;
  import com.imin.iminapi.model.Organization;
  import com.imin.iminapi.repository.OrganizationRepository;
  import com.imin.iminapi.security.ApiException;
  import com.imin.iminapi.security.AuthPrincipal;
  import com.imin.iminapi.security.ErrorCode;
  import org.springframework.http.HttpStatus;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;

  import java.util.ArrayList;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.regex.Pattern;

  /**
   * Brand-book reads/writes. Validation is hand-written (not bean validation) so we can emit
   * per-index field keys — {@code fields: {"accentColors[1]": "..."}} — that the FE highlights on
   * the offending swatch. Hex is lowercase-normalized, deduped case-insensitively (a duplicate
   * wastes a slot), capped at 3; brand_name is trimmed, max 120, blank → NULL.
   */
  @Service
  public class OrgBrandService {

      private static final int MAX_COLORS = 3;
      private static final int MAX_NAME_LEN = 120;
      private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

      private final OrganizationRepository orgs;

      public OrgBrandService(OrganizationRepository orgs) {
          this.orgs = orgs;
      }

      @Transactional(readOnly = true)
      public BrandBookDto get(AuthPrincipal p) {
          return BrandBookDto.from(load(p));
      }

      @Transactional
      public BrandBookDto put(AuthPrincipal p, BrandUpdateRequest body) {
          Organization o = load(p);
          o.setBrandName(normalizeName(body.brandName()));
          o.setBrandAccentColors(validateColors(body.accentColors()));
          o.setBrandLogoOnPosters(body.logoOnPosters() == null ? true : body.logoOnPosters());
          // updated_at is owned by Organization's @PreUpdate hook (onUpdate → Times.nowMicros()),
          // which fires on every flush — do NOT set it here (it would be overwritten anyway, and
          // Instant.now() is not micro-truncated like the rest of the entity).
          return BrandBookDto.from(orgs.save(o));
      }

      @Transactional
      public void setLogoUrl(AuthPrincipal p, String url) {
          Organization o = load(p);
          o.setBrandLogoUrl(url);
          orgs.save(o); // @PreUpdate stamps updated_at on flush
      }

      @Transactional
      public void clearLogoUrl(AuthPrincipal p) {
          Organization o = load(p);
          o.setBrandLogoUrl(null); // toggle (brandLogoOnPosters) intentionally left untouched
          orgs.save(o); // @PreUpdate stamps updated_at on flush
      }

      private Organization load(AuthPrincipal p) {
          return orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
      }

      private static String normalizeName(String raw) {
          if (raw == null) return null;
          String trimmed = raw.trim();
          if (trimmed.isEmpty()) return null;
          if (trimmed.length() > MAX_NAME_LEN) {
              throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                      "Validation failed", Map.of("brandName", "must be ≤ " + MAX_NAME_LEN + " characters"));
          }
          return trimmed;
      }

      private static List<String> validateColors(List<String> raw) {
          if (raw == null || raw.isEmpty()) return new ArrayList<>();
          if (raw.size() > MAX_COLORS) {
              throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                      "Validation failed", Map.of("accentColors", "at most " + MAX_COLORS + " colours"));
          }
          // LinkedHashMap keyed on the lowercase hex preserves first-seen order and dedupes case-insensitively.
          Map<String, String> seen = new LinkedHashMap<>();
          for (int i = 0; i < raw.size(); i++) {
              String c = raw.get(i);
              if (c == null || !HEX.matcher(c).matches()) {
                  throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                          "Validation failed",
                          Map.of("accentColors[" + i + "]", "must be a 6-digit hex colour like #ec4899"));
              }
              seen.putIfAbsent(c.toLowerCase(), c.toLowerCase());
          }
          return new ArrayList<>(seen.values());
      }
  }
  ```

- [ ] Run the service test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=OrgBrandServiceTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/service/org/OrgBrandService.java src/test/java/com/imin/iminapi/service/org/OrgBrandServiceTest.java && git commit -m "$(cat <<'EOF'
Add OrgBrandService with hand-written hex/count validation + per-index field keys

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 1.4 — OrgMediaService (logo upload)

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/org/OrgMediaService.java`
- Test: `src/test/java/com/imin/iminapi/service/org/OrgMediaServiceTest.java`

This mirrors `MediaUploadService` (`MediaUploadService.java:57-81`): precompute the URL, persist it (via `OrgBrandService.setLogoUrl`), put to storage, then best-effort delete the old object. PNG-only magic bytes via the same `0x89 0x50 0x4E 0x47` check; ≤2 MB; min short side 128 px; aspect between 1:4 and 4:1, decoded with `ImageIO`. Key `orgs/{orgId}/brand/logo-{sha256prefix8}.png`.

- [ ] Write the failing service test. Create `src/test/java/com/imin/iminapi/service/org/OrgMediaServiceTest.java`:
  ```java
  package com.imin.iminapi.service.org;

  import com.imin.iminapi.dto.org.LogoUploadResponse;
  import com.imin.iminapi.model.Organization;
  import com.imin.iminapi.model.UserRole;
  import com.imin.iminapi.repository.OrganizationRepository;
  import com.imin.iminapi.security.ApiException;
  import com.imin.iminapi.security.AuthPrincipal;
  import com.imin.iminapi.security.ErrorCode;
  import com.imin.iminapi.storage.InMemoryMediaStorage;
  import org.junit.jupiter.api.Test;

  import javax.imageio.ImageIO;
  import java.awt.image.BufferedImage;
  import java.io.ByteArrayOutputStream;
  import java.util.Optional;
  import java.util.UUID;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;
  import static org.mockito.ArgumentMatchers.any;
  import static org.mockito.Mockito.*;

  class OrgMediaServiceTest {

      OrganizationRepository orgs = mock(OrganizationRepository.class);
      InMemoryMediaStorage storage = new InMemoryMediaStorage("https://media.test/");
      OrgBrandService brandService = mock(OrgBrandService.class);
      OrgMediaService sut = new OrgMediaService(orgs, storage, brandService);

      private AuthPrincipal owner(UUID orgId) {
          return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
      }

      private Organization org(UUID id) {
          Organization o = new Organization();
          o.setId(id); o.setName("Org"); o.setContactEmail("a@b.com"); o.setCountry("DE");
          return o;
      }

      /** A real PNG of the given dimensions (so ImageIO decode in the service succeeds). */
      private static byte[] png(int w, int h) {
          try {
              BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              ImageIO.write(img, "PNG", out);
              return out.toByteArray();
          } catch (Exception e) {
              throw new RuntimeException(e);
          }
      }

      @Test
      void valid_square_png_uploads_and_sets_url() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          when(orgs.findById(id)).thenReturn(Optional.of(o));

          LogoUploadResponse r = sut.uploadLogo(owner(id), png(256, 256), "image/png", "logo.png");

          assertThat(r.logoUrl())
                  .startsWith("https://media.test/orgs/" + id + "/brand/logo-")
                  .endsWith(".png")
                  .matches("https://media\\.test/orgs/" + id + "/brand/logo-[0-9a-f]{16}\\.png");
          verify(brandService).setLogoUrl(any(AuthPrincipal.class), eq(r.logoUrl()));
          assertThat(storage.blobs()).hasSize(1);
      }

      @Test
      void non_png_content_type_rejected() {
          UUID id = UUID.randomUUID();
          when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
          assertThatThrownBy(() -> sut.uploadLogo(owner(id), png(256, 256), "image/jpeg", "logo.jpg"))
                  .isInstanceOf(ApiException.class)
                  .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
      }

      @Test
      void png_declared_but_not_png_magic_rejected() {
          UUID id = UUID.randomUUID();
          when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
          byte[] notPng = new byte[256];
          assertThatThrownBy(() -> sut.uploadLogo(owner(id), notPng, "image/png", "logo.png"))
                  .isInstanceOf(ApiException.class)
                  .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
      }

      @Test
      void over_2mb_rejected() {
          UUID id = UUID.randomUUID();
          when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
          // valid PNG header but oversize body (size is checked before decode)
          byte[] big = png(256, 256);
          byte[] padded = new byte[2 * 1024 * 1024 + 1];
          System.arraycopy(big, 0, padded, 0, big.length);
          assertThatThrownBy(() -> sut.uploadLogo(owner(id), padded, "image/png", "logo.png"))
                  .isInstanceOf(ApiException.class)
                  .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
      }

      @Test
      void short_side_under_128_rejected() {
          UUID id = UUID.randomUUID();
          when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
          assertThatThrownBy(() -> sut.uploadLogo(owner(id), png(127, 256), "image/png", "logo.png"))
                  .isInstanceOf(ApiException.class)
                  .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
      }

      @Test
      void extreme_aspect_ratio_rejected() {
          UUID id = UUID.randomUUID();
          when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
          // 128 x 640 = 1:5, outside the 1:4..4:1 band
          assertThatThrownBy(() -> sut.uploadLogo(owner(id), png(128, 640), "image/png", "logo.png"))
                  .isInstanceOf(ApiException.class)
                  .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
      }

      @Test
      void four_to_one_aspect_is_allowed() {
          UUID id = UUID.randomUUID();
          when(orgs.findById(id)).thenReturn(Optional.of(org(id)));
          // 512 x 128 = exactly 4:1 — allowed
          LogoUploadResponse r = sut.uploadLogo(owner(id), png(512, 128), "image/png", "logo.png");
          assertThat(r.logoUrl()).endsWith(".png");
      }

      @Test
      void reupload_deletes_old_object_after_new_put() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          when(orgs.findById(id)).thenReturn(Optional.of(o));

          LogoUploadResponse r1 = sut.uploadLogo(owner(id), png(256, 256), "image/png", "a.png");
          // simulate the URL persisted (the real OrgBrandService.setLogoUrl would have)
          o.setBrandLogoUrl(r1.logoUrl());

          byte[] second = png(300, 300); // different bytes → different hash → different key
          LogoUploadResponse r2 = sut.uploadLogo(owner(id), second, "image/png", "b.png");

          assertThat(r1.logoUrl()).isNotEqualTo(r2.logoUrl());
          assertThat(storage.blobs()).hasSize(1);
          assertThat(storage.blobs().keySet()).containsExactly(storage.keyFor(r2.logoUrl()));
      }

      @Test
      void deleteLogo_removes_blob_and_clears_url() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          String key = "orgs/" + id + "/brand/logo-aabbccdd.png";
          o.setBrandLogoUrl("https://media.test/" + key);
          storage.put(key, new byte[1], "image/png");
          when(orgs.findById(id)).thenReturn(Optional.of(o));

          sut.deleteLogo(owner(id));

          verify(brandService).clearLogoUrl(any(AuthPrincipal.class));
          assertThat(storage.blobs()).isEmpty();
      }

      @Test
      void other_org_never_leaks_returns_NOT_FOUND_when_org_absent() {
          UUID id = UUID.randomUUID();
          when(orgs.findById(id)).thenReturn(Optional.empty());
          assertThatThrownBy(() -> sut.uploadLogo(owner(id), png(256, 256), "image/png", "logo.png"))
                  .isInstanceOf(ApiException.class)
                  .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
      }
  }
  ```

- [ ] Run it — expected FAIL (class doesn't exist):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=OrgMediaServiceTest -q
  ```
  Expected output contains:
  ```
  COMPILATION ERROR
  ... OrgMediaService ... does not exist
  BUILD FAILURE
  ```

- [ ] Implement the service. Create `src/main/java/com/imin/iminapi/service/org/OrgMediaService.java`:
  ```java
  package com.imin.iminapi.service.org;

  import com.imin.iminapi.dto.org.LogoUploadResponse;
  import com.imin.iminapi.model.Organization;
  import com.imin.iminapi.repository.OrganizationRepository;
  import com.imin.iminapi.security.ApiException;
  import com.imin.iminapi.security.AuthPrincipal;
  import com.imin.iminapi.security.ErrorCode;
  import com.imin.iminapi.storage.MediaStorage;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.http.HttpStatus;
  import org.springframework.stereotype.Service;

  import javax.imageio.ImageIO;
  import java.awt.image.BufferedImage;
  import java.io.ByteArrayInputStream;
  import java.io.IOException;
  import java.security.MessageDigest;
  import java.security.NoSuchAlgorithmException;
  import java.util.HexFormat;
  import java.util.Map;
  import java.util.UUID;

  /**
   * Brand-logo upload, parallel to {@link com.imin.iminapi.service.event.MediaUploadService}: same R2
   * {@link MediaStorage} seam and URL-precompute-then-put retry-safe pattern. PNG-only (Phase 1),
   * ≤2 MB, min short side 128 px, aspect between 1:4 and 4:1. Key is content-addressed:
   * {@code orgs/{orgId}/brand/logo-{sha256prefix8}.png}. The old object is deleted best-effort only
   * after the new put succeeds (orphans accepted as known debt and logged).
   */
  @Service
  public class OrgMediaService {

      private static final Logger log = LoggerFactory.getLogger(OrgMediaService.class);
      private static final long MAX_BYTES = 2L * 1024 * 1024;
      private static final int MIN_SHORT_SIDE = 128;
      private static final double MIN_ASPECT = 0.25; // 1:4
      private static final double MAX_ASPECT = 4.0;  // 4:1

      private final OrganizationRepository orgs;
      private final MediaStorage storage;
      private final OrgBrandService brandService;

      public OrgMediaService(OrganizationRepository orgs, MediaStorage storage, OrgBrandService brandService) {
          this.orgs = orgs;
          this.storage = storage;
          this.brandService = brandService;
      }

      public LogoUploadResponse uploadLogo(AuthPrincipal p, byte[] bytes, String contentType, String originalFilename) {
          Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
          validate(bytes, contentType);

          String hash = contentHash(bytes);
          String key = "orgs/" + o.getId() + "/brand/logo-" + hash + ".png";
          String url = storage.urlFor(key);
          String oldUrl = o.getBrandLogoUrl();

          // Persist the URL first (retry-safe: if the put below throws, a retry re-puts to the same
          // deterministic key). setLogoUrl re-loads and saves the org in its own @Transactional.
          brandService.setLogoUrl(p, url);
          storage.put(key, bytes, contentType);

          // Best-effort cleanup of the previously stored object — only after the new put succeeded.
          if (oldUrl != null && !oldUrl.equals(url)) {
              String oldKey = storage.keyFor(oldUrl);
              if (oldKey != null && !oldKey.equals(key)) {
                  try { storage.delete(oldKey); }
                  catch (Exception e) { log.warn("Orphaned old brand logo {}: {}", oldKey, e.getMessage()); }
              }
          }
          return new LogoUploadResponse(url);
      }

      public void deleteLogo(AuthPrincipal p) {
          Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
          String url = o.getBrandLogoUrl();
          brandService.clearLogoUrl(p);
          if (url != null) {
              String key = storage.keyFor(url);
              if (key != null) {
                  try { storage.delete(key); }
                  catch (Exception e) { log.warn("Best-effort brand-logo delete failed for {}: {}", key, e.getMessage()); }
              }
          }
      }

      private static void validate(byte[] bytes, String contentType) {
          if (bytes.length > MAX_BYTES) throw fieldErr("must be ≤ 2 MB");
          if (!"image/png".equals(contentType)) throw fieldErr("PNG only for poster logos (SVG support later)");
          if (bytes.length < 8 || !isPngMagic(bytes)) throw fieldErr("content does not match declared type");

          BufferedImage img;
          try {
              img = ImageIO.read(new ByteArrayInputStream(bytes));
          } catch (IOException e) {
              throw fieldErr("could not decode PNG");
          }
          if (img == null) throw fieldErr("could not decode PNG");

          int w = img.getWidth();
          int h = img.getHeight();
          if (Math.min(w, h) < MIN_SHORT_SIDE) throw fieldErr("must be at least 128px on the short side");
          double aspect = (double) w / (double) h;
          if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) {
              throw fieldErr("aspect ratio must be between 1:4 and 4:1");
          }
      }

      private static boolean isPngMagic(byte[] b) {
          return (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
                  && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47;
      }

      private static ApiException fieldErr(String msg) {
          return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Invalid file", Map.of("file", msg));
      }

      private static String contentHash(byte[] bytes) {
          try {
              MessageDigest md = MessageDigest.getInstance("SHA-256");
              byte[] digest = md.digest(bytes);
              byte[] prefix = new byte[8];
              System.arraycopy(digest, 0, prefix, 0, 8);
              return HexFormat.of().formatHex(prefix);
          } catch (NoSuchAlgorithmException ex) {
              throw new IllegalStateException(ex);
          }
      }
  }
  ```

- [ ] Run the service test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=OrgMediaServiceTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/service/org/OrgMediaService.java src/test/java/com/imin/iminapi/service/org/OrgMediaServiceTest.java && git commit -m "$(cat <<'EOF'
Add OrgMediaService.uploadLogo (PNG-only, dimension/aspect validation, R2 seam)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 1.5 — OrgBrandController

**Files:**
- Create: `src/main/java/com/imin/iminapi/controller/org/OrgBrandController.java`
- Test: `src/test/java/com/imin/iminapi/controller/org/OrgBrandControllerTest.java`

Org-scoped exactly like `OrgController` (`@CurrentUser AuthPrincipal p`, `@RequestMapping("/api/v1/org")`). No `SecurityConfig` change needed — `/api/v1/**` already requires authentication (`SecurityConfig.java:103`), and `/api/v1/org/**` is not in any `permitAll` matcher. Multipart part name `file` (matches `EventMediaController.java:29`).

- [ ] Write the failing controller test. Create `src/test/java/com/imin/iminapi/controller/org/OrgBrandControllerTest.java`:
  ```java
  package com.imin.iminapi.controller.org;

  import com.fasterxml.jackson.databind.ObjectMapper;
  import com.imin.iminapi.config.TestRateLimitConfig;
  import com.imin.iminapi.dto.org.BrandBookDto;
  import com.imin.iminapi.dto.org.LogoUploadResponse;
  import com.imin.iminapi.model.UserRole;
  import com.imin.iminapi.security.ApiException;
  import com.imin.iminapi.security.AuthPrincipal;
  import com.imin.iminapi.security.ErrorCode;
  import com.imin.iminapi.service.org.OrgBrandService;
  import com.imin.iminapi.service.org.OrgMediaService;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.http.HttpStatus;
  import org.springframework.http.MediaType;
  import org.springframework.mock.web.MockMultipartFile;
  import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
  import org.springframework.security.core.authority.SimpleGrantedAuthority;
  import org.springframework.security.core.context.SecurityContext;
  import org.springframework.security.core.context.SecurityContextHolder;
  import org.springframework.security.test.context.support.WithSecurityContext;
  import org.springframework.security.test.context.support.WithSecurityContextFactory;
  import org.springframework.test.context.bean.override.mockito.MockitoBean;
  import org.springframework.test.web.servlet.MockMvc;

  import java.lang.annotation.Retention;
  import java.lang.annotation.RetentionPolicy;
  import java.util.List;
  import java.util.Map;
  import java.util.UUID;

  import static org.mockito.ArgumentMatchers.any;
  import static org.mockito.Mockito.verify;
  import static org.mockito.Mockito.when;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(TestRateLimitConfig.class)
  class OrgBrandControllerTest {

      @Autowired MockMvc mvc;
      final ObjectMapper om = new ObjectMapper();
      @MockitoBean OrgBrandService brandService;
      @MockitoBean OrgMediaService mediaService;

      static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000010");
      static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000011");

      @Retention(RetentionPolicy.RUNTIME)
      @WithSecurityContext(factory = StubAuthFactory.class)
      public @interface WithStubUser {}

      public static class StubAuthFactory implements WithSecurityContextFactory<WithStubUser> {
          @Override
          public SecurityContext createSecurityContext(WithStubUser annotation) {
              AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
              var auth = new UsernamePasswordAuthenticationToken(
                      p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
              var ctx = SecurityContextHolder.createEmptyContext();
              ctx.setAuthentication(auth);
              return ctx;
          }
      }

      @Test
      void get_without_token_returns_AUTH_MISSING() throws Exception {
          mvc.perform(get("/api/v1/org/brand"))
                  .andExpect(status().isUnauthorized())
                  .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
      }

      @Test
      @WithStubUser
      void get_returns_brand() throws Exception {
          when(brandService.get(any(AuthPrincipal.class))).thenReturn(
                  new BrandBookDto("Tortuga Collective", "https://cdn/logo.png",
                          List.of("#ec4899", "#f6c04a"), true));
          mvc.perform(get("/api/v1/org/brand"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.brandName").value("Tortuga Collective"))
                  .andExpect(jsonPath("$.accentColors[0]").value("#ec4899"))
                  .andExpect(jsonPath("$.logoOnPosters").value(true));
      }

      @Test
      @WithStubUser
      void put_returns_updated_brand() throws Exception {
          when(brandService.put(any(AuthPrincipal.class), any())).thenReturn(
                  new BrandBookDto("Tortuga Collective", null, List.of("#ec4899"), false));
          mvc.perform(put("/api/v1/org/brand")
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(om.writeValueAsString(Map.of(
                                  "brandName", "Tortuga Collective",
                                  "accentColors", List.of("#ec4899"),
                                  "logoOnPosters", false))))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.logoOnPosters").value(false));
      }

      @Test
      @WithStubUser
      void put_invalid_hex_surfaces_per_index_field_key() throws Exception {
          when(brandService.put(any(AuthPrincipal.class), any())).thenThrow(
                  new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                          Map.of("accentColors[1]", "must be a 6-digit hex colour like #ec4899")));
          mvc.perform(put("/api/v1/org/brand")
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(om.writeValueAsString(Map.of(
                                  "accentColors", List.of("#ec4899", "nope", "#a78bfa")))))
                  .andExpect(status().isBadRequest())
                  .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                  .andExpect(jsonPath("$.error.fields['accentColors[1]']").exists());
      }

      @Test
      @WithStubUser
      void post_logo_multipart_returns_url() throws Exception {
          when(mediaService.uploadLogo(any(AuthPrincipal.class), any(), any(), any()))
                  .thenReturn(new LogoUploadResponse("https://cdn/orgs/x/brand/logo-aabbccdd.png"));
          MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3});
          mvc.perform(multipart("/api/v1/org/brand/logo").file(file))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.logoUrl").value("https://cdn/orgs/x/brand/logo-aabbccdd.png"));
      }

      @Test
      @WithStubUser
      void delete_logo_returns_204() throws Exception {
          mvc.perform(delete("/api/v1/org/brand/logo"))
                  .andExpect(status().isNoContent());
          verify(mediaService).deleteLogo(any(AuthPrincipal.class));
      }
  }
  ```

- [ ] Run it — expected FAIL (controller doesn't exist):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=OrgBrandControllerTest -q
  ```
  Expected output contains:
  ```
  COMPILATION ERROR
  ... OrgBrandController ... 
  ```
  (or, if it compiles, the routed tests 404/error — either way BUILD FAILURE until the controller exists).

- [ ] Implement the controller. Create `src/main/java/com/imin/iminapi/controller/org/OrgBrandController.java`:
  ```java
  package com.imin.iminapi.controller.org;

  import com.imin.iminapi.dto.org.BrandBookDto;
  import com.imin.iminapi.dto.org.BrandUpdateRequest;
  import com.imin.iminapi.dto.org.LogoUploadResponse;
  import com.imin.iminapi.security.AuthPrincipal;
  import com.imin.iminapi.security.CurrentUser;
  import com.imin.iminapi.service.org.OrgBrandService;
  import com.imin.iminapi.service.org.OrgMediaService;
  import org.springframework.http.HttpStatus;
  import org.springframework.web.bind.annotation.*;
  import org.springframework.web.multipart.MultipartFile;

  import java.io.IOException;

  @RestController
  @RequestMapping("/api/v1/org/brand")
  public class OrgBrandController {

      private final OrgBrandService brandService;
      private final OrgMediaService mediaService;

      public OrgBrandController(OrgBrandService brandService, OrgMediaService mediaService) {
          this.brandService = brandService;
          this.mediaService = mediaService;
      }

      @GetMapping
      public BrandBookDto get(@CurrentUser AuthPrincipal p) {
          return brandService.get(p);
      }

      @PutMapping
      public BrandBookDto put(@CurrentUser AuthPrincipal p, @RequestBody BrandUpdateRequest body) {
          return brandService.put(p, body);
      }

      @PostMapping(path = "/logo", consumes = "multipart/form-data")
      public LogoUploadResponse uploadLogo(@CurrentUser AuthPrincipal p,
                                           @RequestPart("file") MultipartFile file) throws IOException {
          return mediaService.uploadLogo(p, file.getBytes(),
                  file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                  file.getOriginalFilename() == null ? "logo.png" : file.getOriginalFilename());
      }

      @DeleteMapping("/logo")
      @ResponseStatus(HttpStatus.NO_CONTENT)
      public void deleteLogo(@CurrentUser AuthPrincipal p) {
          mediaService.deleteLogo(p);
      }
  }
  ```

- [ ] Run the controller test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=OrgBrandControllerTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/controller/org/OrgBrandController.java src/test/java/com/imin/iminapi/controller/org/OrgBrandControllerTest.java && git commit -m "$(cat <<'EOF'
Add OrgBrandController (GET/PUT brand, POST/DELETE logo), org-scoped via @CurrentUser

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 1.6 — MaxUploadSizeExceededException → 413 handler

**Files:**
- Modify: `src/main/java/com/imin/iminapi/security/GlobalExceptionHandler.java` (add a new `@ExceptionHandler`; insert after the `AccessDeniedException` handler ending at line 64)
- Test: `src/test/java/com/imin/iminapi/security/GlobalExceptionHandlerUploadSizeTest.java`

Today an oversize multipart body falls through to the `Throwable` handler (`GlobalExceptionHandler.java:91-96`) → 500 INTERNAL. The new handler maps `MaxUploadSizeExceededException` → 413 with the `ApiError` envelope.

> **Scope clarification (the 413 vs the 2 MB logo cap):** The 2 MB logo limit is enforced **in-service** by `OrgMediaService.validate` and surfaces as **400 `FIELD_INVALID`** (`must be ≤ 2 MB`), *not* 413. `MaxUploadSizeExceededException` is only thrown by the container when a body exceeds the **global** `spring.servlet.multipart.max-file-size` / `max-request-size` (`application.yaml:27-28` = **60 MB**). So the 413 path is real but only triggers for bodies >60 MB — that is the case which previously fell through to `handleAny` → 500 and is what this handler fixes. A logo between 2 MB and 60 MB is accepted by the container and rejected as a 400 inside the service. A tighter container-level guard scoped to the logo endpoint would need a per-endpoint multipart limit and is out of scope.

- [ ] Write the failing handler test. Create `src/test/java/com/imin/iminapi/security/GlobalExceptionHandlerUploadSizeTest.java`:
  ```java
  package com.imin.iminapi.security;

  import org.junit.jupiter.api.Test;
  import org.springframework.http.HttpStatus;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.multipart.MaxUploadSizeExceededException;

  import static org.assertj.core.api.Assertions.assertThat;

  class GlobalExceptionHandlerUploadSizeTest {

      GlobalExceptionHandler handler = new GlobalExceptionHandler();

      @Test
      void max_upload_size_maps_to_413_with_envelope() {
          ResponseEntity<ApiError> resp =
                  handler.handleMaxUpload(new MaxUploadSizeExceededException(2L * 1024 * 1024));

          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
          assertThat(resp.getBody()).isNotNull();
          assertThat(resp.getBody().error().code()).isEqualTo(ErrorCode.FIELD_INVALID.name());
          assertThat(resp.getBody().error().fields()).containsKey("file");
      }
  }
  ```
  (This test calls `handleMaxUpload` directly. It must be package-visible — the existing handlers in `GlobalExceptionHandler` are package-private methods, so a same-package test can call it.)

- [ ] Run it — expected FAIL (method doesn't exist):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=GlobalExceptionHandlerUploadSizeTest -q
  ```
  Expected output contains:
  ```
  COMPILATION ERROR
  ... cannot find symbol ... method handleMaxUpload(...)
  BUILD FAILURE
  ```

- [ ] Add the import. Edit `src/main/java/com/imin/iminapi/security/GlobalExceptionHandler.java`. After the existing import on line 15 (`import org.springframework.web.servlet.NoHandlerFoundException;`) add:
  ```java
  import org.springframework.web.multipart.MaxUploadSizeExceededException;
  ```

- [ ] Add the handler method. In the same file, immediately after the `AccessDeniedException` handler (the `handleDenied` method that ends with its closing `}` on line 64), insert:
  ```java

      @ExceptionHandler(MaxUploadSizeExceededException.class)
      ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
          // Without this handler an oversize multipart body falls through to handleAny → 500 INTERNAL.
          // Map it to 413 with the standard envelope so the FE can show a clean "file too large" error.
          return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                  .body(ApiError.of(ErrorCode.FIELD_INVALID, "Uploaded file is too large",
                          java.util.Map.of("file", "exceeds the maximum allowed size")));
      }
  ```

- [ ] Run the handler test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=GlobalExceptionHandlerUploadSizeTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/security/GlobalExceptionHandler.java src/test/java/com/imin/iminapi/security/GlobalExceptionHandlerUploadSizeTest.java && git commit -m "$(cat <<'EOF'
Map MaxUploadSizeExceededException to 413 with ApiError envelope

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 1.7 — Final verification + merge-readiness (PR 1)

**Files:** none (verification only)

- [ ] Run the full new-surface test suite together — expected all PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest='OrgBrandServiceTest,OrgMediaServiceTest,OrgBrandControllerTest,V38BrandBookMigrationTest,GlobalExceptionHandlerUploadSizeTest' -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Run the full build + test suite to confirm nothing regressed (the V38 migration runs against every `@SpringBootTest`):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw clean test
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```
  If any pre-existing test fails, investigate with superpowers:systematic-debugging before proceeding — do NOT merge on a red suite.

- [ ] Sanity-check the OpenAPI surface includes the new endpoints (the FE will `api:sync` this after deploy). Boot the app and curl the docs (requires `MEDIA_ENABLED=false` for a no-R2 local boot):
  ```bash
  cd /Users/ivan/imin/imin-api && MEDIA_ENABLED=false ./mvnw spring-boot:run > /tmp/brand-boot.log 2>&1 &
  ```
  Then, once `Started IminApiApplication` appears in `/tmp/brand-boot.log`:
  ```bash
  curl -s http://localhost:8085/v3/api-docs.yaml | grep -c "/api/v1/org/brand"
  ```
  Expected output (3 path entries: `/org/brand`, `/org/brand/logo`, and they appear under paths):
  ```
  3
  ```
  Then stop the app:
  ```bash
  pkill -f spring-boot:run
  ```

- [ ] Confirm the branch is ready (clean tree, all commits present):
  ```bash
  cd /Users/ivan/imin/imin-api && git status --short && git log --oneline feat/brand-book-data ^master | head
  ```
  Expected: empty `git status` (clean), and the log lists the 6 commits from Tasks 1.1–1.6.

- [ ] **STOP. PR 1 is merge-ready.** Hand off for review/merge/deploy. Do NOT start PR 2 until PR 1 is deployed to production (`imin-api-production.up.railway.app`). The deploy gate is mandatory: the FE `api:sync` and PR 2's `brand_snapshot` reads depend on the V38 columns being live.

---

# PR 2 — Generation integration (`feat/brand-book-generation`)

**Precondition (deploy gate):** PR 1 is merged AND deployed to production. The V38 columns must exist in the live database before this PR's `brand_snapshot` stamping and brand lookups run.

## Task 2.0 — Create the branch

- [ ] Create the branch off the latest default branch (which now includes PR 1):
  ```bash
  cd /Users/ivan/imin/imin-api && git checkout master && git pull && git checkout -b feat/brand-book-generation
  ```
  Expected output ends with:
  ```
  Switched to a new branch 'feat/brand-book-generation'
  ```

## Task 2.1 — Entity fields: brand_snapshot + logo_composite_status

**Files:**
- Modify: `src/main/java/com/imin/iminapi/model/PosterGeneration.java` (add `brandSnapshot` field after `creativeSeed`, line 42)
- Modify: `src/main/java/com/imin/iminapi/model/PosterVariantEntity.java` (add `logoCompositeStatus` field after `validationAttemptsJson`, line 66)
- Test: `src/test/java/com/imin/iminapi/model/BrandColumnsPersistenceTest.java`

The V38 migration (PR 1) already created both DB columns. This task only adds the JPA mappings.

- [ ] Write the failing persistence test. Create `src/test/java/com/imin/iminapi/model/BrandColumnsPersistenceTest.java`:
  ```java
  package com.imin.iminapi.model;

  import com.imin.iminapi.repository.PosterGenerationRepository;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.transaction.annotation.Transactional;

  import java.util.UUID;

  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest
  @Transactional
  class BrandColumnsPersistenceTest {

      @Autowired PosterGenerationRepository repo;

      @Test
      void brand_snapshot_and_logo_composite_status_round_trip() {
          PosterGeneration g = new PosterGeneration();
          g.setGeneratedEventId(UUID.randomUUID());
          g.setStatus(PosterGenerationStatus.PENDING);
          g.setBrandSnapshot("{\"colors\":[\"#ec4899\"],\"logoUrl\":\"https://cdn/l.png\",\"logoOn\":true}");

          PosterVariantEntity v = new PosterVariantEntity();
          v.setPosterGeneration(g);
          v.setVariantStyle("people");
          v.setIdeogramPrompt("prompt");
          v.setStatus(PosterVariantStatus.COMPLETE);
          v.setLogoCompositeStatus("APPLIED");
          g.getVariants().add(v);

          PosterGeneration saved = repo.saveAndFlush(g);
          repo.flush();

          PosterGeneration reloaded = repo.findById(saved.getId()).orElseThrow();
          assertThat(reloaded.getBrandSnapshot()).contains("#ec4899");
          assertThat(reloaded.getVariants().get(0).getLogoCompositeStatus()).isEqualTo("APPLIED");
      }
  }
  ```

- [ ] Run it — expected FAIL (no such getters):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=BrandColumnsPersistenceTest -q
  ```
  Expected output contains:
  ```
  COMPILATION ERROR
  ... cannot find symbol ... method getBrandSnapshot()
  BUILD FAILURE
  ```

- [ ] Add the `brandSnapshot` field. Edit `src/main/java/com/imin/iminapi/model/PosterGeneration.java`. After the `creativeSeed` field block (ends line 42, before `created_at` at line 44), insert:
  ```java

      /**
       * Resolved brand stamped at creation: JSON {colors, logoUrl, logoOn} or NULL when brandless.
       * The corrective remix path re-reads THIS snapshot, not live org state, so a poster remixed
       * after a rebrand doesn't silently mix old prompt colours with a new logo.
       */
      @Column(name = "brand_snapshot", columnDefinition = "TEXT")
      private String brandSnapshot;
  ```

- [ ] Add the `logoCompositeStatus` field. Edit `src/main/java/com/imin/iminapi/model/PosterVariantEntity.java`. After the `validationAttemptsJson` field (ends line 66, before the class closing brace line 67), insert:
  ```java

      /** Logo composite outcome, beside validation_verdict: NULL | 'APPLIED' | 'SKIPPED' | 'FAILED'. */
      @Column(name = "logo_composite_status", length = 16)
      private String logoCompositeStatus;
  ```

- [ ] Run the persistence test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=BrandColumnsPersistenceTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/model/PosterGeneration.java src/main/java/com/imin/iminapi/model/PosterVariantEntity.java src/test/java/com/imin/iminapi/model/BrandColumnsPersistenceTest.java && git commit -m "$(cat <<'EOF'
Map brand_snapshot + logo_composite_status JPA fields (columns from V38)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 2.2 — BrandSnapshot record + JSON helpers

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/poster/BrandSnapshot.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/BrandSnapshotTest.java`

A small value object carrying the resolved brand into the orchestrator and round-tripping to/from the `brand_snapshot` JSON column.

- [ ] Write the failing test. Create `src/test/java/com/imin/iminapi/service/poster/BrandSnapshotTest.java`:
  ```java
  package com.imin.iminapi.service.poster;

  import org.junit.jupiter.api.Test;

  import java.util.List;

  import static org.assertj.core.api.Assertions.assertThat;

  class BrandSnapshotTest {

      @Test
      void toJson_and_back_round_trips() {
          BrandSnapshot s = new BrandSnapshot(List.of("#ec4899", "#f6c04a"), "https://cdn/l.png", true);
          String json = s.toJson();
          assertThat(json).contains("#ec4899").contains("https://cdn/l.png").contains("\"logoOn\":true");

          BrandSnapshot back = BrandSnapshot.fromJson(json);
          assertThat(back.colors()).containsExactly("#ec4899", "#f6c04a");
          assertThat(back.logoUrl()).isEqualTo("https://cdn/l.png");
          assertThat(back.logoOn()).isTrue();
      }

      @Test
      void fromJson_null_or_blank_returns_null() {
          assertThat(BrandSnapshot.fromJson(null)).isNull();
          assertThat(BrandSnapshot.fromJson("  ")).isNull();
      }

      @Test
      void fromJson_malformed_returns_null() {
          assertThat(BrandSnapshot.fromJson("{not json")).isNull();
      }

      @Test
      void packedAccentColor_leads_with_first_then_supporting() {
          BrandSnapshot s = new BrandSnapshot(List.of("#ec4899", "#f6c04a", "#a78bfa"), null, true);
          assertThat(s.packedAccentColor()).isEqualTo("#ec4899 (lead); supporting: #f6c04a, #a78bfa");
      }

      @Test
      void packedAccentColor_single_colour_has_no_supporting_clause() {
          BrandSnapshot s = new BrandSnapshot(List.of("#ec4899"), null, true);
          assertThat(s.packedAccentColor()).isEqualTo("#ec4899 (lead)");
      }

      @Test
      void packedAccentColor_empty_is_null() {
          BrandSnapshot s = new BrandSnapshot(List.of(), null, true);
          assertThat(s.packedAccentColor()).isNull();
      }
  }
  ```

- [ ] Run it — expected FAIL (class doesn't exist):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=BrandSnapshotTest -q
  ```
  Expected output contains:
  ```
  COMPILATION ERROR
  ... BrandSnapshot ... does not exist
  BUILD FAILURE
  ```

- [ ] Implement. Create `src/main/java/com/imin/iminapi/service/poster/BrandSnapshot.java`:
  ```java
  package com.imin.iminapi.service.poster;

  import com.fasterxml.jackson.core.type.TypeReference;
  import com.fasterxml.jackson.databind.ObjectMapper;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;

  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;

  /**
   * The resolved org brand for one generation. Stamped on {@code poster_generations.brand_snapshot}
   * at creation, so the corrective remix path and any audit read the snapshot, not live org state.
   *
   * @param colors  ordered accent hex (#rrggbb); index 0 leads. Empty when brandless.
   * @param logoUrl the org logo URL, or null when none.
   * @param logoOn  whether the logo should be composited for this generation.
   */
  public record BrandSnapshot(List<String> colors, String logoUrl, boolean logoOn) {

      private static final Logger log = LoggerFactory.getLogger(BrandSnapshot.class);
      private static final ObjectMapper MAPPER = new ObjectMapper();

      /** Serialize to the brand_snapshot JSON shape {colors, logoUrl, logoOn}. */
      public String toJson() {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("colors", colors == null ? List.of() : colors);
          m.put("logoUrl", logoUrl);
          m.put("logoOn", logoOn);
          try {
              return MAPPER.writeValueAsString(m);
          } catch (Exception e) {
              log.warn("Could not serialize BrandSnapshot: {}", e.getMessage());
              return null;
          }
      }

      /** Parse a brand_snapshot JSON string; null/blank/malformed → null (brandless). */
      public static BrandSnapshot fromJson(String json) {
          if (json == null || json.isBlank()) return null;
          try {
              Map<String, Object> m = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
              @SuppressWarnings("unchecked")
              List<String> colors = (List<String>) m.getOrDefault("colors", List.of());
              String logoUrl = (String) m.get("logoUrl");
              Object logoOn = m.get("logoOn");
              return new BrandSnapshot(colors == null ? List.of() : colors, logoUrl,
                      logoOn instanceof Boolean b ? b : false);
          } catch (Exception e) {
              log.warn("Malformed brand_snapshot, treating as brandless: {}", e.getMessage());
              return null;
          }
      }

      /**
       * Pack all colours into the single free-text {@code EventCreatorRequest.accentColor} line:
       * {@code "#ec4899 (lead); supporting: #f6c04a, #a78bfa"}. Null when there are no colours.
       */
      public String packedAccentColor() {
          if (colors == null || colors.isEmpty()) return null;
          String lead = colors.get(0) + " (lead)";
          if (colors.size() == 1) return lead;
          String supporting = String.join(", ", colors.subList(1, colors.size()));
          return lead + "; supporting: " + supporting;
      }
  }
  ```

- [ ] Run the test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=BrandSnapshotTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/service/poster/BrandSnapshot.java src/test/java/com/imin/iminapi/service/poster/BrandSnapshotTest.java && git commit -m "$(cat <<'EOF'
Add BrandSnapshot value object (JSON round-trip + packed accent string)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 2.3 — BrandLogoCompositor (pure Java2D)

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/poster/BrandLogoCompositor.java`
- Test: `src/test/java/com/imin/iminapi/service/poster/BrandLogoCompositorTest.java`

Pure Java2D (`BufferedImage`/`Graphics2D`/`AlphaComposite.SrcOver`) — the exact toolkit `QrImageRenderer` uses; no new dependency. Bottom-right; margin 4% of poster width; logo scaled to max 18% of poster width; luminance-sampled rounded scrim behind the logo (REQUIRED). Per-org decoded-logo cache, invalidated on upload/delete. The compositor downloads the logo via the injected `PosterImageStorage.download` (already used by the orchestrator's storage seam).

- [ ] Write the failing golden-image test. Create `src/test/java/com/imin/iminapi/service/poster/BrandLogoCompositorTest.java`:
  ```java
  package com.imin.iminapi.service.poster;

  import org.junit.jupiter.api.Test;

  import javax.imageio.ImageIO;
  import java.awt.Color;
  import java.awt.Graphics2D;
  import java.awt.image.BufferedImage;
  import java.io.ByteArrayInputStream;
  import java.io.ByteArrayOutputStream;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.mockito.Mockito.mock;
  import static org.mockito.Mockito.when;

  class BrandLogoCompositorTest {

      private final PosterImageStorage storage = mock(PosterImageStorage.class);
      private final BrandLogoCompositor sut = new BrandLogoCompositor(storage);

      /** A solid-colour PNG of the given size, encoded to bytes. */
      private static byte[] solidPng(int w, int h, Color c) {
          try {
              BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
              Graphics2D g = img.createGraphics();
              g.setColor(c);
              g.fillRect(0, 0, w, h);
              g.dispose();
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              ImageIO.write(img, "PNG", out);
              return out.toByteArray();
          } catch (Exception e) { throw new RuntimeException(e); }
      }

      private static BufferedImage decode(byte[] png) {
          try { return ImageIO.read(new ByteArrayInputStream(png)); }
          catch (Exception e) { throw new RuntimeException(e); }
      }

      @Test
      void composite_preserves_dimensions_and_changes_bottom_right() {
          byte[] poster = solidPng(800, 1000, new Color(20, 20, 20)); // dark poster
          byte[] logo = solidPng(200, 200, Color.WHITE);              // opaque white mark
          when(storage.download("https://cdn/logo.png")).thenReturn(logo);

          byte[] out = sut.composite(poster, "org-1", "https://cdn/logo.png");
          BufferedImage rawImg = decode(poster);
          BufferedImage outImg = decode(out);

          assertThat(outImg.getWidth()).isEqualTo(rawImg.getWidth());
          assertThat(outImg.getHeight()).isEqualTo(rawImg.getHeight());

          // The bottom-right corner must differ from the raw poster (logo + scrim painted there).
          int x = (int) (rawImg.getWidth() * 0.92);
          int y = (int) (rawImg.getHeight() * 0.94);
          assertThat(outImg.getRGB(x, y)).isNotEqualTo(rawImg.getRGB(x, y));

          // The top-left corner must be untouched.
          assertThat(outImg.getRGB(0, 0)).isEqualTo(rawImg.getRGB(0, 0));
      }

      @Test
      void opaque_logo_on_dark_corner_gets_a_lighter_scrim() {
          byte[] poster = solidPng(800, 1000, new Color(10, 10, 10)); // very dark corner
          byte[] logo = solidPng(200, 200, Color.WHITE);
          when(storage.download("https://cdn/logo.png")).thenReturn(logo);

          byte[] out = sut.composite(poster, "org-2", "https://cdn/logo.png");
          BufferedImage outImg = decode(out);

          // Sample a pixel in the logo region's margin area where the scrim shows but the logo
          // hasn't fully covered it: just inside the logo box on a dark poster the result must be
          // lighter than the original near-black corner (scrim is luminance-contrasting).
          int x = (int) (outImg.getWidth() * 0.74);
          int y = (int) (outImg.getHeight() * 0.86);
          int rgb = outImg.getRGB(x, y);
          int lum = (rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF);
          assertThat(lum).isGreaterThan(30); // brighter than the 10,10,10 background (sum=30)
      }

      @Test
      void cache_invalidate_forces_redownload_on_next_composite() {
          byte[] poster = solidPng(800, 1000, Color.BLACK);
          byte[] logo = solidPng(200, 200, Color.WHITE);
          when(storage.download("https://cdn/logo.png")).thenReturn(logo);

          sut.composite(poster, "org-3", "https://cdn/logo.png"); // download #1, cached
          sut.composite(poster, "org-3", "https://cdn/logo.png"); // served from cache
          sut.invalidate("org-3");
          sut.composite(poster, "org-3", "https://cdn/logo.png"); // download #2

          org.mockito.Mockito.verify(storage, org.mockito.Mockito.times(2)).download("https://cdn/logo.png");
      }
  }
  ```

- [ ] Run it — expected FAIL (class doesn't exist):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=BrandLogoCompositorTest -q
  ```
  Expected output contains:
  ```
  COMPILATION ERROR
  ... BrandLogoCompositor ... does not exist
  BUILD FAILURE
  ```

- [ ] Implement. Create `src/main/java/com/imin/iminapi/service/poster/BrandLogoCompositor.java`:
  ```java
  package com.imin.iminapi.service.poster;

  import org.springframework.stereotype.Component;

  import javax.imageio.ImageIO;
  import java.awt.AlphaComposite;
  import java.awt.Color;
  import java.awt.Graphics2D;
  import java.awt.RenderingHints;
  import java.awt.image.BufferedImage;
  import java.io.ByteArrayInputStream;
  import java.io.ByteArrayOutputStream;
  import java.io.IOException;
  import java.io.UncheckedIOException;
  import java.util.concurrent.ConcurrentHashMap;

  /**
   * Composites an org's logo onto a generated poster with pure Java2D (the same BufferedImage /
   * Graphics2D toolkit {@link com.imin.iminapi.service.ticket.QrImageRenderer} uses — no new
   * dependency). Deterministic placement: bottom-right, margin 4% of poster width, logo scaled to
   * max 18% of poster width (aspect preserved). A luminance-sampled rounded scrim is drawn behind
   * the logo so a white mark on a light corner (or dark-on-dark) stays legible — Ideogram corners
   * are unpredictable per generation. The decoded logo is cached per org and invalidated on
   * upload/delete via {@link #invalidate(String)}.
   *
   * <p>This class does no error isolation itself — the caller ({@link PosterOrchestrator}) wraps it
   * in try/catch so any failure degrades to the un-composited poster. Generation never fails over
   * a decoration.
   */
  @Component
  public class BrandLogoCompositor {

      private static final double MARGIN_FRACTION = 0.04;     // 4% of poster width
      private static final double LOGO_MAX_FRACTION = 0.18;   // 18% of poster width
      private static final double SCRIM_PAD_FRACTION = 0.25;  // scrim pad = 25% of logo box
      private static final int SCRIM_ALPHA = 110;             // 0-255 translucency of the scrim

      private final PosterImageStorage storage;
      private final ConcurrentHashMap<String, BufferedImage> logoCache = new ConcurrentHashMap<>();

      public BrandLogoCompositor(PosterImageStorage storage) {
          this.storage = storage;
      }

      /** Drop the cached decoded logo for an org (call on logo upload/delete). */
      public void invalidate(String orgKey) {
          logoCache.remove(orgKey);
      }

      /**
       * Returns a new PNG with the logo composited bottom-right. Throws on any failure (download,
       * decode, encode) — the caller isolates.
       */
      public byte[] composite(byte[] posterPng, String orgKey, String logoUrl) {
          BufferedImage poster = decode(posterPng);
          BufferedImage logo = logoCache.computeIfAbsent(orgKey, k -> decode(storage.download(logoUrl)));

          int pw = poster.getWidth();
          int ph = poster.getHeight();

          // Scale the logo to at most 18% of poster width, preserving aspect.
          int targetW = (int) Math.round(pw * LOGO_MAX_FRACTION);
          double scale = (double) targetW / logo.getWidth();
          int logoW = Math.max(1, (int) Math.round(logo.getWidth() * scale));
          int logoH = Math.max(1, (int) Math.round(logo.getHeight() * scale));

          int margin = (int) Math.round(pw * MARGIN_FRACTION);
          int logoX = pw - margin - logoW;
          int logoY = ph - margin - logoH;

          // Work on a copy so the input bytes/poster image are never mutated.
          BufferedImage out = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
          Graphics2D g = out.createGraphics();
          g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
          g.drawImage(poster, 0, 0, null);

          // Legibility scrim: sample mean luminance of the destination region, draw a contrasting pill.
          int pad = (int) Math.round(Math.max(logoW, logoH) * SCRIM_PAD_FRACTION);
          int scrimX = logoX - pad;
          int scrimY = logoY - pad;
          int scrimW = logoW + 2 * pad;
          int scrimH = logoH + 2 * pad;
          Color scrim = scrimColor(poster, clamp(scrimX, 0, pw - 1), clamp(scrimY, 0, ph - 1),
                  Math.min(scrimW, pw), Math.min(scrimH, ph));
          g.setColor(scrim);
          int arc = Math.min(scrimW, scrimH) / 2;
          g.fillRoundRect(scrimX, scrimY, scrimW, scrimH, arc, arc);

          // Logo on top with SrcOver so transparency is honoured.
          g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
          g.drawImage(logo, logoX, logoY, logoW, logoH, null);
          g.dispose();

          return encode(out);
      }

      /** A translucent scrim that contrasts the sampled region: dark scrim on light corners, light on dark. */
      private static Color scrimColor(BufferedImage img, int x, int y, int w, int h) {
          long sum = 0;
          long n = 0;
          int x2 = Math.min(x + w, img.getWidth());
          int y2 = Math.min(y + h, img.getHeight());
          for (int yy = y; yy < y2; yy += 4) {
              for (int xx = x; xx < x2; xx += 4) {
                  int rgb = img.getRGB(xx, yy);
                  int r = rgb >> 16 & 0xFF, gg = rgb >> 8 & 0xFF, b = rgb & 0xFF;
                  // Rec. 601 luma
                  sum += Math.round(0.299 * r + 0.587 * gg + 0.114 * b);
                  n++;
              }
          }
          double meanLuma = n == 0 ? 0 : (double) sum / n;
          // Light corner → dark scrim; dark corner → light scrim.
          int base = meanLuma > 127 ? 0 : 255;
          return new Color(base, base, base, SCRIM_ALPHA);
      }

      private static int clamp(int v, int lo, int hi) {
          return Math.max(lo, Math.min(hi, v));
      }

      private static BufferedImage decode(byte[] png) {
          try {
              BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
              if (img == null) throw new IllegalStateException("could not decode image");
              return img;
          } catch (IOException e) {
              throw new UncheckedIOException("Failed to decode image for composite", e);
          }
      }

      private static byte[] encode(BufferedImage img) {
          try {
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              ImageIO.write(img, "PNG", out);
              return out.toByteArray();
          } catch (IOException e) {
              throw new UncheckedIOException("Failed to encode composited PNG", e);
          }
      }
  }
  ```

- [ ] Run the golden-image test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=BrandLogoCompositorTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/service/poster/BrandLogoCompositor.java src/test/java/com/imin/iminapi/service/poster/BrandLogoCompositorTest.java && git commit -m "$(cat <<'EOF'
Add BrandLogoCompositor (Java2D bottom-right placement, luminance scrim, per-org cache)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 2.4 — Composite seam inside PosterOrchestrator.accept()

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java` (constructor + `run(...)` signature + `renderWithValidation`/`accept` threading; lines 75-109 constructor, 121-182 run, 220-279 render/accept)
- Test: `src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java` (modify: add composite cases; existing constructor calls at line 70-73 get a new arg)

**Design (from spec §4):** The composite happens inside `accept()` — the SOLE funnel that sets `finalUrl`, so all three acceptance paths (text-gate best-effort line 251, accepted line 262, style soft-fail line 266) get the logo. The composited PNG is a SECOND `PosterImageStorage.writePng` call → `final_url` = composited URL; `raw_url` keeps the un-composited one. `logo_composite_status` = APPLIED / SKIPPED / FAILED. Any composite error → `final_url = raw_url` + Sentry warning + FAILED; generation never fails over the logo.

**Reader audit (state explicitly, required by spec):**
- `ConceptStudioService.mapPosters` (`ConceptStudioService.java:204-205`) uses `finalUrl ?? rawUrl` → **display/download is `final_url` (composited).** Correct.
- `PosterOrchestrator.toDto` (`PosterOrchestrator.java:353-356`) passes both `rawUrl` and `finalUrl` into `GeneratedPoster` → consumers choose; FE shows `final_url`.
- The corrective **remix** input is the in-memory `image` bytes inside `renderWithValidation` (line 239-240), i.e. the **un-composited** render — correct: remix re-edits the raw render, not a logo-stamped one. `raw_url` is therefore the re-edit/remix input; `final_url` is canonical for display/download.

- [ ] Modify the orchestrator constructor + `run` to accept the compositor and a `BrandSnapshot`. Edit `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java`.

  First, add imports. After line 18 (`import com.imin.iminapi.service.ai.CreativeDirection;`) add:
  ```java
  import io.sentry.Sentry;
  import io.sentry.SentryLevel;
  ```

  Add the field. After line 66 (`private final PosterImageStorage storage;`) add:
  ```java
      private final BrandLogoCompositor logoCompositor;
  ```

  Add the constructor parameter. In the constructor signature, after `PosterImageStorage storage,` (line 83) add a parameter:
  ```java
              BrandLogoCompositor logoCompositor,
  ```
  and in the constructor body, after `this.storage = storage;` (line 96) add:
  ```java
          this.logoCompositor = logoCompositor;
  ```

- [ ] Thread `BrandSnapshot` through `run`. Replace the two `run(...)` overloads (lines 121-126) so the public 3-arg overload defaults to a brandless snapshot and the 5-arg overload gains a `BrandSnapshot` parameter. Replace:
  ```java
      public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept) {
          return run(generatedEventId, request, concept, deriveSeed(generatedEventId), List.of());
      }

      public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept,
                                     long creativeSeed, List<CreativeDirection> directions) {
  ```
  with:
  ```java
      public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept) {
          return run(generatedEventId, request, concept, deriveSeed(generatedEventId), List.of(), null);
      }

      public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept,
                                     long creativeSeed, List<CreativeDirection> directions) {
          return run(generatedEventId, request, concept, creativeSeed, directions, null);
      }

      public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept,
                                     long creativeSeed, List<CreativeDirection> directions, BrandSnapshot brand) {
  ```

- [ ] Stamp the snapshot on the generation row at creation. In `run`, after `generation.setCreativeSeed(creativeSeed);` (line 131, before the `generationRepository.save(generation)` on line 132) add:
  ```java
          if (brand != null) generation.setBrandSnapshot(brand.toJson());
  ```

- [ ] Pass `brand` into each variant task. The variant tasks call `generateOne(gen, v, dir, seed, request, ctx)` (line 152). Thread `brand` through to `accept()`. Change the `generateOne` call (line 152) to:
  ```java
              futures.add(variantPool.submit(() -> generateOne(gen, v, dir, seed, request, ctx, brand)));
  ```

- [ ] Add the `brand` parameter to `generateOne` and `renderWithValidation`. Change the `generateOne` signature (line 184-186) to add `BrandSnapshot brand`:
  ```java
      private GeneratedPoster generateOne(
              PosterGeneration generation, PosterVariant variant, CreativeDirection direction,
              long seed, EventCreatorRequest request, RenderContext ctx, BrandSnapshot brand) {
  ```
  and update its single call to `renderWithValidation` (line 208) to:
  ```java
              return renderWithValidation(entity, variant, seed, request, ctx, brand);
  ```
  Change the `renderWithValidation` signature (line 220-222) to add `BrandSnapshot brand`:
  ```java
      private GeneratedPoster renderWithValidation(
              PosterVariantEntity entity, PosterVariant variant, long baseSeed,
              EventCreatorRequest request, RenderContext ctx, BrandSnapshot brand) {
  ```

- [ ] Thread `brand` into the three `accept(...)` calls inside `renderWithValidation`. There are three call sites (lines 251, 262, 266). Change each:
  - Line 251: `return accept(entity, url, VERDICT_BEST_EFFORT, attempts);` → `return accept(entity, url, VERDICT_BEST_EFFORT, attempts, brand);`
  - Line 262: `return accept(entity, url, VERDICT_ACCEPTED, attempts);` → `return accept(entity, url, VERDICT_ACCEPTED, attempts, brand);`
  - Line 266: `return accept(entity, url, VERDICT_BEST_EFFORT, attempts);` → `return accept(entity, url, VERDICT_BEST_EFFORT, attempts, brand);`

- [ ] Replace the `accept` method (lines 271-279) with the composite-aware version:
  ```java
      /**
       * The single funnel that sets final_url for every acceptance path. Composites the brand logo
       * when the snapshot says to, as a SECOND storage write (final_url = composited URL; raw_url keeps
       * the un-composited render). Failure isolation is absolute: any composite error → final_url =
       * raw_url + Sentry warning + status FAILED. Generation never fails over the logo.
       */
      private GeneratedPoster accept(PosterVariantEntity entity, String rawUrl, String verdict,
                                     List<Map<String, Object>> attempts, BrandSnapshot brand) {
          entity.setValidationVerdict(verdict);
          entity.setValidationAttemptsJson(serialize(attempts));
          entity.setStatus(PosterVariantStatus.COMPLETE);

          String finalUrl = rawUrl;
          String compositeStatus;
          if (brand == null || !brand.logoOn() || brand.logoUrl() == null || brand.logoUrl().isBlank()) {
              compositeStatus = "SKIPPED";
          } else {
              try {
                  byte[] rawBytes = storage.download(rawUrl);
                  byte[] composited = logoCompositor.composite(
                          rawBytes,
                          entity.getPosterGeneration().getGeneratedEventId().toString(),
                          brand.logoUrl());
                  finalUrl = storage.writePng(composited); // SECOND write → distinct object/URL
                  compositeStatus = "APPLIED";
              } catch (RuntimeException e) {
                  log.warn("Logo composite failed; shipping un-composited poster: {}", e.getMessage());
                  Sentry.withScope(scope -> {
                      scope.setLevel(SentryLevel.WARNING);
                      scope.setTag("subsystem", "brand-logo-composite");
                      Sentry.captureException(e);
                  });
                  finalUrl = rawUrl;
                  compositeStatus = "FAILED";
              }
          }
          entity.setFinalUrl(finalUrl);
          entity.setLogoCompositeStatus(compositeStatus);
          return toDto(entity);
      }
  ```
  Note: the previous `accept` set `entity.setFinalUrl(url)` first; the new version sets it last after composite resolution. The `rawUrl` passed in is already on `entity.rawUrl` (set at line 242 during render), so `raw_url` is preserved untouched.

  > **Sentry note (first production call site):** This is the **first real `Sentry.captureException` in `src/main`** — Sentry was previously only referenced in a code comment (`EventVelocityService.java:116`). Two things matter:
  > 1. Keep `Sentry.captureException(e)` **inside** the `Sentry.withScope(scope -> { ... })` lambda (as pasted above). In the Sentry SDK 8.x API, `withScope` runs the callback against a temporary scope; the `scope.setLevel(SentryLevel.WARNING)` and `scope.setTag(...)` only apply to a capture made *within* that callback. Moving `captureException` after the lambda would lose the WARNING level and the tag — and the spec requires a Sentry **warning**, not an error.
  > 2. In the `test` profile the Sentry SDK is **not initialized**, so `Sentry.captureException` is a harmless **no-op** (it does not throw and records nothing). No test setup or mock is required, and the `compositeThrows_...` test exercises this branch safely.

- [ ] Update the existing `PosterOrchestratorTest` constructor helper. Edit `src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java`. Add a compositor field and mock, and pass it to the constructor. After the `storage` field declaration (line 43) add:
  ```java
      private BrandLogoCompositor logoCompositor;
  ```
  In `setUp()` after `storage = mock(PosterImageStorage.class);` (line 55) add:
  ```java
          logoCompositor = mock(BrandLogoCompositor.class);
  ```
  Change the `orchestrator()` helper (lines 70-73) to pass the compositor (inserted after `storage`):
  ```java
      private PosterOrchestrator orchestrator() {
          return new PosterOrchestrator(ideogram, vibeLibrary, styleCardLibrary, referenceLibrary,
                  textSpecFactory, textValidation, styleValidation, storage, logoCompositor, repo,
                  /*maxRegenerations*/ 2, /*remixImageWeight*/ 70, /*maxReferences*/ 3, /*maxConcurrent*/ 6);
      }
  ```

- [ ] Add composite test cases to `PosterOrchestratorTest`. Append these three tests inside the class (before its closing brace):
  ```java
      @Test
      void noBrand_skipsComposite_finalEqualsRaw() {
          when(ideogram.generate(any(), anyLong(), any(), any()))
                  .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
          when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
          when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

          PosterOrchestrator.OrchestrationResult r =
                  orchestrator().run(UUID.randomUUID(), req(), concept());

          // No second writePng beyond the one raw write per variant; compositor never called.
          org.mockito.Mockito.verify(logoCompositor, never()).composite(any(), any(), any());
          assertThat(r.posters()).allSatisfy(p -> assertThat(p.finalUrl()).isEqualTo(p.rawUrl()));
      }

      @Test
      void brandWithLogoOn_appliesComposite_finalDiffersFromRaw_andStatusApplied() {
          when(ideogram.generate(any(), anyLong(), any(), any()))
                  .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
          when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
          when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());
          // CRITICAL: the 3 variants render in PARALLEL on variantPool, and the branded path calls
          // writePng TWICE per variant (raw render bytes {2}, then the composite bytes {7}) — 6 calls
          // across 3 threads. A sequential thenReturn(...) would draw non-deterministically, so key
          // writePng on its byte[] argument (exactly as the existing remix test keys validateOrExplain
          // on the image bytes for the same reason). Composite mock returns {7}; raw render is {2}.
          when(storage.writePng(any())).thenAnswer(inv -> {
              byte[] b = inv.getArgument(0);
              return (b.length > 0 && b[0] == 7) ? "https://img/composited.png" : "https://img/raw.png";
          });
          when(storage.download("https://img/raw.png")).thenReturn(new byte[]{9});
          when(logoCompositor.composite(any(), any(), eq("https://cdn/logo.png")))
                  .thenReturn(new byte[]{7});

          BrandSnapshot brand = new BrandSnapshot(java.util.List.of("#ec4899"), "https://cdn/logo.png", true);
          PosterOrchestrator.OrchestrationResult r = orchestrator().run(
                  UUID.randomUUID(), req(), concept(), 123L, java.util.List.of(), brand);

          // Assert across ALL variants (not .get(0)) — placement among the 3 parallel results is not ordered.
          assertThat(r.posters()).allSatisfy(p -> {
              assertThat(p.finalUrl()).isEqualTo("https://img/composited.png");
              assertThat(p.rawUrl()).isEqualTo("https://img/raw.png");
          });

          ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
          verify(repo, atLeastOnce()).save(saved.capture());
          assertThat(saved.getValue().getBrandSnapshot()).contains("#ec4899");
          assertThat(saved.getValue().getVariants())
                  .allSatisfy(v -> assertThat(v.getLogoCompositeStatus()).isEqualTo("APPLIED"));
      }

      @Test
      void compositeThrows_isIsolated_finalFallsBackToRaw_statusFailed() {
          when(ideogram.generate(any(), anyLong(), any(), any()))
                  .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
          when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
          when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());
          when(storage.writePng(any())).thenReturn("https://img/raw.png");
          when(storage.download("https://img/raw.png")).thenReturn(new byte[]{9});
          when(logoCompositor.composite(any(), any(), any()))
                  .thenThrow(new RuntimeException("decode boom"));

          BrandSnapshot brand = new BrandSnapshot(java.util.List.of("#ec4899"), "https://cdn/logo.png", true);
          PosterOrchestrator.OrchestrationResult r = orchestrator().run(
                  UUID.randomUUID(), req(), concept(), 123L, java.util.List.of(), brand);

          // Generation still succeeds; final_url falls back to raw_url; status FAILED for every variant.
          // (Single writePng return + composite always throwing makes raw the only outcome across the
          // 3 parallel threads, so allSatisfy is deterministic here.)
          assertThat(r.posters()).allSatisfy(p -> {
              assertThat(p.status()).isEqualTo("COMPLETE");
              assertThat(p.finalUrl()).isEqualTo("https://img/raw.png");
              assertThat(p.rawUrl()).isEqualTo("https://img/raw.png");
          });
          ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
          verify(repo, atLeastOnce()).save(saved.capture());
          assertThat(saved.getValue().getVariants())
                  .allSatisfy(v -> assertThat(v.getLogoCompositeStatus()).isEqualTo("FAILED"));
      }
  ```
  (`ArgumentCaptor`, `eq`, `never`, `atLeastOnce`, `verify`, `when`, `any`, `anyLong` are already imported in this test file.)

- [ ] Run the orchestrator test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=PosterOrchestratorTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java && git commit -m "$(cat <<'EOF'
Composite brand logo inside accept() (second writePng, FAILED-isolated, status stamped)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 2.5 — ConceptRequest.logoOnPosters + ConceptStudioService brand wiring

**Files:**
- Modify: `src/main/java/com/imin/iminapi/dto/ai/ConceptRequest.java` (add 12th field)
- Modify: `src/main/java/com/imin/iminapi/service/ai/ConceptStudioService.java` (inject `OrganizationRepository`; resolve brand failure-isolated; pack accent string; build snapshot; pass to orchestrator)
- Modify: `src/test/java/com/imin/iminapi/service/ai/ConceptStudioServiceTest.java` (constructor gets `OrganizationRepository`; all `new ConceptRequest(...)` sites gain a trailing arg; new brand tests)
- Test sites to fix in same file: `ConceptStudioServiceTest.java:86,169,220,230,244` plus the internal `regenerate` construction in `ConceptStudioService.java:73`.

Resolution rule (spec §3.5): `request.logoOnPosters() ?? org.brandLogoOnPosters() ?? true`. Colours come from the org, NOT the request. The org lookup is wrapped in try/catch → on any error proceed **brandless** (snapshot null, `accentColor` null).

- [ ] Add the field to `ConceptRequest`. Edit `src/main/java/com/imin/iminapi/dto/ai/ConceptRequest.java`. Change the record to add a final optional field (note: append AFTER `rsvpUrl` so existing positional construction order is extended, not reordered):
  ```java
  public record ConceptRequest(
          @NotBlank @Size(min = 10, max = 500) String vibe,
          String genre,
          String city,
          Integer capacity,
          String vibeId,
          String title,
          LocalDate eventDate,
          String venue,
          List<String> lineup,
          String address,
          String rsvpUrl,
          // Per-call render directive (NOT brand identity): whether to composite the org logo on
          // this generation. Resolution: request.logoOnPosters() ?? org default ?? true. Optional —
          // a stale FE that omits it falls back to the org default; never an NPE.
          Boolean logoOnPosters) {}
  ```

- [ ] Fix the internal `regenerate` construction in `ConceptStudioService`. Edit `src/main/java/com/imin/iminapi/service/ai/ConceptStudioService.java`. The `new ConceptRequest(...)` at lines 73-78 must gain the trailing `logoOnPosters` arg. Change:
  ```java
          ConceptRequest req = new ConceptRequest(
                  prior.getVibe() == null ? "rerun" : prior.getVibe(),
                  prior.getGenre(), prior.getCity(),
                  /* capacity */ null, /* vibeId */ null,
                  /* title */ null, /* eventDate */ null, /* venue */ null,
                  /* lineup */ null, /* address */ null, /* rsvpUrl */ null);
  ```
  to:
  ```java
          ConceptRequest req = new ConceptRequest(
                  prior.getVibe() == null ? "rerun" : prior.getVibe(),
                  prior.getGenre(), prior.getCity(),
                  /* capacity */ null, /* vibeId */ null,
                  /* title */ null, /* eventDate */ null, /* venue */ null,
                  /* lineup */ null, /* address */ null, /* rsvpUrl */ null,
                  /* logoOnPosters: brand default applies on regenerate */ null);
  ```

- [ ] Inject `OrganizationRepository` into `ConceptStudioService`. Add the import after line 13 (`import com.imin.iminapi.repository.GeneratedEventRepository;`):
  ```java
  import com.imin.iminapi.repository.OrganizationRepository;
  import com.imin.iminapi.model.Organization;
  import com.imin.iminapi.service.poster.BrandSnapshot;
  ```
  Add the field after `private final VibeLibrary vibeLibrary;` (line 43):
  ```java
      private final OrganizationRepository orgs;
  ```
  Add the constructor parameter (after `VibeLibrary vibeLibrary` on line 55) and assignment. Change the constructor signature line 50-55 to include `OrganizationRepository orgs,` before `VibeLibrary vibeLibrary)`:
  ```java
      public ConceptStudioService(AiEventDescriptionService descService,
                                  PosterOrchestrator orchestrator,
                                  PricingService pricing,
                                  ConceptOverviewLlm overviewLlm,
                                  GeneratedEventRepository repo,
                                  OrganizationRepository orgs,
                                  VibeLibrary vibeLibrary) {
  ```
  and in the body after `this.repo = repo;` (line 60) add:
  ```java
          this.orgs = orgs;
  ```

- [ ] Resolve the brand (failure-isolated) and wire it into the legacy request + orchestrator. In `run(...)`, the brand must influence (a) the packed `accentColor` in `toLegacyRequest`, and (b) the `BrandSnapshot` passed to `orchestrator.run`. Add a brand-resolution helper and use it.

  First add the helper method (place it next to `toLegacyRequest`, after the `toLegacyRequest` method ends at line 201):
  ```java
      /**
       * Resolve the org brand for this generation, failure-isolated: a malformed brand_accent_colors
       * JSON, a missing org, or any DB hiccup logs and returns a brandless snapshot. Brand integration
       * must never break the existing generation path.
       *
       * @param requestLogoOnPosters the per-call override (may be null)
       */
      private BrandSnapshot resolveBrand(AuthPrincipal p, Boolean requestLogoOnPosters) {
          try {
              Organization o = orgs.findById(p.orgId()).orElse(null);
              if (o == null) return null;
              List<String> colors = o.getBrandAccentColors() == null ? List.of() : o.getBrandAccentColors();
              boolean logoOn = requestLogoOnPosters != null ? requestLogoOnPosters : o.isBrandLogoOnPosters();
              boolean brandless = colors.isEmpty() && (o.getBrandLogoUrl() == null || o.getBrandLogoUrl().isBlank());
              if (brandless) return null; // empty brand book → fully backward-compatible (accentColor stays null)
              return new BrandSnapshot(colors, o.getBrandLogoUrl(), logoOn);
          } catch (Exception e) {
              log.warn("Brand lookup failed; generating brandless: {}", e.getMessage());
              return null;
          }
      }
  ```

  Now thread it through `run`. The `run` method builds `legacy` at line 95 and calls `orchestrator.run` at line 106. Change the flow so `run` resolves the brand once and passes it to both. Replace line 95:
  ```java
          EventCreatorRequest legacy = toLegacyRequest(req);
  ```
  with:
  ```java
          BrandSnapshot brand = resolveBrand(p, req.logoOnPosters());
          EventCreatorRequest legacy = toLegacyRequest(req, brand);
  ```
  and replace the `orchestrator.run` call (line 106):
  ```java
              render = orchestrator.run(staging.getId(), legacy, poster, creativeSeed, generated.directions());
  ```
  with:
  ```java
              render = orchestrator.run(staging.getId(), legacy, poster, creativeSeed, generated.directions(), brand);
  ```

- [ ] Pack the accent colours into `toLegacyRequest`. Change the `toLegacyRequest` signature (line 181) to accept the brand, and replace the `/* accentColor */ null` at line 195. Change:
  ```java
      private EventCreatorRequest toLegacyRequest(ConceptRequest req) {
          Vibe vibe = resolveVibe(req);
  ```
  to:
  ```java
      private EventCreatorRequest toLegacyRequest(ConceptRequest req, BrandSnapshot brand) {
          Vibe vibe = resolveVibe(req);
          String accentColor = brand == null ? null : brand.packedAccentColor();
  ```
  and change line 195 `/* accentColor */ null,` to:
  ```java
                  /* accentColor */ accentColor,
  ```

- [ ] Update `ConceptStudioServiceTest` for the new constructor + ConceptRequest arity. Edit `src/test/java/com/imin/iminapi/service/ai/ConceptStudioServiceTest.java`.

  Add a repo mock field after the `vibeLibrary` mock (line 45-46):
  ```java
      com.imin.iminapi.repository.OrganizationRepository orgs =
              mock(com.imin.iminapi.repository.OrganizationRepository.class);
  ```
  Change the `sut` construction (lines 48-49) to pass it in the new position:
  ```java
      ConceptStudioService sut = new ConceptStudioService(
              descService, orchestrator, pricing, overviewLlm, repo, orgs, vibeLibrary);
  ```
  Change the orchestrator mock stubs from the 5-arg to the 6-arg overload. There are stubs at lines 77, 125, 161, 268 of the form `when(orchestrator.run(any(), any(), any(), anyLong(), any()))`. The new overload adds a 6th param; update each to:
  ```java
          when(orchestrator.run(any(), any(), any(), anyLong(), any(), any())).thenReturn(...);
  ```
  (keep each existing `.thenReturn(...)` body). Likewise change the verification at line 175 `verify(orchestrator).run(any(), any(), cap.capture(), anyLong(), any());` to:
  ```java
          verify(orchestrator).run(any(), any(), cap.capture(), anyLong(), any(), any());
  ```
  Add a trailing `null` (logoOnPosters) to every `new ConceptRequest(...)` literal in the test. The five sites and their replacements:
  - Line 86-88:
    ```java
          ConceptResponse r = sut.create(p, new ConceptRequest(
                  "Moody Berlin techno warehouse", "Techno", "Berlin", null, null,
                  null, null, null, null, null, null, null));
    ```
  - Line 169-171:
    ```java
          sut.create(p, new ConceptRequest(
                  "Brutalist warehouse rave brief", "Techno", "Berlin", null, "brutalist_techno",
                  null, null, null, null, null, null, null));
    ```
  - Line 220-221:
    ```java
                  new ConceptRequest("Some brief text long enough", null, null, null, "not_a_vibe",
                          null, null, null, null, null, null, null)))
    ```
  - Line 230-232:
    ```java
          sut.create(p, new ConceptRequest(
                  "Moody warehouse techno brief here", "Techno", "Metz", null, null,
                  null, null, null, null, null, null, null));
    ```
  - Line 244-246:
    ```java
          sut.create(p, new ConceptRequest(
                  "Moody warehouse techno brief here", "Techno", null, null, null,
                  null, null, null, null, null, null, null));
    ```

- [ ] Add the brand-wiring tests. Append these inside `ConceptStudioServiceTest` (before the closing brace), and have `stubPipeline()` also default the org lookup to empty (brandless) so existing tests stay brandless. First, extend `stubPipeline()` — after its `when(overviewLlm.generate(...))` stub add:
  ```java
          when(orgs.findById(any())).thenReturn(java.util.Optional.empty());
  ```
  Then add:
  ```java
      @Test
      void brandColours_packed_into_accentColor_lead_plus_supporting() {
          AuthPrincipal p = owner();
          stubPipeline();
          com.imin.iminapi.model.Organization o = new com.imin.iminapi.model.Organization();
          o.setBrandAccentColors(new java.util.ArrayList<>(List.of("#ec4899", "#f6c04a", "#a78bfa")));
          o.setBrandLogoOnPosters(true);
          when(orgs.findById(p.orgId())).thenReturn(java.util.Optional.of(o));

          sut.create(p, new ConceptRequest(
                  "Moody warehouse techno brief here", "Techno", "Berlin", null, null,
                  null, null, null, null, null, null, null));

          ArgumentCaptor<EventCreatorRequest> cap = ArgumentCaptor.forClass(EventCreatorRequest.class);
          verify(descService).generateConcept(cap.capture(), anyLong());
          assertThat(cap.getValue().accentColor())
                  .isEqualTo("#ec4899 (lead); supporting: #f6c04a, #a78bfa");
      }

      @Test
      void emptyBrandBook_keeps_accentColor_null_regression_guard_for_line195() {
          AuthPrincipal p = owner();
          stubPipeline(); // orgs.findById → empty

          sut.create(p, new ConceptRequest(
                  "Moody warehouse techno brief here", "Techno", "Berlin", null, null,
                  null, null, null, null, null, null, null));

          ArgumentCaptor<EventCreatorRequest> cap = ArgumentCaptor.forClass(EventCreatorRequest.class);
          verify(descService).generateConcept(cap.capture(), anyLong());
          assertThat(cap.getValue().accentColor()).isNull();
      }

      @Test
      void requestLogoOnPosters_overrides_org_default() {
          AuthPrincipal p = owner();
          stubPipeline();
          com.imin.iminapi.model.Organization o = new com.imin.iminapi.model.Organization();
          o.setBrandLogoUrl("https://cdn/logo.png");
          o.setBrandLogoOnPosters(true); // org default ON
          when(orgs.findById(p.orgId())).thenReturn(java.util.Optional.of(o));

          // request explicitly turns it OFF
          sut.create(p, new ConceptRequest(
                  "Moody warehouse techno brief here", "Techno", "Berlin", null, null,
                  null, null, null, null, null, null, Boolean.FALSE));

          ArgumentCaptor<com.imin.iminapi.service.poster.BrandSnapshot> cap =
                  ArgumentCaptor.forClass(com.imin.iminapi.service.poster.BrandSnapshot.class);
          verify(orchestrator).run(any(), any(), any(), anyLong(), any(), cap.capture());
          assertThat(cap.getValue().logoOn()).isFalse();
      }

      @Test
      void malformedBrandLookup_proceeds_brandless() {
          AuthPrincipal p = owner();
          stubPipeline();
          when(orgs.findById(p.orgId())).thenThrow(new RuntimeException("db hiccup"));

          // Must not throw — generation proceeds brandless.
          sut.create(p, new ConceptRequest(
                  "Moody warehouse techno brief here", "Techno", "Berlin", null, null,
                  null, null, null, null, null, null, null));

          ArgumentCaptor<EventCreatorRequest> cap = ArgumentCaptor.forClass(EventCreatorRequest.class);
          verify(descService).generateConcept(cap.capture(), anyLong());
          assertThat(cap.getValue().accentColor()).isNull();
          ArgumentCaptor<com.imin.iminapi.service.poster.BrandSnapshot> bcap =
                  ArgumentCaptor.forClass(com.imin.iminapi.service.poster.BrandSnapshot.class);
          verify(orchestrator).run(any(), any(), any(), anyLong(), any(), bcap.capture());
          assertThat(bcap.getValue()).isNull();
      }
  ```

- [ ] Run the concept-studio test — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=ConceptStudioServiceTest -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/dto/ai/ConceptRequest.java src/main/java/com/imin/iminapi/service/ai/ConceptStudioService.java src/test/java/com/imin/iminapi/service/ai/ConceptStudioServiceTest.java && git commit -m "$(cat <<'EOF'
Wire org brand into generation: packed accent string, brand snapshot, logoOnPosters override (failure-isolated)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 2.6 — Invalidate the compositor cache on logo upload/delete (cross-PR seam)

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/org/OrgMediaService.java` (inject `BrandLogoCompositor`, call `invalidate` on upload + delete)
- Test: `src/test/java/com/imin/iminapi/service/org/OrgMediaServiceTest.java` (verify invalidation)

The compositor's per-org cache is keyed on the org id (the orchestrator passes `getGeneratedEventId()` per generation, but the cache must drop the org's decoded logo when the underlying logo changes). The cache key used by the orchestrator is the generation's event id string; to keep the cache correct across re-uploads, `OrgMediaService` invalidates by the **org id string** and the compositor must be keyed by org id at the composite call. Reconcile: the orchestrator passes the org-scoped key. Update the orchestrator's composite call to use the org id (available via the generation row is the event id, not org id — so we key the cache on the **logo URL-derived org id**). To keep this simple and correct, key the cache on the logo URL itself (content-addressed: a new upload changes the URL → new cache entry; old entry is harmless and dropped by `invalidate`).

> **Decision (documented):** Cache key = the **logo URL**, not the org id. The logo URL is content-addressed (`logo-{sha256prefix8}.png`), so a re-upload yields a new URL and therefore a fresh cache entry automatically; `invalidate` clears the stale one. This removes the need for the orchestrator to know the org id. Adjust the compositor and orchestrator accordingly below.

- [ ] Re-key the compositor cache on the logo URL. Edit `src/main/java/com/imin/iminapi/service/poster/BrandLogoCompositor.java`. The cache is already a `ConcurrentHashMap<String, BufferedImage>`. Change `composite` to use `logoUrl` as the cache key and drop the now-redundant `orgKey` parameter:
  ```java
      public byte[] composite(byte[] posterPng, String logoUrl) {
          BufferedImage poster = decode(posterPng);
          BufferedImage logo = logoCache.computeIfAbsent(logoUrl, k -> decode(storage.download(logoUrl)));
  ```
  (Delete the `orgKey` parameter from the signature; everything else in the method body is unchanged.) Change `invalidate` to take the logo URL:
  ```java
      /** Drop the cached decoded logo (call on logo upload/delete, keyed by URL). */
      public void invalidate(String logoUrl) {
          logoCache.remove(logoUrl);
      }
  ```

- [ ] Update the orchestrator composite call to the 2-arg form. Edit `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java` inside `accept` — change:
  ```java
                  byte[] composited = logoCompositor.composite(
                          rawBytes,
                          entity.getPosterGeneration().getGeneratedEventId().toString(),
                          brand.logoUrl());
  ```
  to:
  ```java
                  byte[] composited = logoCompositor.composite(rawBytes, brand.logoUrl());
  ```

- [ ] Update `BrandLogoCompositorTest` calls to the 2-arg form. Edit `src/test/java/com/imin/iminapi/service/poster/BrandLogoCompositorTest.java`. Replace every `sut.composite(poster, "org-N", "https://cdn/logo.png")` with `sut.composite(poster, "https://cdn/logo.png")`, and the cache test's `sut.invalidate("org-3")` with `sut.invalidate("https://cdn/logo.png")`. Concretely, the cache test body becomes:
  ```java
          sut.composite(poster, "https://cdn/logo.png"); // download #1, cached
          sut.composite(poster, "https://cdn/logo.png"); // served from cache
          sut.invalidate("https://cdn/logo.png");
          sut.composite(poster, "https://cdn/logo.png"); // download #2
  ```

- [ ] Update the orchestrator composite stub in `PosterOrchestratorTest`. The `when(logoCompositor.composite(any(), any(), eq("https://cdn/logo.png")))` (3-arg) becomes 2-arg:
  ```java
          when(logoCompositor.composite(any(), eq("https://cdn/logo.png"))).thenReturn(new byte[]{7});
  ```
  and the `verify(logoCompositor, never()).composite(any(), any(), any())` becomes:
  ```java
          org.mockito.Mockito.verify(logoCompositor, never()).composite(any(), any());
  ```
  and the throwing stub `when(logoCompositor.composite(any(), any(), any())).thenThrow(...)` becomes:
  ```java
          when(logoCompositor.composite(any(), any())).thenThrow(new RuntimeException("decode boom"));
  ```

- [ ] Inject the compositor into `OrgMediaService` and invalidate on upload/delete. Edit `src/main/java/com/imin/iminapi/service/org/OrgMediaService.java`. Add the import:
  ```java
  import com.imin.iminapi.service.poster.BrandLogoCompositor;
  ```
  Add the field and constructor param. Change the field block + constructor:
  ```java
      private final OrganizationRepository orgs;
      private final MediaStorage storage;
      private final OrgBrandService brandService;
      private final BrandLogoCompositor logoCompositor;

      public OrgMediaService(OrganizationRepository orgs, MediaStorage storage,
                             OrgBrandService brandService, BrandLogoCompositor logoCompositor) {
          this.orgs = orgs;
          this.storage = storage;
          this.brandService = brandService;
          this.logoCompositor = logoCompositor;
      }
  ```
  In `uploadLogo`, after the old-object cleanup block (just before `return new LogoUploadResponse(url);`) add:
  ```java
          // Drop any cached decode of the previous logo so the next composite re-downloads.
          if (oldUrl != null) logoCompositor.invalidate(oldUrl);
  ```
  In `deleteLogo`, after the best-effort storage delete (inside the `if (url != null)` block, after the try/catch) add:
  ```java
              logoCompositor.invalidate(url);
  ```

- [ ] Update `OrgMediaServiceTest` for the new constructor. Edit `src/test/java/com/imin/iminapi/service/org/OrgMediaServiceTest.java`. Add a compositor mock field and pass it to the constructor:
  ```java
      com.imin.iminapi.service.poster.BrandLogoCompositor logoCompositor =
              mock(com.imin.iminapi.service.poster.BrandLogoCompositor.class);
      OrgMediaService sut = new OrgMediaService(orgs, storage, brandService, logoCompositor);
  ```
  (Replace the existing `OrgMediaService sut = new OrgMediaService(orgs, storage, brandService);` line.) Add an invalidation assertion test:
  ```java
      @Test
      void reupload_invalidates_old_logo_in_compositor_cache() {
          UUID id = UUID.randomUUID();
          Organization o = org(id);
          when(orgs.findById(id)).thenReturn(Optional.of(o));

          LogoUploadResponse r1 = sut.uploadLogo(owner(id), png(256, 256), "image/png", "a.png");
          o.setBrandLogoUrl(r1.logoUrl());
          sut.uploadLogo(owner(id), png(300, 300), "image/png", "b.png");

          verify(logoCompositor).invalidate(r1.logoUrl());
      }
  ```

- [ ] Run all affected tests together — expected PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest='OrgMediaServiceTest,BrandLogoCompositorTest,PosterOrchestratorTest' -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/service/poster/BrandLogoCompositor.java src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java src/main/java/com/imin/iminapi/service/org/OrgMediaService.java src/test/java/com/imin/iminapi/service/poster/BrandLogoCompositorTest.java src/test/java/com/imin/iminapi/service/poster/PosterOrchestratorTest.java src/test/java/com/imin/iminapi/service/org/OrgMediaServiceTest.java && git commit -m "$(cat <<'EOF'
Key logo cache by URL; invalidate on upload/delete

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 2.7 — ADR-0002

**Files:**
- Create: `docs/decisions/ADR-0002-brand-logo-composite.md`

(No test — documentation deliverable.)

- [ ] Create `docs/decisions/ADR-0002-brand-logo-composite.md` with this exact content:
  ```markdown
  # ADR-0002: Composite the brand logo onto accepted posters (carve-out from the no-overlay decision)

  Status: Accepted
  Date: 2026-06-12

  ## Context

  ADR-0001 and the 2026-06-08 native-Ideogram-V3 spec locked a "no post-render overlay"
  decision: the QR-code, address band, and Satori real-font text compositors were removed, and
  "the downloaded Ideogram PNG is the final image." The scope of that decision is explicit and
  narrow: **typography stays model-native.** All event text — title, date, venue, lineup — is
  rendered by Ideogram inside the image and verified by the vision text gate.

  The Brand Book feature (spec 2026-06-11) introduces an organizer-uploaded logo that should
  appear on generated posters, with a per-org toggle and a per-generation override. This raises
  the question: does compositing a logo violate the no-overlay decision?

  ## Decision

  **A brand logo is composited deterministically onto accepted posters via pure Java2D. This is a
  scoped carve-out from ADR-0001's no-overlay decision, not a reversal of it.**

  The carve-out is justified on three grounds:

  1. **A logo is not text.** The no-overlay decision's purpose is that *typography is the image* —
     event copy must be model-native so it reads as part of the flyer, not pasted on. A logo is a
     fixed brand mark, not typography the model should invent.

  2. **A diffusion model provably cannot reproduce an exact mark.** Ideogram V3 has no
     logo-placement, vector-aware, or brand-mark feature; style references do style transfer only
     (they extract palette/mood, not the mark). Passing a logo as a style reference would corrupt
     both the vibe aesthetic and the mark. Deterministic compositing is the only faithful option.

  3. **It restores nothing the decision removed.** The removed compositors drew *text*. This draws
     a single raster mark in one fixed corner. The text pipeline stays 100% model-native.

  ### Mechanism

  - Pure Java2D (`BufferedImage` / `Graphics2D` / `AlphaComposite.SrcOver`) — the same toolkit
    `QrImageRenderer` already uses. No new dependency (`pom.xml` unchanged).
  - **Seam:** inside `PosterOrchestrator.accept()` — the single funnel that sets `final_url`, so all
    three acceptance paths (text-gate best-effort, accepted, style soft-fail) get the logo.
  - **Placement:** bottom-right; margin 4% of poster width; logo scaled to max 18% of poster width,
    aspect preserved.
  - **Legibility scrim (required):** the destination corner's mean luminance is sampled and a
    contrasting translucent rounded scrim is drawn behind the logo, so a white mark on a light
    corner (or dark-on-dark) stays legible — Ideogram backgrounds are unpredictable per generation.
  - **Storage:** the composited PNG is a **second** `PosterImageStorage.writePng` call →
    `final_url` = composited URL; `raw_url` keeps the un-composited render. `final_url` is canonical
    for display/download; `raw_url` is the re-edit/remix input.

  ### Failure isolation (hard rule)

  The composite is wrapped in try/catch. Any failure (logo URL 404, decode error, OOM) logs, emits
  a Sentry **warning**, sets `logo_composite_status = 'FAILED'`, and ships the un-composited poster
  (`final_url = raw_url`). **Generation never fails over a decoration.** Toggle off / no logo →
  `SKIPPED`.

  ## Consequences

  ### Positive
  - Faithful brand mark on every accepted poster with zero extra API calls (CPU-only, in-process).
  - The no-text-overlay architecture is untouched; the boundary is now documented, not implied.
  - `brand_snapshot` makes "why does this poster (not) have a logo?" auditable, and corrective
    remixes read the snapshot, not live org state.

  ### Negative
  - One extra storage object per accepted variant (the composited PNG). `raw_url` and `final_url`
    now genuinely differ for branded generations; every reader was audited (gallery/download use
    `final_url`; remix re-edit uses the raw render bytes).
  - The scrim is a heuristic; a busy corner can still reduce contrast. Acceptable for Phase 1; a
    `logo_placement` enum can refine placement later.

  ## Alternatives considered
  - **Inject the logo as an Ideogram style reference.** Rejected — style transfer cannot reproduce a
    mark and would displace the vibe's curated references (Ideogram accepts exactly one style control).
  - **Composite client-side in the browser.** Rejected — loses the single durable R2 URL and makes
    downloads/share inconsistent across surfaces.
  - **Bake the logo into the prompt as text.** Rejected — a logo is not text; this is exactly what
    the no-overlay decision keeps model-native, and a mark is not reproducible as typography.
  ```

- [ ] Confirm the file is present:
  ```bash
  cd /Users/ivan/imin/imin-api && ls docs/decisions/
  ```
  Expected output:
  ```
  ADR-0001-ideogram-direct.md
  ADR-0002-brand-logo-composite.md
  ```

- [ ] Commit:
  ```bash
  cd /Users/ivan/imin/imin-api && git add docs/decisions/ADR-0002-brand-logo-composite.md && git commit -m "$(cat <<'EOF'
Add ADR-0002: brand-logo composite carve-out from the no-overlay decision

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
  ```

## Task 2.8 — Final verification + merge-readiness (PR 2)

**Files:** none (verification only)

- [ ] Run the full new-and-touched test set together — expected all PASS:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest='BrandColumnsPersistenceTest,BrandSnapshotTest,BrandLogoCompositorTest,PosterOrchestratorTest,ConceptStudioServiceTest,OrgMediaServiceTest' -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Run the full suite to confirm no regression (the wiring touched the live generation path):
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw clean test
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```
  If any test fails, use superpowers:systematic-debugging before proceeding.

- [ ] Smoke-confirm backward compatibility (the gate for the deploy): with an empty brand book, generation must behave as before. This is covered by `emptyBrandBook_keeps_accentColor_null_regression_guard_for_line195` and `noBrand_skipsComposite_finalEqualsRaw`. Re-run them explicitly:
  ```bash
  cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest='ConceptStudioServiceTest#emptyBrandBook_keeps_accentColor_null_regression_guard_for_line195,PosterOrchestratorTest#noBrand_skipsComposite_finalEqualsRaw' -q
  ```
  Expected output ends with:
  ```
  BUILD SUCCESS
  ```

- [ ] Confirm the branch is ready (clean tree, all commits present):
  ```bash
  cd /Users/ivan/imin/imin-api && git status --short && git log --oneline feat/brand-book-generation ^master | head
  ```
  Expected: empty `git status` (clean), and the log lists the commits from Tasks 2.1–2.7.

- [ ] **STOP. PR 2 is merge-ready.** Hand off for review/merge/deploy. After PR 2 deploys, the FE runs its own `api:sync` (FE PR 2) to pick up `ConceptRequest.logoOnPosters`. ADR-0002 is in the tree. The reader audit (final_url canonical for display/download; raw_url for remix) is documented in Task 2.4 and ADR-0002.
