package com.imin.iminapi.buyer;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.oauth.AppleNativeIdentityService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Native Sign in with Apple — the App Store Guideline 4.8 requirement.
 *
 * <p>The property that matters most: a returning Apple user is matched on
 * SUBJECT, so a relay address that changes nothing still lands in the same
 * account, and two different Apple users never collide.
 *
 * <p>This class replaces {@link AppleNativeIdentityService} with a mock, so it
 * proves the HTTP wiring and the resolution matrix, and proves <b>nothing</b>
 * about the {@code email_verified} claim parsing — its stubs hand back
 * {@code emailVerified = true}, which is the very shortcut the real class exists
 * to avoid. {@code AppleNativeIdentityServiceTest} is where that is pinned.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerNativeAppleSignInTest {

    @Autowired MockMvc mvc;
    @Autowired BuyerIdentityRepository identities;
    @Autowired BuyerAccountEmailRepository buyerEmails;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @MockitoBean EmailService email;
    @MockitoBean AppleNativeIdentityService apple;

    @Test
    void firstSignInCreatesABuyerAccountAndNoOrganizer() throws Exception {
        String relay = UUID.randomUUID() + "@privaterelay.appleid.com";
        String subject = "apple-sub-" + UUID.randomUUID();
        when(apple.enabled()).thenReturn(true);
        when(apple.verify(eq("apple-token"), any()))
                .thenReturn(new OAuthUserInfo("apple", subject, relay, true, "Sofiya", "K", "Sofiya K"));

        long orgsBefore = orgs.count();
        long usersBefore = users.count();

        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"apple-token\",\"fullName\":\"Sofiya K\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.emails[0].email").value(relay))
                // Provenance, not decoration: added_via rides this projection to
                // GET /buyer/me and GET /buyer/emails, so filing an Apple relay
                // address as "google" would be a factual error shown to the
                // buyer about where their own address came from.
                .andExpect(jsonPath("$.emails[0].addedVia").value(BuyerAccountEmail.ADDED_VIA_APPLE));

        assertThat(orgs.count()).isEqualTo(orgsBefore);
        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(identities.findByProviderAndProviderUserId("apple", subject)).isPresent();
    }

    @Test
    void secondSignInReusesTheSameAccountEvenWithNoEmailInTheToken() throws Exception {
        String relay = UUID.randomUUID() + "@privaterelay.appleid.com";
        String subject = "apple-sub-" + UUID.randomUUID();
        when(apple.enabled()).thenReturn(true);
        when(apple.verify(eq("first"), any()))
                .thenReturn(new OAuthUserInfo("apple", subject, relay, true, "Sofiya", "K", "Sofiya K"));
        // Apple omits email on every sign-in after the first.
        when(apple.verify(eq("second"), any()))
                .thenReturn(new OAuthUserInfo("apple", subject, null, true, null, null, null));

        String first = mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"first\",\"fullName\":\"Sofiya K\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"second\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(idOf(second)).isEqualTo(idOf(first));
        // A second identity row would mean the match ran on email, not subject:
        // an emailless token would then have created a whole new account.
        assertThat(buyerEmails.findByVerifiedKey(EmailNormalizer.normalize(relay))).isPresent();
    }

    /**
     * The Hide My Email consequence, pinned so nobody "fixes" it later: a relay
     * address matches no past guest order, and the unverified rows the guest
     * checkout left behind are not claimable by weaker matching. Recovery is the
     * add-and-verify-your-real-address flow, not a looser join.
     */
    @Test
    void anUnverifiedAppleTokenNeverMintsAnAddressClaim() throws Exception {
        String relay = UUID.randomUUID() + "@privaterelay.appleid.com";
        when(apple.enabled()).thenReturn(true);
        when(apple.verify(eq("unverified"), any()))
                .thenReturn(new OAuthUserInfo("apple", "apple-sub-" + UUID.randomUUID(),
                        relay, false, null, null, null));

        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"unverified\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OAUTH_EMAIL_UNVERIFIED"))
                // The message reaches an Apple user, so it must not name Google.
                .andExpect(jsonPath("$.error.message",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsStringIgnoringCase("google"))));

        assertThat(buyerEmails.findByVerifiedKey(EmailNormalizer.normalize(relay))).isEmpty();
    }

    @Test
    void disabledProviderIs404() throws Exception {
        when(apple.enabled()).thenReturn(false);
        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"whatever\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_DISABLED"));
    }

    @Test
    void blankIdTokenIs400() throws Exception {
        when(apple.enabled()).thenReturn(true);
        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private static String idOf(String body) {
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
