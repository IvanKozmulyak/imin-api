package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.engine.CalibrationSource;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Input;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Result;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Segment;
import com.imin.iminapi.audienceplan.engine.Exclusions;
import com.imin.iminapi.audienceplan.engine.ResponseModel;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.service.MarketingGuardProperties;
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
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CandidateLoader's SQL inputs, one test per branch. Run on H2 ({@link CandidateLoaderTest}) and on
 * Postgres 17 ({@link CandidateLoaderPostgresTest}) because the inputs are native queries.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
abstract class CandidateLoaderScenarios {

    static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    static final Instant RECENT = NOW.minus(10, ChronoUnit.DAYS);
    static final String NAMED_VERSION = "checkout-named-v1";

    @Autowired FanFeatureRepository fanRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AudiencePlanLogic shippedLogic;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AuditLogger auditLogger;

    UUID orgA;
    UUID orgB;
    Event houseNight;
    Event otherNight;
    private final List<UUID> orgs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orgA = org();
        orgB = org();
        houseNight = event(orgA, "House & Techno");
        otherNight = event(orgA, "Pop");
    }

    @AfterEach
    void tearDown() {
        // Consumers go last: one consumer may hold memberships in several orgs.
        List<UUID> consumers = new ArrayList<>();
        for (UUID org : orgs) {
            List<UUID> mids = jdbc.queryForList("select membership_id from memberships where org_id = ?", UUID.class, org);
            List<UUID> cids = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, org);
            jdbc.update("delete from campaign_recipients where campaign_id in (select id from campaigns where org_id = ?)", org);
            jdbc.update("delete from campaigns where org_id = ?", org);
            for (UUID mid : mids) {
                jdbc.update("delete from consent_records where membership_id = ?", mid);
                jdbc.update("delete from fan_features where membership_id = ?", mid);
            }
            jdbc.update("delete from memberships where org_id = ?", org);
            consumers.addAll(cids);
            jdbc.update("delete from tickets where order_id in (select id from orders where org_id = ?)", org);
            jdbc.update("delete from orders where org_id = ?", org);
            jdbc.update("delete from events where org_id = ?", org);
            jdbc.update("delete from users where org_id = ?", org);
            jdbc.update("delete from organizations where id = ?", org);
        }
        for (UUID cid : consumers) jdbc.update("delete from consumers where consumer_id = ?", cid);
        orgs.clear();
    }

    // ── who is a candidate ─────────────────────────────────────────────────

    @Test
    void onlyConsentGateMailableMembersAreCandidates_theRestArriveAsGateCounts() {
        UUID mailable = mailable(orgA);
        member(orgA);

        Input in = loader().input(orgA, houseNight, 255, 1.6);

        assertThat(in.mailable()).extracting(Person::membershipId).containsExactly(mailable);
        assertThat(in.consentGateExclusions()).containsEntry(ConsentGate.NO_BASIS, 1);
        assertThat(in.eventGenreKey()).isEqualTo("house & techno");
        assertThat(in.targetTickets()).isEqualTo(255);
        assertThat(in.ticketsPerOrder()).isEqualTo(1.6);
    }

    @Test
    void featureRow_givesClassTasteAndNoShows() {
        UUID mid = mailable(orgA);
        features(mid, "loyal", "{\"house & techno\":0.75,\"pop\":0.25}", 2);

        Person p = only(loader().input(orgA, houseNight, 255, 1.6));

        assertThat(p.classKey()).isEqualTo("loyal");
        assertThat(p.taste()).isEqualTo(Map.of("house & techno", 0.75, "pop", 0.25));
        assertThat(p.noShowN()).isEqualTo(2);
    }

    @Test
    void noFeatureRow_givesNoClassAndEmptyTaste() {
        mailable(orgA);

        Person p = only(loader().input(orgA, houseNight, 255, 1.6));

        assertThat(p.classKey()).isNull();
        assertThat(p.taste()).isEmpty();
        assertThat(p.noShowN()).isZero();
    }

    @Test
    void unreadableTaste_isEmpty() {
        UUID mid = mailable(orgA);
        features(mid, "loyal", "not json", 0);

        assertThat(only(loader().input(orgA, houseNight, 255, 1.6)).taste()).isEmpty();
    }

    // ── bought this event ─────────────────────────────────────────────────

    @Test
    void liveTicketForThisEvent_isBought() {
        UUID mid = mailable(orgA);
        order(orgA, houseNight, emailOf(mid), false, "issued");

        assertThat(only(loader().input(orgA, houseNight, 255, 1.6)).boughtThisEvent()).isTrue();
    }

    @Test
    void redeemedTicketForThisEvent_isBought() {
        UUID mid = mailable(orgA);
        order(orgA, houseNight, emailOf(mid), false, "redeemed");

        assertThat(only(loader().input(orgA, houseNight, 255, 1.6)).boughtThisEvent()).isTrue();
    }

    @Test
    void refundedOrRevokedTicketOnly_isNotBought() {
        UUID mid = mailable(orgA);
        order(orgA, houseNight, emailOf(mid), false, "refunded");
        order(orgA, houseNight, emailOf(mid), false, "revoked");

        assertThat(only(loader().input(orgA, houseNight, 255, 1.6)).boughtThisEvent()).isFalse();
    }

    @Test
    void testModeOrder_isNotBought() {
        UUID mid = mailable(orgA);
        order(orgA, houseNight, emailOf(mid), true, "issued");

        assertThat(only(loader().input(orgA, houseNight, 255, 1.6)).boughtThisEvent()).isFalse();
    }

    @Test
    void ticketForAnotherEvent_isNotBought() {
        UUID mid = mailable(orgA);
        order(orgA, otherNight, emailOf(mid), false, "issued");

        assertThat(only(loader().input(orgA, houseNight, 255, 1.6)).boughtThisEvent()).isFalse();
    }

    // ── sends ─────────────────────────────────────────────────────────────

    @Test
    void sentWithinTheFloor_isContacted() {
        UUID mid = mailable(orgA);
        send(orgA, otherNight.getId(), mid, "delivered", NOW.minus(47, ChronoUnit.HOURS));

        assertThat(only(loader().input(orgA, houseNight, 255, 1.6)).contactedWithinFloor()).isTrue();
    }

    @Test
    void sentBeforeTheFloor_isNotContacted() {
        UUID mid = mailable(orgA);
        send(orgA, otherNight.getId(), mid, "sent", NOW.minus(49, ChronoUnit.HOURS));

        Person p = only(loader().input(orgA, houseNight, 255, 1.6));
        assertThat(p.contactedWithinFloor()).isFalse();
        assertThat(p.sends30d()).isEqualTo(1);
    }

    @Test
    void theFloorFollowsTheMarketingGuardSetting() {
        UUID mid = mailable(orgA);
        send(orgA, otherNight.getId(), mid, "sent", NOW.minus(49, ChronoUnit.HOURS));

        assertThat(only(loader(72).input(orgA, houseNight, 255, 1.6)).contactedWithinFloor()).isTrue();
    }

    @Test
    void rowsThatNeverLeft_areNotSends() {
        UUID mid = mailable(orgA);
        // unsubscribed/complained rows are not counted either: the gate already drops those people.
        for (String status : List.of("pending", "skipped", "failed", "bounced", "unsubscribed", "complained")) {
            send(orgA, houseNight.getId(), mid, status, NOW.minus(1, ChronoUnit.HOURS));
        }

        Person p = only(loader().input(orgA, houseNight, 255, 1.6));
        assertThat(p.contactedWithinFloor()).isFalse();
        assertThat(p.sendsThisEvent()).isZero();
        assertThat(p.sends30d()).isZero();
    }

    @Test
    void sendsForThisEvent_countAtAnyAge() {
        UUID mid = mailable(orgA);
        send(orgA, houseNight.getId(), mid, "sent", NOW.minus(60, ChronoUnit.DAYS));
        send(orgA, houseNight.getId(), mid, "opened", NOW.minus(90, ChronoUnit.DAYS));
        send(orgA, otherNight.getId(), mid, "sent", NOW.minus(60, ChronoUnit.DAYS));

        Person p = only(loader().input(orgA, houseNight, 255, 1.6));
        assertThat(p.sendsThisEvent()).isEqualTo(2);
        assertThat(p.sends30d()).isZero();
    }

    @Test
    void sendsIn30Days_countInside_notOutside() {
        UUID mid = mailable(orgA);
        send(orgA, null, mid, "sent", NOW.minus(29, ChronoUnit.DAYS));
        send(orgA, null, mid, "clicked", NOW.minus(3, ChronoUnit.DAYS));
        send(orgA, null, mid, "sent", NOW.minus(31, ChronoUnit.DAYS));

        Person p = only(loader().input(orgA, houseNight, 255, 1.6));
        assertThat(p.sends30d()).isEqualTo(2);
        assertThat(p.sendsThisEvent()).isZero();
    }

    // ── scope ─────────────────────────────────────────────────────────────

    @Test
    void anotherOrgsEvent_isRefused() {
        Event foreign = event(orgB, "House & Techno");
        assertThatThrownBy(() -> loader().input(orgA, foreign, 255, 1.6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loader().input(orgA, null, 255, 1.6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anotherOrgsMembersAndSends_neverEnter() {
        UUID mine = mailable(orgA);
        UUID theirs = mailable(orgB);
        features(theirs, "loyal", "{\"house & techno\":1.0}", 0);
        order(orgB, event(orgB, "House & Techno"), emailOf(theirs), false, "issued");
        send(orgB, null, theirs, "sent", NOW.minus(1, ChronoUnit.HOURS));

        Input in = loader().input(orgA, houseNight, 255, 1.6);

        assertThat(in.mailable()).extracting(Person::membershipId).containsExactly(mine);
    }

    @Test
    void aSharedConsumersOtherOrgOrdersAndSends_neverLeakIn() {
        UUID mine = mailable(orgA);
        UUID consumer = jdbc.queryForObject("select consumer_id from memberships where membership_id = ?",
                UUID.class, mine);
        UUID theirs = member(orgB, consumer);
        String email = emailOf(mine);
        order(orgB, event(orgB, "House & Techno"), email, false, "issued");
        order(orgB, houseNight, email, false, "issued");
        send(orgB, houseNight.getId(), theirs, "sent", NOW.minus(1, ChronoUnit.HOURS));
        send(orgB, null, theirs, "delivered", NOW.minus(2, ChronoUnit.HOURS));

        Person p = only(loader().input(orgA, houseNight, 255, 1.6));

        assertThat(p.membershipId()).isEqualTo(mine);
        assertThat(p.boughtThisEvent()).isFalse();
        assertThat(p.contactedWithinFloor()).isFalse();
        assertThat(p.sendsThisEvent()).isZero();
        assertThat(p.sends30d()).isZero();
    }

    // ── end to end ────────────────────────────────────────────────────────

    @Test
    void build_groupsTenLoyalHouseFansIntoOneSegment_andCountsTheBuyer() {
        for (int i = 0; i < 10; i++) features(mailable(orgA), "loyal", "{\"house & techno\":1.0}", 0);
        UUID buyer = mailable(orgA);
        features(buyer, "loyal", "{\"house & techno\":1.0}", 0);
        order(orgA, houseNight, emailOf(buyer), false, "issued");

        Result r = loader().build(orgA, houseNight, 255, 1.6);

        assertThat(r.segments()).singleElement().satisfies(s -> {
            assertThat(s.classKey()).isEqualTo("loyal");
            assertThat(s.fit()).isEqualTo(Fit.SAME);
            assertThat(s.mailable()).isEqualTo(10);
            assertThat(s.membershipIds()).doesNotContain(buyer);
        });
        assertThat(r.exclusions()).containsEntry(Exclusions.BOUGHT_THIS_EVENT, 1);
        assertThat(r.segments()).extracting(Segment::mailable).containsExactly(10);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    CandidateLoader loader() {
        return loader(48);
    }

    CandidateLoader loader(int floorHours) {
        AudiencePlanLogic logic = ConsentGateScenarios.withNamedVersions(shippedLogic, Set.of(NAMED_VERSION));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ConsentGate gate = new ConsentGate(fanRepo, orgRepo, logic, new AudiencePlanProperties(), clock);
        MarketingGuardProperties guard = new MarketingGuardProperties();
        guard.setFrequencyFloorHours(floorHours);
        return new CandidateLoader(gate, fanRepo, logic, new ResponseModel(logic, CalibrationSource.NONE), guard, clock);
    }

    static Person only(Input in) {
        assertThat(in.mailable()).hasSize(1);
        return in.mailable().get(0);
    }

    UUID org() {
        Organization o = new Organization();
        o.setName("Candidate Org");
        o.setSlug("cand-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("cand@example.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    UUID member(UUID orgId) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("cand-" + UUID.randomUUID() + "@example.com");
        return member(orgId, consumerRepo.save(c).getConsumerId());
    }

    UUID member(UUID orgId, UUID consumerId) {
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumerId);
        return membershipRepo.save(m).getMembershipId();
    }

    /** A member with a checkout consent under an organizer-named text: plan-mailable. */
    UUID mailable(UUID orgId) {
        UUID mid = member(orgId);
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(mid);
        r.setStatus("subscribed");
        r.setLawfulBasis("explicit");
        r.setSource("checkout");
        r.setProofText("proof");
        r.setTextVersion(NAMED_VERSION);
        r.setOrderId(UUID.randomUUID());
        r.setOccurredAt(RECENT);
        consentRepo.save(r);
        jdbc.update("update memberships set consent_status = 'subscribed', consent_basis = 'explicit'"
                + " where membership_id = ?", mid);
        return mid;
    }

    void features(UUID mid, String fanClass, String taste, int noShowN) {
        UUID orgId = jdbc.queryForObject("select org_id from memberships where membership_id = ?", UUID.class, mid);
        FanFeature f = new FanFeature();
        f.setMembershipId(mid);
        f.setOrgId(orgId);
        f.setFanClass(fanClass);
        f.setTaste(taste);
        f.setNoShowN(noShowN);
        f.setLastContactFromPersonAt(RECENT);
        f.setLogicVersion(1);
        fanRepo.save(f);
    }

    String emailOf(UUID mid) {
        return jdbc.queryForObject("select c.normalized_email from consumers c join memberships m "
                + "on m.consumer_id = c.consumer_id where m.membership_id = ?", String.class, mid);
    }

    Event event(UUID orgId, String genre) {
        User owner = new User();
        owner.setEmail("cand-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        owner = userRepo.save(owner);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Candidate Night");
        e.setSlug("cand-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setGenre(genre);
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return eventRepo.save(e);
    }

    void order(UUID orgId, Event event, String email, boolean testMode, String ticketState) {
        Order o = new Order();
        o.setToken("cand-" + UUID.randomUUID());
        o.setEventId(event.getId());
        o.setOrgId(orgId);
        o.setEmail(email);
        o.setTotalMinor(2000);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setTestMode(testMode);
        o = orderRepo.save(o);
        Ticket t = new Ticket();
        t.setToken("cand-t-" + UUID.randomUUID());
        t.setOrderId(o.getId());
        t.setEventId(event.getId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor(2000);
        t.setState(ticketState);
        ticketRepo.save(t);
    }

    /** One campaign per recipient row, since a campaign holds a member at most once. */
    void send(UUID orgId, UUID eventId, UUID mid, String status, Instant at) {
        UUID campaignId = UUID.randomUUID();
        jdbc.update("insert into campaigns (id, org_id, channel, name, status, event_id) values (?, ?, 'email', 'c', 'sent', ?)",
                campaignId, orgId, eventId);
        jdbc.update("insert into campaign_recipients (id, campaign_id, membership_id, status, last_event_at)"
                + " values (?, ?, ?, ?, ?)", UUID.randomUUID(), campaignId, mid, status, Timestamp.from(at));
    }
}
