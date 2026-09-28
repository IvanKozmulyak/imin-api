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
import com.imin.iminapi.audienceplan.dto.SurveyPageResponse;
import com.imin.iminapi.audienceplan.dto.SurveyResponseRequest;
import com.imin.iminapi.audienceplan.dto.SurveySettingsResponse;
import com.imin.iminapi.audienceplan.repository.SurveyResponseRepository;
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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class SurveyServiceTest {

    private static final String ORG_NAME = "Vechirka Survey";
    private static final String NOTICE = "survey-notice-2026-10";
    private static final String VERSION = "survey-org-named-2026-09";
    private static final String TEXT = "Email me about events by " + ORG_NAME
            + ". I agree to receive email marketing and can unsubscribe any time, one click in every email.";

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
        jdbc.update("delete from erased_addresses where org_id is null and email_normalized like '%@survey.test'");
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
        Instant before = Instant.now().truncatedTo(ChronoUnit.DAYS);
        service.submit(token, answers("Metz", List.of(), null, null, false));
        Instant after = Instant.now().truncatedTo(ChronoUnit.DAYS);

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
        service.submit(token, consented("Guest@Survey.test", TEXT, VERSION));

        Map<String, Object> r = onlyResponse();
        Map<String, Object> m = membership("guest@survey.test");
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
        service.submit(token, consented("pending@survey.test", TEXT, VERSION));

        Map<String, Object> m = membership("pending@survey.test");
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
        projector.upsertMembership(orgId, "buyer@survey.test", null);
        UUID mid = (UUID) membership("buyer@survey.test").get("membership_id");
        consentService.capture(orgId, mid, "explicit", "checkout", "Ticked at checkout", "email",
                "checkout-org-named-2026-09", null, ConsentOrigin.DATA_SUBJECT, null);

        service.submit(token, consented("buyer@survey.test", TEXT, VERSION));

        Map<String, Object> m = membership("buyer@survey.test");
        assertThat(m.get("consent_status")).isEqualTo("subscribed");
        assertThat(m.get("consent_basis")).isEqualTo("explicit");
        assertThat(sendGate.evaluate(orgId, List.of(mid)).sendable()).containsExactly(mid);
        assertThat(gate.canMarket(orgId, mid)).isTrue();
    }

    @Test
    void emailWithoutTick_is400AndStoresNothing() {
        assertInvalid(() -> service.submit(token, request(null, null, "friend", null, null, NOTICE, "en", false,
                "guest@survey.test", TEXT, VERSION, null)), "consentGiven");
        assertNothingStored("guest@survey.test");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {" "})
    void tickWithoutEmail_is400(String email) {
        assertInvalid(() -> service.submit(token, consented(email, TEXT, VERSION)), "email");
        assertThat(responseCount()).isZero();
    }

    @Test
    void malformedEmail_is400() {
        assertInvalid(() -> service.submit(token, consented("not-an-email", TEXT, VERSION)), "email");
    }

    @Test
    void overlongEmail_is400() {
        assertInvalid(() -> service.submit(token, consented("a".repeat(250) + "@survey.test", TEXT, VERSION)), "email");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"door-org-named-2026-09", "survey-v0"})
    void consentVersionOffTheSurveyAllowlist_is400AndStoresNothing(String version) {
        assertInvalid(() -> service.submit(token, consented("v@survey.test", TEXT, version)), "consentTextVersion");
        assertNothingStored("v@survey.test");
    }

    @Test
    void consentTextNotNamingTheOrganizer_is400AndStoresNothing() {
        assertInvalid(() -> service.submit(token, consented("t@survey.test",
                "Email me about this organiser's events.", VERSION)), "consentText");
        assertNothingStored("t@survey.test");
    }

    @Test
    void blankConsentText_is400() {
        assertInvalid(() -> service.submit(token, consented("t@survey.test", " ", VERSION)), "consentText");
    }

    @Test
    void overlongConsentText_is400() {
        assertInvalid(() -> service.submit(token, consented("t@survey.test", TEXT + "x".repeat(2001), VERSION)),
                "consentText");
    }

    // ── answers kept anonymous for addresses that must not be re-subscribed ──

    @Test
    void erasedForThisOrg_keepsTheAnswerAnonymous() {
        erase(orgId, "gone@survey.test");
        assertAnonymousAccepted("gone@survey.test");
    }

    @Test
    void erasedPlatformWide_keepsTheAnswerAnonymous() {
        erase(null, "gone2@survey.test");
        assertAnonymousAccepted("gone2@survey.test");
    }

    @Test
    void stickyOptOut_keepsTheAnswerAnonymous() {
        MarketingOptOut o = new MarketingOptOut();
        o.setEmailNormalized("sticky@survey.test");
        o.setOrgId(orgId);
        o.setChannel("email");
        o.setSource("unsubscribe_link");
        optOuts.save(o);
        assertAnonymousAccepted("sticky@survey.test");
    }

    @Test
    void unsubscribedMember_keepsTheAnswerAnonymousAndStaysUnsubscribed() {
        projector.upsertMembership(orgId, "unsub@survey.test", null);
        jdbc.update("update memberships set consent_status = 'unsubscribed' where org_id = ?", orgId);

        assertThat(service.submit(token, consented("unsub@survey.test", TEXT, VERSION)).received()).isTrue();

        assertThat(onlyResponse().get("heard_from")).isEqualTo("friend");
        assertThat(consentCount()).isZero();
        assertThat(membership("unsub@survey.test").get("consent_status")).isEqualTo("unsubscribed");
    }

    @Test
    void marketingSuppressedMember_keepsTheAnswerAnonymous() {
        projector.upsertMembership(orgId, "supp@survey.test", null);
        SuppressionEntry s = new SuppressionEntry();
        s.setScope(SuppressionEntry.SCOPE_MARKETING);
        s.setOrgId(orgId);
        s.setMembershipId((UUID) membership("supp@survey.test").get("membership_id"));
        s.setReason("manual");
        suppressions.save(s);

        assertThat(service.submit(token, consented("supp@survey.test", TEXT, VERSION)).received()).isTrue();

        assertThat(onlyResponse().get("heard_from")).isEqualTo("friend");
        assertThat(consentCount()).isZero();
    }

    @Test
    void erasePendingMember_keepsTheAnswerAnonymous() {
        projector.upsertMembership(orgId, "pending@survey.test", null);
        jdbc.update("update memberships set status = 'erase_pending' where org_id = ?", orgId);

        assertThat(service.submit(token, consented("pending@survey.test", TEXT, VERSION)).received()).isTrue();

        assertThat(onlyResponse().get("heard_from")).isEqualTo("friend");
        assertThat(consentCount()).isZero();
    }

    // ── validation ────────────────────────────────────────────────────────

    @Test
    void unknownField_is400NamingItAndStoresNothing() {
        SurveyResponseRequest body = request("Metz", null, null, null, null, NOTICE, "en", null, null, null, null,
                Map.of("religion", "x"));
        assertInvalid(() -> service.submit(token, body), "religion");
        assertThat(responseCount()).isZero();
    }

    @Test
    void genreOutsideTheWhitelist_is400() {
        assertInvalid(() -> service.submit(token, answers(null, List.of("pop", "techno"), null, null, null)),
                "otherGenres");
        assertThat(responseCount()).isZero();
    }

    @Test
    void nullGenreEntry_is400() {
        assertInvalid(() -> service.submit(token, answers(null, Arrays.asList("pop", null), null, null, null)),
                "otherGenres");
    }

    @Test
    void noAnswers_is400() {
        assertInvalid(() -> service.submit(token, answers(" ", List.of(), " ", null, null)), "answers");
    }

    @Test
    void missingBody_is400() {
        assertInvalid(() -> service.submit(token, null), "answers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"57100", "Metz 4", "a@b.c"})
    void communeThatIsNotAPlaceName_is400(String commune) {
        assertInvalid(() -> service.submit(token, answers(commune, null, null, null, null)), "homeCommune");
    }

    @Test
    void placeNamePunctuation_isAccepted() {
        service.submit(token, answers("Saint-Julien-lès-Metz l’Île", null, null, null, null));
        assertThat(onlyResponse().get("home_commune")).isEqualTo("Saint-Julien-lès-Metz l’Île");
    }

    @Test
    void overlongCommune_is400() {
        assertInvalid(() -> service.submit(token, answers("a".repeat(81), null, null, null, null)), "homeCommune");
    }

    @Test
    void unknownHeardFrom_is400() {
        assertInvalid(() -> service.submit(token, answers(null, null, "radio", null, null)), "heardFrom");
    }

    @Test
    void unknownAgeBand_is400() {
        assertInvalid(() -> service.submit(token, answers(null, null, null, "under_18", null)), "ageBand");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"survey-v0", "door-org-named-2026-09"})
    void noticeVersionOffTheAllowlist_is400(String notice) {
        assertInvalid(() -> service.submit(token, request(null, null, "friend", null, null, notice, "en",
                null, null, null, null, null)), "noticeVersion");
        assertThat(responseCount()).isZero();
    }

    // ── 404s (one per condition) ──────────────────────────────────────────

    @Test
    void switchedOff_is404() {
        service.setEnabled(organizer, event.getId(), false);
        assertNotFound(() -> service.submit(token, answers("Metz", null, null, null, null)));
        assertThat(responseCount()).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {" ", "wrong"})
    void unknownToken_is404(String t) {
        assertNotFound(() -> service.submit(t, answers("Metz", null, null, null, null)));
    }

    @Test
    void deletedEvent_is404() {
        jdbc.update("update events set deleted_at = ? where id = ?", Timestamp.from(Instant.now()), event.getId());
        assertNotFound(() -> service.submit(token, answers("Metz", null, null, null, null)));
    }

    @Test
    void draftEvent_is404() {
        jdbc.update("update events set status = 'DRAFT' where id = ?", event.getId());
        assertNotFound(() -> service.submit(token, answers("Metz", null, null, null, null)));
    }

    @Test
    void cancelledEvent_is404() {
        jdbc.update("update events set status = 'CANCELLED' where id = ?", event.getId());
        assertNotFound(() -> service.submit(token, answers("Metz", null, null, null, null)));
    }

    @Test
    void unpublishedEvent_is404() {
        jdbc.update("update events set published_at = null where id = ?", event.getId());
        assertNotFound(() -> service.submit(token, answers("Metz", null, null, null, null)));
    }

    @Test
    void killSwitch_is404() {
        AudiencePlanProperties off = new AudiencePlanProperties();
        off.setEnabled(false);
        assertNotFound(() -> serviceWith(off).submit(token, answers("Metz", null, null, null, null)));
        assertNotFound(() -> serviceWith(off).page(token));
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

    @Test
    void settings_otherOrgsEvent_is404() {
        Event foreign = event(otherOrgId, EventStatus.LIVE);
        assertNotFound(() -> service.settings(organizer, foreign.getId()));
        assertNotFound(() -> service.setEnabled(organizer, foreign.getId(), true));
        assertThat(events.findById(foreign.getId()).orElseThrow().getSurveyToken()).isNull();
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
    void responses_countEveryAnswerOfThisEventOnly() {
        service.submit(token, answers("Metz", null, null, null, null));
        service.submit(token, consented("a@survey.test", TEXT, VERSION));
        Event second = event(orgId, EventStatus.LIVE);
        String t2 = service.setEnabled(organizer, second.getId(), true).surveyUrl().replaceAll(".*\\?t=", "");
        service.submit(t2, answers("Nancy", null, null, null, null));

        assertThat(service.settings(organizer, event.getId()).responses()).isEqualTo(2);
        assertThat(service.settings(organizer, second.getId()).responses()).isEqualTo(1);
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private SurveyService serviceWith(AudiencePlanProperties props) {
        return new SurveyService(events, orgs, consumers, memberships, erased, optOuts, suppressions, responses,
                projector, consentService, new AudiencePlanAccess(props), logic, emailProps, confirmations);
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

    private static SurveyResponseRequest consented(String email, String text, String version) {
        return request(null, null, "friend", null, null, NOTICE, "fr", true, email, text, version, null);
    }

    private void assertAnonymousAccepted(String email) {
        assertThat(service.submit(token, consented(email, TEXT, VERSION)).received()).isTrue();
        assertThat(onlyResponse().get("heard_from")).isEqualTo("friend");
        assertThat(consentCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from consumers where normalized_email = ?", Integer.class, email))
                .isZero();
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
        e.setPublishedAt(Instant.now().minusSeconds(3600));
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
        assertThat(jdbc.queryForObject("select count(*) from consumers where normalized_email = ?", Integer.class, email))
                .isZero();
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
