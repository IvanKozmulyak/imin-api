package com.imin.iminapi.service.ticket.google;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.service.EventContentService;
import com.imin.iminapi.service.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the Google Wallet beans actually wire, and that they wire <b>closed</b>.
 *
 * <h2>Why this is worth a context</h2>
 *
 * <p>The first attempt at this layer gave {@link GoogleWalletApiClient} two
 * constructors and neither an {@code @Autowired}. Spring's implicit
 * single-constructor rule only applies when there is exactly one, so the
 * container went looking for a no-arg constructor, found none, and refused to
 * start the context — which is not a wallet-shaped failure at all. It is
 * <em>every</em> Spring test in the repository erroring at once, 1075 of them,
 * from one annotation missing on one class nothing else depends on. A unit test
 * of this client cannot see that; only a started context can.
 *
 * <p>The annotations below deliberately mirror {@code IminApiApplicationTests}
 * exactly, so both classes resolve to the same cached context and this file
 * costs nothing to run.
 *
 * <h2>And that it is off</h2>
 *
 * <p>{@code src/test/resources/application.yaml} carries no
 * {@code imin.google-wallet} block, so the beans below are in the state every
 * developer machine and every CI run is in: constructed, injectable, and
 * incapable of reaching Google. That is the state to assert, because the failure
 * mode being guarded is a wallet that quietly becomes live.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class GoogleWalletContextTest {

    @MockitoBean(name = "replicateRestClient")
    RestClient replicateRestClient;

    @MockitoBean EventContentService eventContentService;
    @MockitoBean AuthService authService;

    @Autowired GoogleWalletProperties props;
    @Autowired GoogleWalletApiClient api;
    @Autowired GoogleWalletJwtSigner signer;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("googleWalletRestClient")
    RestClient googleWalletRestClient;

    @Test
    void everyGoogleWalletBeanIsConstructedByTheContainer() {
        assertThat(props).isNotNull();
        assertThat(api).isNotNull();
        assertThat(signer).isNotNull();
        assertThat(googleWalletRestClient)
                .as("named, because this project has several RestClient beans")
                .isNotNull();
    }

    /**
     * Off, and honest about why. {@code GOOGLE_WALLET_ENABLED} defaults false so
     * that setting an issuer id — the thing you must do to get through Google's
     * first onboarding stage — cannot by itself light a buyer CTA that only works
     * for accounts on the issuer's tester list.
     */
    @Test
    void theWalletIsClosedUnderTheTestProfileAndSaysSo() {
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.fullyConfigured()).isFalse();
        assertThat(api.isUsable()).isFalse();
        assertThat(signer.isUsable()).isFalse();

        assertThat(props.gateReason()).isPresent();
        assertThat(props.gateReason().orElseThrow())
                .contains("GOOGLE_WALLET_ISSUER_ID")
                .contains("GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64")
                .contains("GOOGLE_WALLET_ENABLED");
    }

    /**
     * A closed gate is not a fault, so it must not look like one:
     * {@code unusableReason} is for a credential that was configured and would
     * not load, and an unconfigured wallet has no reason to report.
     */
    @Test
    void anUnconfiguredWalletReportsNoCredentialFault() {
        assertThat(signer.unusableReason()).isEmpty();
    }
}
