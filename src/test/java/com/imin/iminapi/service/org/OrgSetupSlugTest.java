package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.dto.org.OrgSetupRequest;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.oauth.OAuthAccountService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.auth.OrganizerTerms;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.util.Slugger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The slug setup gives an organization: the name's own slug when free, else the same suffix scheme as sign-up. */
@IminIntegrationTest
class OrgSetupSlugTest {

    private static final String RANDOM = "random";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired OAuthAccountService oauth;
    @Autowired OrgSetupService setup;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    static Stream<Arguments> slugs() {
        return Stream.of(
                Arguments.of("a free name keeps its slug", List.of(), null, ""),
                Arguments.of("the org's own slug counts as free", List.of(), "", ""),
                Arguments.of("a taken slug gets -2", List.of(""), null, "-2"),
                Arguments.of("taken up to -9 gets -10", suffixes(9), null, "-10"),
                Arguments.of("taken up to -10 gets a random suffix", suffixes(10), null, RANDOM),
                Arguments.of("its own -3 counts as free in the suffix walk", List.of("", "-2"), "-3", "-3"));
    }

    /** The plain slug, then -2 .. -last. */
    private static List<String> suffixes(int last) {
        List<String> out = new ArrayList<>(List.of(""));
        for (int n = 2; n <= last; n++) out.add("-" + n);
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("slugs")
    void setupPicksTheSlug(String label, List<String> takenByOthers, String own, String expected) {
        String orgName = "Slug Case " + UUID.randomUUID().toString().substring(0, 8);
        String base = Slugger.slugify(orgName);
        AuthPrincipal owner = googleSignUp();
        if (own != null) setSlug(owner.orgId(), base + own);
        for (String suffix : takenByOthers) setSlug(fx.org().getId(), base + suffix);

        setup.complete(owner, request(orgName));

        String slug = slugOf(owner.orgId());
        if (RANDOM.equals(expected)) assertThat(slug).matches(Pattern.quote(base) + "-[0-9a-f]{6}");
        else assertThat(slug).isEqualTo(base + expected);
    }

    @Test
    void aSlugTakenWhileSetupWrites_isA409AndSetupStaysPending() throws Exception {
        String orgName = "Slug Race " + UUID.randomUUID().toString().substring(0, 8);
        AuthPrincipal owner = googleSignUp();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (PgFaults.Pause paused = PgFaults.pauseWrites(dataSource, "organizations", "id", owner.orgId())) {
            Future<?> running = pool.submit(() -> setup.complete(owner, request(orgName)));
            paused.awaitBlocked(WAIT);
            setSlug(fx.org().getId(), Slugger.slugify(orgName)); // another org takes the slug setup chose
            paused.release();

            ExecutionException failed = catchThrowableOfType(ExecutionException.class,
                    () -> running.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertThat(failed.getCause()).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.code()).isEqualTo(ErrorCode.DUPLICATE);
            });
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select setup_completed_at from organizations where id = ?",
                java.sql.Timestamp.class, owner.orgId())).isNull();
    }

    // ---------- helpers ----------

    private AuthPrincipal googleSignUp() {
        AuthResponse signedIn = oauth.resolve(new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                fx.email("ada"), true, "Ada", "Lovelace", "Ada Lovelace"));
        return new AuthPrincipal(signedIn.user().id(), signedIn.org().id(), UserRole.OWNER, UUID.randomUUID());
    }

    private static OrgSetupRequest request(String orgName) {
        return new OrgSetupRequest("Ada", "Lovelace", orgName, "FR", OrganizerTerms.CURRENT_VERSION, null);
    }

    private void setSlug(UUID org, String slug) {
        jdbc.update("update organizations set slug = ? where id = ?", slug, org);
    }

    private String slugOf(UUID org) {
        return jdbc.queryForObject("select slug from organizations where id = ?", String.class, org);
    }
}
