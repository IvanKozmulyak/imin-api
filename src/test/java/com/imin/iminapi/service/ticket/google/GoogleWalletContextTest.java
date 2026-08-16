package com.imin.iminapi.service.ticket.google;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.service.EventContentService;
import com.imin.iminapi.service.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;
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
    @Autowired GoogleWalletProvisioner provisioner;
    @Autowired GoogleWalletPassService passService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("googleWalletRestClient")
    RestClient googleWalletRestClient;

    @Test
    void everyGoogleWalletBeanIsConstructedByTheContainer() {
        assertThat(props).isNotNull();
        assertThat(api).isNotNull();
        assertThat(signer).isNotNull();
        assertThat(provisioner)
                .as("the provisioner pulls in EmailProperties for the buyer ticket link")
                .isNotNull();
        assertThat(googleWalletRestClient)
                .as("named, because this project has several RestClient beans")
                .isNotNull();
        assertThat(passService)
                .as("the save-link service pulls in three repositories and the QR signer")
                .isNotNull();
    }

    /**
     * <b>No transaction may wrap the Google calls, and this is where that is
     * checked against the real container.</b>
     *
     * <p>{@link GoogleWalletProvisioner} refuses at runtime if a transaction is
     * active, but a runtime guard only fires on a request that reaches it — and
     * on a machine with the wallet off, nothing ever does. This is the static
     * half: the bean the container hands out is the plain object, not a
     * transactional proxy, and neither the class nor {@code saveUrl} carries the
     * annotation.
     *
     * <p>The edit it catches is a reasonable-looking one. {@code saveUrl} only
     * reads rows, so {@code @Transactional(readOnly = true)} is exactly what a
     * reviewer would suggest — and it would hold a pooled JDBC connection across
     * up to three 5s-timeout calls to Google on an unauthenticated endpoint,
     * turning a slow upstream into pool exhaustion for the whole API. It would
     * pass every other test in the repository.
     */
    @Test
    void theSaveLinkServiceIsNotWrappedInATransaction() {
        assertThat(AopUtils.isAopProxy(passService))
                .as("a transactional proxy here would put Google's latency inside a JDBC connection")
                .isFalse();
        assertThat(AnnotationUtils.findAnnotation(GoogleWalletPassService.class, Transactional.class))
                .isNull();
        assertThat(AnnotationUtils.findAnnotation(
                ReflectionUtils.findMethod(GoogleWalletPassService.class, "saveUrl", String.class),
                Transactional.class))
                .isNull();
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
