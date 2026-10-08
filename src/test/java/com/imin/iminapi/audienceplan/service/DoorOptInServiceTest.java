package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.ErasedAddress;
import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.audienceplan.dto.DoorOptInPageResponse;
import com.imin.iminapi.audienceplan.dto.DoorOptInRequest;
import com.imin.iminapi.audienceplan.dto.DoorOptInResponse;
import com.imin.iminapi.audienceplan.dto.DoorOptInSettingsResponse;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@IminIntegrationTest
class DoorOptInServiceTest {

    private static final String ORG_NAME = "Vechirka Door";
    private static final String VERSION = "door-org-named-2026-09";
    private static final String CHECKOUT_VERSION = "checkout-org-named-2026-09";
    private static final String TEXT = "Email me about events by " + ORG_NAME
            + ". I agree to receive email marketing and can unsubscribe any time, one click in every email.";

    @Autowired DoorOptInService service;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired ErasedAddressRepository erased;
    @Autowired MarketingOptOutRepository optOuts;
    @Autowired SuppressionRepository suppressions;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired AudienceOrderProjector projector;
    @Autowired ConsentService consentService;
    @Autowired com.imin.iminapi.audience.service.ConsentConfirmationService confirmations;
    @Autowired AudiencePlanLogic logic;
    @Autowired EmailProperties emailProps;
    @Autowired ConsentGate gate;
    @Autowired SendGateService sendGate;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;

    private final List<String> platformErased = new ArrayList<>();
    private UUID orgId;
    private UUID otherOrgId;
    private UUID ownerId;
    private AuthPrincipal organizer;
    private Event event;
    private String token;

    @BeforeEach
    void setUp() {
        orgId = org(ORG_NAME);
        otherOrgId = org("Someone Else");
        User owner = new User();
        owner.setEmail("door-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        ownerId = users.save(owner).getId();
        organizer = new AuthPrincipal(ownerId, orgId, UserRole.ADMIN, UUID.randomUUID());
        event = event(orgId, EventStatus.LIVE, true);
        token = service.setEnabled(organizer, event.getId(), true).doorUrl().replaceAll(".*\\?t=", "");
    }

    @AfterEach
    void tearDown() {
        // A stored sign-up recomputes the member's features on the live pool; let it finish before the rows go.
        AsyncDrain.drain(fanFeatureExecutor);
        for (UUID org : List.of(orgId, otherOrgId)) {
            List<UUID> cids = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, org);
            jdbc.update("delete from consent_records where membership_id in (select membership_id from memberships where org_id = ?)", org);
            jdbc.update("delete from fan_features where org_id = ?", org);
            jdbc.update("delete from suppression_entries where org_id = ?", org);
            jdbc.update("delete from memberships where org_id = ?", org);
            for (UUID cid : cids) jdbc.update("delete from consumers where consumer_id = ?", cid);
            jdbc.update("delete from marketing_optouts where org_id = ?", org);
            jdbc.update("delete from erased_addresses where org_id = ?", org);
            jdbc.update("delete from events where org_id = ?", org);
        }
        jdbc.update("delete from users where org_id = ?", orgId);
        jdbc.update("delete from organizations where id in (?, ?)", orgId, otherOrgId);
        for (String email : platformErased) {
            jdbc.update("delete from erased_addresses where org_id is null and email_normalized = ?", email);
        }
    }

    // ── sign-up ────────────────────────────────────────────────────────────

    @Test
    void ticked_storesOneExplicitDoorRecordWithTextVersionEventAndLocale() {
        String email = addr("guest");
        DoorOptInResponse r = service.optIn(event.getId(), body(email.toUpperCase(Locale.ROOT), true, TEXT, VERSION, "fr"));

        assertThat(r.received()).isTrue();
        List<Map<String, Object>> rows = consentRows(email);
        assertThat(rows).hasSize(1);
        Map<String, Object> c = rows.get(0);
        assertThat(c.get("lawful_basis")).isEqualTo("explicit");
        assertThat(c.get("source")).isEqualTo("door_qr");
        assertThat(c.get("channel")).isEqualTo("email");
        assertThat(c.get("status")).isEqualTo("subscribed");
        assertThat(c.get("text_version")).isEqualTo(VERSION);
        assertThat(c.get("event_id")).isEqualTo(event.getId());
        assertThat(c.get("order_id")).isNull();
        assertThat((String) c.get("proof_text")).contains("\"" + TEXT + "\"").contains("(locale fr)")
                .contains(event.getId().toString());
        assertThat(c.get("confirmation_required")).isEqualTo(true);
        assertThat(c.get("confirmed_at")).isNull();
    }

    // ── pending confirmation ───────────────────────────────────────────────

    @Test
    void signUpAlone_isMailableByNeitherGateUntilConfirmed() {
        String email = addr("pending");
        service.optIn(event.getId(), body(email, true, TEXT, VERSION, "en"));

        Map<String, Object> m = membership(email);
        UUID mid = (UUID) m.get("membership_id");
        assertThat(m.get("consent_status")).isEqualTo("never");
        assertThat(m.get("consent_basis")).isNull();
        SendGateService.GateResult send = sendGate.evaluate(orgId, List.of(mid));
        assertThat(send.sendable()).isEmpty();
        assertThat(send.excluded()).extracting(ExclusionReason::reason).containsExactly("no_lawful_basis");
        assertThat(gate.reasons(orgId, List.of(mid))).containsEntry(mid, Optional.of(ConsentGate.NO_BASIS));
        assertThat(gate.canMarket(orgId, mid)).isFalse();
    }

    @Test
    void signUpOfAMemberWithACheckoutConsent_keepsThemMailableByBothGates() {
        String email = addr("buyer");
        projector.upsertMembership(orgId, email, null);
        UUID mid = (UUID) membership(email).get("membership_id");
        consentService.capture(orgId, mid, "explicit", "checkout", "Ticked at checkout", "email",
                CHECKOUT_VERSION, null, ConsentOrigin.DATA_SUBJECT, null);

        service.optIn(event.getId(), body(email, true, TEXT, VERSION, "en"));

        Map<String, Object> m = membership(email);
        assertThat(m.get("consent_status")).isEqualTo("subscribed");
        assertThat(m.get("consent_basis")).isEqualTo("explicit");
        assertThat(sendGate.evaluate(orgId, List.of(mid)).sendable()).containsExactly(mid);
        assertThat(gate.canMarket(orgId, mid)).isTrue();
        assertThat(consentRows(email)).extracting(r -> r.get("source"))
                .containsExactlyInAnyOrder("checkout", "door_qr");
    }

    @Test
    void confirmedSignUp_isMailableByTheGate() {
        String email = addr("confirmed");
        service.optIn(event.getId(), body(email, true, TEXT, VERSION, "en"));
        UUID mid = (UUID) membership(email).get("membership_id");

        jdbc.update("update consent_records set confirmed_at = ? where membership_id = ?",
                Timestamp.from(clock.instant()), mid);

        assertThat(gate.canMarket(orgId, mid)).isTrue();
    }

    @Test
    void existingObjection_staysUntilTheSignUpIsConfirmed() {
        String email = addr("objector");
        projector.upsertMembership(orgId, email, null);
        jdbc.update("update memberships set objected_profiling = true where org_id = ?", orgId);

        service.optIn(event.getId(), body(email, true, TEXT, VERSION, "en"));

        assertThat(membership(email).get("objected_profiling")).isEqualTo(true);
        assertThat(consentRows(email)).hasSize(1);
    }

    @Test
    void unsupportedLocale_isRecordedAsEnglish() {
        String email = addr("loc");
        service.optIn(event.getId(), body(email, true, TEXT, VERSION, "de"));
        assertThat((String) consentRows(email).get(0).get("proof_text")).contains("(locale en)");
    }

    @Test
    void locale_normalizesCaseAndSpaceAndFallsBackToEnglish() {
        assertThat(DoorOptInService.locale(" UK ")).isEqualTo("uk");
        assertThat(DoorOptInService.locale(null)).isEqualTo("en");
        assertThat(DoorOptInService.locale("pt")).isEqualTo("en");
    }

    // ── validation ────────────────────────────────────────────────────────

    /** A null email column means a fresh valid address, so only the named field is wrong. */
    static Stream<Arguments> invalidSignUps() {
        return Stream.of(
                arguments("box not ticked (null)", null, null, TEXT, VERSION, "consentGiven"),
                arguments("box not ticked (false)", null, false, TEXT, VERSION, "consentGiven"),
                arguments("blank email", "  ", true, TEXT, VERSION, "email"),
                arguments("overlong email", "a".repeat(250) + "@door.test", true, TEXT, VERSION, "email"),
                arguments("malformed email", "not-an-email", true, TEXT, VERSION, "email"),
                arguments("no text version", null, true, TEXT, null, "consentTextVersion"),
                arguments("the checkout text version", null, true, TEXT, CHECKOUT_VERSION, "consentTextVersion"),
                arguments("an unknown door version", null, true, TEXT, "door-v0", "consentTextVersion"),
                arguments("blank text", null, true, " ", VERSION, "consentText"),
                arguments("overlong text", null, true, TEXT + "x".repeat(2001), VERSION, "consentText"),
                arguments("text that does not name the organizer", null, true,
                        "Email me about this organiser's events. I agree to receive email marketing.", VERSION,
                        "consentText"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSignUps")
    void anInvalidSignUp_is400NamingTheField_andStoresNothing(String label, String email, Boolean ticked, String text,
                                                              String version, String field) {
        String address = email == null ? addr("invalid") : email;
        assertInvalid(() -> service.optIn(event.getId(), body(address, ticked, text, version, "en")), field);
        assertNothingStored(address);
    }

    @Test
    void organizerNameMatch_ignoresCase() {
        String email = addr("case");
        service.optIn(event.getId(), body(email, true, TEXT.replace(ORG_NAME, "VECHIRKA DOOR"), VERSION, "en"));
        assertThat(consentRows(email)).hasSize(1);
    }

    // ── 404s (one per condition) ──────────────────────────────────────────

    enum Closed {
        SWITCHED_OFF, WRONG_TOKEN, MISSING_TOKEN, MISSING_BODY, UNKNOWN_EVENT, DELETED_EVENT, DRAFT_EVENT,
        CANCELLED_EVENT, UNPUBLISHED_EVENT, KILL_SWITCH, ORG_OFF_THE_ALLOW_LIST
    }

    @ParameterizedTest
    @EnumSource(Closed.class)
    void aClosedDoorPage_is404_andStoresNothing(Closed closed) {
        String email = addr("closed");
        DoorOptInService svc = service;
        UUID eventId = event.getId();
        DoorOptInRequest request = body(email, true, TEXT, VERSION, "en");
        switch (closed) {
            case SWITCHED_OFF -> service.setEnabled(organizer, event.getId(), false);
            case WRONG_TOKEN -> request = new DoorOptInRequest("wrong", email, true, TEXT, VERSION, "en");
            case MISSING_TOKEN -> request = new DoorOptInRequest(null, email, true, TEXT, VERSION, "en");
            case MISSING_BODY -> request = null;
            case UNKNOWN_EVENT -> eventId = UUID.randomUUID();
            case DELETED_EVENT -> jdbc.update("update events set deleted_at = ? where id = ?",
                    Timestamp.from(clock.instant()), event.getId());
            case DRAFT_EVENT -> jdbc.update("update events set status = 'DRAFT' where id = ?", event.getId());
            case CANCELLED_EVENT -> jdbc.update("update events set status = 'CANCELLED' where id = ?", event.getId());
            case UNPUBLISHED_EVENT -> jdbc.update("update events set published_at = null where id = ?", event.getId());
            case KILL_SWITCH -> {
                AudiencePlanProperties off = new AudiencePlanProperties();
                off.setEnabled(false);
                svc = serviceWith(off);
            }
            case ORG_OFF_THE_ALLOW_LIST -> {
                AudiencePlanProperties listed = new AudiencePlanProperties();
                listed.setEnabled(true);
                listed.setBetaOrgIds(Set.of(otherOrgId));
                svc = serviceWith(listed);
            }
        }
        DoorOptInService target = svc;
        UUID id = eventId;
        DoorOptInRequest req = request;

        assertNotFound(() -> target.optIn(id, req));
        assertNothingStored(email);
    }

    @Test
    void pastEvent_staysOpen() {
        String email = addr("late");
        jdbc.update("update events set status = 'PAST' where id = ?", event.getId());
        service.optIn(event.getId(), body(email, true, TEXT, VERSION, "en"));
        assertThat(consentRows(email)).hasSize(1);
    }

    // ── accepted without storing ──────────────────────────────────────────

    enum Blocked {
        ERASED_FOR_THIS_ORG(false), ERASED_PLATFORM_WIDE(false), STICKY_OPT_OUT(false), UNSUBSCRIBED_MEMBER(true),
        MARKETING_SUPPRESSED_MEMBER(true), ERASE_PENDING_MEMBER(true);

        final boolean member;

        Blocked(boolean member) {
            this.member = member;
        }
    }

    @ParameterizedTest
    @EnumSource(Blocked.class)
    void aBlockedAddress_isAccepted_andNothingIsStoredOrResubscribed(Blocked blocked) {
        String email = addr("blocked");
        if (blocked.member) projector.upsertMembership(orgId, email, null);
        switch (blocked) {
            case ERASED_FOR_THIS_ORG -> erase(orgId, email);
            case ERASED_PLATFORM_WIDE -> erase(null, email);
            case STICKY_OPT_OUT -> {
                MarketingOptOut o = new MarketingOptOut();
                o.setEmailNormalized(email);
                o.setOrgId(orgId);
                o.setChannel("email");
                o.setSource("unsubscribe_link");
                optOuts.save(o);
            }
            case UNSUBSCRIBED_MEMBER ->
                    jdbc.update("update memberships set consent_status = 'unsubscribed' where org_id = ?", orgId);
            case MARKETING_SUPPRESSED_MEMBER -> {
                SuppressionEntry s = new SuppressionEntry();
                s.setScope(SuppressionEntry.SCOPE_MARKETING);
                s.setOrgId(orgId);
                s.setMembershipId((UUID) membership(email).get("membership_id"));
                s.setReason("manual");
                suppressions.save(s);
            }
            case ERASE_PENDING_MEMBER ->
                    jdbc.update("update memberships set status = 'erase_pending' where org_id = ?", orgId);
        }
        Map<String, Object> before = blocked.member ? membership(email) : null;

        assertThat(service.optIn(event.getId(), body(email, true, TEXT, VERSION, "en")).received()).isTrue();

        if (blocked.member) {
            assertThat(consentRows(email)).isEmpty();
            assertThat(membership(email)).isEqualTo(before);
        } else {
            assertNothingStored(email);
        }
    }

    // ── public page ───────────────────────────────────────────────────────

    @Test
    void page_showsEventAndTheOrganizerNameTheSentenceMustContain() {
        DoorOptInPageResponse p = service.page(event.getId(), token);

        assertThat(p.eventId()).isEqualTo(event.getId());
        assertThat(p.eventName()).isEqualTo("Door Night");
        assertThat(p.organizerName()).isEqualTo(ORG_NAME);
        assertThat(p.startsAt()).isEqualTo(event.getStartsAt());
        assertThat(p.timezone()).isEqualTo("Europe/Paris");
        assertThat(p.venueCity()).isEqualTo("Metz");
    }

    @Test
    void page_wrongToken_is404() {
        assertNotFound(() -> service.page(event.getId(), token + "x"));
    }

    // ── organizer ─────────────────────────────────────────────────────────

    @Test
    void settings_neverEnabled_hasNoUrlAndZeroSignups() {
        Event fresh = event(orgId, EventStatus.LIVE, false);

        DoorOptInSettingsResponse s = service.settings(organizer, fresh.getId());

        assertThat(s.enabled()).isFalse();
        assertThat(s.doorUrl()).isNull();
        assertThat(s.signups()).isZero();
    }

    @Test
    void enable_createsATokenOnTheBuyerDomainWithoutBumpingTheEditEtag() {
        Event fresh = event(orgId, EventStatus.LIVE, false);
        Instant before = events.findById(fresh.getId()).orElseThrow().getUpdatedAt();

        DoorOptInSettingsResponse s = service.setEnabled(organizer, fresh.getId(), true);

        Event stored = events.findById(fresh.getId()).orElseThrow();
        assertThat(stored.isDoorOptinEnabled()).isTrue();
        assertThat(stored.getDoorOptinToken()).matches("[A-Za-z0-9_-]{22}");
        assertThat(s.enabled()).isTrue();
        assertThat(s.doorUrl()).isEqualTo("https://app.imin.wtf/e/" + fresh.getId() + "/door?t=" + stored.getDoorOptinToken());
        assertThat(stored.getUpdatedAt()).isEqualTo(before);
    }

    @Test
    void disable_keepsTheTokenAndReEnableReusesIt() {
        DoorOptInSettingsResponse off = service.setEnabled(organizer, event.getId(), false);
        assertThat(off.enabled()).isFalse();
        assertThat(off.doorUrl()).endsWith("?t=" + token);
        assertThat(events.findById(event.getId()).orElseThrow().isDoorOptinEnabled()).isFalse();

        DoorOptInSettingsResponse on = service.setEnabled(organizer, event.getId(), true);
        assertThat(on.doorUrl()).endsWith("?t=" + token);
    }

    @Test
    void member_canReadSettingsButCannotSwitch() {
        AuthPrincipal member = new AuthPrincipal(ownerId, orgId, UserRole.MEMBER, UUID.randomUUID());

        assertThat(service.settings(member, event.getId()).enabled()).isTrue();
        assertThatThrownBy(() -> service.setEnabled(member, event.getId(), false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(events.findById(event.getId()).orElseThrow().isDoorOptinEnabled()).isTrue();
    }

    @Test
    void admin_canSwitch() {
        assertThat(service.setEnabled(organizer, event.getId(), false).enabled()).isFalse();
        assertThat(events.findById(event.getId()).orElseThrow().isDoorOptinEnabled()).isFalse();
    }

    enum Hidden { OTHER_ORGS_EVENT, DELETED_EVENT, KILL_SWITCH }

    @ParameterizedTest
    @EnumSource(Hidden.class)
    void settingsOfAnEventTheCallerCannotSee_are404_andTheSwitchStays(Hidden hidden) {
        DoorOptInService svc = service;
        UUID target = event.getId();
        switch (hidden) {
            case OTHER_ORGS_EVENT -> target = event(otherOrgId, EventStatus.LIVE, false).getId();
            case DELETED_EVENT -> jdbc.update("update events set deleted_at = ? where id = ?",
                    Timestamp.from(clock.instant()), event.getId());
            case KILL_SWITCH -> {
                AudiencePlanProperties off = new AudiencePlanProperties();
                off.setEnabled(false);
                svc = serviceWith(off);
            }
        }
        DoorOptInService s = svc;
        UUID id = target;
        Map<String, Object> before = doorState(id);

        assertNotFound(() -> s.settings(organizer, id));
        assertNotFound(() -> s.setEnabled(organizer, id, !(Boolean) before.get("door_optin_enabled")));
        assertThat(doorState(id)).isEqualTo(before);
    }

    @Test
    void signups_countDistinctDoorMembersOfThisEventOnly() {
        String a = addr("a");
        service.optIn(event.getId(), body(a, true, TEXT, VERSION, "en"));
        service.optIn(event.getId(), body(a, true, TEXT, VERSION, "en"));
        service.optIn(event.getId(), body(addr("b"), true, TEXT, VERSION, "en"));
        Event second = event(orgId, EventStatus.LIVE, false);
        String t2 = service.setEnabled(organizer, second.getId(), true).doorUrl().replaceAll(".*\\?t=", "");
        service.optIn(second.getId(), new DoorOptInRequest(t2, addr("c"), true, TEXT, VERSION, "en"));

        assertThat(service.settings(organizer, event.getId()).signups()).isEqualTo(2);
        assertThat(service.settings(organizer, second.getId()).signups()).isEqualTo(1);
    }

    @Test
    void newToken_isUrlSafe128BitAndRandom() {
        String a = DoorOptInService.newToken();
        assertThat(a).matches("[A-Za-z0-9_-]{22}");
        assertThat(DoorOptInService.newToken()).isNotEqualTo(a);
    }

    // ── out-of-date full-entity saves ─────────────────────────────────────

    @Test
    void fullEventSaveLoadedBeforeEnable_neverRevertsDoorSwitch() {
        Event fresh = event(orgId, EventStatus.LIVE, true);
        Event stale = events.findById(fresh.getId()).orElseThrow();
        String url = service.setEnabled(organizer, fresh.getId(), true).doorUrl();

        stale.setName("Renamed");
        events.save(stale);

        Map<String, Object> row = jdbc.queryForMap(
                "select name, door_optin_enabled, door_optin_token from events where id = ?", fresh.getId());
        assertThat(row.get("name")).isEqualTo("Renamed");
        assertThat(row.get("door_optin_enabled")).isEqualTo(true);
        assertThat(url).endsWith("?t=" + row.get("door_optin_token"));
        assertThat((String) row.get("door_optin_token")).matches("[A-Za-z0-9_-]{22}");
    }

    @Test
    void saveThenEnable_writesDoorSwitch() {
        Event fresh = event(orgId, EventStatus.LIVE, true);
        Event loaded = events.findById(fresh.getId()).orElseThrow();
        loaded.setName("Saved First");
        events.save(loaded);

        service.setEnabled(organizer, fresh.getId(), true);

        Map<String, Object> row = jdbc.queryForMap(
                "select name, door_optin_enabled, door_optin_token from events where id = ?", fresh.getId());
        assertThat(row.get("name")).isEqualTo("Saved First");
        assertThat(row.get("door_optin_enabled")).isEqualTo(true);
        assertThat((String) row.get("door_optin_token")).matches("[A-Za-z0-9_-]{22}");
    }

    @Test
    void insertCarriesDoorValues() {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Inserted");
        e.setSlug("door-insert-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.parse("2026-10-24T20:00:00Z"));
        e.setCreatedBy(ownerId);
        e.setDoorOptinEnabled(true);
        e.setDoorOptinToken("abcdefghijklmnopqrstuv");
        UUID id = events.save(e).getId();

        Map<String, Object> row = jdbc.queryForMap(
                "select door_optin_enabled, door_optin_token from events where id = ?", id);
        assertThat(row.get("door_optin_enabled")).isEqualTo(true);
        assertThat(row.get("door_optin_token")).isEqualTo("abcdefghijklmnopqrstuv");
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private DoorOptInService serviceWith(AudiencePlanProperties props) {
        return new DoorOptInService(events, orgs, consumers, memberships, erased, optOuts, suppressions, consentRecords,
                projector, consentService, new AudiencePlanAccess(props), logic, emailProps, confirmations);
    }

    /** Addresses are keyed across orgs, so every test writes its own. */
    private static String addr(String tag) {
        return tag + "-" + UUID.randomUUID() + "@door.test";
    }

    private DoorOptInRequest body(String email, Boolean ticked, String text, String version, String locale) {
        return new DoorOptInRequest(token, email, ticked, text, version, locale);
    }

    private UUID org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug("door-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("door@example.com");
        o.setCountry("FR");
        return orgs.save(o).getId();
    }

    private Event event(UUID org, EventStatus status, boolean published) {
        Event e = new Event();
        e.setOrgId(org);
        e.setName("Door Night");
        e.setSlug("door-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setPublishedAt(published || status != EventStatus.DRAFT ? clock.instant().minus(Duration.ofHours(1)) : null);
        e.setStartsAt(Instant.parse("2026-10-24T20:00:00Z"));
        e.setTimezone("Europe/Paris");
        e.setVenueCity("Metz");
        e.setCreatedBy(ownerId);
        e.setCurrency("EUR");
        return events.save(e);
    }

    private void erase(UUID org, String email) {
        ErasedAddress a = new ErasedAddress();
        a.setOrgId(org);
        a.setEmailNormalized(email);
        erased.save(a);
        if (org == null) platformErased.add(email);
    }

    private Map<String, Object> doorState(UUID eventId) {
        return jdbc.queryForMap("select door_optin_enabled, door_optin_token from events where id = ?", eventId);
    }

    private List<Map<String, Object>> consentRows(String email) {
        return jdbc.queryForList("select cr.* from consent_records cr join memberships m on m.membership_id = cr.membership_id"
                + " join consumers c on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?",
                orgId, email);
    }

    private Map<String, Object> membership(String email) {
        return jdbc.queryForMap("select m.* from memberships m join consumers c on c.consumer_id = m.consumer_id"
                + " where m.org_id = ? and c.normalized_email = ?", orgId, email);
    }

    private void assertNothingStored(String email) {
        assertThat(jdbc.queryForObject("select count(*) from consumers where normalized_email = ?", Integer.class, email))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from consent_records where event_id = ?", Integer.class,
                event.getId())).isZero();
    }

    private static void assertInvalid(Runnable call, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(e.fields()).containsOnlyKeys(field);
        });
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
        });
    }
}
