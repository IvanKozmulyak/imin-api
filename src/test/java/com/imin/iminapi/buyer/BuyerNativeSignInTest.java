package com.imin.iminapi.buyer;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.oauth.AppleNativeIdentityService;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.oauth.OAuthProperties;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Native sign-in with Google and with Apple (App Store Guideline 4.8): an OS-issued ID token,
 * no redirect, no nonce cookie.
 *
 * <p>The properties that matter, for both providers: an unverified address cannot mint or join an
 * address claim, a buyer sign-in creates no organizer, and a returning Apple user is matched on
 * SUBJECT, so a token with no email still lands in the same account.
 *
 * <p>The verifiers are stubbed on the shared spies, so this proves the HTTP wiring and the
 * resolution matrix and <b>nothing</b> about claim parsing — {@code AppleNativeIdentityServiceTest}
 * and {@code GoogleOAuthServiceTest} pin that.
 */
@IminIntegrationTest
class BuyerNativeSignInTest {

    @Autowired MockMvc mvc;
    @Autowired BuyerIdentityRepository identities;
    @Autowired BuyerAccountEmailRepository buyerEmails;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired GoogleOAuthService google;
    @Autowired AppleNativeIdentityService apple;
    @Autowired OAuthProperties oauthProps;
    @Autowired PropertyFlips flips;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"apple", "google"})
    void aVerifiedTokenSignsInWithABuyerAccountAndNoOrganizer(String provider) throws Exception {
        String address = address(provider);
        String subject = provider + "-sub-" + UUID.randomUUID();
        enable(provider);
        stub(provider, "token-ok", new OAuthUserInfo(provider, subject, address, true, "Sofiya", "K", "Sofiya K"));

        signIn(provider, "token-ok")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.emails[0].email").value(address))
                .andExpect(jsonPath("$.emails[0].verified").value(true))
                // Provenance, not decoration: added_via rides this projection to GET /buyer/me, so
                // filing an Apple relay address as "google" would be a factual error shown to the buyer.
                .andExpect(jsonPath("$.emails[0].addedVia").value(provider));

        assertThat(jdbc.queryForObject("select count(*) from organizations where lower(contact_email) = ?",
                Integer.class, address.toLowerCase())).isZero();
        assertThat(users.existsByEmailLower(address.toLowerCase())).isFalse();
        assertThat(identities.findByProviderAndProviderUserId(provider, subject)).isPresent();
    }

    /**
     * The status alone would pass if the gate ran after the claim was written; the missing row pins it.
     * For Apple this is also the Hide My Email consequence: a relay address is not claimable by weaker
     * matching. The message reaches users of both providers, so it names neither.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"apple", "google"})
    void anUnverifiedTokenNeverMintsAnAddressClaim(String provider) throws Exception {
        String address = address(provider);
        enable(provider);
        stub(provider, "token-unverified", new OAuthUserInfo(provider, provider + "-sub-" + UUID.randomUUID(),
                address, false, null, null, null));

        signIn(provider, "token-unverified")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OAUTH_EMAIL_UNVERIFIED"))
                .andExpect(jsonPath("$.error.message",
                        allOf(not(containsStringIgnoringCase("google")), not(containsStringIgnoringCase("apple")))));

        assertThat(buyerEmails.findByVerifiedKey(EmailNormalizer.normalize(address))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"apple", "google"})
    void aBlankIdTokenIs400(String provider) throws Exception {
        enable(provider);

        signIn(provider, "").andExpect(status().isBadRequest());
    }

    /** Google's audience falls back to the web client id, so both are blanked to turn the lane off. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"apple", "google"})
    void aProviderWithNoAudienceIs404(String provider) throws Exception {
        flips.set(oauthProps, provider + ".nativeAudience", "");
        if (provider.equals("google")) flips.set(oauthProps, "google.clientId", "");

        signIn(provider, "whatever")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_DISABLED"));
    }

    @Test
    void aSecondAppleSignInReusesTheSameAccountEvenWithNoEmailInTheToken() throws Exception {
        String relay = address("apple");
        String subject = "apple-sub-" + UUID.randomUUID();
        enable("apple");
        stub("apple", "first", new OAuthUserInfo("apple", subject, relay, true, "Sofiya", "K", "Sofiya K"));
        // Apple omits email on every sign-in after the first.
        stub("apple", "second", new OAuthUserInfo("apple", subject, null, true, null, null, null));

        String first = signIn("apple", "first").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = signIn("apple", "second").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(idOf(second)).isEqualTo(idOf(first));
        // A second identity row would mean the match ran on email, not subject:
        // an emailless token would then have created a whole new account.
        assertThat(buyerEmails.findByVerifiedKey(EmailNormalizer.normalize(relay))).isPresent();
    }

    private void enable(String provider) {
        flips.set(oauthProps, provider + ".nativeAudience", "test-" + provider + "-native-audience");
    }

    /** doReturn, never when(): the shared spies call Google and Apple for real unless stubbed. */
    private void stub(String provider, String token, OAuthUserInfo info) {
        if (provider.equals("apple")) {
            doReturn(info).when(apple).verify(eq(token), any());
        } else {
            doReturn(info).when(google).verifyNativeIdToken(token);
        }
    }

    private ResultActions signIn(String provider, String token) throws Exception {
        String body = provider.equals("apple")
                ? "{\"idToken\":\"" + token + "\",\"fullName\":\"Sofiya K\"}"
                : "{\"idToken\":\"" + token + "\"}";
        return mvc.perform(post("/api/v1/buyer/auth/" + provider + "/native")
                .header("X-Imin-Client", "native")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String address(String provider) {
        return provider.equals("apple")
                ? UUID.randomUUID() + "@privaterelay.appleid.com"
                : "g-" + UUID.randomUUID() + "@example.test";
    }

    private static String idOf(String body) {
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
