package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.SegmentResolveDto;
import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Segment tests:
 * - resolve(rules).size() == count(resolve) for each of the 7 prebuilt segments
 * - each of 7 prebuilt predicates matches correctly
 * - static snapshot frozen while dynamic re-evaluates
 * - custom rule JSON evaluated correctly
 * - segment isolation: segments from orgA not visible to orgB
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceSegmentTest {

    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired SegmentRepository segmentRepo;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired SegmentService segmentService;
    @Autowired OrganizationRepository orgRepo;
    @Autowired DataSource dataSource;

    @MockitoBean AuditLogger auditLogger;

    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal principalA;

    @BeforeEach
    void setUp() {
        wipe();
        orgA = org("SegOrgA").getId();
        orgB = org("SegOrgB").getId();
        principalA = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 1: Repeat (events >= 2)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_repeat_matches_events_gte_2() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Repeat");

        // Member with 1 event — should NOT match
        Membership m1 = seedMembership(orgA, "onetimer@s.com");
        m1.setEvents(1);
        membershipRepo.save(m1);

        // Member with 2 events — should match
        Membership m2 = seedMembership(orgA, "repeat@s.com");
        m2.setEvents(2);
        membershipRepo.save(m2);

        // Member with 5 events — should match
        Membership m3 = seedMembership(orgA, "super@s.com");
        m3.setEvents(5);
        membershipRepo.save(m3);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactlyInAnyOrder(m2.getMembershipId(), m3.getMembershipId());
        assertThat(resolved).doesNotContain(m1);
    }

    @Test
    void prebuilt_repeat_resolve_count_equals_resolve_size() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Repeat");

        Membership m1 = seedMembership(orgA, "rep1@s.com");
        m1.setEvents(3);
        membershipRepo.save(m1);
        Membership m2 = seedMembership(orgA, "rep2@s.com");
        m2.setEvents(2);
        membershipRepo.save(m2);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);

        assertThat(dto.matched()).isEqualTo(list.size());
        assertThat(dto.matched()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 2: VIP (spend_minor >= 20000 && events >= 4)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_vip_matches_spend_and_events() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "VIP");

        // Should match
        Membership vip = seedMembership(orgA, "vip@s.com");
        vip.setSpendMinor(25000);
        vip.setEvents(4);
        membershipRepo.save(vip);

        // Spend ok but not enough events
        Membership notVip1 = seedMembership(orgA, "notvip1@s.com");
        notVip1.setSpendMinor(25000);
        notVip1.setEvents(3);
        membershipRepo.save(notVip1);

        // Events ok but not enough spend
        Membership notVip2 = seedMembership(orgA, "notvip2@s.com");
        notVip2.setSpendMinor(19999);
        notVip2.setEvents(5);
        membershipRepo.save(notVip2);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactly(vip.getMembershipId());
    }

    @Test
    void prebuilt_vip_resolve_count_equals_resolve_size() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "VIP");

        Membership v = seedMembership(orgA, "vip2@s.com");
        v.setSpendMinor(20000);
        v.setEvents(4);
        membershipRepo.save(v);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);
        assertThat(dto.matched()).isEqualTo(list.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 3: Lapsed (recency >= 90 && consent_status = 'subscribed')
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_lapsed_matches_old_subscribed() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Lapsed");

        // Lapsed + subscribed → should match
        Membership lapsed = seedMembership(orgA, "lapsed@s.com");
        lapsed.setRecencyDays(120);
        lapsed.setConsentStatus("subscribed");
        membershipRepo.save(lapsed);

        // Recent subscribed → should NOT match
        Membership recent = seedMembership(orgA, "recent@s.com");
        recent.setRecencyDays(30);
        recent.setConsentStatus("subscribed");
        membershipRepo.save(recent);

        // Lapsed but unsubscribed → should NOT match
        Membership unsubscribed = seedMembership(orgA, "unsub@s.com");
        unsubscribed.setRecencyDays(120);
        unsubscribed.setConsentStatus("unsubscribed");
        membershipRepo.save(unsubscribed);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactly(lapsed.getMembershipId());
    }

    @Test
    void prebuilt_lapsed_resolve_count_equals_resolve_size() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Lapsed");

        Membership l = seedMembership(orgA, "l1@s.com");
        l.setRecencyDays(90);
        l.setConsentStatus("subscribed");
        membershipRepo.save(l);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);
        assertThat(dto.matched()).isEqualTo(list.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 4: First-timers (events == 1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_firsttimers_matches_events_equals_1() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "First-timers");

        Membership first = seedMembership(orgA, "first@s.com");
        first.setEvents(1);
        membershipRepo.save(first);

        Membership repeat = seedMembership(orgA, "repeat2@s.com");
        repeat.setEvents(2);
        membershipRepo.save(repeat);

        Membership prospect = seedMembership(orgA, "prosp@s.com");
        // events=0 by default

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactly(first.getMembershipId());
    }

    @Test
    void prebuilt_firsttimers_resolve_count_equals_resolve_size() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "First-timers");

        Membership m = seedMembership(orgA, "ft1@s.com");
        m.setEvents(1);
        membershipRepo.save(m);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);
        assertThat(dto.matched()).isEqualTo(list.size());
        assertThat(dto.matched()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 5: Promoters (nps >= 9)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_promoters_matches_nps_gte_9() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Promoters");

        Membership promoter = seedMembership(orgA, "prom@s.com");
        promoter.setNps((short) 9);
        membershipRepo.save(promoter);

        Membership highPromoter = seedMembership(orgA, "highprom@s.com");
        highPromoter.setNps((short) 10);
        membershipRepo.save(highPromoter);

        Membership notPromoter = seedMembership(orgA, "notprom@s.com");
        notPromoter.setNps((short) 8);
        membershipRepo.save(notPromoter);

        Membership noNps = seedMembership(orgA, "nonps@s.com");
        // nps=null by default

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactlyInAnyOrder(promoter.getMembershipId(), highPromoter.getMembershipId());
    }

    @Test
    void prebuilt_promoters_resolve_count_equals_resolve_size() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Promoters");

        Membership p = seedMembership(orgA, "p2@s.com");
        p.setNps((short) 9);
        membershipRepo.save(p);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);
        assertThat(dto.matched()).isEqualTo(list.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 6: Bought-no-showed (no_show > 0)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_bought_no_showed_matches_no_show_gt_0() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Bought-no-showed");

        Membership noShow = seedMembership(orgA, "noshow@s.com");
        noShow.setNoShow(1);
        membershipRepo.save(noShow);

        Membership attended = seedMembership(orgA, "attended@s.com");
        attended.setAttended(1);
        // noShow=0 by default
        membershipRepo.save(attended);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactly(noShow.getMembershipId());
    }

    @Test
    void prebuilt_bought_no_showed_resolve_count_equals_resolve_size() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Bought-no-showed");

        Membership ns = seedMembership(orgA, "ns2@s.com");
        ns.setNoShow(2);
        membershipRepo.save(ns);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);
        assertThat(dto.matched()).isEqualTo(list.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prebuilt segment 7: Newest-30d (recency <= 30 && events <= 1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void prebuilt_newest_30d_matches_recency_lte_30_events_lte_1() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Newest-30d");

        // Should match: recent, single-event
        Membership newest = seedMembership(orgA, "newest@s.com");
        newest.setRecencyDays(15);
        newest.setEvents(1);
        membershipRepo.save(newest);

        // Should match: recency=30, events=0 (prospect who signed up recently)
        Membership prospect = seedMembership(orgA, "newprospect@s.com");
        prospect.setRecencyDays(30);
        prospect.setEvents(0);
        membershipRepo.save(prospect);

        // Should NOT match: too old
        Membership old = seedMembership(orgA, "old@s.com");
        old.setRecencyDays(31);
        old.setEvents(1);
        membershipRepo.save(old);

        // Should NOT match: recent but repeat
        Membership repeatRecent = seedMembership(orgA, "repeatrecent@s.com");
        repeatRecent.setRecencyDays(5);
        repeatRecent.setEvents(2);
        membershipRepo.save(repeatRecent);

        List<Membership> resolved = segmentService.resolveMembers(orgA, seg);
        assertThat(resolved).extracting(Membership::getMembershipId)
                .containsExactlyInAnyOrder(newest.getMembershipId(), prospect.getMembershipId());
    }

    @Test
    void prebuilt_newest30d_resolve_count_equals_resolve_size() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Newest-30d");

        Membership m = seedMembership(orgA, "n2@s.com");
        m.setRecencyDays(7);
        m.setEvents(1);
        membershipRepo.save(m);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);
        assertThat(dto.matched()).isEqualTo(list.size());
        assertThat(dto.matched()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static snapshot is frozen — dynamic re-evaluates
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void static_snapshot_frozen_while_dynamic_reevaluates() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "Repeat");

        // No repeats yet → resolve = 0
        assertThat(segmentService.resolveMembers(orgA, seg)).isEmpty();

        // Take snapshot when empty
        segmentService.snapshot(orgA, seg.getId(), principalA);
        Segment snapped = segmentRepo.findByIdAndOrgId(seg.getId(), orgA).orElseThrow();
        assertThat(snapped.getKind()).isEqualTo("static");

        // Add a repeat member AFTER snapshot
        Membership m = seedMembership(orgA, "aftersnap@s.com");
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
        segmentService.ensurePrebuiltSegments(orgA);
        Segment seg = findPrebuilt(orgA, "VIP");

        // Add a VIP
        Membership vip = seedMembership(orgA, "snapvip@s.com");
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

        // Matches both conditions
        Membership both = seedMembership(orgA, "bigspend@s.com");
        both.setEvents(5);
        both.setSpendMinor(15000);
        membershipRepo.save(both);

        // Enough events but not enough spend
        Membership eventsOnly = seedMembership(orgA, "eventsonly@s.com");
        eventsOnly.setEvents(5);
        eventsOnly.setSpendMinor(5000);
        membershipRepo.save(eventsOnly);

        // Enough spend but not enough events
        Membership spendOnly = seedMembership(orgA, "spendonly@s.com");
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

        Membership r1 = seedMembership(orgA, "cr1@s.com");
        r1.setEvents(2);
        membershipRepo.save(r1);
        Membership r2 = seedMembership(orgA, "cr2@s.com");
        r2.setEvents(3);
        membershipRepo.save(r2);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        List<Membership> list = segmentService.resolveMembers(orgA, seg);
        assertThat(dto.matched()).isEqualTo(list.size());
        assertThat(dto.matched()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ensure 7 prebuilt segments are provisioned exactly once
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void ensure_prebuilt_segments_idempotent() {
        segmentService.ensurePrebuiltSegments(orgA);
        segmentService.ensurePrebuiltSegments(orgA); // second call is no-op

        List<Segment> segments = segmentRepo.findByOrgId(orgA);
        long prebuilt = segments.stream().filter(Segment::isPrebuilt).count();
        assertThat(prebuilt).isEqualTo(7);
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

        // Member with consent
        Membership withConsent = seedMembership(orgA, "consented@s.com");
        withConsent.setEvents(3);
        withConsent.setConsentStatus("subscribed");
        withConsent.setConsentBasis("explicit");
        membershipRepo.save(withConsent);

        // Member without consent
        Membership noConsent = seedMembership(orgA, "noconsent@s.com");
        noConsent.setEvents(2);
        // consentStatus='never' by default, consentBasis=null
        membershipRepo.save(noConsent);

        SegmentResolveDto dto = segmentService.resolve(orgA, seg.getId());
        assertThat(dto.matched()).isEqualTo(2);
        assertThat(dto.mailable()).isEqualTo(1);
        assertThat(dto.excluded()).isEqualTo(1);
        assertThat(dto.mailable()).isLessThanOrEqualTo(dto.matched());
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

    private Organization org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail(name + "@test.com");
        o.setCountry("DE");
        return orgRepo.save(o);
    }

    private void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from suppression_entries");
            s.execute("delete from consent_records");
            s.execute("delete from segments");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
            s.execute("delete from tickets");
            s.execute("delete from orders");
            s.execute("delete from events");
            s.execute("delete from users");
            s.execute("delete from organizations");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}
