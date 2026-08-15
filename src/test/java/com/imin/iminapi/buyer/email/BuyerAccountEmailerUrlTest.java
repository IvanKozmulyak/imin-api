package com.imin.iminapi.buyer.email;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The buyer site's page routes, as this service links to them.
 *
 * <p>This exists because both were wrong in production and nothing caught it:
 * {@code /auth/login} and {@code /auth/reset-password} are the API endpoint
 * paths under {@code /api/v1/buyer}, while imin-public serves the pages at
 * {@code /auth/sign-in} and {@code /auth/reset}. Every password-reset email
 * landed on a 404, and three more emails linked to a dead sign-in page.
 *
 * <p>A string test cannot reach across repos, so it does the next best thing:
 * it makes the value deliberate and names where the other half lives. If
 * {@code imin-public/app/auth/} is reorganised, this fails until both repos
 * move together.
 */
class BuyerAccountEmailerUrlTest {

    private final BuyerAccountEmailer emailer = new BuyerAccountEmailer(
            Mockito.mock(EmailService.class),
            Mockito.mock(EmailTemplateRenderer.class),
            propsWithBase("https://app.imin.wtf"));

    @Test
    void signInUrlMatchesTheRouteIminPublicActuallyServes() {
        // imin-public/app/auth/sign-in/page.tsx
        assertThat(emailer.signInUrl()).isEqualTo("https://app.imin.wtf/auth/sign-in");
    }

    @Test
    void resetUrlMatchesTheRouteIminPublicActuallyServes() {
        // imin-public/app/auth/reset/page.tsx — reads searchParams.token
        assertThat(emailer.resetUrl("tok_abc123"))
                .isEqualTo("https://app.imin.wtf/auth/reset?token=tok_abc123");
    }

    @Test
    void neitherUrlPointsAtAnApiEndpointPath() {
        // The original defect in one assertion: /auth/login and
        // /auth/reset-password exist, but only under /api/v1/buyer.
        assertThat(emailer.signInUrl()).doesNotContain("/auth/login");
        assertThat(emailer.resetUrl("t")).doesNotContain("/auth/reset-password");
    }

    @Test
    void urlsRespectAConfiguredBaseSoLocalDevAndPreviewsWork() {
        BuyerAccountEmailer local = new BuyerAccountEmailer(
                Mockito.mock(EmailService.class),
                Mockito.mock(EmailTemplateRenderer.class),
                propsWithBase("http://localhost:3000"));

        assertThat(local.signInUrl()).isEqualTo("http://localhost:3000/auth/sign-in");
        assertThat(local.resetUrl("t")).isEqualTo("http://localhost:3000/auth/reset?token=t");
    }

    private static EmailProperties propsWithBase(String base) {
        EmailProperties props = new EmailProperties();
        props.setBuyerSiteBaseUrl(base);
        return props;
    }
}
