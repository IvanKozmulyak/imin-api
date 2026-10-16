package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentConfirmationService;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.audienceplan.config.FollowProperties;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.PropertyFlips;
import com.imin.iminapi.support.RecordingRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Keep me posted" on imin.wtf over HTTP: rate limit, the 404 and 400 gates, what is stored, and its own CORS. */
@IminIntegrationTest
class PublicFollowControllerTest {

    private static final String URL = "/api/v1/public/orgs/{slug}/follow";
    private static final String VERSION = "site-follow-2026-10";
    private static final String SITE = "https://imin.wtf";
    private static final String OK = "{\"ok\":true}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired PropertyFlips flips;
    @Autowired FollowProperties followProps;
    @Autowired AudiencePlanProperties planProps;
    @Autowired RecordingRateLimiter limiter;
    @Autowired RecordingEmailService mail;
    @Autowired AudienceOrderProjector projector;
    @Autowired ConsentService consentService;
    @Autowired ConsentConfirmationService confirmations;
    @Autowired SuppressionRepository suppressions;
    @Autowired ConsentGate gate;
    @Autowired MutableClock clock;
    @Autowired @Qualifier("ticketEmailExecutor") Executor ticketEmailExecutor;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;

    private final List<String> bounced = new ArrayList<>();
    private Organization org;
    private String text;

    @BeforeEach
    void setUp() {
        org = fx.org();
        text = "Send me " + org.getName() + " news. Unsubscribe anytime.";
    }

    @AfterEach
    void tearDown() {
        // A stored sign-up recomputes the member's features on the live pool; let it finish before the rows go.
        AsyncDrain.drain(fanFeatureExecutor);
        UUID o = org.getId();
        List<UUID> consumers = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, o);
        jdbc.update("delete from consent_confirmation_tokens where org_id = ?", o);
        jdbc.update("delete from consent_records where membership_id in"
                + " (select membership_id from memberships where org_id = ?)", o);
        jdbc.update("delete from fan_features where org_id = ?", o);
        jdbc.update("delete from suppression_entries where org_id = ?", o);
        jdbc.update("delete from memberships where org_id = ?", o);
        for (UUID c : consumers) jdbc.update("delete from consumers where consumer_id = ?", c);
        jdbc.update("delete from marketing_optouts where org_id = ?", o);
        jdbc.update("delete from erased_addresses where org_id = ?", o);
        for (String email : bounced) {
            jdbc.update("delete from suppression_entries where scope = 'deliverability' and normalized_email = ?", email);
        }
        jdbc.update("delete from audit_logs where org_id = ?", o);
        jdbc.update("delete from organizations where id = ?", o);
    }

    // ── stored ────────────────────────────────────────────────────────────

    @Test
    void tickedSignUp_storesAPendingSiteFollowConsent_andSendsItsConfirmationEmail() throws Exception {
        listOrg();
        flips.set(planProps, "consentConfirmationEmailsEnabled", true);
        String email = fx.email("follow");

        follow(org.getSlug(), json(email, true, text, VERSION))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SITE))
                .andExpect(content().string(OK));

        assertThat(limiter.calls()).contains(new RecordingRateLimiter.Call("follow-optin", "ip:127.0.0.1"));
        Map<String, Object> m = membership(email);
        // Double opt-in: the membership grants nothing until the emailed link is clicked.
        assertThat(m.get("consent_status")).isEqualTo("never");
        List<Map<String, Object>> rows = consentRows(email);
        assertThat(rows).hasSize(1);
        Map<String, Object> c = rows.get(0);
        assertThat(c).containsEntry("lawful_basis", "explicit").containsEntry("source", "site_follow")
                .containsEntry("channel", "email").containsEntry("status", "subscribed")
                .containsEntry("text_version", VERSION).containsEntry("confirmation_required", true);
        assertThat(c.get("event_id")).isNull();
        assertThat(c.get("order_id")).isNull();
        assertThat(c.get("confirmed_at")).isNull();
        assertThat(c.get("proof_text"))
                .isEqualTo("Ticked the sign-up switch on the imin.wtf page (locale fr) next to: \"" + text + "\"");
        assertThat(jdbc.queryForList("select consent_record_id from consent_confirmation_tokens where membership_id = ?",
                UUID.class, m.get("membership_id"))).containsExactly((UUID) c.get("id"));
        assertThat(mail.sent()).filteredOn(s -> s.to().equals(email)).hasSize(1);
    }

    /** The dashboard's "Older · can't email" tag marks a grant ConsentGate does not accept as proof. */
    @Test
    void aFollower_passesConsentGateOnlyOnceTheEmailedLinkIsClicked() throws Exception {
        listOrg();
        flips.set(planProps, "consentConfirmationEmailsEnabled", true);
        String email = fx.email("gate");
        follow(org.getSlug(), json(email, true, text, VERSION)).andExpect(status().isOk());
        UUID mid = (UUID) membership(email).get("membership_id");

        assertThat(gate.reasons(org.getId(), List.of(mid))).containsEntry(mid, Optional.of(ConsentGate.NO_BASIS));
        assertThat(gate.canMarket(org.getId(), mid)).isFalse();

        mvc.perform(post("/api/v1/public/consent/confirm").param("t", confirmationTokenSentTo(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("confirmed"));

        assertThat(gate.reasons(org.getId(), List.of(mid))).containsEntry(mid, Optional.empty());
        assertThat(gate.canMarket(org.getId(), mid)).isTrue();
        assertThat(gate.unprovenGrantIds(org.getId(), mid)).isEmpty();
    }

    @Test
    void theConfirmationEmail_isSentOnTheMailPool_notOnTheRequestThread() throws Exception {
        listOrg();
        flips.set(planProps, "consentConfirmationEmailsEnabled", true);
        String email = fx.email("async");

        follow(org.getSlug(), json(email, true, text, VERSION)).andExpect(status().isOk());

        List<RecordingEmailService.SentEmail> sent = mail.sent();
        int i = IntStream.range(0, sent.size()).filter(k -> sent.get(k).to().equals(email)).findFirst().orElseThrow();
        assertThat(mail.sentOnThread(i)).isNotSameAs(Thread.currentThread());
        assertThat(mail.sentOnThread(i).getName()).startsWith("ticket-email-");
    }

    /** What the earlier sign-up of the same address left behind; only a live follow link holds the repeat. */
    enum Earlier {
        LIVE_FOLLOW_LINK(false), CONFIRMED_FOLLOW(true), EXPIRED_FOLLOW_LINK(true), LIVE_DOOR_LINK(true),
        USED_FOLLOW_LINK_STILL_PENDING(true), LIVE_FOLLOW_LINK_CONFIRMED_BY_ANOTHER(true);

        final boolean stores;

        Earlier(boolean stores) {
            this.stores = stores;
        }
    }

    @ParameterizedTest
    @EnumSource(Earlier.class)
    void aRepeat_isHeldOnlyWhileAPendingFollowHasALiveLink(Earlier earlier) throws Exception {
        listOrg();
        flips.set(planProps, "consentConfirmationEmailsEnabled", true);
        String email = fx.email("repeat");
        if (earlier == Earlier.LIVE_DOOR_LINK) {
            doorSignUpWithALiveLink(email);
        } else {
            follow(org.getSlug(), json(email, true, text, VERSION)).andExpect(status().isOk());
        }
        Object mid = membership(email).get("membership_id");
        switch (earlier) {
            case CONFIRMED_FOLLOW -> mvc.perform(post("/api/v1/public/consent/confirm")
                            .param("t", confirmationTokenSentTo(email)))
                    .andExpect(jsonPath("$.state").value("confirmed"));
            case EXPIRED_FOLLOW_LINK -> clock.advance(Duration.ofDays(8));
            // A click that confirmed nothing (the address had opted out then): the link is spent, the sign-up pending.
            case USED_FOLLOW_LINK_STILL_PENDING ->
                    jdbc.update("update consent_confirmation_tokens set used_at = now() where membership_id = ?", mid);
            // Another link of this member confirmed every pending sign-up, this one included.
            case LIVE_FOLLOW_LINK_CONFIRMED_BY_ANOTHER ->
                    jdbc.update("update consent_records set confirmed_at = now() where membership_id = ?", mid);
            default -> { }
        }
        int before = followRows(email).size();

        follow(org.getSlug(), json(email, true, text, VERSION))
                .andExpect(status().isOk())
                .andExpect(content().string(OK));

        assertThat(followRows(email)).hasSize(before + (earlier.stores ? 1 : 0));
    }

    /** A failed send removes its link, so nothing holds the next sign-up: the retry gets the email. */
    @Test
    void aRetryAfterAFailedSend_isStoredAndMailed() throws Exception {
        listOrg();
        flips.set(planProps, "consentConfirmationEmailsEnabled", true);
        String email = fx.email("retry");
        mail.failNextSendWith(new IllegalStateException("provider down"));
        follow(org.getSlug(), json(email, true, text, VERSION)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from consent_confirmation_tokens where membership_id = ?",
                Integer.class, membership(email).get("membership_id"))).isZero();
        assertThat(consentRows(email)).hasSize(1);

        clock.advance(Duration.ofHours(25));
        follow(org.getSlug(), json(email, true, text, VERSION)).andExpect(status().isOk());

        assertThat(mail.sent()).filteredOn(s -> s.to().equals(email)).hasSize(1);
    }

    @Test
    void aBouncedAddress_isStoredPending_butNeverMailed() throws Exception {
        listOrg();
        flips.set(planProps, "consentConfirmationEmailsEnabled", true);
        String email = fx.email("bounced");
        suppressions.insertDeliverabilityIfAbsent(UUID.randomUUID(), email, SuppressionEntry.REASON_HARD_BOUNCE);
        bounced.add(email);

        follow(org.getSlug(), json(email, true, text, VERSION))
                .andExpect(status().isOk())
                .andExpect(content().string(OK));

        assertThat(consentRows(email)).singleElement()
                .satisfies(c -> assertThat(c.get("confirmed_at")).isNull());
        assertThat(jdbc.queryForObject("select count(*) from consent_confirmation_tokens where membership_id = ?",
                Integer.class, membership(email).get("membership_id"))).isZero();
        assertThat(mail.sent()).noneMatch(s -> s.to().equals(email));
    }

    // ── rate limit, charged before anything else ──────────────────────────

    @ParameterizedTest(name = "org on the list: {0}")
    @ValueSource(booleans = {true, false})
    void overTheLimit_is429BeforeAnyLookup_andStoresNothing(boolean listed) throws Exception {
        if (listed) listOrg();
        limiter.limit("follow-optin", 0);
        String email = fx.email("limited");

        follow(org.getSlug(), json(email, true, text, VERSION))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertNothingStored(email);
    }

    // ── one 404 ───────────────────────────────────────────────────────────

    enum Closed { SLUG_LIST_EMPTY, SLUG_NOT_ON_THE_LIST, UNKNOWN_SLUG, KILL_SWITCH, ORG_OFF_THE_BETA_LIST }

    @ParameterizedTest
    @EnumSource(Closed.class)
    void aClosedFollow_isTheSame404_andStoresNothing(Closed closed) throws Exception {
        String email = fx.email("closed");
        String slug = org.getSlug();
        switch (closed) {
            case SLUG_LIST_EMPTY -> { } // the default: no org takes sign-ups
            case SLUG_NOT_ON_THE_LIST -> flips.set(followProps, "orgSlugs", List.of("another-" + UUID.randomUUID()));
            case UNKNOWN_SLUG -> {
                slug = "no-such-org-" + UUID.randomUUID();
                flips.set(followProps, "orgSlugs", List.of(slug));
            }
            case KILL_SWITCH -> {
                listOrg();
                flips.set(planProps, "enabled", false);
            }
            case ORG_OFF_THE_BETA_LIST -> {
                listOrg();
                flips.set(planProps, "betaOrgIds", Set.of(UUID.randomUUID()));
            }
        }

        follow(slug, json(email, true, text, VERSION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Organization not found"))
                .andExpect(jsonPath("$.error.fields").doesNotExist());

        assertNothingStored(email);
    }

    // ── 400 with the field map ────────────────────────────────────────────

    /** A null email or text stands for a valid one, and {org} for the organizer, so only the named field is wrong. */
    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                arguments("box not ticked", null, false, null, VERSION, "consentGiven"),
                arguments("box missing", null, null, null, VERSION, "consentGiven"),
                arguments("the door's text version", null, true, null, "door-org-named-2026-09", "consentTextVersion"),
                arguments("an unknown text version", null, true, null, "site-follow-v0", "consentTextVersion"),
                arguments("text that does not name the organizer", null, true,
                        "Send me news. Unsubscribe anytime.", VERSION, "consentText"),
                arguments("a quote in the text", null, true, "Send me {org} news. \"Unsubscribe\" anytime.", VERSION,
                        "consentText"),
                arguments("a line break in the text", null, true, "Send me {org} news.\nUnsubscribe anytime.", VERSION,
                        "consentText"),
                arguments("a direction override in the text", null, true, "Send me {org} news.\u202E Unsubscribe anytime.",
                        VERSION, "consentText"),
                arguments("malformed email", "not-an-email", true, null, VERSION, "email"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void anInvalidBody_is400NamingTheField_andStoresNothing(String label, String email, Boolean ticked, String text,
                                                           String version, String field) throws Exception {
        listOrg();
        String address = email == null ? fx.email("invalid") : email;

        follow(org.getSlug(), json(address, ticked, text == null ? this.text : text.replace("{org}", org.getName()), version))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields", aMapWithSize(1)))
                .andExpect(jsonPath("$.error.fields", hasKey(field)));

        assertNothingStored(address);
    }

    @ParameterizedTest(name = "body [{0}]")
    @ValueSource(strings = {"", "{}"})
    void aBodyWithoutAnEmail_is400OnTheEmailField(String body) throws Exception {
        listOrg();

        follow(org.getSlug(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields", aMapWithSize(1)))
                .andExpect(jsonPath("$.error.fields", hasKey("email")));
    }

    // ── accepted without storing ──────────────────────────────────────────

    /** The shared grant's other refusals are DoorOptInServiceTest's; this proves the follow path runs them. */
    @Test
    void anUnsubscribedAddress_getsTheSameOk_andNothingIsStoredOrMailed() throws Exception {
        listOrg();
        flips.set(planProps, "consentConfirmationEmailsEnabled", true);
        String email = fx.email("unsubscribed");
        projector.upsertMembership(org.getId(), email, null);
        jdbc.update("update memberships set consent_status = 'unsubscribed' where membership_id = ?",
                membership(email).get("membership_id"));
        Map<String, Object> before = membership(email);

        follow(org.getSlug(), json(email, true, text, VERSION))
                .andExpect(status().isOk())
                .andExpect(content().string(OK));

        assertThat(consentRows(email)).isEmpty();
        assertThat(membership(email)).isEqualTo(before);
        assertThat(mail.sent()).noneMatch(s -> s.to().equals(email));
    }

    // ── CORS: imin.wtf on this path only ──────────────────────────────────

    @Test
    void preflightFromImin_wtf_isAllowedForAJsonPost_withoutCredentials() throws Exception {
        mvc.perform(options(URL, "any-org")
                        .header(HttpHeaders.ORIGIN, SITE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SITE))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST,OPTIONS"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    /** The dashboard origin is on the platform-wide list, so it shows the follow path does not inherit that list. */
    @ParameterizedTest
    @ValueSource(strings = {"https://dashboard.imin.wtf", "https://imin.wtf.example.test"})
    void preflightFromAnotherOrigin_isRefusedOnTheFollowPath(String origin) throws Exception {
        mvc.perform(options(URL, "any-org")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    /** POST is the method the follow mapping grants, so a mapping wider than the follow path shows up here. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/public/events", "/api/v1/public/orgs/any-org"})
    void preflightFromImin_wtf_isStillRefusedOnOtherPublicPaths(String path) throws Exception {
        mvc.perform(options(path)
                        .header(HttpHeaders.ORIGIN, SITE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isForbidden());
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private void listOrg() {
        flips.set(followProps, "orgSlugs", List.of(org.getSlug()));
    }

    /** Waits for the confirmation email, which is sent after the request on the mail pool. */
    private ResultActions follow(String slug, String body) throws Exception {
        ResultActions result = mvc.perform(post(URL, slug).header(HttpHeaders.ORIGIN, SITE)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        AsyncDrain.drain(ticketEmailExecutor);
        return result;
    }

    private static String json(String email, Boolean ticked, String text, String version) {
        return "{\"email\":" + quote(email) + ",\"consentGiven\":" + ticked + ",\"consentText\":" + quote(text)
                + ",\"consentTextVersion\":" + quote(version) + ",\"locale\":\"fr\"}";
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder json = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') json.append('\\').append(c);
            else if (c < 0x20 || Character.getType(c) == Character.FORMAT) json.append(String.format("\\u%04x", (int) c));
            else json.append(c);
        }
        return json.append('"').toString();
    }

    private String confirmationTokenSentTo(String email) {
        String body = mail.sent().stream().filter(s -> s.to().equals(email)).findFirst().orElseThrow().text();
        String url = body.lines().map(String::trim).filter(l -> l.contains("/consent/confirm?t=")).findFirst()
                .orElseThrow();
        return url.substring(url.indexOf("?t=") + 3);
    }

    /** A pending door QR sign-up of this address whose emailed link is still live. */
    private void doorSignUpWithALiveLink(String email) {
        projector.upsertMembership(org.getId(), email, null);
        UUID mid = (UUID) membership(email).get("membership_id");
        UUID record = consentService.capture(org.getId(), mid, "explicit", "door_qr", "Ticked at the door", "email",
                "door-org-named-2026-09", null, ConsentOrigin.DATA_SUBJECT, null);
        confirmations.requestFor(org.getId(), mid, record, "en");
        AsyncDrain.drain(ticketEmailExecutor);
        assertThat(jdbc.queryForObject("select count(*) from consent_confirmation_tokens where membership_id = ?"
                + " and used_at is null", Integer.class, mid)).isEqualTo(1);
    }

    private List<Map<String, Object>> followRows(String email) {
        return consentRows(email).stream().filter(r -> "site_follow".equals(r.get("source"))).toList();
    }

    private List<Map<String, Object>> consentRows(String email) {
        return jdbc.queryForList("select cr.* from consent_records cr join memberships m on m.membership_id = cr.membership_id"
                + " join consumers c on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?",
                org.getId(), email);
    }

    private Map<String, Object> membership(String email) {
        return jdbc.queryForMap("select m.* from memberships m join consumers c on c.consumer_id = m.consumer_id"
                + " where m.org_id = ? and c.normalized_email = ?", org.getId(), email);
    }

    private void assertNothingStored(String email) {
        assertThat(jdbc.queryForObject("select count(*) from consumers where normalized_email = ?", Integer.class, email))
                .isZero();
        assertThat(mail.sent()).noneMatch(s -> s.to().equals(email));
    }
}
