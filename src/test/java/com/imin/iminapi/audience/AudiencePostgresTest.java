package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.MemberDto;
import com.imin.iminapi.audience.dto.MemberPage;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceMetricsService;
import com.imin.iminapi.audience.service.AudienceService;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// Note: MemberDto.membershipId() returns String (UUID.toString()), not UUID.

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audience read queries on the shared Postgres. The paged member list runs through {@code MemberListQuery};
 * the CSV export through {@code listByOrg}/{@code searchByOrg} (case D).
 *
 * <p>The regression canary (case D): a nullable {@code search} bound into one query with {@code lower(:search)}
 * became {@code bytea} on Postgres ({@code function lower(bytea) does not exist}); H2 accepted it, hence the split.
 */
@IminIntegrationTest
class AudiencePostgresTest {

    @Autowired AudienceService        audienceService;
    @Autowired AudienceMetricsService metricsService;
    @Autowired MembershipRepository   membershipRepo;
    @Autowired ConsumerRepository     consumerRepo;
    @Autowired JdbcTemplate           jdbc;
    @Autowired IminFixtures           fx;
    @Autowired Clock                  clock;

    // Audience tables have no FK to organizations: fresh ids per test keep every query own-org.
    private UUID orgA;
    private UUID orgB;
    private String bobEmail;
    private String danaEmail;
    private Consumer bob;

    @BeforeEach
    void seedData() {
        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
        bobEmail = fx.email("bob");
        danaEmail = fx.email("dana");
        Instant now = clock.instant();
        // Org A: 3 members
        // member-1: "Alice Dupont"  – lifecycle=prospect,  last_purchase=null
        // member-2: "Bob Marley"    – lifecycle=firsttime, last_purchase set
        // member-3: "Carlos Ruiz"   – lifecycle=repeat,    last_purchase set
        // Org B: 1 member (scoping canary)
        seedMembership(orgA, fx.email("alice"), "Alice Dupont", "prospect", null,
                now.minus(3, ChronoUnit.HOURS));
        bob = seedMembership(orgA, bobEmail, "Bob Marley", "firsttime",
                now.minus(2, ChronoUnit.DAYS),
                now.minus(2, ChronoUnit.HOURS));
        seedMembership(orgA, fx.email("carlos"), "Carlos Ruiz", "repeat",
                now.minus(1, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.HOURS));
        // Org B member – must never appear in org A queries
        seedMembership(orgB, danaEmail, "Dana Other", "prospect", null,
                now.minus(30, ChronoUnit.MINUTES));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from memberships where org_id in (?, ?)", orgA, orgB);
    }

    // =========================================================================
    // Case A – paged list (MemberListQuery) with null search
    // =========================================================================

    @Test
    void caseA_listMembers_nullSearch_nullLifecycle_doesNotThrow() {
        // A null search and lifecycle must not reach SQL as an untyped bytea parameter.
        MemberPage page = audienceService.listMembers(orgA, new AudienceService.MemberListRequest(null, 50, null, null, null, null, null, null));
        assertThat(page).isNotNull();
        assertThat(page.items()).hasSize(3);
    }

    @Test
    void caseA_listMembers_nullSearch_withLifecycle_doesNotThrow() {
        // Lifecycle filter with null search
        MemberPage page = audienceService.listMembers(orgA, new AudienceService.MemberListRequest(null, 50, "firsttime", null, null, null, null, null));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isEqualTo("Bob Marley");
    }

    // =========================================================================
    // Case B – search set on the paged list
    // =========================================================================

    /**
     * Name matches case-insensitively and in part; an email matches only exactly (ignoring case and
     * surrounding space) and only for a member of this org.
     */
    @ParameterizedTest
    @ValueSource(strings = {"name-lower", "name-upper", "name-part", "email-exact", "email-part",
            "other-orgs-email", "no-match"})
    void caseB_listMembers_withSearch(String row) {
        String bobDomain = bobEmail.substring(bobEmail.indexOf('@') + 1);
        String search;
        List<String> expected;
        switch (row) {
            case "name-lower" -> { search = "alice"; expected = List.of("Alice Dupont"); }
            case "name-upper" -> { search = "CARLOS"; expected = List.of("Carlos Ruiz"); }
            case "name-part" -> { search = "bob m"; expected = List.of("Bob Marley"); }
            case "email-exact" -> { search = "  " + mixedCase(bobEmail) + " "; expected = List.of("Bob Marley"); }
            case "email-part" -> { search = bobDomain; expected = List.of(); }
            case "other-orgs-email" -> { search = danaEmail; expected = List.of(); }
            case "no-match" -> { search = "zzznomatch"; expected = List.of(); }
            default -> throw new IllegalArgumentException(row);
        }

        MemberPage page = audienceService.listMembers(orgA, new AudienceService.MemberListRequest(null, 50, null, search, null, null, null, null));

        assertThat(page.items()).extracting(MemberDto::name).containsExactlyElementsOf(expected);
        assertThat(page.nextCursor()).isNull();
    }

    /** The CSV export matches the exact email of this org's member only, even when the person is in another org too. */
    @Test
    void caseB_exportMembersCsv_matchesOnlyAnExactEmailOfThisOrg() {
        Membership inB = new Membership();
        inB.setOrgId(orgB);
        inB.setConsumerId(bob.getConsumerId());
        inB.setDisplayName("B Side");
        membershipRepo.save(inB);

        assertThat(audienceService.exportMembersCsv(orgA, null, " " + mixedCase(bobEmail)))
                .extracting(MemberDto::name).containsExactly("Bob Marley");
        assertThat(audienceService.exportMembersCsv(orgA, null, bobEmail.substring(bobEmail.indexOf('@') + 1)))
                .isEmpty();
    }

    // =========================================================================
    // Case C – keyset pagination: cursor round-trip, nullable last_purchase safe
    // =========================================================================

    @Test
    void caseC_pagination_cursorRoundTrip_noOverlap() {
        // limit=1 → first page has 1 item and a nextCursor
        MemberPage page1 = audienceService.listMembers(orgA, new AudienceService.MemberListRequest(null, 1, null, null, null, null, null, null));
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.nextCursor()).as("Page 1 must produce a nextCursor").isNotNull();

        String cursor1 = page1.nextCursor();
        String firstId = page1.items().get(0).membershipId(); // String in DTO

        // Page 2
        MemberPage page2 = audienceService.listMembers(orgA, new AudienceService.MemberListRequest(cursor1, 1, null, null, null, null, null, null));
        assertThat(page2.items()).hasSize(1);
        String secondId = page2.items().get(0).membershipId();
        assertThat(secondId).isNotEqualTo(firstId);

        // Page 3 (last: 3 members total, 1 per page)
        MemberPage page3 = audienceService.listMembers(orgA, new AudienceService.MemberListRequest(page2.nextCursor(), 1, null, null, null, null, null, null));
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
        MemberPage page1 = audienceService.listMembers(orgA, new AudienceService.MemberListRequest(null, 2, null, null, null, null, null, null));
        assertThat(page1.items()).hasSize(2);
        String cursor = page1.nextCursor();
        assertThat(cursor).isNotNull();

        MemberPage page2 = audienceService.listMembers(orgA, new AudienceService.MemberListRequest(cursor, 2, null, null, null, null, null, null));
        assertThat(page2.items()).hasSize(1); // 3rd member
        // Combined: no duplicate IDs across pages
        List<String> allIds = page1.items().stream().map(MemberDto::membershipId).toList();
        List<String> page2Ids = page2.items().stream().map(MemberDto::membershipId).toList();
        assertThat(page2Ids).doesNotContainAnyElementsOf(allIds);
    }

    // =========================================================================
    // Case D – exportMembersCsv: null search → listByOrg, search → searchByOrg (the lower(bytea) canary)
    // =========================================================================

    @Test
    void caseD_exportMembersCsv_nullSearchAndLifecycle_doesNotThrow() {
        List<MemberDto> rows = audienceService.exportMembersCsv(orgA, null, null);
        assertThat(rows).hasSize(3);
    }

    @Test
    void caseD_exportMembersCsv_withSearch_filters() {
        List<MemberDto> rows = audienceService.exportMembersCsv(orgA, null, "marley");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).name()).isEqualTo("Bob Marley");
    }

    // =========================================================================
    // Case E – tenant scoping: org B's member never leaks into org A queries
    // =========================================================================

    @Test
    void caseE_tenantScoping_orgBMemberNotVisibleToOrgA() {
        MemberPage page = audienceService.listMembers(orgA, new AudienceService.MemberListRequest(null, 50, null, null, null, null, null, null));
        List<String> names = page.items().stream().map(MemberDto::name).toList();
        assertThat(names).doesNotContain("Dana Other");
    }

    @Test
    void caseE_tenantScoping_orgAMembersNotVisibleToOrgB() {
        MemberPage page = audienceService.listMembers(orgB, new AudienceService.MemberListRequest(null, 50, null, null, null, null, null, null));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isEqualTo("Dana Other");
    }

    // =========================================================================
    // Case F – metrics counts and segment queries run without error on Postgres
    // =========================================================================

    @Test
    void caseF_metricsCompute_doesNotThrow() {
        // Exercises all the count(*) queries + findCreatedSince against real Postgres
        var metrics = metricsService.compute(orgA);
        assertThat(metrics.totalMembers()).isEqualTo(3);
        assertThat(metrics.prospects()).isEqualTo(1); // Alice
    }

    @Test
    void caseF_segmentPredicates_runWithoutError() {
        // findRepeats, findVips, findLapsed, findFirstTimers — run on Postgres
        assertThat(membershipRepo.findRepeats(orgA)).isNotNull();
        assertThat(membershipRepo.findVips(orgA)).isNotNull();
        assertThat(membershipRepo.findLapsed(orgA)).isNotNull();
        assertThat(membershipRepo.findFirstTimers(orgA)).isNotNull();
        // Bob has lifecycle=firsttime
        assertThat(membershipRepo.findFirstTimers(orgA))
                .extracting(Membership::getDisplayName)
                .contains("Bob Marley");
    }

    @Test
    void caseF_countQueries_scopedCorrectly() {
        assertThat(membershipRepo.countByOrgId(orgA)).isEqualTo(3);
        assertThat(membershipRepo.countByOrgId(orgB)).isEqualTo(1);
        // Bob (events=1) + Carlos (events=2) are buyers; Alice is prospect (events=0)
        assertThat(membershipRepo.countBuyersByOrgId(orgA)).isEqualTo(2);
        assertThat(membershipRepo.countProspectsByOrgId(orgA)).isEqualTo(1); // Alice only
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The address as a person might type it: capitalised, domain upper-cased. */
    private static String mixedCase(String address) {
        int at = address.indexOf('@');
        return Character.toUpperCase(address.charAt(0)) + address.substring(1, at)
                + address.substring(at).toUpperCase(Locale.ROOT);
    }

    private Consumer seedMembership(UUID orgId, String email, String displayName,
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
        membershipRepo.save(m);

        // Spread created_at so keyset pagination ordering is deterministic.
        jdbc.update("UPDATE memberships SET created_at = ? WHERE membership_id = ?",
                Timestamp.from(createdAt), m.getMembershipId());
        return c;
    }
}
