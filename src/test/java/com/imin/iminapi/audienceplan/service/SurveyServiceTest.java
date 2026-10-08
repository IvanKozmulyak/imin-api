package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.ErasedAddress;
import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.SuppressionEntry;
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
import com.imin.iminapi.audienceplan.dto.SurveyPageResponse;
import com.imin.iminapi.audienceplan.dto.SurveyResponseRequest;
import com.imin.iminapi.audienceplan.dto.SurveySettingsResponse;
import com.imin.iminapi.audienceplan.repository.SurveyResponseRepository;
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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@IminIntegrationTest
class SurveyServiceTest {

    private static final String ORG_NAME = "Vechirka Survey";
    private static final String NOTICE = "survey-notice-2026-10";
    private static final String VERSION = "survey-org-named-2026-09";
    private static final String TEXT = "Email me about events by " + ORG_NAME
            + ". I agree to receive email marketing and can unsubscribe any time, one click in every email.";
    /** Stands for a fresh valid address in a parameter row. */
    private static final String FRESH = "<fresh>";

    @Autowired SurveyService service;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired ErasedAddressRepository erased;
    @Autowired MarketingOptOutRepository optOuts;
    @Autowired SuppressionRepository suppressions;
    @Autowired SurveyResponseRepository responses;
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
        owner.setEmail("survey-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        ownerId = users.save(owner).getId();
        organizer = new AuthPrincipal(ownerId, orgId, UserRole.ADMIN, UUID.randomUUID());
        event = event(orgId, EventStatus.PAST);
        token = service.setEnabled(organizer, event.getId(), true).surveyUrl().replaceAll(".*\\?t=", "");
    }

    @AfterEach
    void tearDown() {
        // A stored consent recomputes the member's features on the live pool; let it finish before the rows go.
        AsyncDrain.drain(fanFeatureExecutor);
        for (UUID org : List.of(orgId, otherOrgId)) {
            List<UUID> cids = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, org);
            jdbc.update("delete from survey_responses where org_id = ?", org);
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

    // ── anonymous answers ─────────────────────────────────────────────────

    @Test
    void answerRow_hasNoColumnThatCouldLinkItToAPerson() {
        List<String> columns = jdbc.queryForList("select lower(column_name) from information_schema.columns"
                + " where lower(table_name) = 'survey_responses'", String.class);
        assertThat(columns).containsExactlyInAnyOrder("id", "org_id", "event_id", "home_commune", "other_genres",
                "heard_from", "age_band", "first_time", "notice_version", "locale", "created_at");
    }

    @Test
    void anonymousAnswer_isStoredWithoutMembershipOrConsent() {
        assertThat(service.submit(token, answers("  Thionville ", List.of("pop", "house & techno"), "instagram",
                "25_34", true)).received()).isTrue();

        Map<String, Object> r = onlyResponse();
        assertThat(r.get("org_id")).isEqualTo(orgId);
        assertThat(r.get("event_id")).isEqualTo(event.getId());
        assertThat(r.get("home_commune")).isEqualTo("Thionville");
        assertThat(r.get("other_genres")).isEqualTo("[\"pop\",\"house & techno\"]");
        assertThat(r.get("heard_from")).isEqualTo("instagram");
        assertThat(r.get("age_band")).isEqualTo("25_34");
        assertThat(r.get("first_time")).isEqualTo(true);
        assertThat(r.get("notice_version")).isEqualTo(NOTICE);
        assertThat(r.get("locale")).isEqualTo("fr");
        assertThat(r.get("created_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from memberships where org_id = ?", Integer.class, orgId)).isZero();
        assertThat(consentCount()).isZero();
    }

    @Test
    void answerTime_isStoredAsTheUtcDayWithNoTimeOfDay() {
        Instant before = clock.instant().truncatedTo(ChronoUnit.DAYS);
        service.submit(token, answers("Metz", List.of(), null, null, false));
        Instant after = clock.instant().truncatedTo(ChronoUnit.DAYS);

        Instant stored = jdbc.queryForObject("select created_at from survey_responses where event_id = ?",
                OffsetDateTime.class, event.getId()).toInstant();
        assertThat(stored).isEqualTo(stored.truncatedTo(ChronoUnit.DAYS));
        assertThat(stored).isBetween(before, after);
    }

    @Test
    void unansweredQuestions_areStoredAsNull() {
        service.submit(token, answers(null, List.of(), null, null, false));

        Map<String, Object> r = onlyResponse();
        assertThat(r.get("home_commune")).isNull();
        assertThat(r.get("other_genres")).isNull();
        assertThat(r.get("heard_from")).isNull();
        assertThat(r.get("age_band")).isNull();
        assertThat(r.get("first_time")).isEqualTo(false);
    }

    @Test
    void duplicateGenres_areStoredOnce() {
        service.submit(token, answers(null, List.of("pop", "pop"), null, null, null));
        assertThat(onlyResponse().get("other_genres")).isEqualTo("[\"pop\"]");
    }

    @Test
    void unsupportedLocale_isStoredAsEnglish() {
        service.submit(token, request(null, List.of(), "friend", null, null, NOTICE, "de", null, null, null, null, null));
        assertThat(onlyResponse().get("locale")).isEqualTo("en");
    }

    // ── consented answers ─────────────────────────────────────────────────

    @Test
    void tickedBox_recordsAnExplicitSurveyConsentSeparateFromTheAnswer() {
        String email = addr("guest");
        service.submit(token, consented(email.toUpperCase(Locale.ROOT), TEXT, VERSION));

        Map<String, Object> r = onlyResponse();
        Map<String, Object> m = membership(email);
        Map<String, Object> c = jdbc.queryForMap("select * from consent_records where event_id = ?", event.getId());
        assertThat(r).doesNotContainKeys("membership_id", "consent_record_id");
        assertThat(r.values()).doesNotContain(m.get("membership_id"), m.get("consumer_id"), c.get("id"));
        assertThat(c.get("membership_id")).isEqualTo(m.get("membership_id"));
        assertThat(c.get("lawful_basis")).isEqualTo("explicit");
        assertThat(c.get("source")).isEqualTo("survey");
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

    @Test
    void consentAlone_isMailableByNeitherGateUntilConfirmed() {
        String email = addr("pending");
        service.submit(token, consented(email, TEXT, VERSION));

        Map<String, Object> m = membership(email);
        UUID mid = (UUID) m.get("membership_id");
        assertThat(m.get("consent_status")).isEqualTo("never");
        assertThat(m.get("consent_basis")).isNull();
        SendGateService.GateResult send = sendGate.evaluate(orgId, List.of(mid));
        assertThat(send.sendable()).isEmpty();
        assertThat(send.excluded()).extracting(ExclusionReason::reason).containsExactly("no_lawful_basis");
        assertThat(gate.reasons(orgId, List.of(mid))).containsEntry(mid, Optional.of(ConsentGate.NO_BASIS));
    }

    @Test
    void consentOfAMemberWithACheckoutConsent_keepsThemMailableByBothGates() {
        String email = addr("buyer");
        projector.upsertMembership(orgId, email, null);
        UUID mid = (UUID) membership(email).get("membership_id");
        consentService.capture(orgId, mid, "explicit", "checkout", "Ticked at checkout", "email",
                "checkout-org-named-2026-09", null, ConsentOrigin.DATA_SUBJECT, null);

        service.submit(token, consented(email, TEXT, VERSION));

        Map<String, Object> m = membership(email);
        assertThat(m.get("consent_status")).isEqualTo("subscribed");
        assertThat(m.get("consent_basis")).isEqualTo("explicit");
        assertThat(sendGate.evaluate(orgId, List.of(mid)).sendable()).containsExactly(mid);
        assertThat(gate.canMarket(orgId, mid)).isTrue();
    }

    static Stream<Arguments> invalidConsents() {
        return Stream.of(
                arguments("an email without the tick", false, FRESH, TEXT, VERSION, "consentGiven"),
                arguments("a tick without an email", true, null, TEXT, VERSION, "email"),
                arguments("a tick with a blank email", true, " ", TEXT, VERSION, "email"),
                arguments("a malformed email", true, "not-an-email", TEXT, VERSION, "email"),
                arguments("an overlong email", true, "a".repeat(250) + "@survey.test", TEXT, VERSION, "email"),
                arguments("no text version", true, FRESH, TEXT, null, "consentTextVersion"),
                arguments("the door text version", true, FRESH, TEXT, "door-org-named-2026-09", "consentTextVersion"),
                arguments("an unknown survey version", true, FRESH, TEXT, "survey-v0", "consentTextVersion"),
                arguments("text that does not name the organizer", true, FRESH,
                        "Email me about this organiser's events.", VERSION, "consentText"),
                arguments("blank text", true, FRESH, " ", VERSION, "consentText"),
                arguments("overlong text", true, FRESH, TEXT + "x".repeat(2001), VERSION, "consentText"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConsents")
    void anInvalidConsent_is400NamingTheField_andStoresNothing(String label, Boolean ticked, String email, String text,
                                                               String version, String field) {
        String address = FRESH.equals(email) ? addr("invalid") : email;
        SurveyResponseRequest body = request(null, null, "friend", null, null, NOTICE, "fr", ticked, address, text,
                version, null);

        assertInvalid(() -> service.submit(token, body), field);
        assertNothingStored(address);
    }

    // ── answers kept anonymous for addresses that must not be re-subscribed ──

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
    void aBlockedAddress_keepsTheAnswerAnonymous_andIsNeverResubscribed(Blocked blocked) {
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

        assertThat(service.submit(token, consented(email, TEXT, VERSION)).received()).isTrue();

        assertThat(onlyResponse().get("heard_from")).isEqualTo("friend");
        assertThat(consentCount()).isZero();
        if (blocked.member) {
            assertThat(membership(email)).isEqualTo(before);
        } else {
            assertThat(jdbc.queryForObject("select count(*) from consumers where normalized_email = ?", Integer.class,
                    email)).isZero();
        }
    }

    // ── validation ────────────────────────────────────────────────────────

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                arguments("an unknown field", request("Metz", null, null, null, null, NOTICE, "en", null, null, null,
                        null, Map.of("religion", "x")), "religion"),
                arguments("a genre outside the whitelist", answers(null, List.of("pop", "techno"), null, null, null),
                        "otherGenres"),
                arguments("a null genre entry", answers(null, Arrays.asList("pop", null), null, null, null),
                        "otherGenres"),
                arguments("no answers", answers(" ", List.of(), " ", null, null), "answers"),
                arguments("no body", null, "answers"),
                arguments("a postcode as commune", answers("57100", null, null, null, null), "homeCommune"),
                arguments("a commune with a digit", answers("Metz 4", null, null, null, null), "homeCommune"),
                arguments("an address as commune", answers("a@b.c", null, null, null, null), "homeCommune"),
                arguments("an overlong commune", answers("a".repeat(81), null, null, null, null), "homeCommune"),
                arguments("an unknown heard-from", answers(null, null, "radio", null, null), "heardFrom"),
                arguments("an unknown age band", answers(null, null, null, "under_18", null), "ageBand"),
                arguments("no notice version", notice(null), "noticeVersion"),
                arguments("an unknown notice version", notice("survey-v0"), "noticeVersion"),
                arguments("the door text as notice", notice("door-org-named-2026-09"), "noticeVersion"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void anInvalidBody_is400NamingTheField_andStoresNoAnswer(String label, SurveyResponseRequest body, String field) {
        assertInvalid(() -> service.submit(token, body), field);
        assertThat(responseCount()).isZero();
    }

    @Test
    void placeNamePunctuation_isAccepted() {
        service.submit(token, answers("Saint-Julien-lès-Metz l’Île", null, null, null, null));
        assertThat(onlyResponse().get("home_commune")).isEqualTo("Saint-Julien-lès-Metz l’Île");
    }

    // ── 404s (one per condition) ──────────────────────────────────────────

    enum Closed {
        SWITCHED_OFF, NULL_TOKEN, BLANK_TOKEN, WRONG_TOKEN, DELETED_EVENT, DRAFT_EVENT, CANCELLED_EVENT,
        UNPUBLISHED_EVENT, KILL_SWITCH
    }

    @ParameterizedTest
    @EnumSource(Closed.class)
    void aClosedSurvey_is404ForThePageAndTheAnswer_andStoresNothing(Closed closed) {
        SurveyService svc = service;
        String t = token;
        switch (closed) {
            case SWITCHED_OFF -> service.setEnabled(organizer, event.getId(), false);
            case NULL_TOKEN -> t = null;
            case BLANK_TOKEN -> t = " ";
            case WRONG_TOKEN -> t = "wrong";
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
        }
        SurveyService target = svc;
        String tok = t;

        assertNotFound(() -> target.submit(tok, answers("Metz", null, null, null, null)));
        assertNotFound(() -> target.page(tok));
        assertThat(responseCount()).isZero();
    }

    @Test
    void closedState_isCheckedBeforeTheBody() {
        service.setEnabled(organizer, event.getId(), false);
        assertNotFound(() -> service.submit(token, null));
    }

    // ── public page ───────────────────────────────────────────────────────

    @Test
    void page_showsEventAndTheOrganizerName() {
        SurveyPageResponse p = service.page(token);

        assertThat(p.eventId()).isEqualTo(event.getId());
        assertThat(p.eventName()).isEqualTo("Survey Night");
        assertThat(p.organizerName()).isEqualTo(ORG_NAME);
        assertThat(p.organizerLegalName()).isNull();
        assertThat(p.organizerLegalContact()).isNull();
        assertThat(p.startsAt()).isEqualTo(event.getStartsAt());
        assertThat(p.timezone()).isEqualTo("Europe/Paris");
        assertThat(p.venueCity()).isEqualTo("Metz");
    }

    @Test
    void page_carriesTheOrganizerLegalIdentityWhenSet() {
        jdbc.update("update organizations set legal_name = ?, legal_contact = ? where id = ?",
                "Vechirka SAS", "Legal: 1 rue X, Metz, hello@vechirka.test", orgId);

        SurveyPageResponse p = service.page(token);

        assertThat(p.organizerLegalName()).isEqualTo("Vechirka SAS");
        assertThat(p.organizerLegalContact()).isEqualTo("Legal: 1 rue X, Metz, hello@vechirka.test");
    }

    @Test
    void page_blankLegalIdentity_isNull() {
        jdbc.update("update organizations set legal_name = ' ', legal_contact = '' where id = ?", orgId);

        SurveyPageResponse p = service.page(token);

        assertThat(p.organizerLegalName()).isNull();
        assertThat(p.organizerLegalContact()).isNull();
    }

    @Test
    void page_wrongToken_is404() {
        assertNotFound(() -> service.page(token + "x"));
    }

    // ── organizer ─────────────────────────────────────────────────────────

    @Test
    void settings_neverEnabled_hasNoUrlAndZeroResponses() {
        Event fresh = event(orgId, EventStatus.LIVE);

        SurveySettingsResponse s = service.settings(organizer, fresh.getId());

        assertThat(s.enabled()).isFalse();
        assertThat(s.surveyUrl()).isNull();
        assertThat(s.responses()).isZero();
    }

    @Test
    void enable_createsATokenOnTheBuyerDomainWithoutBumpingTheEditEtag() {
        Event fresh = event(orgId, EventStatus.LIVE);
        Instant before = events.findById(fresh.getId()).orElseThrow().getUpdatedAt();

        SurveySettingsResponse s = service.setEnabled(organizer, fresh.getId(), true);

        Event stored = events.findById(fresh.getId()).orElseThrow();
        assertThat(stored.isSurveyEnabled()).isTrue();
        assertThat(stored.getSurveyToken()).matches("[A-Za-z0-9_-]{22}");
        assertThat(stored.getDoorOptinToken()).isNull();
        assertThat(s.enabled()).isTrue();
        assertThat(s.surveyUrl()).isEqualTo("https://app.imin.wtf/e/" + fresh.getId() + "/survey?t=" + stored.getSurveyToken());
        assertThat(stored.getUpdatedAt()).isEqualTo(before);
    }

    @Test
    void disable_keepsTheTokenAndReEnableReusesIt() {
        SurveySettingsResponse off = service.setEnabled(organizer, event.getId(), false);
        assertThat(off.enabled()).isFalse();
        assertThat(off.surveyUrl()).endsWith("?t=" + token);
        assertThat(events.findById(event.getId()).orElseThrow().isSurveyEnabled()).isFalse();

        assertThat(service.setEnabled(organizer, event.getId(), true).surveyUrl()).endsWith("?t=" + token);
    }

    @Test
    void member_canReadSettingsButCannotSwitch() {
        AuthPrincipal member = new AuthPrincipal(ownerId, orgId, UserRole.MEMBER, UUID.randomUUID());

        assertThat(service.settings(member, event.getId()).enabled()).isTrue();
        assertThatThrownBy(() -> service.setEnabled(member, event.getId(), false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(events.findById(event.getId()).orElseThrow().isSurveyEnabled()).isTrue();
    }

    enum Hidden { OTHER_ORGS_EVENT, DELETED_EVENT, KILL_SWITCH }

    @ParameterizedTest
    @EnumSource(Hidden.class)
    void settingsOfAnEventTheCallerCannotSee_are404_andTheSwitchStays(Hidden hidden) {
        SurveyService svc = service;
        UUID target = event.getId();
        switch (hidden) {
            case OTHER_ORGS_EVENT -> target = event(otherOrgId, EventStatus.LIVE).getId();
            case DELETED_EVENT -> jdbc.update("update events set deleted_at = ? where id = ?",
                    Timestamp.from(clock.instant()), event.getId());
            case KILL_SWITCH -> {
                AudiencePlanProperties off = new AudiencePlanProperties();
                off.setEnabled(false);
                svc = serviceWith(off);
            }
        }
        SurveyService s = svc;
        UUID id = target;
        Map<String, Object> before = surveyState(id);

        assertNotFound(() -> s.settings(organizer, id));
        assertNotFound(() -> s.setEnabled(organizer, id, !(Boolean) before.get("survey_enabled")));
        assertThat(surveyState(id)).isEqualTo(before);
    }

    @Test
    void responses_countEveryAnswerOfThisEventOnly() {
        service.submit(token, answers("Metz", null, null, null, null));
        service.submit(token, consented(addr("a"), TEXT, VERSION));
        Event second = event(orgId, EventStatus.LIVE);
        String t2 = service.setEnabled(organizer, second.getId(), true).surveyUrl().replaceAll(".*\\?t=", "");
        service.submit(t2, answers("Nancy", null, null, null, null));

        assertThat(service.settings(organizer, event.getId()).responses()).isEqualTo(2);
        assertThat(service.settings(organizer, second.getId()).responses()).isEqualTo(1);
    }

    // ── out-of-date full-entity saves ─────────────────────────────────────

    @Test
    void fullEventSaveLoadedBeforeEnable_neverRevertsSurveySwitch() {
        Event fresh = event(orgId, EventStatus.LIVE);
        Event stale = events.findById(fresh.getId()).orElseThrow();
        String url = service.setEnabled(organizer, fresh.getId(), true).surveyUrl();

        stale.setName("Renamed");
        events.save(stale);

        Map<String, Object> row = jdbc.queryForMap("select name, survey_enabled, survey_token from events where id = ?",
                fresh.getId());
        assertThat(row.get("name")).isEqualTo("Renamed");
        assertThat(row.get("survey_enabled")).isEqualTo(true);
        assertThat(url).endsWith("?t=" + row.get("survey_token"));
        assertThat((String) row.get("survey_token")).matches("[A-Za-z0-9_-]{22}");
    }

    @Test
    void saveThenEnable_writesSurveySwitch() {
        Event fresh = event(orgId, EventStatus.LIVE);
        Event loaded = events.findById(fresh.getId()).orElseThrow();
        loaded.setName("Saved First");
        events.save(loaded);

        service.setEnabled(organizer, fresh.getId(), true);

        Map<String, Object> row = jdbc.queryForMap("select name, survey_enabled, survey_token from events where id = ?",
                fresh.getId());
        assertThat(row.get("name")).isEqualTo("Saved First");
        assertThat(row.get("survey_enabled")).isEqualTo(true);
        assertThat((String) row.get("survey_token")).matches("[A-Za-z0-9_-]{22}");
    }

    @Test
    void insertCarriesSurveyValues() {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Inserted");
        e.setSlug("survey-insert-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.parse("2026-09-19T20:00:00Z"));
        e.setCreatedBy(ownerId);
        e.setSurveyEnabled(true);
        e.setSurveyToken("abcdefghijklmnopqrstuv");
        UUID id = events.save(e).getId();

        Map<String, Object> row = jdbc.queryForMap("select survey_enabled, survey_token from events where id = ?", id);
        assertThat(row.get("survey_enabled")).isEqualTo(true);
        assertThat(row.get("survey_token")).isEqualTo("abcdefghijklmnopqrstuv");
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private SurveyService serviceWith(AudiencePlanProperties props) {
        return new SurveyService(events, orgs, consumers, memberships, erased, optOuts, suppressions, responses,
                projector, consentService, new AudiencePlanAccess(props), logic, emailProps, confirmations);
    }

    /** Addresses are keyed across orgs, so every test writes its own. */
    private static String addr(String tag) {
        return tag + "-" + UUID.randomUUID() + "@survey.test";
    }

    private static SurveyResponseRequest request(String commune, List<String> genres, String heard, String age,
                                                 Boolean first, String notice, String locale, Boolean ticked,
                                                 String email, String text, String version,
                                                 Map<String, Object> unknown) {
        return new SurveyResponseRequest(commune, genres, heard, age, first, notice, locale, ticked, email, text,
                version, unknown);
    }

    private static SurveyResponseRequest answers(String commune, List<String> genres, String heard, String age,
                                                 Boolean first) {
        return request(commune, genres, heard, age, first, NOTICE, "fr", null, null, null, null, null);
    }

    private static SurveyResponseRequest notice(String notice) {
        return request(null, null, "friend", null, null, notice, "en", null, null, null, null, null);
    }

    private static SurveyResponseRequest consented(String email, String text, String version) {
        return request(null, null, "friend", null, null, NOTICE, "fr", true, email, text, version, null);
    }

    private UUID org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug("survey-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("survey@example.com");
        o.setCountry("FR");
        return orgs.save(o).getId();
    }

    private Event event(UUID org, EventStatus status) {
        Event e = new Event();
        e.setOrgId(org);
        e.setName("Survey Night");
        e.setSlug("survey-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setPublishedAt(clock.instant().minus(Duration.ofHours(1)));
        e.setStartsAt(Instant.parse("2026-09-19T20:00:00Z"));
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

    private Map<String, Object> surveyState(UUID eventId) {
        return jdbc.queryForMap("select survey_enabled, survey_token from events where id = ?", eventId);
    }

    private Map<String, Object> onlyResponse() {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from survey_responses where event_id = ?",
                event.getId());
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private int responseCount() {
        return jdbc.queryForObject("select count(*) from survey_responses where org_id = ?", Integer.class, orgId);
    }

    private int consentCount() {
        return jdbc.queryForObject("select count(*) from consent_records where event_id = ?", Integer.class,
                event.getId());
    }

    private Map<String, Object> membership(String email) {
        return jdbc.queryForMap("select m.* from memberships m join consumers c on c.consumer_id = m.consumer_id"
                + " where m.org_id = ? and c.normalized_email = ?", orgId, email);
    }

    private void assertNothingStored(String email) {
        if (email != null) {
            assertThat(jdbc.queryForObject("select count(*) from consumers where normalized_email = ?", Integer.class,
                    email)).isZero();
        }
        assertThat(consentCount()).isZero();
        assertThat(responseCount()).isZero();
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
