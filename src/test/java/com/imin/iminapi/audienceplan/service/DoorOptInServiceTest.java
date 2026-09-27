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
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.dto.DoorOptInPageResponse;
import com.imin.iminapi.audienceplan.dto.DoorOptInRequest;
import com.imin.iminapi.audienceplan.dto.DoorOptInResponse;
import com.imin.iminapi.audienceplan.dto.DoorOptInSettingsResponse;
import com.imin.iminapi.config.TestRateLimitConfig;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class DoorOptInServiceTest {

    private static final String ORG_NAME = "Vechirka Door";
    private static final String VERSION = "door-org-named-2026-09";
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
    @Autowired AudiencePlanLogic logic;
    @Autowired EmailProperties emailProps;
    @Autowired ConsentGate gate;
    @Autowired JdbcTemplate jdbc;

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
        jdbc.update("delete from erased_addresses where org_id is null and email_normalized like '%@door.test'");
    }

    // ── sign-up ────────────────────────────────────────────────────────────

    @Test
    void ticked_storesOneExplicitDoorRecordWithTextVersionEventAndLocale() {
        DoorOptInResponse r = service.optIn(event.getId(), body("Guest@Door.test", true, TEXT, VERSION, "fr"));

        assertThat(r.received()).isTrue();
        List<Map<String, Object>> rows = consentRows("guest@door.test");
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
        Map<String, Object> m = membership("guest@door.test");
        assertThat(m.get("consent_status")).isEqualTo("subscribed");
        assertThat(m.get("consent_basis")).isEqualTo("explicit");
        assertThat(gate.canMarket(orgId, (UUID) m.get("membership_id"))).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = false)
    void unticked_is400AndStoresNothing(Boolean ticked) {
        assertInvalid(() -> service.optIn(event.getId(), body("guest@door.test", ticked, TEXT, VERSION, "en")),
                "consentGiven");
        assertNothingStored("guest@door.test");
    }

    @Test
    void existingObjection_isLiftedByTheGuestsOwnTick() {
        projector.upsertMembership(orgId, "objector@door.test", null);
        jdbc.update("update memberships set objected_profiling = true where org_id = ?", orgId);

        service.optIn(event.getId(), body("objector@door.test", true, TEXT, VERSION, "en"));

        assertThat(membership("objector@door.test").get("objected_profiling")).isEqualTo(false);
    }

    @Test
    void unsupportedLocale_isRecordedAsEnglish() {
        service.optIn(event.getId(), body("loc@door.test", true, TEXT, VERSION, "de"));
        assertThat((String) consentRows("loc@door.test").get(0).get("proof_text")).contains("(locale en)");
    }

    @Test
    void locale_normalizesCaseAndSpaceAndFallsBackToEnglish() {
        assertThat(DoorOptInService.locale(" UK ")).isEqualTo("uk");
        assertThat(DoorOptInService.locale(null)).isEqualTo("en");
        assertThat(DoorOptInService.locale("pt")).isEqualTo("en");
    }

    // ── validation ────────────────────────────────────────────────────────

    @Test
    void blankEmail_is400() {
        assertInvalid(() -> service.optIn(event.getId(), body("  ", true, TEXT, VERSION, "en")), "email");
    }

    @Test
    void overlongEmail_is400() {
        String email = "a".repeat(250) + "@door.test";
        assertInvalid(() -> service.optIn(event.getId(), body(email, true, TEXT, VERSION, "en")), "email");
    }

    @Test
    void malformedEmail_is400() {
        assertInvalid(() -> service.optIn(event.getId(), body("not-an-email", true, TEXT, VERSION, "en")), "email");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"checkout-org-named-2026-09", "door-v0"})
    void versionOffTheDoorAllowlist_is400AndStoresNothing(String version) {
        assertInvalid(() -> service.optIn(event.getId(), body("v@door.test", true, TEXT, version, "en")),
                "consentTextVersion");
        assertNothingStored("v@door.test");
    }

    @Test
    void blankText_is400() {
        assertInvalid(() -> service.optIn(event.getId(), body("t@door.test", true, " ", VERSION, "en")), "consentText");
    }

    @Test
    void overlongText_is400() {
        String text = TEXT + "x".repeat(2001);
        assertInvalid(() -> service.optIn(event.getId(), body("t@door.test", true, text, VERSION, "en")), "consentText");
    }

    @Test
    void textThatDoesNotNameTheOrganizer_is400AndStoresNothing() {
        String generic = "Email me about this organiser's events. I agree to receive email marketing.";
        assertInvalid(() -> service.optIn(event.getId(), body("t@door.test", true, generic, VERSION, "en")),
                "consentText");
        assertNothingStored("t@door.test");
    }

    @Test
    void organizerNameMatch_ignoresCase() {
        service.optIn(event.getId(), body("case@door.test", true, TEXT.replace(ORG_NAME, "VECHIRKA DOOR"), VERSION, "en"));
        assertThat(consentRows("case@door.test")).hasSize(1);
    }

    // ── 404s (one per condition) ──────────────────────────────────────────

    @Test
    void switchedOff_is404() {
        service.setEnabled(organizer, event.getId(), false);
        assertNotFound(() -> service.optIn(event.getId(), body("x@door.test", true, TEXT, VERSION, "en")));
        assertNothingStored("x@door.test");
    }

    @Test
    void wrongToken_is404() {
        assertNotFound(() -> service.optIn(event.getId(),
                new DoorOptInRequest("wrong", "x@door.test", true, TEXT, VERSION, "en")));
    }

    @Test
    void missingToken_is404() {
        assertNotFound(() -> service.optIn(event.getId(),
                new DoorOptInRequest(null, "x@door.test", true, TEXT, VERSION, "en")));
    }

    @Test
    void missingBody_is404() {
        assertNotFound(() -> service.optIn(event.getId(), null));
    }

    @Test
    void unknownEvent_is404() {
        assertNotFound(() -> service.optIn(UUID.randomUUID(), body("x@door.test", true, TEXT, VERSION, "en")));
    }

    @Test
    void deletedEvent_is404() {
        jdbc.update("update events set deleted_at = ? where id = ?", Timestamp.from(Instant.now()), event.getId());
        assertNotFound(() -> service.optIn(event.getId(), body("x@door.test", true, TEXT, VERSION, "en")));
    }

    @Test
    void draftEvent_is404() {
        jdbc.update("update events set status = 'DRAFT' where id = ?", event.getId());
        assertNotFound(() -> service.optIn(event.getId(), body("x@door.test", true, TEXT, VERSION, "en")));
    }

    @Test
    void cancelledEvent_is404() {
        jdbc.update("update events set status = 'CANCELLED' where id = ?", event.getId());
        assertNotFound(() -> service.optIn(event.getId(), body("x@door.test", true, TEXT, VERSION, "en")));
    }

    @Test
    void unpublishedEvent_is404() {
        jdbc.update("update events set published_at = null where id = ?", event.getId());
        assertNotFound(() -> service.optIn(event.getId(), body("x@door.test", true, TEXT, VERSION, "en")));
    }

    @Test
    void pastEvent_staysOpen() {
        jdbc.update("update events set status = 'PAST' where id = ?", event.getId());
        service.optIn(event.getId(), body("late@door.test", true, TEXT, VERSION, "en"));
        assertThat(consentRows("late@door.test")).hasSize(1);
    }

    @Test
    void killSwitch_is404() {
        AudiencePlanProperties off = new AudiencePlanProperties();
        off.setEnabled(false);
        assertNotFound(() -> serviceWith(off).optIn(event.getId(), body("x@door.test", true, TEXT, VERSION, "en")));
        assertNothingStored("x@door.test");
    }

    @Test
    void orgOffTheAllowList_is404() {
        AudiencePlanProperties listed = new AudiencePlanProperties();
        listed.setEnabled(true);
        listed.setBetaOrgIds(Set.of(otherOrgId));
        assertNotFound(() -> serviceWith(listed).optIn(event.getId(), body("x@door.test", true, TEXT, VERSION, "en")));
    }

    // ── accepted without storing ──────────────────────────────────────────

    @Test
    void erasedForThisOrg_isAcceptedAndNotStored() {
        erase(orgId, "gone@door.test");
        assertThat(service.optIn(event.getId(), body("gone@door.test", true, TEXT, VERSION, "en")).received()).isTrue();
        assertNothingStored("gone@door.test");
    }

    @Test
    void erasedPlatformWide_isAcceptedAndNotStored() {
        erase(null, "gone2@door.test");
        assertThat(service.optIn(event.getId(), body("gone2@door.test", true, TEXT, VERSION, "en")).received()).isTrue();
        assertNothingStored("gone2@door.test");
    }

    @Test
    void stickyOptOut_isAcceptedAndNotStored() {
        MarketingOptOut o = new MarketingOptOut();
        o.setEmailNormalized("sticky@door.test");
        o.setOrgId(orgId);
        o.setChannel("email");
        o.setSource("unsubscribe_link");
        optOuts.save(o);

        assertThat(service.optIn(event.getId(), body("sticky@door.test", true, TEXT, VERSION, "en")).received()).isTrue();
        assertNothingStored("sticky@door.test");
    }

    @Test
    void unsubscribedMember_isAcceptedAndNotResubscribed() {
        projector.upsertMembership(orgId, "unsub@door.test", null);
        jdbc.update("update memberships set consent_status = 'unsubscribed' where org_id = ?", orgId);

        assertThat(service.optIn(event.getId(), body("unsub@door.test", true, TEXT, VERSION, "en")).received()).isTrue();

        assertThat(consentRows("unsub@door.test")).isEmpty();
        assertThat(membership("unsub@door.test").get("consent_status")).isEqualTo("unsubscribed");
    }

    @Test
    void marketingSuppressedMember_isAcceptedAndNotStored() {
        projector.upsertMembership(orgId, "supp@door.test", null);
        SuppressionEntry s = new SuppressionEntry();
        s.setScope(SuppressionEntry.SCOPE_MARKETING);
        s.setOrgId(orgId);
        s.setMembershipId((UUID) membership("supp@door.test").get("membership_id"));
        s.setReason("manual");
        suppressions.save(s);

        assertThat(service.optIn(event.getId(), body("supp@door.test", true, TEXT, VERSION, "en")).received()).isTrue();

        assertThat(consentRows("supp@door.test")).isEmpty();
    }

    @Test
    void erasePendingMember_isAcceptedAndNotStored() {
        projector.upsertMembership(orgId, "pending@door.test", null);
        jdbc.update("update memberships set status = 'erase_pending' where org_id = ?", orgId);

        assertThat(service.optIn(event.getId(), body("pending@door.test", true, TEXT, VERSION, "en")).received()).isTrue();

        assertThat(consentRows("pending@door.test")).isEmpty();
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

    @Test
    void settings_otherOrgsEvent_is404() {
        Event foreign = event(otherOrgId, EventStatus.LIVE, false);
        assertNotFound(() -> service.settings(organizer, foreign.getId()));
        assertNotFound(() -> service.setEnabled(organizer, foreign.getId(), true));
        assertThat(events.findById(foreign.getId()).orElseThrow().getDoorOptinToken()).isNull();
    }

    @Test
    void settings_deletedEvent_is404() {
        jdbc.update("update events set deleted_at = ? where id = ?", Timestamp.from(Instant.now()), event.getId());
        assertNotFound(() -> service.settings(organizer, event.getId()));
    }

    @Test
    void settings_killSwitch_is404() {
        AudiencePlanProperties off = new AudiencePlanProperties();
        off.setEnabled(false);
        assertNotFound(() -> serviceWith(off).settings(organizer, event.getId()));
    }

    @Test
    void signups_countDistinctDoorMembersOfThisEventOnly() {
        service.optIn(event.getId(), body("a@door.test", true, TEXT, VERSION, "en"));
        service.optIn(event.getId(), body("a@door.test", true, TEXT, VERSION, "en"));
        service.optIn(event.getId(), body("b@door.test", true, TEXT, VERSION, "en"));
        Event second = event(orgId, EventStatus.LIVE, false);
        String t2 = service.setEnabled(organizer, second.getId(), true).doorUrl().replaceAll(".*\\?t=", "");
        service.optIn(second.getId(), new DoorOptInRequest(t2, "c@door.test", true, TEXT, VERSION, "en"));

        assertThat(service.settings(organizer, event.getId()).signups()).isEqualTo(2);
        assertThat(service.settings(organizer, second.getId()).signups()).isEqualTo(1);
    }

    @Test
    void newToken_isUrlSafe128BitAndRandom() {
        String a = DoorOptInService.newToken();
        assertThat(a).matches("[A-Za-z0-9_-]{22}");
        assertThat(DoorOptInService.newToken()).isNotEqualTo(a);
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private DoorOptInService serviceWith(AudiencePlanProperties props) {
        return new DoorOptInService(events, orgs, consumers, memberships, erased, optOuts, suppressions, consentRecords,
                projector, consentService, new AudiencePlanAccess(props), logic, emailProps);
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
        e.setPublishedAt(published || status != EventStatus.DRAFT ? Instant.now().minusSeconds(3600) : null);
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
