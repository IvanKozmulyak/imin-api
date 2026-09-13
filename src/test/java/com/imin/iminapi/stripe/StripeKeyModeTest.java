package com.imin.iminapi.stripe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code StripeProperties.isLiveKey()} decides whether every order, payout run and dispute is
 * stamped as real money. A mis-read key is silent in both directions: read as test, all live
 * revenue drops out of the payout net; read as live, test money is paid from a real balance.
 * One case per branch, plus the boot banner an operator uses to confirm the deploy.
 */
class StripeKeyModeTest {

    @Test
    void aStandardLiveKeyIsLive() {
        assertThat(props("sk_live_51Abc").isLiveKey()).isTrue();
    }

    @Test
    void aRestrictedLiveKeyIsLive() {
        assertThat(props("rk_live_51Abc").isLiveKey()).isTrue();
    }

    @Test
    void aTestKeyIsNotLive() {
        assertThat(props("sk_test_51Abc").isLiveKey()).isFalse();
    }

    @Test
    void aLiveKeyPastedWithSurroundingWhitespaceIsStillLive() {
        // A secret pasted into a Railway variable routinely carries a trailing newline.
        assertThat(props("  sk_live_51Abc\n").isLiveKey()).isTrue();
    }

    @Test
    void aBlankKeyIsNotLive() {
        assertThat(props("   ").isLiveKey()).isFalse();
    }

    @Test
    void anUnsetKeyIsNotLive() {
        assertThat(new StripeProperties().isLiveKey()).isFalse();
    }

    @Test
    void aLiveKeyIsNotMatchedMidString() {
        // Only a prefix means live — "live" appearing later in a test key must not flip it.
        assertThat(props("sk_test_sk_live_51Abc").isLiveKey()).isFalse();
    }

    @Test
    void aPastedKeyIsTrimmedBeforeTheClientIsBuilt() {
        // The raw value is legal in a Railway variable and illegal in an HTTP header: untrimmed,
        // the mode reads live while every Stripe call fails on the header.
        StripeProperties p = props("  sk_live_51Abc\n");
        new StripeConfig().stripeClient(p);

        assertThat(p.trimmedSecretKey()).isEqualTo("sk_live_51Abc");
    }

    @Test
    void aWhitespaceOnlyKeyStillFailsFastAtStartup() {
        assertThatThrownBy(() -> new StripeConfig().stripeClient(props("  \n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRIPE_SECRET_KEY is not set");
    }

    @Test
    void startupLogsTheDetectedLiveMode() {
        assertThat(bootLog("sk_live_51Abc"))
                .as("the operator reads this line to confirm the cutover deploy")
                .contains("STRIPE MODE: live");
    }

    @Test
    void startupLogsTheDetectedTestMode() {
        assertThat(bootLog("sk_test_51Abc")).contains("STRIPE MODE: test");
    }

    @Test
    void theBootBannerIsNeverLoggedAtErrorLevel() {
        // application-prod.yaml ships ERROR to Sentry — an every-boot banner there files an
        // issue on every deploy.
        assertThat(bootLogAt(Level.ERROR, "sk_live_51Abc")).doesNotContain("STRIPE MODE");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static StripeProperties props(String secretKey) {
        StripeProperties p = new StripeProperties();
        p.setSecretKey(secretKey);
        return p;
    }

    /**
     * Builds the client the way Spring does and returns the WARN lines StripeConfig logged.
     * WARN, not ERROR: prod routes ERROR to Sentry, so an every-boot banner there is noise.
     */
    private static String bootLog(String secretKey) {
        return bootLogAt(Level.WARN, secretKey);
    }

    private static String bootLogAt(Level level, String secretKey) {
        Logger logger = (Logger) LoggerFactory.getLogger(StripeConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new StripeConfig().stripeClient(props(secretKey));
            return appender.list.stream()
                    .filter(e -> e.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
