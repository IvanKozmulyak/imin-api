package com.imin.iminapi.security;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The perimeter the 2026-09 audit found open: Swagger reachable in production
 * despite the docs saying otherwise, a credentialed CORS wildcard on a
 * registrable Vercel namespace, no response-hardening headers on an origin that
 * returns buyer addresses and ticket QRs, and actuator behind a chain that ends
 * in {@code .anyRequest().permitAll()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class SecurityPerimeterTest {

    @Autowired MockMvc mvc;

    // ── prod config, read from source ──────────────────────────────────────
    //
    // Read rather than booted, for the reason RateLimitBucketCoverageTest reads
    // its config: the prod profile is not active here, so there is no live value
    // to inspect, and a test that asserted on the TEST profile's springdoc
    // settings would pass while production served the whole API map.

    @Test
    void swagger_ui_is_disabled_and_api_docs_stay_on_under_the_prod_profile() throws IOException {
        String prod = Files.readString(
                Path.of("src/main/resources/application-prod.yaml"), StandardCharsets.UTF_8);

        // The springdoc block only — `enabled: true` also appears under sentry.
        String springdoc = prod.substring(prod.indexOf("springdoc:"));
        springdoc = springdoc.substring(0, springdoc.indexOf("\nserver:"));

        // Swagger UI must be off; /v3/api-docs stays on because imin-webapp's
        // api:sync reads the contract from production (docs/API_SYNC.md).
        String swaggerUi = springdoc.substring(springdoc.indexOf("swagger-ui:"));
        assertThat(swaggerUi)
                .as("CLAUDE.md has always claimed Swagger UI is dev-only; it was not")
                .contains("enabled: false")
                .doesNotContain("enabled: true");
        String apiDocs = springdoc.substring(springdoc.indexOf("api-docs:"), springdoc.indexOf("swagger-ui:"));
        assertThat(apiDocs)
                .as("/v3/api-docs is the FE contract source; keep it reachable in prod")
                .contains("enabled: true");
    }

    @Test
    void the_credentialed_cors_allowlist_has_no_registrable_wildcards() throws IOException {
        String config = Files.readString(
                Path.of("src/main/resources/application.yaml"), StandardCharsets.UTF_8);
        String line = config.lines()
                .filter(l -> l.contains("allowed-origin-patterns:"))
                .findFirst().orElseThrow();

        // Vercel project names are first-come, so `https://imin-webapp-*.vercel.app`
        // next to allowCredentials(true) is an allow-list anyone can join.
        assertThat(line).doesNotContain("imin-webapp-*").doesNotContain("imin-public-*");
        assertThat(line).contains("https://dashboard.imin.wtf").contains("https://app.imin.wtf");
        // Previews get their own, empty-by-default list.
        assertThat(config).contains("preview-origin-patterns:");
    }

    @Test
    void only_health_is_exposed_over_http_in_prod() throws IOException {
        String prod = Files.readString(
                Path.of("src/main/resources/application-prod.yaml"), StandardCharsets.UTF_8);
        assertThat(prod).contains("include: health");
    }

    // ── live response headers ──────────────────────────────────────────────

    @Test
    void every_response_carries_the_hardening_headers() throws Exception {
        mvc.perform(get("/api/v1/public/app-config"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Permissions-Policy",
                        "camera=(), microphone=(), geolocation=()"));
    }

    /** Order and ticket tokens live in the URL path, so a leaked referrer leaks the ticket. */
    @Test
    void api_responses_carry_the_strict_csp_and_cannot_be_framed() throws Exception {
        mvc.perform(get("/api/v1/public/app-config"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'"));
    }

    /** The 401 path writes and flushes its own body — the headers must already be on it. */
    @Test
    void an_unauthenticated_401_still_carries_the_headers() throws Exception {
        mvc.perform(get("/api/v1/orgs/me"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'"));
    }

    /**
     * A page this app serves itself must not get {@code default-src 'none'} —
     * that would break Swagger UI in dev and the poster assets everywhere.
     */
    @Test
    void non_api_paths_are_not_given_the_json_only_csp() throws Exception {
        mvc.perform(get("/images/does-not-exist.png"))
                .andExpect(header().doesNotExist("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    // ── Spring Data REST ───────────────────────────────────────────────────

    /**
     * Spring Data REST is on the classpath and, with no {@code spring.data.rest.base-path},
     * it mounts its own {@code RepositoryController}/{@code ProfileController} at the servlet
     * root — outside the {@code /api/v1/**} {@code .authenticated()} rule and therefore under
     * the chain's closing {@code .anyRequest().permitAll()}. The repository export guard is the
     * control that holds today; this pins the second line of defence so a repository that ever
     * loses {@code @RepositoryRestResource(exported = false)} becomes an authenticated resource
     * rather than an unauthenticated public CRUD surface.
     */
    @Test
    void spring_data_rest_publishes_nothing_at_the_servlet_root() throws Exception {
        for (String path : new String[] {"/", "/profile", "/orders"}) {
            mvc.perform(get(path)).andExpect(result ->
                    assertThat(result.getResponse().getStatus())
                            .as("Spring Data REST must not answer %s", path)
                            .isNotEqualTo(200));
        }
    }

    // ── actuator ───────────────────────────────────────────────────────────

    private void assertDenied(String path) throws Exception {
        mvc.perform(get(path)).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void actuator_is_denied_apart_from_health() throws Exception {
        // Reachable, whatever it currently reports — 503 is a health verdict, 403
        // would mean the probe itself had been locked out.
        mvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
        // denyAll on an anonymous request goes through the authentication entry
        // point, so the wire answer is 401 rather than 403. Either is a denial;
        // what matters is that it is not the endpoint's own body.
        assertDenied("/actuator/env");
        assertDenied("/actuator/beans");
        assertDenied("/actuator/configprops");
    }
}
