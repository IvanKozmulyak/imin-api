package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.MemberDto;
import com.imin.iminapi.audience.dto.MemberPage;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceMetricsService;
import com.imin.iminapi.audience.service.AudienceService;
import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

// Note: MemberDto.membershipId() returns String (UUID.toString()), not UUID.

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres integration test for Audience read queries.
 *
 * <p>Runs against a real Postgres 17 container via Testcontainers.
 * Skips gracefully when Docker is absent ({@code @Testcontainers(disabledWithoutDocker=true)}).
 *
 * <p>The regression canary: before the fix, passing {@code search=null} into
 * the single-method {@code listByOrg} would bind the nullable String as {@code bytea}
 * on Postgres, causing {@code function lower(bytea) does not exist}. H2 silently
 * accepted the same query. These tests would have FAILED before the split into
 * {@code listByOrg} / {@code searchByOrg}.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudiencePostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:17-alpine");

    /**
     * Override the H2 settings baked into src/test/resources/application.yaml so the
     * full Spring context boots against the Testcontainers Postgres, not H2.
     * Also disable docker-compose integration (it is off in test yaml already, but
     * belt-and-suspenders) and keep Flyway enabled so V47-V51 run on the real DB.
     */
    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",            PG::getJdbcUrl);
        r.add("spring.datasource.username",        PG::getUsername);
        r.add("spring.datasource.password",        PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Use "none" not "validate" — Hibernate trips on Postgres CHAR(n) vs varchar pedantry
        // in the auth_sessions.token_hash column; Flyway already guarantees schema correctness.
        r.add("spring.jpa.hibernate.ddl-auto",     () -> "none");
        r.add("spring.jpa.properties.hibernate.dialect",
              () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled",             () -> "true");
        r.add("spring.docker.compose.enabled",     () -> "false");
    }

    // ── beans under test ──────────────────────────────────────────────────────

    @Autowired AudienceService        audienceService;
    @Autowired AudienceMetricsService metricsService;
    @Autowired MembershipRepository   membershipRepo;
    @Autowired ConsumerRepository     consumerRepo;
    @Autowired DataSource             dataSource;

    // ── fixture UUIDs ─────────────────────────────────────────────────────────

    static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    // ── setup / teardown ─────────────────────────────────────────────────────

    @BeforeEach
    void seedData() {
        wipe();
        // Org A: 3 members
        // member-1: "Alice Dupont"  – lifecycle=prospect,  last_purchase=null
        // member-2: "Bob Marley"    – lifecycle=firsttime, last_purchase set
        // member-3: "Carlos Ruiz"   – lifecycle=repeat,    last_purchase set
        // Org B: 1 member (scoping canary)
        seedMembership(ORG_A, "alice@a.com",  "Alice Dupont", "prospect",  null,
                Instant.now().minus(3, ChronoUnit.HOURS));
        seedMembership(ORG_A, "bob@a.com",    "Bob Marley",   "firsttime",
                Instant.now().minus(2, ChronoUnit.DAYS),
                Instant.now().minus(2, ChronoUnit.HOURS));
        seedMembership(ORG_A, "carlos@a.com", "Carlos Ruiz",  "repeat",
                Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.HOURS));
        // Org B member – must never appear in org A queries
        seedMembership(ORG_B, "dana@b.com",   "Dana Other",   "prospect", null,
                Instant.now().minus(30, ChronoUnit.MINUTES));
    }

    @AfterEach
    void cleanup() {
        wipe();
    }

    // =========================================================================
    // Sanity: we are talking to REAL Postgres, not H2
    // =========================================================================

    @Test
    void sanity_connectedToPostgres() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement  s = c.createStatement();
             ResultSet  r = s.executeQuery("select version()")) {
            assertThat(r.next()).isTrue();
            String version = r.getString(1);
            assertThat(version).as("Should be Postgres, not H2").containsIgnoringCase("PostgreSQL");
        }
    }

    // =========================================================================
    // Case A – search=null, lifecycle=null → listByOrg branch, no lower(bytea) error
    // =========================================================================

    @Test
    void caseA_listMembers_nullSearch_nullLifecycle_doesNotThrow() {
        // This is the regression canary. Before the split, Hibernate bound
        // null String as bytea in a single-method query that contained lower(:search),
        // causing "function lower(bytea) does not exist" on Postgres.
        MemberPage page = audienceService.listMembers(ORG_A, null, 50, null, null);
        assertThat(page).isNotNull();
        assertThat(page.items()).hasSize(3);
    }

    @Test
    void caseA_listMembers_nullSearch_withLifecycle_doesNotThrow() {
        // Lifecycle filter with null search – also listByOrg branch
        MemberPage page = audienceService.listMembers(ORG_A, null, 50, "firsttime", null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isEqualTo("Bob Marley");
    }

    // =========================================================================
    // Case B – search set → searchByOrg branch, case-insensitive match
    // =========================================================================

    @Test
    void caseB_listMembers_withSearch_returnsMatchingMember() {
        // "alice" should match "Alice Dupont" case-insensitively via lower()/like
        MemberPage page = audienceService.listMembers(ORG_A, null, 50, null, "alice");
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isEqualTo("Alice Dupont");
    }

    @Test
    void caseB_listMembers_withSearch_upperCase_matchesCaseInsensitively() {
        MemberPage page = audienceService.listMembers(ORG_A, null, 50, null, "CARLOS");
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isEqualTo("Carlos Ruiz");
    }

    @Test
    void caseB_listMembers_withSearch_noMatch_returnsEmpty() {
        MemberPage page = audienceService.listMembers(ORG_A, null, 50, null, "zzznomatch");
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    // =========================================================================
    // Case C – keyset pagination: cursor round-trip, nullable last_purchase safe
    // =========================================================================

    @Test
    void caseC_pagination_cursorRoundTrip_noOverlap() {
        // limit=1 → first page has 1 item and a nextCursor
        MemberPage page1 = audienceService.listMembers(ORG_A, null, 1, null, null);
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.nextCursor()).as("Page 1 must produce a nextCursor").isNotNull();

        String cursor1 = page1.nextCursor();
        String firstId = page1.items().get(0).membershipId(); // String in DTO

        // Page 2
        MemberPage page2 = audienceService.listMembers(ORG_A, cursor1, 1, null, null);
        assertThat(page2.items()).hasSize(1);
        String secondId = page2.items().get(0).membershipId();
        assertThat(secondId).isNotEqualTo(firstId);

        // Page 3 (last: 3 members total, 1 per page)
        MemberPage page3 = audienceService.listMembers(ORG_A, page2.nextCursor(), 1, null, null);
        assertThat(page3.items()).hasSize(1);
        String thirdId = page3.items().get(0).membershipId();
        assertThat(thirdId).isNotIn(firstId, secondId);

        // Page 4 must be empty – no nextCursor on page 3
        assertThat(page3.nextCursor()).isNull();
    }

    @Test
    void caseC_pagination_nullLastPurchaseDoesNotBreakSort() {
        // Alice has last_purchase=null; the sort key is (created_at DESC, membership_id DESC),
        // both non-null, so the null last_purchase must not cause an error in the cursor query.
        MemberPage page1 = audienceService.listMembers(ORG_A, null, 2, null, null);
        assertThat(page1.items()).hasSize(2);
        String cursor = page1.nextCursor();
        assertThat(cursor).isNotNull();

        MemberPage page2 = audienceService.listMembers(ORG_A, cursor, 2, null, null);
        assertThat(page2.items()).hasSize(1); // 3rd member
        // Combined: no duplicate IDs across pages
        List<String> allIds = page1.items().stream().map(MemberDto::membershipId).toList();
        List<String> page2Ids = page2.items().stream().map(MemberDto::membershipId).toList();
        assertThat(page2Ids).doesNotContainAnyElementsOf(allIds);
    }

    // =========================================================================
    // Case D – exportMembersCsv with null search/lifecycle → listByOrg branch
    // =========================================================================

    @Test
    void caseD_exportMembersCsv_nullSearchAndLifecycle_doesNotThrow() {
        List<MemberDto> rows = audienceService.exportMembersCsv(ORG_A, null, null);
        assertThat(rows).hasSize(3);
    }

    @Test
    void caseD_exportMembersCsv_withSearch_filters() {
        List<MemberDto> rows = audienceService.exportMembersCsv(ORG_A, null, "marley");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).name()).isEqualTo("Bob Marley");
    }

    // =========================================================================
    // Case E – tenant scoping: org B's member never leaks into org A queries
    // =========================================================================

    @Test
    void caseE_tenantScoping_orgBMemberNotVisibleToOrgA() {
        MemberPage page = audienceService.listMembers(ORG_A, null, 50, null, null);
        List<String> names = page.items().stream().map(MemberDto::name).toList();
        assertThat(names).doesNotContain("Dana Other");
    }

    @Test
    void caseE_tenantScoping_orgAMembersNotVisibleToOrgB() {
        MemberPage page = audienceService.listMembers(ORG_B, null, 50, null, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isEqualTo("Dana Other");
    }

    // =========================================================================
    // Case F – metrics counts and segment queries run without error on Postgres
    // =========================================================================

    @Test
    void caseF_metricsCompute_doesNotThrow() {
        // Exercises all the count(*) queries + findCreatedSince against real Postgres
        var metrics = metricsService.compute(ORG_A);
        assertThat(metrics.totalMembers()).isEqualTo(3);
        assertThat(metrics.prospects()).isEqualTo(1); // Alice
    }

    @Test
    void caseF_segmentPredicates_runWithoutError() {
        // findRepeats, findVips, findLapsed, findFirstTimers — run on Postgres
        assertThat(membershipRepo.findRepeats(ORG_A)).isNotNull();
        assertThat(membershipRepo.findVips(ORG_A)).isNotNull();
        assertThat(membershipRepo.findLapsed(ORG_A)).isNotNull();
        assertThat(membershipRepo.findFirstTimers(ORG_A)).isNotNull();
        // Bob has lifecycle=firsttime
        assertThat(membershipRepo.findFirstTimers(ORG_A))
                .extracting(Membership::getDisplayName)
                .contains("Bob Marley");
    }

    @Test
    void caseF_countQueries_scopedCorrectly() {
        assertThat(membershipRepo.countByOrgId(ORG_A)).isEqualTo(3);
        assertThat(membershipRepo.countByOrgId(ORG_B)).isEqualTo(1);
        // Bob (events=1) + Carlos (events=2) are buyers; Alice is prospect (events=0)
        assertThat(membershipRepo.countBuyersByOrgId(ORG_A)).isEqualTo(2);
        assertThat(membershipRepo.countProspectsByOrgId(ORG_A)).isEqualTo(1); // Alice only
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void seedMembership(UUID orgId, String email, String displayName,
                                 String lifecycle, Instant lastPurchase, Instant createdAt) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(email);
        c.setDisplayName(displayName);
        consumerRepo.save(c);

        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setDisplayName(displayName);
        m.setLifecycle(lifecycle);
        m.setLastPurchase(lastPurchase);
        // Set events to match lifecycle expectations
        if ("firsttime".equals(lifecycle)) m.setEvents(1);
        if ("repeat".equals(lifecycle))    m.setEvents(2);
        // Override createdAt via reflection-free setter (it's a plain field with @PrePersist)
        // We do this BEFORE save so the trigger sets it — but since we need spread-out
        // created_at for keyset pagination tests, we update after save.
        membershipRepo.save(m);

        // Update created_at directly via JDBC so pagination ordering is deterministic.
        // Use java.sql.Timestamp (not Instant) — the PG JDBC driver can't infer the SQL
        // type for Instant via setObject() without an explicit Types value.
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE memberships SET created_at = ? WHERE membership_id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(createdAt));
            ps.setObject(2, m.getMembershipId());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to set created_at for " + displayName, e);
        }
    }

    private void wipe() {
        try (Connection c = dataSource.getConnection();
             Statement  s = c.createStatement()) {
            s.execute("DELETE FROM suppression_entries");
            s.execute("DELETE FROM consent_records");
            s.execute("DELETE FROM segments");
            s.execute("DELETE FROM memberships");
            s.execute("DELETE FROM consumers");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}
