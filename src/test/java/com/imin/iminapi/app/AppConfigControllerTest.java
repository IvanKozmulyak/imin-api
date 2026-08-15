package com.imin.iminapi.app;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The force-upgrade gate, end to end over HTTP.
 *
 * <p>Driven through MockMvc rather than by calling the controller directly
 * because three of the things that could break it are not in the controller:
 * the {@code GET /api/v1/public/**} permitAll rule, the {@code imin.app.*}
 * property binding, and the JSON field names a shipped binary will parse
 * forever. A unit test on the method would pass with all three broken.
 *
 * <p>Note the properties come from {@code @TestPropertySource}, not from
 * {@code src/main/resources/application.yaml}: the test classpath's
 * {@code application.yaml} <b>replaces</b> the main one wholesale, so the
 * {@code ${IMIN_APP_*}} placeholders there are not visible to any test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = {
        "imin.app.ios.min-supported-version=1.2.0",
        "imin.app.ios.latest-version=1.9.0",
        "imin.app.ios.store-url=https://apps.apple.com/app/id0000000000",
        "imin.app.android.min-supported-version=2.0.0",
        "imin.app.android.latest-version=2.0.0",
})
class AppConfigControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void belowTheMinimumIsUpdateRequired() throws Exception {
        mvc.perform(get("/api/v1/public/app-config?platform=ios&version=1.1.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("update_required"))
                .andExpect(jsonPath("$.minSupportedVersion").value("1.2.0"))
                .andExpect(jsonPath("$.storeUrl").value("https://apps.apple.com/app/id0000000000"));
    }

    @Test
    void betweenMinimumAndLatestIsUpdateRecommended() throws Exception {
        mvc.perform(get("/api/v1/public/app-config?platform=ios&version=1.3.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("update_recommended"));
    }

    @Test
    void atTheLatestIsOk() throws Exception {
        mvc.perform(get("/api/v1/public/app-config?platform=ios&version=1.9.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    /**
     * The lexical trap, over the wire. 1.10.0 is newer than 1.9.0; a
     * {@code String.compareTo} gate would answer {@code update_recommended} and
     * nag the freshest install in the field forever.
     */
    @Test
    void tenIsNewerThanNine() throws Exception {
        mvc.perform(get("/api/v1/public/app-config?platform=ios&version=1.10.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    /**
     * Fail open. A request whose version we cannot read must not be blocked —
     * there is no channel left to un-block it through.
     */
    @Test
    void absentOrJunkVersionIsOk() throws Exception {
        mvc.perform(get("/api/v1/public/app-config?platform=ios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mvc.perform(get("/api/v1/public/app-config?platform=android&version=not-a-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void unknownPlatformIs400() throws Exception {
        mvc.perform(get("/api/v1/public/app-config?platform=blackberry&version=1.0.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /**
     * The reference data rides along so a cold launch is one round trip. Both
     * lists are empty on an empty test database — the assertion is that the
     * keys exist, because their absence is what would send the app back for two
     * more requests.
     */
    @Test
    void foldsInTheReferenceDataAndAlwaysCarriesFlags() throws Exception {
        mvc.perform(get("/api/v1/public/app-config?platform=ios&version=1.9.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities").isArray())
                .andExpect(jsonPath("$.genres").isArray())
                .andExpect(jsonPath("$.flags").exists());
    }

    /** Unauthenticated, like every other {@code /api/v1/public} GET. */
    @Test
    void needsNoCredential() throws Exception {
        mvc.perform(get("/api/v1/public/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                // No platform named, so there is no release to report. Null, never
                // a placeholder version the app might compare itself against.
                .andExpect(jsonPath("$.minSupportedVersion").doesNotExist());
    }
}
