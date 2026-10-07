package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.SegmentResolveDto;
import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Segment tests:
 * - resolve(rules).size() == resolve DTO matched for each provisioned prebuilt segment
 * - each prebuilt predicate matches correctly
 * - static snapshot frozen while dynamic re-evaluates
 * - custom rule JSON evaluated correctly
 * - segment isolation: segments from orgA not visible to orgB
 */
@IminIntegrationTest
class AudienceSegmentTest {

    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired SegmentRepository segmentRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired SegmentService segmentService;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired AuditRows audit;

    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal principalA;

    @BeforeEach
    void setUp() {
        orgA = fx.org().getId();
        orgB = fx.org().getId();
        principalA = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
    }

    /** Own memberships (consent rows and fan features cascade), own segments, then own orgs. */
    @AfterEach
    void tearDown() {
        try {
            jdbc.update("delete from memberships where org_id in (?, ?)", orgA, orgB);
            jdbc.update("delete from segments where org_id in (?, ?)", orgA, orgB);
        } finally {
            OrgRows.delete(jdbc, List.of(orgA, orgB));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 1: Repeat (events >= 2)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_repeat_matches_events_gte_2() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Repeat");

        // Member with 1 event — should NOT match
        Membership m1 = seedMembership(orgA, fx.email("onetimer"));
        m1.setEvents(1);
        membershipRepo.save(m1);

        // Member with 2 events — should match
        Membership m2 = seedMembership(orgA, fx.email("repeat"));
        m2.setEvents(2);
        membershipRepo.save(m2);

        // Member with 5 events — should match
        Membership m3 = seedMembership(orgA, fx.email("super"));
        m3.setEvents(5);
        membershipRepo.save(m3);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactlyInAnyOrder(m2.getMembershipId(), m3.getMembershipId());
        assertThat(resolved).doesNotContain(m1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 2: VIP (spend_minor >= 20000 && events >= 4)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_vip_matches_spend_and_events() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "VIP");

        // Should match
        Membership vip = seedMembership(orgA, fx.email("vip"));
        vip.setSpendMinor(25000);
        vip.setEvents(4);
        membershipRepo.save(vip);

        // Spend ok but not enough events
        Membership notVip1 = seedMembership(orgA, fx.email("notvip1"));
        notVip1.setSpendMinor(25000);
        notVip1.setEvents(3);
        membershipRepo.save(notVip1);

        // Events ok but not enough spend
        Membership notVip2 = seedMembership(orgA, fx.email("notvip2"));
        notVip2.setSpendMinor(19999);
        notVip2.setEvents(5);
        membershipRepo.save(notVip2);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactly(vip.getMembershipId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 3: Lapsed (recency >= 90 && consent_status = 'subscribed')
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_lapsed_matches_old_subscribed() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Lapsed");

        // Lapsed + subscribed → should match
        Membership lapsed = seedMembership(orgA, fx.email("lapsed"));
        lapsed.setRecencyDays(120);
        lapsed.setConsentStatus("subscribed");
        membershipRepo.save(lapsed);

        // Recent subscribed → should NOT match
        Membership recent = seedMembership(orgA, fx.email("recent"));
        recent.setRecencyDays(30);
        recent.setConsentStatus("subscribed");
        membershipRepo.save(recent);

        // Lapsed but unsubscribed → should NOT match
        Membership unsubscribed = seedMembership(orgA, fx.email("unsub"));
        unsubscribed.setRecencyDays(120);
        unsubscribed.setConsentStatus("unsubscribed");
        membershipRepo.save(unsubscribed);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactly(lapsed.getMembershipId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 4: First-timers (events == 1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_firsttimers_matches_events_equals_1() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "First-timers");

        Membership first = seedMembership(orgA, fx.email("first"));
        first.setEvents(1);
        membershipRepo.save(first);

        Membership repeat = seedMembership(orgA, fx.email("repeat2"));
        repeat.setEvents(2);
        membershipRepo.save(repeat);

        Membership prospect = seedMembership(orgA, fx.email("prosp"));
        // events=0 by default

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactly(first.getMembershipId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Promoters: retired until NPS is collected
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void promoters_is_not_provisioned_for_a_new_org() {
        segmentService.ensurePrebuiltSegments(orgA);

        assertThat(segmentRepo.findByOrgId(orgA)).extracting(Segment::getPrebuiltKey)
                .doesNotContain(PrebuiltSegment.PROMOTERS.key())
                .hasSize(6);
    }

    @Test
    void an_existing_promoters_row_is_hidden_from_the_list_but_still_resolves() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment legacy = new Segment();
        legacy.setOrgId(orgA);
        legacy.setName("Promoters");
        legacy.setKind("dynamic");
        legacy.setPrebuilt(true);
        legacy.setPrebuiltKey(PrebuiltSegment.PROMOTERS.key());
        legacy.setRulesJson(PrebuiltSegment.PROMOTERS.rulesJson());
        legacy = segmentRepo.save(legacy);

        Membership promoter = seedMembership(orgA, fx.email("prom"));
        promoter.setNps((short) 9);
        membershipRepo.save(promoter);
        seedMembership(orgA, fx.email("nonps"));

        assertThat(segmentService.listSegments(orgA)).extracting(Segment::getId).doesNotContain(legacy.getId());
        assertThat(segmentService.listSegments(orgA)).hasSize(6);
        assertThat(segmentService.resolveMembers(orgA, legacy)).extracting(Membership::getMembershipId)
                .containsExactly(promoter.getMembershipId());
    }

    @Test
    void a_momentum_snapshot_is_hidden_from_the_list_and_cannot_be_deleted() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment snapshot = new Segment();
        snapshot.setOrgId(orgA);
        snapshot.setName("Momentum: loyal guests, same genre fit");
        snapshot.setKind("static");
        snapshot.setOrigin(Segment.ORIGIN_MOMENTUM);
        snapshot.setSnapshotIds("[]");
        UUID id = segmentRepo.save(snapshot).getId();

        assertThat(segmentService.listSegments(orgA)).extracting(Segment::getId).doesNotContain(id).hasSize(6);
        assertThatThrownBy(() -> segmentService.deleteSegment(orgA, id, principalA))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(segmentRepo.findByIdAndOrgId(id, orgA)).isPresent();
    }

    @Test
    void a_retired_promoters_row_does_not_block_creating_a_segment_with_that_name() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment legacy = new Segment();
        legacy.setOrgId(orgA);
        legacy.setName("Promoters");
        legacy.setKind("dynamic");
        legacy.setPrebuilt(true);
        legacy.setPrebuiltKey(PrebuiltSegment.PROMOTERS.key());
        legacy.setRulesJson(PrebuiltSegment.PROMOTERS.rulesJson());
        segmentRepo.save(legacy);

        Segment created = segmentService.createSegment(orgA, " promoters ", "dynamic", null, principalA);

        assertThat(created.getName()).isEqualTo("promoters");
        assertThat(segmentService.listSegments(orgA)).extracting(Segment::getId).contains(created.getId());
        assertThatThrownBy(() -> segmentService.createSegment(orgA, "PROMOTERS", "dynamic", null, principalA))
                .isInstanceOfSatisfying(com.imin.iminapi.security.ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 6: Bought-no-showed (no_show > 0)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_bought_no_showed_matches_no_show_gt_0() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Bought-no-showed");

        Membership noShow = seedMembership(orgA, fx.email("noshow"));
        noShow.setNoShow(1);
        membershipRepo.save(noShow);

        Membership attended = seedMembership(orgA, fx.email("attended"));
        attended.setAttended(1);
        // noShow=0 by default
        membershipRepo.save(attended);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactly(noShow.getMembershipId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 7: Newest-30d (recency <= 30 && events <= 1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_newest_30d_matches_recency_lte_30_events_lte_1() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Newest-30d");

        // Should match: recent, single-event
        Membership newest = seedMembership(orgA, fx.email("newest"));
        newest.setRecencyDays(15);
        newest.setEvents(1);
        membershipRepo.save(newest);

        // Should match: recency=30, events=0 (prospect who signed up recently)
        Membership prospect = seedMembership(orgA, fx.email("newprospect"));
        prospect.setRecencyDays(30);
        prospect.setEvents(0);
        membershipRepo.save(prospect);

        // Should NOT match: too old
        Membership old = seedMembership(orgA, fx.email("old"));
        old.setRecencyDays(31);
        old.setEvents(1);
        membershipRepo.save(old);

        // Should NOT match: recent but repeat
        Membership repeatRecent = seedMembership(orgA, fx.email("repeatrecent"));
        repeatRecent.setRecencyDays(5);
        repeatRecent.setEvents(2);
        membershipRepo.save(repeatRecent);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactlyInAnyOrder(newest.getMembershipId(), prospect.getMembershipId());
    }

    /**
     * The resolve DTO's matched count (members the gate gave a verdict) equals the resolved list for every
     * provisioned prebuilt; a NULL expected means the row pins the equality only.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", value = {
            "Repeat,           2",
            "VIP,              NULL",
            "Lapsed,           NULL",
            "First-timers,     1",
            "Bought-no-showed, NULL",
            "Newest-30d,       1"})
    void prebuilt_resolve_count_equals_resolve_size(String prebuilt, Integer expected) {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, prebuilt);
        switch (prebuilt) {
            case "Repeat" -> {
                Membership m1 = seedMembership(orgA, fx.email("rep1"));
                m1.setEvents(3);
                membershipRepo.save(m1);
                Membership m2 = seedMembership(orgA, fx.email("rep2"));
                m2.setEvents(2);
                membershipRepo.save(m2);
            }
            case "VIP" -> {
                Membership v = seedMembership(orgA, fx.email("vip2"));
                v.setSpendMinor(20000);
                v.setEvents(4);
                membershipRepo.save(v);
            }
            case "Lapsed" -> {
                Membership l = seedMembership(orgA, fx.email("l1"));
                l.setRecencyDays(90);
                l.setConsentStatus("subscribed");
                membershipRepo.save(l);
            }
            case "First-timers" -> {
                Membership m = seedMembership(orgA, fx.email("ft1"));
                m.setEvents(1);
                membershipRepo.save(m);
            }
            case "Bought-no-showed" -> {
                Membership ns = seedMembership(orgA, fx.email("ns2"));
                ns.setNoShow(2);
                membershipRepo.save(ns);
            }
            case "Newest-30d" -> {
                Membership m = seedMembership(orgA, fx.email("n2"));
                m.setRecencyDays(7);
                m.setEvents(1);
                membershipRepo.save(m);
            }
            default -> throw new IllegalArgumentException(prebuilt);
        }

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);

        assertThat(dto.matched()).isEqualTo(list.size());
        if (expected != null) assertThat(dto.matched()).isEqualTo(expected);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static snapshot is frozen — dynamic re-evaluates
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * audience-15: snapshot froze whatever segment it was handed, prebuilt included, with
     * no way back — one click on Repeat pinned every future Momentum campaign to a stale id
     * list. Prebuilts are refused; snapshotting is for the organizer's own segments.
     */
    @Test
    void snapshot_refuses_a_prebuilt_segment_and_leaves_it_dynamic() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment repeat = findPrebuilt(orgA, "Repeat");

        assertThatThrownBy(() -> segmentService.snapshot(orgA, repeat.getId(), principalA))
                .isInstanceOfSatisfying(com.imin.iminapi.security.ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));

        Segment reloaded = segmentRepo.findByIdAndOrgId(repeat.getId(), orgA).orElseThrow();
        assertThat(reloaded.getKind()).isEqualTo("dynamic");
        assertThat(reloaded.getSnapshotIds()).isNull();

        // Momentum's default target keeps re-evaluating.
        Membership m = seedMembership(orgA, fx.email("afterrefusal"));
        m.setEvents(2);
        membershipRepo.save(m);
        assertThat(segmentService.resolveMembers(orgA, reloaded))
                .extracting(Membership::getMembershipId).containsExactly(m.getMembershipId());
    }

    @Test
    void static_snapshot_frozen_while_dynamic_reevaluates() {
        Segment seg = segmentService.createSegment(orgA, "My repeats", "dynamic",
                "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"}]", principalA);

        // No repeats yet → resolve = 0
        assertThat(segmentService.resolveMembers(orgA, seg)).isEmpty();

        // Take snapshot when empty
        segmentService.snapshot(orgA, seg.getId(), principalA);
        Segment snapped = segmentRepo.findByIdAndOrgId(seg.getId(), orgA).orElseThrow();
        assertThat(snapped.getKind()).isEqualTo("static");

        // Add a repeat member AFTER snapshot
        Membership m = seedMembership(orgA, fx.email("aftersnap"));
        m.setEvents(2);
        membershipRepo.save(m);

        // Static snapshot still empty (frozen)
        assertThat(segmentService.resolveMembers(orgA, snapped)).isEmpty();

        // Dynamic segment (using same rules) sees the new member
        Segment dynSeg = new Segment();
        dynSeg.setOrgId(orgA);
        dynSeg.setName("Repeat");
        dynSeg.setKind("dynamic");
        dynSeg.setRulesJson("[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"}]");

        List<Membership> live = segmentService.resolveMembers(orgA, dynSeg);
        assertThat(live).extracting(Membership::getMembershipId)
                .contains(m.getMembershipId());
    }

    @Test
    void static_snapshot_after_adding_member_contains_that_member() {
        Segment seg = segmentService.createSegment(orgA, "My VIPs", "dynamic",
                "[{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"20000\"},"
                        + "{\"field\":\"events\",\"operator\":\">=\",\"value\":\"4\"}]", principalA);

        // Add a VIP
        Membership vip = seedMembership(orgA, fx.email("snapvip"));
        vip.setSpendMinor(20000);
        vip.setEvents(4);
        membershipRepo.save(vip);

        // Snapshot with VIP in it
        segmentService.snapshot(orgA, seg.getId(), principalA);
        Segment snapped = segmentRepo.findByIdAndOrgId(seg.getId(), orgA).orElseThrow();

        // Snapshot contains the VIP
        List<Membership> fromSnapshot = segmentService.resolveMembers(orgA, snapped);
        assertThat(fromSnapshot).extracting(Membership::getMembershipId)
                .contains(vip.getMembershipId());

        // Now remove VIP status
        vip.setEvents(1);
        membershipRepo.save(vip);

        // Snapshot still returns VIP (frozen)
        List<Membership> stillFrozen = segmentService.resolveMembers(orgA, snapped);
        assertThat(stillFrozen).extracting(Membership::getMembershipId)
                .contains(vip.getMembershipId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Custom JSON rules evaluated correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void custom_rules_conjunctive_all_must_match() {
        String rulesJson = "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"3\"}," +
                            "{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"10000\"}]";
        Segment custom = segmentService.createSegment(orgA, "Big Spenders", "dynamic", rulesJson, principalA);
        audit.assertRecorded(orgA, AuditActions.SEGMENT_CREATED, "segment", custom.getId());

        // Matches both conditions
        Membership both = seedMembership(orgA, fx.email("bigspend"));
        both.setEvents(5);
        both.setSpendMinor(15000);
        membershipRepo.save(both);

        // Enough events but not enough spend
        Membership eventsOnly = seedMembership(orgA, fx.email("eventsonly"));
        eventsOnly.setEvents(5);
        eventsOnly.setSpendMinor(5000);
        membershipRepo.save(eventsOnly);

        // Enough spend but not enough events
        Membership spendOnly = seedMembership(orgA, fx.email("spendonly"));
        spendOnly.setEvents(2);
        spendOnly.setSpendMinor(15000);
        membershipRepo.save(spendOnly);

        List<Membership> resolved = segmentService.resolveMembers(orgA, custom);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactly(both.getMembershipId());
    }

    @Test
    void custom_rules_resolve_count_matches_list_size() {
        String rulesJson = "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"}]";
        Segment seg = segmentService.createSegment(orgA, "Repeaters", "dynamic", rulesJson, principalA);

        Membership r1 = seedMembership(orgA, fx.email("cr1"));
        r1.setEvents(2);
        membershipRepo.save(r1);
        Membership r2 = seedMembership(orgA, fx.email("cr2"));
        r2.setEvents(3);
        membershipRepo.save(r2);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);
        assertThat(dto.matched()).isEqualTo(list.size());
        assertThat(dto.matched()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // audience-1: prebuilt routing is by stable key, never by display name
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A segment that merely SHARES a prebuilt's display name must evaluate its own rules.
     * Resolution used to switch on the name, so this row resolved with the prebuilt VIP
     * query (spend >= 20000 and events >= 4) while the dashboard showed its own rules —
     * and RecipientMaterializer mailed the wrong list.
     */
    @Test
    void segment_sharing_a_prebuilt_name_evaluates_its_own_rules() {
        segmentService.ensurePrebuiltSegments(orgA);

        Segment impostor = new Segment();
        impostor.setOrgId(orgA);
        impostor.setName("VIP");           // same display name, no prebuilt key
        impostor.setKind("dynamic");
        impostor.setRulesJson("[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"1\"}]");
        impostor = segmentRepo.save(impostor);

        Membership modest = seedMembership(orgA, fx.email("modest"));
        modest.setEvents(1);
        modest.setSpendMinor(500);
        membershipRepo.save(modest);

        assertThat(segmentService.resolveMembers(orgA, impostor))
                .extracting(Membership::getMembershipId)
                .containsExactly(modest.getMembershipId());
    }

    /** The prebuilt itself still uses its indexed query, resolved by key. */
    @Test
    void prebuilt_vip_still_routes_to_the_indexed_query() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment vipSegment = findPrebuilt(orgA, "VIP");
        assertThat(vipSegment.getPrebuiltKey()).isEqualTo("VIP");

        Membership modest = seedMembership(orgA, fx.email("modest2"));
        modest.setEvents(1);
        modest.setSpendMinor(500);
        membershipRepo.save(modest);

        assertThat(segmentService.resolveMembers(orgA, vipSegment)).isEmpty();
    }

    /** Momentum's default target is the prebuilt Repeat row, found by key not by name. */
    @Test
    void default_target_segment_is_the_prebuilt_repeat_row() {
        segmentService.ensurePrebuiltSegments(orgA);

        Segment lookalike = new Segment();
        lookalike.setOrgId(orgA);
        lookalike.setName("Repeat");
        lookalike.setKind("dynamic");
        lookalike = segmentRepo.save(lookalike);

        assertThat(segmentService.defaultTargetSegmentId(orgA))
                .isEqualTo(findPrebuilt(orgA, "Repeat").getId())
                .isNotEqualTo(lookalike.getId());
    }

    @Test
    void creating_a_segment_whose_name_is_taken_is_a_409() {
        segmentService.ensurePrebuiltSegments(orgA);

        assertThatThrownBy(() -> segmentService.createSegment(orgA, " vip ", "dynamic", null, principalA))
                .isInstanceOfSatisfying(com.imin.iminapi.security.ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.DUPLICATE);
                });
    }

    /**
     * audience-5: an unreadable rules_json used to resolve to the ENTIRE org audience.
     * The failure mode of a rule the engine cannot read must be "nobody", not "everybody"
     * — this list feeds RecipientMaterializer.
     */
    @Test
    void a_segment_whose_rules_cannot_be_parsed_matches_nobody() {
        Membership anyone = seedMembership(orgA, fx.email("unparseable"));
        anyone.setEvents(4);
        membershipRepo.save(anyone);

        // The shape a truncated TEXT value has; createSegment would reject it today.
        Segment broken = new Segment();
        broken.setOrgId(orgA);
        broken.setName("Truncated rules");
        broken.setKind("dynamic");
        broken.setRulesJson("[{\"field\":\"events\",\"operator\":\">=\",\"val");
        broken = segmentRepo.save(broken);

        assertThat(segmentService.resolveMembers(orgA, broken)).isEmpty();
        assertThat(segmentService.resolve(orgA, broken.getId()).matched()).isZero();
    }

    /** A blank rules_json still means "everyone" — that is documented, not a parse failure. */
    @Test
    void a_segment_with_no_rules_still_matches_everyone() {
        Membership anyone = seedMembership(orgA, fx.email("norules"));
        membershipRepo.save(anyone);

        Segment all = segmentService.createSegment(orgA, "Everyone", "dynamic", null, principalA);

        assertThat(segmentService.resolveMembers(orgA, all))
                .extracting(Membership::getMembershipId).contains(anyone.getMembershipId());
    }

    /** liveCount is the number the Audience tab shows; it must agree with resolution. */
    @Test
    void live_count_agrees_with_resolved_size_for_every_segment_kind() {
        segmentService.ensurePrebuiltSegments(orgA);

        Membership repeat = seedMembership(orgA, fx.email("lc-repeat"));
        repeat.setEvents(3);
        repeat.setSpendMinor(30000);
        membershipRepo.save(repeat);
        Membership single = seedMembership(orgA, fx.email("lc-single"));
        single.setEvents(1);
        membershipRepo.save(single);

        Segment prebuiltRepeat = findPrebuilt(orgA, "Repeat");
        Segment custom = segmentService.createSegment(orgA, "Three plus", "dynamic",
                "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"3\"}]", principalA);
        Segment everyone = segmentService.createSegment(orgA, "All of them", "dynamic", null, principalA);
        segmentService.snapshot(orgA, custom.getId(), principalA);
        Segment frozen = segmentRepo.findByIdAndOrgId(custom.getId(), orgA).orElseThrow();

        for (Segment seg : List.of(prebuiltRepeat, everyone, frozen)) {
            assertThat(segmentService.liveCount(orgA, seg))
                    .as("liveCount for %s", seg.getName())
                    .isEqualTo(segmentService.resolveMembers(orgA, seg).size());
        }
        assertThat(segmentService.liveCount(orgA, prebuiltRepeat)).isEqualTo(1);
        assertThat(segmentService.liveCount(orgA, everyone)).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ensure the 6 provisioned prebuilt segments are created exactly once
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void ensure_prebuilt_segments_idempotent() {
        segmentService.ensurePrebuiltSegments(orgA);
        segmentService.ensurePrebuiltSegments(orgA); // second call is no-op

        List<Segment> segments = segmentRepo.findByOrgId(orgA);
        long prebuilt = segments.stream().filter(Segment::isPrebuilt).count();
        assertThat(prebuilt).isEqualTo(6);
    }

    @Test
    void prebuilt_segments_org_isolated() {
        segmentService.ensurePrebuiltSegments(orgA);
        segmentService.ensurePrebuiltSegments(orgB);

        // orgA can't see orgB's segments and vice versa
        List<Segment> orgASegs = segmentRepo.findByOrgId(orgA);
        List<Segment> orgBSegs = segmentRepo.findByOrgId(orgB);

        Set<UUID> orgAIds = new HashSet<>();
        orgASegs.forEach(s -> orgAIds.add(s.getId()));
        orgBSegs.forEach(s -> assertThat(orgAIds).doesNotContain(s.getId()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SegmentResolveDto: mailable is a subset of matched
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void resolve_dto_mailable_le_matched() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Repeat");

        // Member with a proven consent (door QR, text version, recent)
        Membership withConsent = seedMembership(orgA, fx.email("consented"));
        withConsent.setEvents(3);
        withConsent.setConsentStatus("subscribed");
        withConsent.setConsentBasis("explicit");
        membershipRepo.save(withConsent);
        ConsentRecord proof = new ConsentRecord();
        proof.setMembershipId(withConsent.getMembershipId());
        proof.setStatus("subscribed");
        proof.setLawfulBasis("explicit");
        proof.setSource("door_qr");
        proof.setTextVersion("door-v1");
        consentRepo.save(proof);

        // Member without consent
        Membership noConsent = seedMembership(orgA, fx.email("noconsent"));
        noConsent.setEvents(2);
        // consentStatus='never' by default, consentBasis=null
        membershipRepo.save(noConsent);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        assertThat(dto.matched()).isEqualTo(2);
        assertThat(dto.mailable()).isEqualTo(1);
        assertThat(dto.excluded()).isEqualTo(1);
        assertThat(dto.mailable()).isLessThanOrEqualTo(dto.matched());
        assertThat(dto.exclusions()).containsEntry("no_basis", 1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Segment findPrebuilt(UUID orgId, String name) {
        return segmentRepo.findByOrgId(orgId).stream()
                .filter(s -> name.equals(s.getName()) && s.isPrebuilt())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Prebuilt segment not found: " + name));
    }

    private Membership seedMembership(UUID orgId, String email) {
        String normalized = EmailNormalizer.normalize(email);
        Consumer consumer = consumerRepo.findByNormalizedEmail(normalized).orElse(null);
        if (consumer == null) {
            consumer = new Consumer();
            consumer.setNormalizedEmail(normalized);
            consumer.setDisplayName(email);
            consumer = consumerRepo.save(consumer);
        }
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        return membershipRepo.save(m);
    }
}
