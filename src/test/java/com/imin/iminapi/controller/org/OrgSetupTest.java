package com.imin.iminapi.controller.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.oauth.OAuthAccountService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.auth.OrganizerTerms;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.util.Slugger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A social sign-up creates an organizer account without a country, an organization name or the organizer
 * terms; POST /api/v1/org/setup collects them exactly as email sign-up does, once, from the owner.
 */
@IminIntegrationTest
class OrgSetupTest {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired OAuthAccountService oauth;
    @Autowired UserRepository users;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired RecordingEmailService mail;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper om = new ObjectMapper();

    // ---------- sign-in states ----------

    @Test
    void aFirstGoogleSignIn_leavesTheCountryUnchosenAndAsksForSetup() throws Exception {
        AuthResponse signedIn = googleSignUp();

        assertThat(signedIn.org().country()).as("no default country is chosen for a social sign-up").isNull();
        mvc.perform(get("/api/v1/auth/me").with(as(owner(signedIn))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.org.setupRequired").value(true));
        Map<String, Object> row = orgRow(signedIn);
        assertThat(row.get("country")).isNull();
        assertThat(row.get("setup_completed_at")).isNull();
    }

    @Test
    void anEmailSignUp_isSetUpFromTheStart() throws Exception {
        String email = fx.email("signup");
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", email, "password", "correct-horse-42",
                                "firstName", "Mira", "lastName", "Lutz", "orgName", "Lutz Nights", "country", "FR",
                                "acceptedTerms", true, "termsVersion", OrganizerTerms.CURRENT_VERSION))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", email, "code", codeSentTo(email)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.org.country").value("FR"))
                .andExpect(jsonPath("$.org.setupRequired").value(false));
    }

    // ---------- POST /org/setup ----------

    @Test
    void finishingSetup_recordsTheOwnerTheOrganizationAndTheTerms() throws Exception {
        AuthResponse signedIn = googleSignUp();
        String orgName = "Night Owls " + UUID.randomUUID().toString().substring(0, 8);
        Instant before = Instant.now();

        setup(owner(signedIn), validBody(Map.of("firstName", " Ada ", "lastName", "Byron", "orgName", orgName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.org.setupRequired").value(false))
                .andExpect(jsonPath("$.org.country").value("FR"))
                .andExpect(jsonPath("$.org.name").value(orgName))
                .andExpect(jsonPath("$.org.slug").value(Slugger.slugify(orgName)))
                .andExpect(jsonPath("$.org.timezone").value("Europe/Paris"))
                .andExpect(jsonPath("$.user.firstName").value("Ada"))
                .andExpect(jsonPath("$.user.lastName").value("Byron"))
                .andExpect(jsonPath("$.user.avatarInitials").value("AB"));

        Map<String, Object> user = jdbc.queryForMap(
                "select terms_accepted_at, terms_version from users where id = ?", signedIn.user().id());
        assertThat(user.get("terms_version")).isEqualTo(OrganizerTerms.CURRENT_VERSION);
        assertThat(((java.sql.Timestamp) user.get("terms_accepted_at")).toInstant()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(orgRow(signedIn).get("setup_completed_at")).isNotNull();
        mvc.perform(get("/api/v1/auth/me").with(as(owner(signedIn))))
                .andExpect(jsonPath("$.org.setupRequired").value(false))
                .andExpect(jsonPath("$.org.country").value("FR"));
    }

    @Test
    void anExplicitTimezone_winsOverTheCountrysOwn() throws Exception {
        AuthResponse signedIn = googleSignUp();

        setup(owner(signedIn), validBody(Map.of("timezone", "Europe/Madrid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.org.timezone").value("Europe/Madrid"));
    }

    @Test
    void anOrganizationThatHasPublished_keepsItsSlug() throws Exception {
        AuthResponse signedIn = googleSignUp();
        Organization org = orgs.findById(signedIn.org().id()).orElseThrow();
        Event published = fx.event(org, owner(signedIn), EventStatus.LIVE, Instant.now().plusSeconds(86_400));
        published.setPublishedAt(Instant.now());
        events.save(published);

        setup(owner(signedIn), validBody(Map.of("orgName", "Renamed " + UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.org.slug").value(org.getSlug()));
    }

    static Stream<Arguments> refusedLikeSignUp() {
        return Stream.of(
                Arguments.of("country", "FRA", 400, "FIELD_INVALID"),
                Arguments.of("country", "", 400, "FIELD_INVALID"),
                Arguments.of("country", "UA", 422, "COUNTRY_NOT_SUPPORTED"),
                Arguments.of("country", "RU", 422, "COUNTRY_NOT_ALLOWED"),
                Arguments.of("firstName", " ", 400, "FIELD_INVALID"),
                Arguments.of("lastName", null, 400, "FIELD_INVALID"),
                Arguments.of("orgName", "", 400, "FIELD_INVALID"),
                Arguments.of("termsVersion", "2020-01-01", 400, "FIELD_INVALID"),
                Arguments.of("termsVersion", null, 400, "FIELD_INVALID"),
                Arguments.of("timezone", "Mars/Olympus_Mons", 400, "FIELD_INVALID"),
                Arguments.of("timezone", "+02:00", 400, "FIELD_INVALID"));
    }

    @ParameterizedTest(name = "{0}={1} -> {2} {3}")
    @MethodSource("refusedLikeSignUp")
    void setup_refusesWhatSignUpRefuses_andChangesNothing(String field, String value, int status, String code)
            throws Exception {
        AuthResponse signedIn = googleSignUp();
        Map<String, Object> body = validBody(Map.of());
        body.put(field, value);

        setup(owner(signedIn), body)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.fields." + field).exists());

        Map<String, Object> row = orgRow(signedIn);
        assertThat(row.get("setup_completed_at")).isNull();
        assertThat(row.get("country")).isNull();
        assertThat(jdbc.queryForObject("select terms_version from users where id = ?", String.class,
                signedIn.user().id())).isNull();
    }

    @Test
    void aSecondSetup_isRefusedAndChangesNothing() throws Exception {
        AuthResponse signedIn = googleSignUp();
        String firstName = "First " + UUID.randomUUID();
        setup(owner(signedIn), validBody(Map.of("orgName", firstName))).andExpect(status().isOk());

        setup(owner(signedIn), validBody(Map.of("orgName", "Second " + UUID.randomUUID(), "country", "DE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        Map<String, Object> row = orgRow(signedIn);
        assertThat(row.get("name")).isEqualTo(firstName);
        assertThat(row.get("country")).isEqualTo("FR");
    }

    @Test
    void onlyTheOwnerCanFinishSetup() throws Exception {
        AuthResponse signedIn = googleSignUp();
        User member = new User();
        member.setOrgId(signedIn.org().id());
        member.setEmail(fx.email("member"));
        member.setRole(UserRole.ADMIN);
        member = users.save(member);

        setup(member, validBody(Map.of()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(orgRow(signedIn).get("setup_completed_at")).isNull();
    }

    // ---------- helpers ----------

    private AuthResponse googleSignUp() {
        return oauth.resolve(new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                fx.email("ada"), true, "Ada", "Lovelace", "Ada Lovelace"));
    }

    private User owner(AuthResponse signedIn) {
        return users.findById(signedIn.user().id()).orElseThrow();
    }

    private Map<String, Object> validBody(Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Ada");
        body.put("lastName", "Lovelace");
        body.put("orgName", "Lovelace Nights " + UUID.randomUUID().toString().substring(0, 8));
        body.put("country", "FR");
        body.put("termsVersion", OrganizerTerms.CURRENT_VERSION);
        body.putAll(overrides);
        return body;
    }

    private ResultActions setup(User as, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/org/setup").with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private Map<String, Object> orgRow(AuthResponse signedIn) {
        return jdbc.queryForMap("select name, country, setup_completed_at from organizations where id = ?",
                signedIn.org().id());
    }

    private String codeSentTo(String email) {
        String text = mail.sent().stream().filter(m -> m.to().equals(email)).reduce((a, b) -> b)
                .orElseThrow().text();
        Matcher m = CODE.matcher(text);
        assertThat(m.find()).as("the verification email carries a six-digit code").isTrue();
        return m.group(1);
    }

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }
}
