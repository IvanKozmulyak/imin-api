package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.SegmentDto;
import com.imin.iminapi.audience.dto.SegmentResolveDto;
import com.imin.iminapi.audience.dto.SegmentRule;
import com.imin.iminapi.audience.dto.SegmentRuleGroup;
import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The segment rule grammar: groups, the new fields, validation and ConsentGate exclusion reasons. */
@IminIntegrationTest
class SegmentRuleGrammarTest {

    @Autowired SegmentService segmentService;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired FanFeatureRepository fanRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal principal;
    private final List<UUID> orgs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orgA = org();
        orgB = org();
        principal = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        // Consumers can be shared by both orgs, so they go only after every org's memberships.
        java.util.Set<UUID> cids = new java.util.LinkedHashSet<>();
        for (UUID org : orgs) {
            List<UUID> mids = jdbc.queryForList("select membership_id from memberships where org_id = ?", UUID.class, org);
            cids.addAll(jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, org));
            for (UUID mid : mids) {
                jdbc.update("delete from consent_records where membership_id = ?", mid);
                jdbc.update("delete from fan_features where membership_id = ?", mid);
            }
            jdbc.update("delete from segments where org_id = ?", org);
            jdbc.update("delete from memberships where org_id = ?", org);
        }
        for (UUID cid : cids) jdbc.update("delete from consumers where consumer_id = ?", cid);
        for (UUID org : orgs) {
            jdbc.update("delete from tickets where order_id in (select id from orders where org_id = ?)", org);
            jdbc.update("delete from orders where org_id = ?", org);
            jdbc.update("delete from events where org_id = ?", org);
            jdbc.update("delete from users where org_id = ?", org);
            jdbc.update("delete from organizations where id = ?", org);
        }
        orgs.clear();
    }

    // ── grammar shapes ───────────────────────────────────────────────────────

    @Test
    void legacy_flat_array_is_one_and_group() {
        Membership both = member(orgA, 3, 20000);
        member(orgA, 3, 100);
        member(orgA, 1, 20000);

        assertThat(ids(("[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"},"
                + "{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"10000\"}]")))
                .containsExactly(both.getMembershipId());
    }

    @Test
    void and_group_needs_every_rule() {
        Membership both = member(orgA, 3, 20000);
        member(orgA, 3, 100);

        assertThat(ids((groups(group("and", rule("events", ">=", "2"), rule("spend_minor", ">=", "10000"))))))
                .containsExactly(both.getMembershipId());
    }

    @Test
    void or_group_needs_one_rule() {
        Membership many = member(orgA, 5, 0);
        Membership rich = member(orgA, 1, 50000);
        member(orgA, 1, 0);

        assertThat(ids((groups(group("or", rule("events", ">=", "4"), rule("spend_minor", ">=", "40000"))))))
                .containsExactlyInAnyOrder(many.getMembershipId(), rich.getMembershipId());
    }

    @Test
    void not_group_leaves_out_anyone_matching_any_rule() {
        member(orgA, 5, 0);
        member(orgA, 1, 50000);
        Membership neither = member(orgA, 1, 0);

        assertThat(ids((groups(group("not", rule("events", ">=", "4"), rule("spend_minor", ">=", "40000"))))))
                .containsExactly(neither.getMembershipId());
    }

    @Test
    void groups_are_anded_together() {
        Membership keep = member(orgA, 3, 0);
        member(orgA, 3, 0, m -> m.setNoShow(2));
        member(orgA, 1, 0);

        assertThat(ids((groups(
                group("and", rule("events", ">=", "2")),
                group("not", rule("no_show", ">", "0"))))))
                .containsExactly(keep.getMembershipId());
    }

    @Test
    void a_group_without_rules_is_neutral() {
        Membership a = member(orgA, 3, 0);
        member(orgA, 1, 0);

        assertThat(ids((groups(group("or"), group("and", rule("events", ">=", "2"))))))
                .containsExactly(a.getMembershipId());
        assertThat(preview(groups(group("not"))).matched()).isEqualTo(2);
    }

    @Test
    void blank_rules_match_everyone_and_unreadable_rules_match_nobody() {
        member(orgA, 1, 0);
        member(orgA, 2, 0);

        assertThat(segmentService.previewRules(orgA, "").matched()).isEqualTo(2);
        assertThat(segmentService.previewRules(orgA,
                "{\"groups\":[{\"combinator\":\"xor\",\"rules\":[]}]}").matched()).isZero();
    }

    // ── new fields ───────────────────────────────────────────────────────────

    @Test
    void guest_class_equals_and_in_and_a_member_without_features_is_none() {
        Membership loyal = member(orgA, 0, 0);
        features(loyal, "loyal", "{}", "[]");
        Membership repeat = member(orgA, 0, 0);
        features(repeat, "repeat", "{}", "[]");
        Membership noRow = member(orgA, 0, 0);

        assertThat(ids((groups(group("and", rule("guest_class", "==", "loyal"))))))
                .containsExactly(loyal.getMembershipId());
        assertThat(ids((groups(group("and", rule("guest_class", "in", "loyal, repeat"))))))
                .containsExactlyInAnyOrder(loyal.getMembershipId(), repeat.getMembershipId());
        assertThat(ids((groups(group("and", rule("guest_class", "==", "none"))))))
                .containsExactly(noRow.getMembershipId());
    }

    @Test
    void fan_features_are_scoped_by_the_membership_org_not_the_feature_row() {
        Membership inA = member(orgA, 0, 0);
        features(inA, "loyal", "{}", "[]");
        jdbc.update("update fan_features set org_id = ? where membership_id = ?", orgB, inA.getMembershipId());
        member(orgB, 0, 0);

        assertThat(fanRepo.findSegmentFactsByOrgId(orgA)).extracting(r -> r[0]).containsExactly(inA.getMembershipId());
        assertThat(fanRepo.findSegmentFactsByOrgId(orgB)).isEmpty();
        assertThat(ids(groups(group("and", rule("guest_class", "==", "loyal"))))).containsExactly(inA.getMembershipId());
    }

    @Test
    void genre_matches_buckets_with_a_positive_weight() {
        Membership house = member(orgA, 0, 0);
        features(house, "repeat", "{\"house & techno\":0.8,\"pop\":0.2}", "[]");
        Membership pop = member(orgA, 0, 0);
        features(pop, "repeat", "{\"pop\":1.0,\"house & techno\":0.0}", "[]");

        assertThat(ids((groups(group("and", rule("genre", "==", "house & techno"))))))
                .containsExactly(house.getMembershipId());
        assertThat(ids((groups(group("and", rule("genre", "in", "pop,jazz & acoustic"))))))
                .containsExactlyInAnyOrder(house.getMembershipId(), pop.getMembershipId());
    }

    @Test
    void city_reads_purchase_cities_case_insensitively_never_the_membership_city() {
        Membership metz = member(orgA, 0, 0);
        features(metz, "repeat", "{}", "[\"metz\",\"nancy\"]");
        Membership typedOnly = member(orgA, 0, 0, m -> m.setCity("Metz"));

        assertThat(ids((groups(group("and", rule("city", "==", "  METZ ")))))).containsExactly(metz.getMembershipId());
        assertThat(ids((groups(group("and", rule("city", "in", "lyon,Nancy")))))).containsExactly(metz.getMembershipId());
        assertThat(ids((groups(group("and", rule("city", "==", "metz"))))))
                .doesNotContain(typedOnly.getMembershipId());
    }

    @Test
    void attended_event_counts_live_tickets_on_real_orders_of_this_org() {
        UUID event = event(orgA);
        UUID other = event(orgA);
        Membership held = member(orgA, 0, 0);
        ticket(order(orgA, event, held, false), event, "issued");
        Membership refunded = member(orgA, 0, 0);
        ticket(order(orgA, event, refunded, false), event, "refunded");
        Membership testMode = member(orgA, 0, 0);
        ticket(order(orgA, event, testMode, true), event, "issued");
        Membership otherEvent = member(orgA, 0, 0);
        ticket(order(orgA, other, otherEvent, false), other, "redeemed");

        assertThat(ids((groups(group("and", rule("attended_event", "==", event.toString()))))))
                .containsExactly(held.getMembershipId());
        assertThat(ids((groups(group("and", rule("attended_event", "in", event + "," + other))))))
                .containsExactlyInAnyOrder(held.getMembershipId(), otherEvent.getMembershipId());
    }

    @Test
    void attended_event_ignores_another_orgs_order_for_the_same_person() {
        UUID eventB = event(orgB);
        Membership inA = member(orgA, 0, 0);
        Membership inB = memberFor(orgB, consumerOf(inA));
        ticket(order(orgB, eventB, inB, false), eventB, "issued");

        assertThat(segmentService.previewRules(orgA,
                groups(group("and", rule("attended_event", "==", eventB.toString())))).matched()).isZero();
    }

    // ── validation ───────────────────────────────────────────────────────────

    @Test
    void create_accepts_a_valid_grouped_rule_set() {
        UUID event = event(orgA);
        Segment s = segmentService.createSegment(orgA, "Grouped", "dynamic", groups(
                group("and", rule("guest_class", "in", "loyal,repeat"), rule("genre", "==", "pop")),
                group("or", rule("city", "==", "metz"), rule("attended_event", "==", event.toString())),
                group("not", rule("no_show", ">", "0"))), principal);

        assertThat(s.getId()).isNotNull();
    }

    /** Every invalid rule set is a 400 naming the problem on {@code rulesJson}; nothing is saved. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "genre outside the 8 buckets      | genre must be one of the 8 genre buckets",
            "unknown guest class              | guest_class must be one of",
            "in on a legacy field             | unsupported operator 'in'",
            "ordering on a set field          | unsupported operator '>='",
            "empty in list                    | is missing a value",
            "unknown combinator               | must be a JSON array",
            "another org's event              | not yours",
            "non-uuid event                   | needs an event id",
            "11 groups                        | more than 10 groups",
            "51 values                        | more than 50 values",
            "group of 21 rules                | a group has more than 20 rules",
            "201-character value              | has a value that is too long",
            "201-character value in a list    | has a value that is too long",
            "unknown field                    | uses an unknown field 'totally_unknown'"})
    void create_rejects_an_invalid_rule_set(String name, String message) {
        assertRejected(rejectedRules(name), message);
    }

    @Test
    void create_accepts_20_rules_in_a_group_and_a_200_character_value() {
        String[] twenty = java.util.Collections.nCopies(20, rule("events", ">=", "1")).toArray(String[]::new);

        assertThat(segmentService.createSegment(orgA, "Twenty", "dynamic", groups(group("and", twenty)), principal).getId())
                .isNotNull();
        assertThat(segmentService.createSegment(orgA, "Long city", "dynamic",
                groups(group("and", rule("city", "==", "c".repeat(200)))), principal).getId()).isNotNull();
    }

    private String rejectedRules(String name) {
        return switch (name) {
            case "genre outside the 8 buckets" -> groups(group("and", rule("genre", "==", "techno")));
            case "unknown guest class" -> groups(group("and", rule("guest_class", "==", "vip")));
            case "in on a legacy field" -> "[{\"field\":\"events\",\"operator\":\"in\",\"value\":\"1,2\"}]";
            case "ordering on a set field" -> groups(group("and", rule("genre", ">=", "pop")));
            case "empty in list" -> groups(group("and", rule("city", "in", " , ")));
            case "unknown combinator" -> "{\"groups\":[{\"combinator\":\"xor\",\"rules\":[]}]}";
            case "another org's event" -> groups(group("and", rule("attended_event", "==", event(orgB).toString())));
            case "non-uuid event" -> groups(group("and", rule("attended_event", "==", "last-friday")));
            case "11 groups" -> {
                StringBuilder many = new StringBuilder("{\"groups\":[");
                for (int i = 0; i < 11; i++) many.append(i == 0 ? "" : ",").append(group("and", rule("events", ">=", "1")));
                yield many.append("]}").toString();
            }
            case "51 values" -> groups(group("and", rule("city", "in",
                    String.join(",", java.util.stream.IntStream.range(0, 51).mapToObj(i -> "c" + i).toList()))));
            case "group of 21 rules" -> groups(group("and",
                    java.util.Collections.nCopies(21, rule("events", ">=", "1")).toArray(String[]::new)));
            case "201-character value" -> groups(group("and", rule("city", "==", "c".repeat(201))));
            case "201-character value in a list" -> groups(group("and", rule("city", "in", "metz," + "c".repeat(201))));
            case "unknown field" -> "[{\"field\":\"totally_unknown\",\"operator\":\">=\",\"value\":\"3\"}]";
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    void create_and_preview_reject_rules_longer_than_the_cap_before_parsing() {
        String huge = "[" + "x".repeat(65_536) + "]";

        assertRejected(huge, "is longer than 65536 characters");
        assertThatThrownBy(() -> segmentService.previewValidated(orgA, huge))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void a_rule_the_engine_cannot_run_makes_the_whole_segment_match_nobody() {
        member(orgA, 3, 0);
        member(orgA, 1, 0);
        // Stored without validation (older rows, the AI draft preview): false inside not would mean everyone.
        String notUnknown = groups(group("not", rule("tags", "==", "vip")));
        String orBadNumber = groups(group("or", rule("events", ">=", "1"), rule("spend_minor", ">=", "lots")));
        String notBadClass = groups(group("and", rule("events", ">=", "1")), group("not", rule("guest_class", "==", "vip")));

        for (String rules : List.of(notUnknown, orBadNumber, notBadClass)) {
            assertThat(ids(rules)).as(rules).isEmpty();
            Segment stored = new Segment();
            stored.setOrgId(orgA);
            stored.setKind("dynamic");
            stored.setRulesJson(rules);
            assertThat(segmentService.liveCount(orgA, stored)).as(rules).isZero();
        }
    }

    @Test
    void preview_validated_rejects_bad_rules_with_400() {
        assertThatThrownBy(() -> segmentService.previewValidated(orgA, groups(group("and", rule("genre", "==", "techno")))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── counts and exclusion reasons ─────────────────────────────────────────

    @Test
    void resolve_and_preview_report_every_consent_gate_reason_summing_to_excluded() {
        Membership mailable = member(orgA, 3, 0);
        prove(mailable);
        member(orgA, 3, 0, m -> m.setConsentStatus("unsubscribed"));
        member(orgA, 3, 0);
        member(orgA, 3, 0, m -> m.setObjectedProfiling(true));
        member(orgA, 1, 0);
        String rules = "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"}]";
        Segment s = segmentService.createSegment(orgA, "Regulars", "dynamic", rules, principal);

        for (SegmentResolveDto dto : List.of(segmentService.resolve(orgA, s.getId()),
                segmentService.previewRules(orgA, rules))) {
            assertThat(dto.matched()).isEqualTo(4);
            assertThat(dto.mailable()).isEqualTo(1);
            assertThat(dto.excluded()).isEqualTo(3);
            assertThat(dto.exclusions()).containsOnlyKeys(ConsentGate.REASONS);
            assertThat(dto.exclusions()).containsEntry("unsubscribed", 1).containsEntry("no_basis", 1)
                    .containsEntry("objected", 1).containsEntry("legacy_unproven", 0);
            assertThat(dto.exclusions().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(dto.excluded());
        }
    }

    @Test
    void a_zero_match_segment_still_carries_every_reason_at_zero() {
        SegmentResolveDto dto = segmentService.previewRules(orgA, "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"99\"}]");

        assertThat(dto.matched()).isZero();
        assertThat(dto.exclusions()).containsOnlyKeys(ConsentGate.REASONS).allSatisfy((k, v) -> assertThat(v).isZero());
    }

    @Test
    void live_count_honours_groups_and_new_fields() {
        Membership loyal = member(orgA, 4, 0);
        features(loyal, "loyal", "{\"pop\":1.0}", "[]");
        Membership repeat = member(orgA, 2, 0);
        features(repeat, "repeat", "{\"pop\":1.0}", "[]");
        member(orgA, 1, 0);
        Segment s = segmentService.createSegment(orgA, "Pop not loyal", "dynamic",
                groups(group("and", rule("genre", "==", "pop")), group("not", rule("guest_class", "==", "loyal"))),
                principal);

        assertThat(segmentService.liveCount(orgA, s)).isEqualTo(1);
        assertThat(segmentService.resolveMembers(orgA, s)).extracting(Membership::getMembershipId)
                .containsExactly(repeat.getMembershipId());
    }

    // ── SegmentDto ───────────────────────────────────────────────────────────

    @Test
    void dto_exposes_legacy_rules_as_one_and_group() {
        Segment s = new Segment();
        s.setRulesJson("[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"}]");

        SegmentDto dto = SegmentDto.from(s, 0);

        assertThat(dto.ruleGroups()).containsExactly(
                new SegmentRuleGroup("and", List.of(new SegmentRule("events", ">=", "2"))));
        assertThat(dto.rules()).containsExactly(java.util.Map.of("field", "events", "operator", ">=", "value", "2"));
        assertThat(dto.rulesSummary()).containsExactly("events >= 2");
    }

    @Test
    void dto_exposes_groups_and_leaves_flat_rules_empty_for_or_and_not() {
        Segment s = new Segment();
        s.setRulesJson(groups(group("or", rule("genre", "==", "pop"), rule("city", "==", "metz")),
                group("not", rule("no_show", ">", "0"))));

        SegmentDto dto = SegmentDto.from(s, 0);

        assertThat(dto.ruleGroups()).containsExactly(
                new SegmentRuleGroup("or", List.of(new SegmentRule("genre", "==", "pop"), new SegmentRule("city", "==", "metz"))),
                new SegmentRuleGroup("not", List.of(new SegmentRule("no_show", ">", "0"))));
        assertThat(dto.rules()).isEmpty();
        assertThat(dto.rulesSummary()).containsExactly("or: genre == pop; city == metz", "not: no_show > 0");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SegmentResolveDto preview(String rules) {
        return segmentService.previewRules(orgA, rules);
    }

    /** Members the rules resolve to; the preview count is asserted to agree. */
    private List<UUID> ids(String rules) {
        Segment transient_ = new Segment();
        transient_.setOrgId(orgA);
        transient_.setKind("dynamic");
        transient_.setRulesJson(rules);
        List<UUID> out = segmentService.resolveMembers(orgA, transient_).stream().map(Membership::getMembershipId).toList();
        assertThat(preview(rules).matched()).isEqualTo(out.size());
        return out;
    }

    private void assertRejected(String rules, String message) {
        assertThatThrownBy(() -> segmentService.createSegment(orgA, "Bad " + UUID.randomUUID(), "dynamic", rules, principal))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.fields().get("rulesJson")).contains(message);
                });
    }

    private static String groups(String... groups) {
        return "{\"groups\":[" + String.join(",", groups) + "]}";
    }

    private static String group(String combinator, String... rules) {
        return "{\"combinator\":\"" + combinator + "\",\"rules\":[" + String.join(",", rules) + "]}";
    }

    private static String rule(String field, String op, String value) {
        return "{\"field\":\"" + field + "\",\"operator\":\"" + op + "\",\"value\":\"" + value + "\"}";
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("Grammar");
        o.setSlug("grammar-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("grammar-" + UUID.randomUUID() + "@test.com");
        o.setCountry("FR");
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    private Membership member(UUID orgId, int events, long spend) {
        return member(orgId, events, spend, m -> { });
    }

    private Membership member(UUID orgId, int events, long spend, java.util.function.Consumer<Membership> tweak) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(EmailNormalizer.normalize("g-" + UUID.randomUUID() + "@example.com"));
        c = consumerRepo.save(c);
        Membership m = memberFor(orgId, c.getConsumerId());
        m.setEvents(events);
        m.setSpendMinor(spend);
        tweak.accept(m);
        return membershipRepo.save(m);
    }

    private Membership memberFor(UUID orgId, UUID consumerId) {
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumerId);
        return membershipRepo.save(m);
    }

    private UUID consumerOf(Membership m) {
        return m.getConsumerId();
    }

    private String emailOf(Membership m) {
        return jdbc.queryForObject("select normalized_email from consumers where consumer_id = ?", String.class,
                m.getConsumerId());
    }

    private void features(Membership m, String cls, String taste, String cities) {
        FanFeature f = new FanFeature();
        f.setMembershipId(m.getMembershipId());
        f.setOrgId(m.getOrgId());
        f.setFanClass(cls);
        f.setTaste(taste);
        f.setCities(cities);
        f.setLogicVersion(1);
        fanRepo.save(f);
    }

    /** A door-QR consent with a text version: proven and recent, so ConsentGate mails the member. */
    private void prove(Membership m) {
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        membershipRepo.save(m);
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(m.getMembershipId());
        r.setStatus("subscribed");
        r.setLawfulBasis("explicit");
        r.setSource("door_qr");
        r.setTextVersion("door-v1");
        r.setOccurredAt(clock.instant());
        consentRepo.save(r);
    }

    private UUID event(UUID orgId) {
        User owner = new User();
        owner.setEmail("g-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        owner = userRepo.save(owner);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Grammar Night");
        e.setSlug("g-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(clock.instant().plusSeconds(86400));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return eventRepo.save(e).getId();
    }

    private UUID order(UUID orgId, UUID eventId, Membership buyer, boolean testMode) {
        Order o = new Order();
        o.setToken("g-" + UUID.randomUUID());
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setEmail(emailOf(buyer));
        o.setTotalMinor(3000);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setTestMode(testMode);
        return orderRepo.save(o).getId();
    }

    private void ticket(UUID orderId, UUID eventId, String state) {
        Ticket t = new Ticket();
        t.setToken("g-t-" + UUID.randomUUID());
        t.setOrderId(orderId);
        t.setEventId(eventId);
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor(3000);
        t.setState(state);
        ticketRepo.save(t);
    }
}
