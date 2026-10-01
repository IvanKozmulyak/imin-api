package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.dto.AudienceMemberClass;
import com.imin.iminapi.audience.dto.AudienceMetricsDto;
import com.imin.iminapi.audience.dto.ConsentHistoryEntry;
import com.imin.iminapi.audience.dto.MemberDto;
import com.imin.iminapi.audience.dto.MemberPage;
import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.AudienceMetricsService;
import com.imin.iminapi.audience.service.AudienceService;
import com.imin.iminapi.audience.service.AudienceService.MemberListRequest;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.audience.service.MemberListQuery;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceImport;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.AudienceImportRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The Audience read model: member list filters and sorts, member fields, metrics and the consent trail.
 * Run on H2 ({@link AudienceReadModelTest}) and Postgres 17 ({@link AudienceReadModelPostgresTest}):
 * the list and the ConsentGate subquery are native SQL.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
abstract class AudienceReadModelScenarios {

    static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    static final Instant RECENT = NOW.minus(10, ChronoUnit.DAYS);
    static final String HOUSE = "house & techno";
    static final String POP = "pop";

    @Autowired FanFeatureRepository fanRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AudiencePlanLogic shippedLogic;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired AudienceImportRepository importRepo;
    @Autowired ImportRowProvenanceRepository provenanceRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired MemberListQuery memberListQuery;
    @Autowired DsarService dsarService;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AuditLogger auditLogger;

    UUID orgA;
    UUID orgB;
    private final List<UUID> orgs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orgA = org();
        orgB = org();
    }

    @AfterEach
    void tearDown() {
        for (UUID org : orgs) {
            List<UUID> mids = jdbc.queryForList("select membership_id from memberships where org_id = ?", UUID.class, org);
            List<UUID> cids = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, org);
            jdbc.update("delete from campaign_recipients where campaign_id in (select id from campaigns where org_id = ?)", org);
            jdbc.update("delete from campaigns where org_id = ?", org);
            for (UUID mid : mids) {
                jdbc.update("delete from consent_records where membership_id = ?", mid);
                jdbc.update("delete from import_row_provenance where membership_id = ?", mid);
                jdbc.update("delete from fan_features where membership_id = ?", mid);
            }
            jdbc.update("delete from audience_imports where org_id = ?", org);
            jdbc.update("delete from suppression_entries where org_id = ?", org);
            jdbc.update("delete from memberships where org_id = ?", org);
            for (UUID cid : cids) jdbc.update("delete from consumers where consumer_id = ?", cid);
            jdbc.update("delete from tickets where order_id in (select id from orders where org_id = ?)", org);
            jdbc.update("delete from orders where org_id = ?", org);
            jdbc.update("delete from events where org_id = ?", org);
            jdbc.update("delete from users where org_id = ?", org);
            jdbc.update("delete from organizations where id = ?", org);
        }
        orgs.clear();
    }

    // ── members list: sort ────────────────────────────────────────────────

    @Test
    void sort_default_isCreatedAtNewestFirst_andPagesWithoutGapsOrRepeats() {
        UUID old = member(orgA);
        UUID mid = member(orgA);
        UUID recent = member(orgA);
        setCreatedAt(old, NOW.minus(3, ChronoUnit.DAYS));
        setCreatedAt(mid, NOW.minus(2, ChronoUnit.DAYS).plusNanos(123_000));
        setCreatedAt(recent, NOW.minus(1, ChronoUnit.DAYS));

        assertThat(allPages(null, 1)).containsExactly(recent, mid, old);
        assertThat(allPages("created_at", 2)).containsExactly(recent, mid, old);
    }

    @Test
    void sort_spendMinor_ordersBySpend_tiesByIdDescending() {
        UUID low = member(orgA);
        UUID tieA = member(orgA);
        UUID tieB = member(orgA);
        UUID high = member(orgA);
        set(low, "spend_minor", 100L);
        set(tieA, "spend_minor", 500L);
        set(tieB, "spend_minor", 500L);
        set(high, "spend_minor", 900L);
        List<UUID> ties = idsDescending(tieA, tieB);

        assertThat(allPages("spend_minor", 1)).containsExactly(high, ties.get(0), ties.get(1), low);
    }

    @Test
    void sort_lastPurchase_putsNeverPurchasedLast_acrossPages() {
        UUID older = member(orgA);
        UUID newer = member(orgA);
        UUID never1 = member(orgA);
        UUID never2 = member(orgA);
        setTimestamp(older, "last_purchase", NOW.minus(20, ChronoUnit.DAYS));
        setTimestamp(newer, "last_purchase", NOW.minus(2, ChronoUnit.DAYS));
        List<UUID> nevers = idsDescending(never1, never2);

        assertThat(allPages("last_purchase", 1)).containsExactly(newer, older, nevers.get(0), nevers.get(1));
    }

    @Test
    void sort_events_ordersByEventCount() {
        UUID one = member(orgA);
        UUID five = member(orgA);
        UUID three = member(orgA);
        set(one, "events", 1L);
        set(five, "events", 5L);
        set(three, "events", 3L);

        assertThat(allPages("events", 2)).containsExactly(five, three, one);
    }

    @Test
    void sort_unknown_isBadRequest() {
        assertThatThrownBy(() -> service(true).listMembers(orgA, request(null, "name", null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void cursor_fromAnotherSort_isBadRequest() {
        member(orgA);
        member(orgA);
        String spendCursor = service(true).listMembers(orgA, request(null, "spend_minor", null, null, null, 1)).nextCursor();
        assertThat(spendCursor).isNotNull();

        assertThatThrownBy(() -> service(true).listMembers(orgA, request(spendCursor, "events", null, null, null, 1)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spend_minor", "last_purchase", "events"})
    void cursor_inTheOriginalMillisFormat_withAnotherSort_isBadRequest(String sort) {
        String legacy = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (NOW.toEpochMilli() + "," + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service(true).listMembers(orgA, request(legacy, sort, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"created_at", "spend_minor", "events"})
    void cursor_withAnEmptyKey_forASortThatIsNeverNull_isBadRequest(String sort) {
        String empty = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v2," + sort + ",," + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service(true).listMembers(orgA, request(empty, sort, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void cursor_inTheOriginalMillisFormat_stillPagesByCreatedAt() {
        UUID first = member(orgA);
        UUID second = member(orgA);
        setCreatedAt(first, NOW.minus(1, ChronoUnit.DAYS));
        setCreatedAt(second, NOW.minus(2, ChronoUnit.DAYS));
        String legacy = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (NOW.minus(1, ChronoUnit.DAYS).toEpochMilli() + "," + first).getBytes(StandardCharsets.UTF_8));

        assertThat(ids(service(true).listMembers(orgA, request(legacy, null, null, null, null)))).containsExactly(second);
    }

    // ── members list: filters ─────────────────────────────────────────────

    @Test
    void guestClass_filter_returnsOnlyThatClass() {
        UUID loyal = member(orgA);
        UUID repeat = member(orgA);
        member(orgA); // no feature row
        features(loyal, "loyal", 3, "{}");
        features(repeat, "repeat", 2, "{}");

        assertThat(ids(service(true).listMembers(orgA, request(null, null, "loyal", null, null)))).containsExactly(loyal);
    }

    @Test
    void guestClass_none_matchesNoneRowsAndMembersWithoutRows_likeClassCounts() {
        UUID noneRow = member(orgA);
        UUID noRow = member(orgA);
        UUID loyal = member(orgA);
        features(noneRow, "none", 0, "{}");
        features(loyal, "loyal", 3, "{}");

        assertThat(ids(service(true).listMembers(orgA, request(null, null, "none", null, null))))
                .containsExactlyInAnyOrder(noneRow, noRow);
        assertThat(metrics(true).compute(orgA).classCounts()).containsEntry("none", 2L).containsEntry("loyal", 1L);
    }

    @Test
    void guestClass_unknown_isBadRequest() {
        assertThatThrownBy(() -> service(true).listMembers(orgA, request(null, null, "vip", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void genre_filter_returnsMembersWhoseTasteHasTheBucket() {
        UUID house = member(orgA);
        UUID popOnly = member(orgA);
        member(orgA); // no feature row
        features(house, "loyal", 3, "{\"house & techno\":0.6,\"pop\":0.4}");
        features(popOnly, "repeat", 2, "{\"pop\":1.0}");

        assertThat(ids(service(true).listMembers(orgA, request(null, null, null, HOUSE, null)))).containsExactly(house);
    }

    @Test
    void genre_outsideTheEightBuckets_isBadRequest() {
        assertThatThrownBy(() -> service(true).listMembers(orgA, request(null, null, null, "techno", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void mailable_true_returnsExactlyConsentGateMailable_false_returnsTheRest() {
        UUID mailable = mailableMember(orgA);
        UUID legacy = legacyMember(orgA);
        UUID noBasis = member(orgA);

        assertThat(gate().mailableMembershipIds(orgA)).containsExactly(mailable);
        assertThat(ids(service(true).listMembers(orgA, request(null, null, null, null, true)))).containsExactly(mailable);
        assertThat(ids(service(true).listMembers(orgA, request(null, null, null, null, false))))
                .containsExactlyInAnyOrder(legacy, noBasis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"guestClass", "genre", "mailable"})
    void newFilters_areNotFound_whileTheAudiencePlanIsSwitchedOff(String filter) {
        MemberListRequest req = switch (filter) {
            case "guestClass" -> request(null, null, "loyal", null, null);
            case "genre" -> request(null, null, null, HOUSE, null);
            default -> request(null, null, null, null, true);
        };
        assertThatThrownBy(() -> service(false).listMembers(orgA, req))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void filtersCombine_andStayInsideTheOrg() {
        UUID match = mailableMember(orgA);
        features(match, "loyal", 3, "{\"house & techno\":1.0}");
        UUID otherOrg = mailableMember(orgB);
        features(otherOrg, "loyal", 3, "{\"house & techno\":1.0}");

        assertThat(ids(service(true).listMembers(orgA, request(null, "spend_minor", "loyal", HOUSE, true))))
                .containsExactly(match);
    }

    // ── member fields ─────────────────────────────────────────────────────

    @Test
    void member_carriesClassAndTaste_fromFanFeatures() {
        UUID m = member(orgA);
        features(m, "first_timer", 1, "{\"house & techno\":0.75,\"pop\":0.25}");

        MemberDto dto = service(true).getMember(orgA, m);

        assertThat(dto.guestClass()).isEqualTo(AudienceMemberClass.FIRST_TIMER);
        assertThat(dto.taste()).isEqualTo(Map.of(HOUSE, 0.75, POP, 0.25));
    }

    @Test
    void member_withoutFeatureRow_hasNullClassAndTaste_andZeroSends() {
        UUID m = member(orgA);

        MemberDto dto = service(true).getMember(orgA, m);

        assertThat(dto.guestClass()).isNull();
        assertThat(dto.taste()).isNull();
        assertThat(dto.sends30d()).isZero();
    }

    @Test
    void sends30d_countsThisOrgsSentEmailsInTheLast30Days_only() {
        UUID m = member(orgA);
        UUID campaignA = campaign(orgA);
        UUID campaignA2 = campaign(orgA);
        UUID campaignA3 = campaign(orgA);
        UUID campaignA4 = campaign(orgA);
        UUID campaignB = campaign(orgB);
        recipient(campaignA, m, "sent", NOW.minus(3, ChronoUnit.DAYS));
        recipient(campaignA2, m, "delivered", NOW.minus(30, ChronoUnit.DAYS));
        recipient(campaignA3, m, "pending", NOW.minus(1, ChronoUnit.DAYS));
        recipient(campaignA4, m, "sent", NOW.minus(30, ChronoUnit.DAYS).minusSeconds(1));
        recipient(campaignB, m, "sent", NOW.minus(1, ChronoUnit.DAYS));

        assertThat(service(true).getMember(orgA, m).sends30d()).isEqualTo(2);
    }

    @Test
    void memberFields_areNull_whileTheAudiencePlanIsSwitchedOff() {
        UUID m = member(orgA);
        features(m, "loyal", 3, "{\"pop\":1.0}");

        MemberDto dto = service(false).getMember(orgA, m);
        MemberPage page = service(false).listMembers(orgA, request(null, null, null, null, null));

        assertThat(dto.guestClass()).isNull();
        assertThat(dto.taste()).isNull();
        assertThat(dto.sends30d()).isNull();
        assertThat(page.items().get(0).guestClass()).isNull();
    }

    @Test
    void listItems_carryTheSameMemberFields() {
        UUID m = member(orgA);
        features(m, "lapsing", 1, "{\"pop\":1.0}");

        MemberDto item = service(true).listMembers(orgA, request(null, null, null, null, null)).items().get(0);

        assertThat(item.guestClass()).isEqualTo(AudienceMemberClass.LAPSING);
        assertThat(item.taste()).containsEntry(POP, 1.0);
        assertThat(item.sends30d()).isZero();
    }

    // ── metrics ───────────────────────────────────────────────────────────

    @Test
    void newLast30Days_countsExactly30DaysAgo_notOneSecondEarlier() {
        UUID in = member(orgA);
        UUID out = member(orgA);
        UUID today = member(orgA);
        setCreatedAt(in, NOW.minus(30, ChronoUnit.DAYS));
        setCreatedAt(out, NOW.minus(30, ChronoUnit.DAYS).minusSeconds(1));
        setCreatedAt(today, NOW.minusSeconds(60));

        assertThat(metrics(true).compute(orgA).newLast30Days()).isEqualTo(2L);
    }

    @Test
    void cameBackPct_isMembersWithTwoPaidOrdersOverMembersWithOne_fromPaidOrdersOnly() {
        UUID none = member(orgA);
        UUID one = member(orgA);
        UUID two = member(orgA);
        UUID three = member(orgA);
        features(none, "none", 0, "{}");
        features(one, "first_timer", 1, "{}");
        features(two, "repeat", 2, "{}");
        features(three, "loyal", 3, "{}");
        set(none, "orders", 5L); // free or refunded orders on the membership never count

        AudienceMetricsDto dto = metrics(true).compute(orgA);

        assertThat(dto.cameBackPct().n()).isEqualTo(3);
        assertThat(dto.cameBackPct().mid()).isEqualTo(66.7);
        assertThat(dto.cameBackPct().low()).isLessThan(66.7);
        assertThat(dto.cameBackPct().high()).isGreaterThan(66.7);
    }

    @Test
    void showedUpPct_countsHeldTicketsOfPaidOrdersForEndedEventsOfThisOrg() {
        UUID ended = event(orgA, NOW.minus(5, ChronoUnit.DAYS));
        UUID upcoming = event(orgA, NOW.plus(5, ChronoUnit.DAYS));
        UUID paid = order(orgA, ended, "stripe", 3000, false);
        ticket(paid, ended, "redeemed");
        ticket(paid, ended, "issued");
        ticket(paid, ended, "refunded");
        ticket(order(orgA, ended, "free", 0, false), ended, "redeemed");
        ticket(order(orgA, ended, "stripe", 3000, true), ended, "redeemed");
        ticket(order(orgA, upcoming, "stripe", 3000, false), upcoming, "issued");
        UUID endedB = event(orgB, NOW.minus(5, ChronoUnit.DAYS));
        ticket(order(orgB, endedB, "stripe", 3000, false), endedB, "issued");

        AudienceMetricsDto dto = metrics(true).compute(orgA);

        assertThat(dto.showedUpPct().n()).isEqualTo(2);
        assertThat(dto.showedUpPct().mid()).isEqualTo(50.0);
    }

    @Test
    void rates_areNull_withNothingToDivideBy() {
        member(orgA);

        AudienceMetricsDto dto = metrics(true).compute(orgA);

        assertThat(dto.showedUpPct()).isNull();
        assertThat(dto.cameBackPct()).isNull();
        assertThat(dto.tasteShares()).isNull();
        assertThat(dto.tasteMembers()).isZero();
    }

    @Test
    void mailable_equalsTheConsentGateCount_withLegacyCountedSeparately() {
        mailableMember(orgA);
        mailableMember(orgA);
        legacyMember(orgA);
        member(orgA);
        mailableMember(orgB);
        ConsentGate.Breakdown plan = gate().breakdown(orgA);

        AudienceMetricsDto dto = metrics(true).compute(orgA);

        assertThat(dto.mailable()).isEqualTo(plan.mailable()).isEqualTo(2);
        assertThat(dto.legacyNotMailable()).isEqualTo(plan.legacyNotMailable()).isEqualTo(1);
        assertThat(dto.exclusions()).isEqualTo(plan.exclusions());
        assertThat(dto.mailableByBasis()).containsEntry("explicit", 2).containsEntry("soft_opt_in", 0);
        assertThat(dto.mailable() + dto.exclusions().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo((int) dto.totalMembers());
    }

    @Test
    void mailableByBasis_sumsToMailable_withSoftOptInAtZero_whileTheFlagIsOff() {
        mailableMember(orgA);
        softOptInMember(orgA);
        legacyMember(orgA);

        AudienceMetricsDto off = metrics(true).compute(orgA);
        AudienceMetricsDto on = metrics(readModel(gate(true))).compute(orgA);

        assertThat(off.mailableByBasis()).containsOnlyKeys("explicit", "soft_opt_in")
                .containsEntry("explicit", 1).containsEntry("soft_opt_in", 0);
        assertThat(off.mailableByBasis().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(off.mailable());
        assertThat(on.mailableByBasis()).containsEntry("explicit", 1).containsEntry("soft_opt_in", 1);
        assertThat(on.mailableByBasis().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(on.mailable());
    }

    @Test
    void mailableByBasis_readsTheGatesConsent_notTheMembershipsDenormalizedBasis() {
        UUID m = mailableMember(orgA);
        jdbc.update("update memberships set consent_basis = 'soft_opt_in' where membership_id = ?", m);

        assertThat(metrics(true).compute(orgA).mailableByBasis())
                .containsEntry("explicit", 1).containsEntry("soft_opt_in", 0);
    }

    @Test
    void tasteShares_foldEveryPage_whenTheOrgHasMoreTastesThanOnePage() {
        int n = AudienceReadModel.TASTE_PAGE + 1;
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID c = UUID.randomUUID();
            jdbc.update("insert into consumers (consumer_id, normalized_email) values (?, ?)", c, "rm-" + c + "@example.com");
            UUID mid = UUID.randomUUID();
            jdbc.update("insert into memberships (membership_id, org_id, consumer_id) values (?, ?, ?)", mid, orgA, c);
            rows.add(new Object[] {mid, orgA, i == 0 ? "{\"pop\":1.0}" : "{\"house & techno\":1.0}"});
        }
        jdbc.batchUpdate("insert into fan_features (membership_id, org_id, taste, class, paid_orders, logic_version) "
                + "values (?, ?, ?, 'loyal', 1, 1)", rows);

        AudienceMetricsDto dto = metrics(true).compute(orgA);

        assertThat(dto.tasteMembers()).isEqualTo((long) n);
        assertThat(dto.tasteShares().get(POP)).isCloseTo(1.0 / n, within(1e-9));
        assertThat(dto.tasteShares().get(HOUSE)).isCloseTo((n - 1.0) / n, within(1e-9));
    }

    @Test
    void classCounts_coverEveryClass_andSumToTheMemberCount() {
        features(member(orgA), "loyal", 3, "{}");
        features(member(orgA), "dormant", 1, "{}");
        features(member(orgA), "imported", 0, "{}");
        member(orgA);

        AudienceMetricsDto dto = metrics(true).compute(orgA);

        assertThat(dto.classCounts()).containsOnlyKeys("loyal", "repeat", "first_timer", "lapsing", "dormant",
                "imported", "none");
        assertThat(dto.classCounts()).containsEntry("loyal", 1L).containsEntry("dormant", 1L)
                .containsEntry("imported", 1L).containsEntry("none", 1L).containsEntry("repeat", 0L);
        assertThat(dto.classCounts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(dto.totalMembers());
    }

    @Test
    void tasteShares_averageThisOrgsTaste_overAllEightBuckets() {
        features(member(orgA), "loyal", 3, "{\"house & techno\":1.0}");
        features(member(orgA), "repeat", 2, "{\"house & techno\":0.5,\"pop\":0.5}");
        features(member(orgA), "none", 0, "{}");
        features(member(orgB), "loyal", 3, "{\"jazz & acoustic\":1.0}");

        AudienceMetricsDto dto = metrics(true).compute(orgA);

        assertThat(dto.tasteShares()).containsOnlyKeys(shippedLogic.genres().whitelist());
        assertThat(dto.tasteShares().get(HOUSE)).isCloseTo(0.75, within(1e-9));
        assertThat(dto.tasteShares().get(POP)).isCloseTo(0.25, within(1e-9));
        assertThat(dto.tasteShares().get("jazz & acoustic")).isZero();
        assertThat(dto.tasteShares().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-9));
        assertThat(dto.tasteMembers()).isEqualTo(2L);
    }

    @Test
    void newMetrics_areNull_whileTheAudiencePlanIsSwitchedOff() {
        mailableMember(orgA);

        AudienceMetricsDto dto = metrics(false).compute(orgA);

        assertThat(dto.totalMembers()).isEqualTo(1);
        assertThat(dto.newLast30Days()).isNull();
        assertThat(dto.showedUpPct()).isNull();
        assertThat(dto.cameBackPct()).isNull();
        assertThat(dto.mailable()).isNull();
        assertThat(dto.legacyNotMailable()).isNull();
        assertThat(dto.mailableByBasis()).isNull();
        assertThat(dto.exclusions()).isNull();
        assertThat(dto.classCounts()).isNull();
        assertThat(dto.tasteShares()).isNull();
        assertThat(dto.tasteMembers()).isNull();
    }

    // ── consent history ───────────────────────────────────────────────────

    @Test
    void consentHistory_carriesVersionOrderLocaleAndLegacyFlag() {
        UUID m = member(orgA);
        UUID ev = event(orgA, NOW.plus(3, ChronoUnit.DAYS));
        UUID orderId = order(orgA, ev, "stripe", 3000, false);
        jdbc.update("update orders set buyer_locale = 'fr' where id = ?", orderId);
        consent(m, "subscribed", "explicit", "checkout", "checkout-v0", orderId, RECENT.minusSeconds(30));
        consent(m, "subscribed", "explicit", "door_qr", "door-v1", null, RECENT.minusSeconds(20));
        consent(m, "unsubscribed", null, "one_click", null, null, RECENT.minusSeconds(10));
        UUID imported = member(orgA);
        consent(imported, "subscribed", "explicit", "organizer_import_row", "attest-v1", null, RECENT);
        provenance(orgA, imported);

        List<ConsentHistoryEntry> trail = dsarService.consentHistory(orgA, m);

        ConsentHistoryEntry checkout = byOrigin(trail, "checkout");
        assertThat(checkout.textVersion()).isEqualTo("checkout-v0");
        assertThat(checkout.orderId()).isEqualTo(orderId);
        assertThat(checkout.locale()).isEqualTo("fr");
        assertThat(checkout.legacy()).isTrue();
        ConsentHistoryEntry door = byOrigin(trail, "door_qr");
        assertThat(door.locale()).isNull();
        assertThat(door.legacy()).isFalse();
        assertThat(byOrigin(trail, "one_click").legacy()).isFalse();
        assertThat(dsarService.consentHistory(orgA, imported).get(0).legacy()).isFalse();
    }

    @Test
    void consentHistory_neverShowsTheLocaleOfAnotherOrgsOrder() {
        UUID m = member(orgA);
        UUID evB = event(orgB, NOW.plus(3, ChronoUnit.DAYS));
        UUID foreignOrder = order(orgB, evB, "stripe", 3000, false);
        jdbc.update("update orders set buyer_locale = 'uk' where id = ?", foreignOrder);
        consent(m, "subscribed", "explicit", "checkout", "checkout-v0", foreignOrder, RECENT);

        assertThat(dsarService.consentHistory(orgA, m).get(0).locale()).isNull();
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    static AudiencePlanProperties props(boolean enabled) {
        AudiencePlanProperties p = new AudiencePlanProperties();
        p.setEnabled(enabled);
        return p;
    }

    ConsentGate gate() {
        return gate(false);
    }

    ConsentGate gate(boolean softOptIn) {
        AudiencePlanProperties p = props(true);
        p.setSoftOptInEnabled(softOptIn);
        return new ConsentGate(fanRepo, orgRepo, shippedLogic, p, clock());
    }

    AudienceReadModel readModel() {
        return readModel(gate());
    }

    AudienceReadModel readModel(ConsentGate gate) {
        return new AudienceReadModel(fanRepo, membershipRepo, ticketRepo, recipientRepo, gate, shippedLogic, clock());
    }

    AudienceMetricsService metrics(boolean enabled) {
        return new AudienceMetricsService(membershipRepo, new AudiencePlanAccess(props(enabled)), readModel(),
                recipientRepo);
    }

    AudienceMetricsService metrics(AudienceReadModel readModel) {
        return new AudienceMetricsService(membershipRepo, new AudiencePlanAccess(props(true)), readModel,
                recipientRepo);
    }

    AudienceService service(boolean enabled) {
        return new AudienceService(membershipRepo, consumerRepo, suppressionRepo, memberListQuery,
                new AudiencePlanAccess(props(enabled)), shippedLogic, gate(), readModel());
    }

    static MemberListRequest request(String cursor, String sort, String guestClass, String genre, Boolean mailable) {
        return request(cursor, sort, guestClass, genre, mailable, 50);
    }

    static MemberListRequest request(String cursor, String sort, String guestClass, String genre, Boolean mailable,
                                     int limit) {
        return new MemberListRequest(cursor, limit, null, null, sort, guestClass, genre, mailable);
    }

    List<UUID> allPages(String sort, int limit) {
        List<UUID> out = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 20; i++) {
            MemberPage page = service(true).listMembers(orgA, request(cursor, sort, null, null, null, limit));
            out.addAll(ids(page));
            cursor = page.nextCursor();
            if (cursor == null) break;
        }
        assertThat(new HashSet<>(out)).hasSameSizeAs(out);
        return out;
    }

    static List<UUID> ids(MemberPage page) {
        return page.items().stream().map(d -> UUID.fromString(d.membershipId())).toList();
    }

    /** The two ids in the order the database sorts membership_id descending. */
    List<UUID> idsDescending(UUID a, UUID b) {
        return jdbc.queryForList("select membership_id from memberships where membership_id in (?, ?) "
                + "order by membership_id desc", UUID.class, a, b);
    }

    UUID org() {
        Organization o = new Organization();
        o.setName("Read Model Org");
        o.setSlug("rm-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("rm@example.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    UUID member(UUID orgId) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("rm-" + UUID.randomUUID() + "@example.com");
        c = consumerRepo.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        return membershipRepo.save(m).getMembershipId();
    }

    /** Explicit door-QR consent with a text version, given recently: mailable under the shipped logic. */
    UUID mailableMember(UUID orgId) {
        UUID m = member(orgId);
        consent(m, "subscribed", "explicit", "door_qr", "door-v1", null, RECENT);
        return m;
    }

    /** Checkout consent without an organizer-named version: legacy_unproven under the shipped logic. */
    UUID legacyMember(UUID orgId) {
        UUID m = member(orgId);
        consent(m, "subscribed", "explicit", "checkout", null, null, RECENT);
        return m;
    }

    /** Soft opt-in at this org's own paid checkout: mailable only while the soft opt-in flag is on. */
    UUID softOptInMember(UUID orgId) {
        UUID m = member(orgId);
        UUID ev = event(orgId, NOW.minus(5, ChronoUnit.DAYS));
        UUID paid = order(orgId, ev, "stripe", 3000, false);
        ticket(paid, ev, Ticket.STATE_ISSUED);
        consent(m, "subscribed", "soft_opt_in", "checkout", null, paid, RECENT);
        return m;
    }

    void consent(UUID mid, String status, String basis, String source, String textVersion, UUID orderId, Instant at) {
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(mid);
        r.setStatus(status);
        r.setLawfulBasis(basis);
        r.setSource(source);
        r.setProofText("proof");
        r.setTextVersion(textVersion);
        r.setOrderId(orderId);
        r.setOccurredAt(at);
        consentRepo.save(r);
        jdbc.update("update memberships set consent_status = ?, consent_basis = ? where membership_id = ?",
                status, basis, mid);
    }

    void provenance(UUID orgId, UUID mid) {
        AudienceImport imp = new AudienceImport();
        imp.setOrgId(orgId);
        imp = importRepo.save(imp);
        ImportRowProvenance p = new ImportRowProvenance();
        p.setImportId(imp.getId());
        p.setMembershipId(mid);
        p.setRowNumber(1);
        p.setSourcePlatform("shotgun");
        p.setExportDate(LocalDate.parse("2026-09-01"));
        p.setMarketingStatus("opted_in");
        p.setProofRef("export.csv");
        p.setAccepted(true);
        provenanceRepo.save(p);
    }

    void features(UUID mid, String cls, int paidOrders, String taste) {
        UUID orgId = jdbc.queryForObject("select org_id from memberships where membership_id = ?", UUID.class, mid);
        FanFeature f = new FanFeature();
        f.setMembershipId(mid);
        f.setOrgId(orgId);
        f.setFanClass(cls);
        f.setPaidOrders(paidOrders);
        f.setTaste(taste);
        f.setLogicVersion(1);
        fanRepo.save(f);
    }

    void set(UUID mid, String column, long value) {
        jdbc.update("update memberships set " + column + " = ? where membership_id = ?", value, mid);
    }

    void setTimestamp(UUID mid, String column, Instant at) {
        jdbc.update("update memberships set " + column + " = ? where membership_id = ?", Timestamp.from(at), mid);
    }

    void setCreatedAt(UUID mid, Instant at) {
        setTimestamp(mid, "created_at", at);
    }

    UUID campaign(UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into campaigns (id, org_id, channel, name, status) values (?, ?, 'email', 'c', 'sent')",
                id, orgId);
        return id;
    }

    void recipient(UUID campaignId, UUID mid, String status, Instant lastEventAt) {
        jdbc.update("insert into campaign_recipients (id, campaign_id, membership_id, status, last_event_at) "
                + "values (?, ?, ?, ?, ?)", UUID.randomUUID(), campaignId, mid, status, Timestamp.from(lastEventAt));
    }

    UUID event(UUID orgId, Instant startsAt) {
        User owner = new User();
        owner.setEmail("rm-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        owner = userRepo.save(owner);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Read Model Night");
        e.setSlug("rm-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(NOW.minus(30, ChronoUnit.DAYS));
        e.setStartsAt(startsAt);
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return eventRepo.save(e).getId();
    }

    UUID order(UUID orgId, UUID eventId, String paymentMethod, long totalMinor, boolean testMode) {
        Order o = new Order();
        o.setToken("rm-" + UUID.randomUUID());
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setEmail("buyer@example.com");
        o.setTotalMinor(totalMinor);
        o.setCurrency("EUR");
        o.setPaymentMethod(paymentMethod);
        o.setTestMode(testMode);
        return orderRepo.save(o).getId();
    }

    void ticket(UUID orderId, UUID eventId, String state) {
        Ticket t = new Ticket();
        t.setToken("rm-t-" + UUID.randomUUID());
        t.setOrderId(orderId);
        t.setEventId(eventId);
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor(3000);
        t.setState(state);
        ticketRepo.save(t);
    }

    static ConsentHistoryEntry byOrigin(List<ConsentHistoryEntry> trail, String source) {
        return trail.stream().filter(e -> source.equals(e.source())).findFirst().orElseThrow();
    }
}
