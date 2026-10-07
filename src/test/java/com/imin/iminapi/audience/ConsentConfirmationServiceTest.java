package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.ConsentConfirmationResponse;
import com.imin.iminapi.audience.dto.ConsentHistoryEntry;
import com.imin.iminapi.audience.dto.DsarRecords;
import com.imin.iminapi.audience.model.ErasedAddress;
import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.ConsentConfirmationService;
import com.imin.iminapi.audience.service.ConsentConfirmationTokenSigner;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.dto.DoorOptInRequest;
import com.imin.iminapi.audienceplan.dto.SurveyResponseRequest;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.audienceplan.service.DoorOptInService;
import com.imin.iminapi.audienceplan.service.SurveyService;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import com.imin.iminapi.support.RecordingRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Double opt-in for door QR and survey sign-ups, end to end with the recording mail provider, service and link. */
@IminIntegrationTest
class ConsentConfirmationServiceTest {

    private static final String URL = "/api/v1/public/consent/confirm";

    private static final String ORG_NAME = "Vechirka Confirm";
    private static final String DOOR_VERSION = "door-org-named-2026-09";
    private static final String SURVEY_VERSION = "survey-org-named-2026-09";
    private static final String NOTICE = "survey-notice-2026-10";
    private static final String TEXT = "Email me about events by " + ORG_NAME
            + ". I agree to receive email marketing and can unsubscribe any time, one click in every email.";

    @Autowired ConsentConfirmationService service;
    @Autowired ConsentConfirmationTokenSigner signer;
    @Autowired DoorOptInService door;
    @Autowired SurveyService survey;
    @Autowired ConsentService consentService;
    @Autowired DsarService dsar;
    @Autowired ConsentGate gate;
    @Autowired SendGateService sendGate;
    @Autowired AudiencePlanProperties props;
    @Autowired PropertyFlips flips;
    @Autowired RecordingEmailService mail;
    @Autowired RecordingRateLimiter limiter;
    @Autowired MockMvc mvc;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired ErasedAddressRepository erased;
    @Autowired MarketingOptOutRepository optOuts;
    @Autowired SuppressionRepository suppressions;
    @Autowired JdbcTemplate jdbc;

    private UUID orgId;
    private String run;
    private String orgContact;
    private AuthPrincipal owner;
    private Event event;
    private String doorToken;

    @BeforeEach
    void setUp() {
        run = UUID.randomUUID().toString().substring(0, 8);
        flips.set(props, "consentConfirmationEmailsEnabled", true);
        Organization o = new Organization();
        o.setName(ORG_NAME);
        o.setSlug("confirm-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail(orgContact = "confirm-" + run + "@example.com");
        o.setCountry("FR");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("confirm-owner-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        UUID ownerId = users.save(u).getId();
        owner = new AuthPrincipal(ownerId, orgId, UserRole.OWNER, UUID.randomUUID());
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Confirm Night");
        e.setSlug("confirm-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minusSeconds(3600));
        e.setStartsAt(Instant.parse("2026-10-24T20:00:00Z"));
        e.setTimezone("Europe/Paris");
        e.setVenueCity("Metz");
        e.setCreatedBy(ownerId);
        e.setCurrency("EUR");
        event = events.save(e);
        doorToken = door.setEnabled(owner, event.getId(), true).doorUrl().replaceAll(".*\\?t=", "");
    }

    @AfterEach
    void tearDown() {
        List<UUID> cids = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, orgId);
        jdbc.update("delete from consent_confirmation_tokens where org_id = ?", orgId);
        jdbc.update("delete from consent_records where membership_id in (select membership_id from memberships where org_id = ?)", orgId);
        jdbc.update("delete from fan_features where org_id = ?", orgId);
        jdbc.update("delete from suppression_entries where org_id = ?", orgId);
        jdbc.update("delete from memberships where org_id = ?", orgId);
        for (UUID cid : cids) jdbc.update("delete from consumers where consumer_id = ?", cid);
        jdbc.update("delete from marketing_optouts where org_id = ?", orgId);
        jdbc.update("delete from erased_addresses where org_id = ?", orgId);
        jdbc.update("delete from erased_addresses where org_id is null and email_normalized like ?", "%-" + run + "@confirm.test");
        jdbc.update("delete from survey_responses where event_id = ?", event.getId());
        jdbc.update("delete from audit_logs where org_id = ?", orgId);
        jdbc.update("delete from events where org_id = ?", orgId);
        jdbc.update("delete from users where org_id = ?", orgId);
        jdbc.update("delete from organizations where id = ?", orgId);
    }

    // ── the email ─────────────────────────────────────────────────────────

    @Test
    void flagOff_signUpSendsNothing_mintsNothing_andStaysPending() {
        flips.set(props, "consentConfirmationEmailsEnabled", false);

        signUpAtDoor(a("off"), "fr");

        assertThat(ownMail()).isEmpty();
        assertThat(tokenRows(a("off"))).isEmpty();
        assertThat(consentRows(a("off")).get(0).get("confirmed_at")).isNull();
        assertThat(membership(a("off")).get("consent_status")).isEqualTo("never");
    }

    @Test
    void doorSignUp_sendsOneEmail_fromTheOrganizerViaImin_withASevenDayLink() {
        Instant before = Instant.now();
        signUpAtDoor("Guest-" + run + "@Confirm.test", "en");

        assertThat(ownMail()).extracting(RecordingEmailService.SentEmail::to).containsExactly(a("guest"));
        RecordingEmailService.SentEmail sent = lastOwn();
        assertThat(sent.to()).isEqualTo(a("guest"));
        assertThat(sent.subject()).isEqualTo("Confirm your email for " + ORG_NAME);
        assertThat(mail.lastFrom()).isEqualTo("\"" + ORG_NAME + " via IMIN\" <noreply@imin.test>");
        String url = confirmUrlIn(sent.text());
        assertThat(url).startsWith("https://app.imin.wtf/consent/confirm?t=");
        assertThat(sent.html()).contains("href=\"" + url + "\"").contains(ORG_NAME);
        // One explanation and one link, no pixel.
        assertThat(sent.html()).doesNotContain("<img");
        assertThat(sent.html().split("href=", -1)).hasSize(2);
        assertThat(sent.headers()).isEmpty();

        Map<String, Object> row = tokenRows(a("guest")).get(0);
        assertThat(row.get("locale")).isEqualTo("en");
        assertThat(row.get("used_at")).isNull();
        assertThat(row.get("consent_record_id")).isEqualTo(consentRows(a("guest")).get(0).get("id"));
        Instant sentAt = instant(row.get("sent_at"));
        assertThat(sentAt).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(instant(row.get("expires_at"))).isEqualTo(sentAt.plus(Duration.ofDays(7)));
        assertThat(signer.verify(tokenOf(url))).contains((UUID) row.get("id"));
    }

    @ParameterizedTest
    @CsvSource({
            "fr, Confirmez votre e-mail pour, Confirmer mon e-mail",
            "es, Confirma tu correo para, Confirmar mi correo",
            "uk, Підтвердьте email для, Підтвердити email",
            "en, Confirm your email for, Confirm my email",
            "de, Confirm your email for, Confirm my email"})
    void emailIsInThePageLocale(String locale, String subjectStart, String button) {
        signUpAtDoor(a("locale-" + locale), locale);

        RecordingEmailService.SentEmail sent = lastOwn();
        assertThat(sent.subject()).isEqualTo(subjectStart + " " + ORG_NAME);
        assertThat(sent.html()).contains(button);
        assertThat(sent.text()).contains(button);
    }

    @Test
    void surveySignUp_sendsTheEmailToo() {
        String token = survey.setEnabled(owner, event.getId(), true).surveyUrl().replaceAll(".*\\?t=", "");

        survey.submit(token, new SurveyResponseRequest(null, null, "friend", null, null, NOTICE, "es", true,
                a("survey"), TEXT, SURVEY_VERSION, null));

        assertThat(ownMail()).extracting(RecordingEmailService.SentEmail::to).containsExactly(a("survey"));
        assertThat(lastOwn().subject()).startsWith("Confirma tu correo");
        assertThat(tokenRows(a("survey"))).hasSize(1);
    }

    @Test
    void secondSignUpWithin24h_sendsNoSecondEmail() {
        signUpAtDoor(a("twice"), "en");
        signUpAtDoor(a("twice"), "en");

        assertThat(ownMail()).extracting(RecordingEmailService.SentEmail::to).containsExactly(a("twice"));
        assertThat(tokenRows(a("twice"))).hasSize(1);
        assertThat(consentRows(a("twice"))).hasSize(2);
    }

    @Test
    void secondSignUpAfter24h_sendsAgain() {
        signUpAtDoor(a("later"), "en");
        jdbc.update("update consent_confirmation_tokens set sent_at = ? where org_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))), orgId);

        signUpAtDoor(a("later"), "fr");

        assertThat(ownMail()).extracting(RecordingEmailService.SentEmail::to).containsExactly(a("later"), a("later"));
        assertThat(tokenRows(a("later"))).hasSize(2);
    }

    @Test
    void failedSend_keepsTheSignUp_andRemovesTheEmailRowSoALaterSignUpRetries() {
        mail.failNextSendWith(new IllegalStateException("provider down"));

        assertThat(signUpAtDoor(a("fails"), "en")).isTrue();

        assertThat(consentRows(a("fails"))).hasSize(1);
        assertThat(tokenRows(a("fails"))).isEmpty();
        signUpAtDoor(a("fails"), "en");
        assertThat(ownMail()).extracting(RecordingEmailService.SentEmail::to).containsExactly(a("fails"));
    }

    // ── preview (GET) ─────────────────────────────────────────────────────

    @Test
    void preview_ofAFreshLink_isPendingWithTheOrganizer_andChangesNothing() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("preview"))));

        assertThat(service.preview(token)).isEqualTo(new ConsentConfirmationResponse("pending", ORG_NAME));
        assertThat(service.preview(token).state()).isEqualTo("pending");

        assertThat(tokenRows(a("preview")).get(0).get("used_at")).isNull();
        assertThat(consentRows(a("preview")).get(0).get("confirmed_at")).isNull();
    }

    @Test
    void preview_ofAnOptedOutAddress_isInvalid() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("preview-out"))));
        unsubscribe(a("preview-out"));

        assertThat(service.preview(token)).isEqualTo(ConsentConfirmationResponse.invalid());
    }

    // ── confirm (POST) ────────────────────────────────────────────────────

    @Test
    void confirm_setsConfirmedAt_grantsLikeACheckoutConsent_liftsTheObjection_andAudits() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("yes"))));
        UUID mid = membershipId(a("yes"));
        jdbc.update("update memberships set objected_profiling = true where membership_id = ?", mid);
        assertThat(gate.canMarket(orgId, mid)).isFalse();

        ConsentConfirmationResponse r = service.confirm(token);

        assertThat(r).isEqualTo(new ConsentConfirmationResponse("confirmed", ORG_NAME));
        Map<String, Object> rec = consentRows(a("yes")).get(0);
        assertThat(rec.get("confirmed_at")).isNotNull();
        assertThat(rec.get("confirmation_required")).isEqualTo(true);
        Map<String, Object> m = membership(a("yes"));
        assertThat(m.get("consent_status")).isEqualTo("subscribed");
        assertThat(m.get("consent_basis")).isEqualTo("explicit");
        assertThat(m.get("objected_profiling")).isEqualTo(false);
        assertThat(sendGate.evaluate(orgId, List.of(mid)).sendable()).containsExactly(mid);
        assertThat(gate.canMarket(orgId, mid)).isTrue();
        assertThat(tokenRows(a("yes")).get(0).get("used_at")).isNotNull();
        assertThat(jdbc.queryForList("select action, target_id, actor_id, summary from audit_logs where org_id = ?", orgId))
                .singleElement().satisfies(a -> {
                    assertThat(a.get("action")).isEqualTo("CONSENT_CONFIRMED");
                    assertThat(a.get("target_id")).isEqualTo(mid);
                    assertThat(a.get("actor_id")).isNull();
                    assertThat((String) a.get("summary")).contains("records=1").contains("source=door_qr");
                });
    }

    @Test
    void confirm_alsoConfirmsAnEarlierSignUpThatGotNoEmailOfItsOwn() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("both"))));
        signUpAtDoor(a("both"), "en");

        service.confirm(token);

        assertThat(consentRows(a("both"))).hasSize(2)
                .allSatisfy(r -> assertThat(r.get("confirmed_at")).isNotNull());
    }

    @Test
    void confirm_twice_isInvalidTheSecondTime() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("reuse"))));

        assertThat(service.confirm(token).state()).isEqualTo("confirmed");
        Object firstConfirmedAt = consentRows(a("reuse")).get(0).get("confirmed_at");

        assertThat(service.confirm(token)).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(service.preview(token)).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(consentRows(a("reuse")).get(0).get("confirmed_at")).isEqualTo(firstConfirmedAt);
    }

    @Test
    void confirm_afterSevenDays_isInvalid_andConfirmsNothing() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("late"))));
        jdbc.update("update consent_confirmation_tokens set expires_at = ? where org_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), orgId);

        assertThat(service.preview(token)).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(service.confirm(token)).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(consentRows(a("late")).get(0).get("confirmed_at")).isNull();
        assertThat(tokenRows(a("late")).get(0).get("used_at")).isNull();
    }

    @Test
    void confirm_withATamperedToken_isInvalid() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("tamper"))));
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        assertThat(service.confirm(tampered)).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(consentRows(a("tamper")).get(0).get("confirmed_at")).isNull();
    }

    @Test
    void confirm_withAWellSignedUnknownRow_orGarbage_isInvalid() {
        assertThat(service.confirm(signer.sign(UUID.randomUUID()))).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(service.confirm("garbage")).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(service.confirm(null)).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(service.preview(null)).isEqualTo(ConsentConfirmationResponse.invalid());
    }

    @Test
    void confirm_whileTheOrgIsOffTheAudienceTool_isInvalid() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("beta"))));
        flips.set(props, "betaOrgIds", Set.of(UUID.randomUUID()));

        assertThat(service.confirm(token)).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(consentRows(a("beta")).get(0).get("confirmed_at")).isNull();
    }

    @Test
    void confirm_afterAnUnsubscribe_neverResurrectsIt_andBurnsTheLink() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("unsub"))));
        unsubscribe(a("unsub"));

        assertThat(service.confirm(token)).isEqualTo(ConsentConfirmationResponse.invalid());

        assertThat(membership(a("unsub")).get("consent_status")).isEqualTo("unsubscribed");
        assertThat(consentRows(a("unsub"))).allSatisfy(r -> assertThat(r.get("confirmed_at")).isNull());
        assertThat(tokenRows(a("unsub")).get(0).get("used_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where org_id = ? and action = 'CONSENT_CONFIRMED'",
                Integer.class, orgId)).isZero();
    }

    @Test
    void confirm_ofAStickyOptOut_isInvalid() {
        assertNotResurrected(a("sticky"), (mid) -> {
            MarketingOptOut o = new MarketingOptOut();
            o.setEmailNormalized(a("sticky"));
            o.setOrgId(orgId);
            o.setChannel("email");
            o.setSource("test");
            optOuts.save(o);
        });
    }

    @Test
    void confirm_ofAMarketingSuppressedMember_isInvalid() {
        assertNotResurrected(a("suppressed"), (mid) -> {
            SuppressionEntry s = new SuppressionEntry();
            s.setOrgId(orgId);
            s.setMembershipId(mid);
            s.setReason("manual");
            s.setScope(SuppressionEntry.SCOPE_MARKETING);
            suppressions.save(s);
        });
    }

    @Test
    void confirm_ofAnErasePendingMember_isInvalid() {
        assertNotResurrected(a("pending-erase"), (mid) ->
                jdbc.update("update memberships set status = 'erase_pending' where membership_id = ?", mid));
    }

    @Test
    void confirm_ofAnErasedAddress_isInvalid() {
        assertNotResurrected(a("erased"), (mid) -> {
            ErasedAddress a = new ErasedAddress();
            a.setOrgId(orgId);
            a.setEmailNormalized(a("erased"));
            erased.save(a);
        });
    }

    @Test
    void confirm_ofAPlatformWideErasedAddress_isInvalid() {
        assertNotResurrected(a("erased-everywhere"), (mid) -> {
            ErasedAddress a = new ErasedAddress();
            a.setEmailNormalized(a("erased-everywhere"));
            erased.save(a);
        });
    }

    @Test
    void confirm_ofAMemberInAnyNonActiveStatus_isInvalid() {
        assertNotResurrected(a("archived"), (mid) ->
                jdbc.update("update memberships set status = 'archived' where membership_id = ?", mid));
    }

    // ── the public link over HTTP ─────────────────────────────────────────

    /** The buyer site previews from one server IP; a shared bucket would turn a busy hour into dead links. */
    @Test
    void http_get_previewsWithoutAuth_isNotRateLimited_isNotCached_andConfirmsNothing() throws Exception {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("http-get"))));

        mvc.perform(get(URL).param("t", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.organizerName").value(ORG_NAME));

        assertThat(limiter.calls()).noneMatch(c -> "consent-confirm".equals(c.bucket()));
        assertThat(consentRows(a("http-get")).get(0).get("confirmed_at")).isNull();
    }

    @Test
    void http_post_confirmsWithoutAuth_chargesTheBucketPerIp_andIsNotCached() throws Exception {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("http-post"))));

        mvc.perform(post(URL).param("t", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.organizerName").value(ORG_NAME));

        assertThat(limiter.calls()).contains(new RecordingRateLimiter.Call("consent-confirm", "ip:127.0.0.1"));
        assertThat(consentRows(a("http-post")).get(0).get("confirmed_at")).isNotNull();
    }

    @Test
    void http_post_withoutToken_answersTheNeutralInvalid() throws Exception {
        mvc.perform(post(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("invalid"))
                .andExpect(jsonPath("$.organizerName").doesNotExist());
    }

    @Test
    void http_post_rateLimited_is429_andConfirmsNothing() throws Exception {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("http-limited"))));
        limiter.limit("consent-confirm", 0);

        mvc.perform(post(URL).param("t", token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(consentRows(a("http-limited")).get(0).get("confirmed_at")).isNull();
    }

    // ── records ───────────────────────────────────────────────────────────

    @Test
    void dsarExport_andConsentHistory_carryTheConfirmation() {
        String token = tokenOf(confirmUrlIn(signUpAndMail(a("dsar"))));
        UUID mid = membershipId(a("dsar"));
        service.confirm(token);

        DsarRecords records = dsar.exportRecords(orgId, mid, owner);
        assertThat(records.consentConfirmations()).singleElement().satisfies(c -> {
            assertThat(c.consentRecordId()).isEqualTo(consentRows(a("dsar")).get(0).get("id"));
            assertThat(c.locale()).isEqualTo("en");
            assertThat(c.sentAt()).isNotNull();
            assertThat(c.expiresAt()).isEqualTo(c.sentAt().plus(Duration.ofDays(7)));
            assertThat(c.usedAt()).isNotNull();
        });
        List<ConsentHistoryEntry> history = dsar.consentHistory(orgId, mid);
        assertThat(history).singleElement().satisfies(h -> {
            assertThat(h.confirmationRequired()).isTrue();
            assertThat(h.confirmedAt()).isNotNull();
        });
    }

    @Test
    void erasingTheMember_removesItsConfirmationEmails() {
        signUpAndMail(a("gone"));
        UUID mid = membershipId(a("gone"));
        jdbc.update("delete from consent_records where membership_id = ?", mid);

        assertThat(jdbc.queryForObject("select count(*) from consent_confirmation_tokens where membership_id = ?",
                Integer.class, mid)).isZero();
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    /** Mail to this run's addresses or org contact; late async sends from other classes are ignored. */
    private List<RecordingEmailService.SentEmail> ownMail() {
        return mail.sent().stream()
                .filter(m -> m.to().endsWith("-" + run + "@confirm.test") || m.to().equals(orgContact)).toList();
    }

    private RecordingEmailService.SentEmail lastOwn() {
        List<RecordingEmailService.SentEmail> own = ownMail();
        return own.isEmpty() ? null : own.get(own.size() - 1);
    }

    /** Unique per test: consumers and platform-wide erasure entries are keyed by address. */
    private String a(String local) {
        return local + "-" + run + "@confirm.test";
    }

    private void assertNotResurrected(String address, Consumer<UUID> optOut) {
        String token = tokenOf(confirmUrlIn(signUpAndMail(address)));
        UUID mid = membershipId(address);
        optOut.accept(mid);

        assertThat(service.preview(token)).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(service.confirm(token)).isEqualTo(ConsentConfirmationResponse.invalid());

        assertThat(consentRows(address).get(0).get("confirmed_at")).isNull();
        assertThat(membership(address).get("consent_status")).isEqualTo("never");
        assertThat(gate.canMarket(orgId, mid)).isFalse();
        // The valid link is burned all the same, so it cannot be replayed once the opt-out is lifted.
        assertThat(tokenRows(address).get(0).get("used_at")).isNotNull();
    }

    private boolean signUpAtDoor(String address, String locale) {
        return door.optIn(event.getId(), new DoorOptInRequest(doorToken, address, true, TEXT, DOOR_VERSION, locale))
                .received();
    }

    /** Signs up in English and returns the email text. */
    private String signUpAndMail(String address) {
        signUpAtDoor(address, "en");
        return lastOwn().text();
    }

    private void unsubscribe(String address) {
        consentService.unsubscribe(orgId, membershipId(address), "footer_link", "email", ConsentOrigin.OPERATOR, null);
    }

    private static Instant instant(Object column) {
        return column instanceof Timestamp t ? t.toInstant() : ((java.time.OffsetDateTime) column).toInstant();
    }

    private static String confirmUrlIn(String text) {
        return text.lines().map(String::trim).filter(l -> l.contains("/consent/confirm?t=")).findFirst().orElseThrow();
    }

    private static String tokenOf(String url) {
        return url.substring(url.indexOf("?t=") + 3);
    }

    private List<Map<String, Object>> consentRows(String address) {
        return jdbc.queryForList("select cr.* from consent_records cr join memberships m on m.membership_id = cr.membership_id"
                + " join consumers c on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?"
                + " order by cr.occurred_at", orgId, address);
    }

    private List<Map<String, Object>> tokenRows(String address) {
        return jdbc.queryForList("select t.* from consent_confirmation_tokens t join memberships m on m.membership_id = t.membership_id"
                + " join consumers c on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?"
                + " order by t.sent_at", orgId, address);
    }

    private Map<String, Object> membership(String address) {
        return jdbc.queryForMap("select m.* from memberships m join consumers c on c.consumer_id = m.consumer_id"
                + " where m.org_id = ? and c.normalized_email = ?", orgId, address);
    }

    private UUID membershipId(String address) {
        return (UUID) membership(address).get("membership_id");
    }
}
