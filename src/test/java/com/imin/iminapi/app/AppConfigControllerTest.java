package com.imin.iminapi.app;

import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The force-upgrade gate, end to end over HTTP: the public permitAll rule, the per-request read of
 * {@code imin.app.*} and the JSON field names a shipped binary parses. Version ordering and the
 * fail-open rule for junk versions are owned by {@link AppVersionsTest}.
 */
@IminIntegrationTest
class AppConfigControllerTest {

    private static final String IOS_STORE = "https://apps.apple.com/app/id0000000000";

    @Autowired MockMvc mvc;
    @Autowired PropertyFlips flips;
    @Autowired AppReleaseProperties releases;

    @BeforeEach
    void releases() {
        flips.set(releases, "ios.minSupportedVersion", "1.2.0");
        flips.set(releases, "ios.latestVersion", "1.9.0");
        flips.set(releases, "ios.storeUrl", IOS_STORE);
        flips.set(releases, "android.minSupportedVersion", "2.0.0");
        flips.set(releases, "android.latestVersion", "2.0.0");
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "ios,     1.1.9, update_required",
            "ios,     1.3.0, update_recommended",
            "ios,     1.9.0, ok",
            "android, 1.9.0, update_required",
    })
    void verdictFollowsTheConfiguredReleasesOfThePlatform(String platform, String version, String verdict)
            throws Exception {
        String min = platform.equals("ios") ? "1.2.0" : "2.0.0";
        var result = mvc.perform(get("/api/v1/public/app-config?platform=" + platform + "&version=" + version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(verdict))
                .andExpect(jsonPath("$.minSupportedVersion").value(min));
        if (platform.equals("ios")) {
            result.andExpect(jsonPath("$.storeUrl").value(IOS_STORE));
        } else {
            result.andExpect(jsonPath("$.storeUrl").doesNotExist());
        }
    }

    @Test
    void unknownPlatformIs400() throws Exception {
        mvc.perform(get("/api/v1/public/app-config?platform=blackberry&version=1.0.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /** The reference data rides along so a cold launch is one round trip; the keys must exist. */
    @Test
    void foldsInTheReferenceDataAndAlwaysCarriesFlags() throws Exception {
        mvc.perform(get("/api/v1/public/app-config?platform=ios&version=1.9.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities").isArray())
                .andExpect(jsonPath("$.genres").isArray())
                .andExpect(jsonPath("$.flags").exists());
    }

    /** Unauthenticated, like every other {@code /api/v1/public} GET; no platform means no release to report. */
    @Test
    void needsNoCredential() throws Exception {
        mvc.perform(get("/api/v1/public/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.minSupportedVersion").doesNotExist());
    }
}
