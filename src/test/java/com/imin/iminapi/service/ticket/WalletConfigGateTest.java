package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What "configured" means, pinned.
 *
 * <p>The bug this exists to stop: a .p12 exported with an EMPTY password is
 * legal and common ({@code openssl pkcs12 -export -passout pass:}). The
 * original {@code fullyConfigured()} required {@code certPassword} to be
 * non-blank, so an operator could set four env vars correctly, hold a perfectly
 * good certificate, and get a permanent 503 with no log line distinguishing it
 * from "not set up yet".
 *
 * <p>Asserting {@code fullyConfigured() == true} for a blank password would
 * prove nothing on its own — it is the same boolean read back. The load-bearing
 * test is {@link #aPasswordlessP12ActuallySignsARealPass()}, which mints a
 * genuinely passwordless PKCS#12 and drives it all the way through
 * {@link AppleWalletPassService#generatePass(String)} to a signed archive.
 */
class WalletConfigGateTest {

    // ── the gate ────────────────────────────────────────────────────────────

    @Test
    void blankPasswordDoesNotDisqualifyAnOtherwiseCompleteConfig() {
        AppleWalletProperties p = configured();
        p.setCertPassword("");
        assertThat(p.fullyConfigured()).isTrue();
    }

    @Test
    void aMissingCertIsStillNotConfigured() {
        AppleWalletProperties p = configured();
        p.setCertP12Base64("");
        assertThat(p.fullyConfigured()).isFalse();
    }

    @Test
    void allBlankIsNotConfigured() {
        assertThat(new AppleWalletProperties().fullyConfigured()).isFalse();
    }

    /**
     * The kill switch. Present so a production incident can turn passes off
     * without deleting a certificate out of Railway's env and losing it.
     */
    @Test
    void disabledOverridesAFullConfig() {
        AppleWalletProperties p = configured();
        p.setEnabled(false);
        assertThat(p.fullyConfigured()).isFalse();
    }

    /**
     * Nothing in the test tree binds {@code imin.apple-wallet.*} —
     * {@code src/test/resources/application.yaml} REPLACES the main file and
     * carries no such block — so the default has to come from the Java field or
     * the switch is off everywhere including production's own default.
     */
    @Test
    void enabledDefaultsToTrueFromTheJavaFieldNotTheYaml() {
        assertThat(new AppleWalletProperties().isEnabled()).isTrue();
    }

    // ── the production consequence, driven end to end ────────────────────────

    /**
     * <b>Defect 1, proven at the level it actually bites.</b>
     *
     * <p>A real PKCS#12 with an empty export password, pushed through the real
     * signing path. Before the gate fix this did not merely return a different
     * boolean: {@code isConfigured()} was false, so {@code generatePass} threw
     * {@code IllegalStateException("Apple Wallet not configured")} and the
     * endpoint answered 503 — forever, for a certificate that works.
     */
    @Test
    void aPasswordlessP12ActuallySignsARealPass() throws Exception {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate("");
        assertThat(bundle.password())
                .as("the fixture must genuinely have no export password, "
                        + "otherwise this test proves nothing")
                .isEmpty();

        AppleWalletProperties props = new AppleWalletProperties();
        props.setPassTypeId("pass.test.imin");
        props.setTeamId("TESTTEAMID");
        props.setCertP12Base64(bundle.p12Base64());
        props.setCertPassword(bundle.password()); // "" — as an operator would leave it
        props.setWwdrPemBase64(bundle.wwdrPemBase64());

        AppleWalletPassService svc = serviceFor(props);

        assertThat(svc.isConfigured())
                .as("an empty export password is not a missing configuration")
                .isTrue();

        byte[] pkpass = svc.generatePass("TKT_X");
        assertThat(listZipEntries(pkpass))
                .as("the archive Apple would actually receive")
                .contains("pass.json", "manifest.json", "signature");
    }

    /**
     * The same certificate, with the password field left literally unset rather
     * than set to "". Guards the null path into jpasskit, which does
     * {@code keyStorePassword.toCharArray()} and NPEs on null.
     */
    @Test
    void anUnsetPasswordFieldIsTreatedAsEmptyRatherThanNull() throws Exception {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate("");
        AppleWalletProperties props = new AppleWalletProperties();
        props.setPassTypeId("pass.test.imin");
        props.setTeamId("TESTTEAMID");
        props.setCertP12Base64(bundle.p12Base64());
        props.setCertPassword(null);
        props.setWwdrPemBase64(bundle.wwdrPemBase64());

        assertThat(props.certPasswordOrEmpty()).isEmpty();
        assertThat(WalletCredentialCheck.validate(props)).isEmpty();
        assertThat(listZipEntries(serviceFor(props).generatePass("TKT_X"))).contains("signature");
    }

    // ── credential validation ────────────────────────────────────────────────

    @Test
    void realKeyMaterialValidates() {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate();
        AppleWalletProperties p = new AppleWalletProperties();
        p.setPassTypeId("pass.test.imin");
        p.setTeamId("TESTTEAMID");
        p.setCertP12Base64(bundle.p12Base64());
        p.setCertPassword(bundle.password());
        p.setWwdrPemBase64(bundle.wwdrPemBase64());

        assertThat(WalletCredentialCheck.validate(p)).isEmpty();
    }

    /**
     * The case that currently 500s on the first buyer's tap instead of failing
     * at deploy time: syntactically present, cryptographically useless.
     */
    @Test
    void garbageBase64FailsWithAReason() {
        AppleWalletProperties p = configured();
        p.setCertP12Base64("bm90LWEtcDEy"); // "not-a-p12"
        assertThat(WalletCredentialCheck.validate(p))
                .isPresent()
                .get().asString().containsIgnoringCase("p12");
    }

    @Test
    void nonBase64FailsWithABase64SpecificReason() {
        AppleWalletProperties p = configured();
        p.setCertP12Base64("!!! definitely not base64 !!!");
        assertThat(WalletCredentialCheck.validate(p))
                .isPresent()
                .get().asString().contains("APPLE_WALLET_CERT_P12_BASE64");
    }

    /**
     * The reason the check is a certificate load and not a string check: this
     * config is complete, well-formed, correctly base64'd — and cannot sign.
     */
    @Test
    void wrongPasswordFailsWithAReason() {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate();
        AppleWalletProperties p = new AppleWalletProperties();
        p.setPassTypeId("pass.test.imin");
        p.setTeamId("TESTTEAMID");
        p.setCertP12Base64(bundle.p12Base64());
        p.setCertPassword("definitely-not-the-password");
        p.setWwdrPemBase64(bundle.wwdrPemBase64());

        assertThat(p.fullyConfigured())
                .as("the string gate cannot see this — which is the whole point")
                .isTrue();
        assertThat(WalletCredentialCheck.validate(p))
                .isPresent()
                .get().asString().containsIgnoringCase("p12");
    }

    /**
     * A WWDR intermediate that is not a certificate at all. Distinct from the
     * wrong-password case because it is the other half of the pair an operator
     * has to paste in, and the failure has to name something greppable.
     */
    @Test
    void aBrokenWwdrIntermediateFailsWithAReason() {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate();
        AppleWalletProperties p = new AppleWalletProperties();
        p.setPassTypeId("pass.test.imin");
        p.setTeamId("TESTTEAMID");
        p.setCertP12Base64(bundle.p12Base64());
        p.setCertPassword(bundle.password());
        p.setWwdrPemBase64(java.util.Base64.getEncoder()
                .encodeToString("not a certificate".getBytes(StandardCharsets.US_ASCII)));

        assertThat(WalletCredentialCheck.validate(p)).isPresent();
    }

    // ── the check actually runs at startup ───────────────────────────────────

    /**
     * <b>Defect 2.</b> A wrong password has to surface when the app boots, not
     * when the first buyer taps the button hours later. Asserted on the log the
     * service emits from its constructor, because that log line IS the
     * deliverable — there is no return value and, deliberately, no throw.
     */
    @Test
    void aWrongPasswordIsReportedAtConstructionTimeNotAtRequestTime() {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate("the-real-password");
        AppleWalletProperties props = new AppleWalletProperties();
        props.setPassTypeId("pass.test.imin");
        props.setTeamId("TESTTEAMID");
        props.setCertP12Base64(bundle.p12Base64());
        props.setCertPassword("not-the-real-password");
        props.setWwdrPemBase64(bundle.wwdrPemBase64());

        List<ILoggingEvent> logged = captureWhile(() -> serviceFor(props));

        assertThat(logged)
                .as("a certificate that cannot sign must announce itself at boot")
                .anySatisfy(ev -> {
                    assertThat(ev.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(ev.getFormattedMessage())
                            .contains("UNUSABLE")
                            .containsIgnoringCase("p12")
                            .contains("APPLE_WALLET_ENABLED=false");
                });
    }

    /**
     * And it must not take the API down. Ticket issuance, email, checkout and
     * the door all work fine without wallet passes; refusing to boot over a
     * decoration would be a far worse outage than the one being diagnosed.
     */
    @Test
    void badCredentialsDoNotPreventTheServiceFromBeingConstructed() {
        AppleWalletProperties props = configured(); // "Zm9v" is not a p12
        assertThat(WalletCredentialCheck.validate(props)).isPresent();
        assertThat(serviceFor(props)).isNotNull();
    }

    /** The healthy case says so too, so a silent log is itself a signal. */
    @Test
    void goodCredentialsLogAnInfoConfirmationAtStartup() {
        AppleWalletProperties props = new AppleWalletProperties();
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate();
        props.setPassTypeId("pass.test.imin");
        props.setTeamId("TESTTEAMID");
        props.setCertP12Base64(bundle.p12Base64());
        props.setCertPassword(bundle.password());
        props.setWwdrPemBase64(bundle.wwdrPemBase64());

        assertThat(captureWhile(() -> serviceFor(props)))
                .anySatisfy(ev -> assertThat(ev.getFormattedMessage())
                        .contains("configured and credentials load OK"));
    }

    @Test
    void unconfiguredValidatesTriviallyRatherThanReportingAFalseFault() {
        // Nothing set is not a fault — it is the default state of the system.
        assertThat(WalletCredentialCheck.validate(new AppleWalletProperties())).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AppleWalletProperties configured() {
        AppleWalletProperties p = new AppleWalletProperties();
        p.setPassTypeId("pass.com.imin.ticket");
        p.setTeamId("ABCDE12345");
        p.setCertP12Base64("Zm9v");
        p.setCertPassword("pw");
        p.setWwdrPemBase64("YmFy");
        return p;
    }

    private static AppleWalletPassService serviceFor(AppleWalletProperties props) {
        TicketRepository tickets = mock(TicketRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        EventRepository events = mock(EventRepository.class);

        Ticket t = new Ticket();
        t.setToken("TKT_X");
        t.setOrderId(UUID.randomUUID());
        t.setEventId(UUID.randomUUID());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setState(Ticket.STATE_ISSUED);
        when(tickets.findByToken("TKT_X")).thenReturn(Optional.of(t));

        Order o = new Order();
        o.setId(t.getOrderId());
        o.setEventId(t.getEventId());
        o.setOrgId(UUID.randomUUID());
        when(orders.findById(t.getOrderId())).thenReturn(Optional.of(o));

        Event e = new Event();
        e.setId(t.getEventId());
        e.setName("Saturn Night");
        e.setStartsAt(OffsetDateTime.parse("2026-06-15T22:00:00+02:00").toInstant());
        e.setTimezone("Europe/Paris");
        e.setVenueName("Le Petit Bain");
        e.setVenueCity("Paris");
        when(events.findById(t.getEventId())).thenReturn(Optional.of(e));

        TicketProperties tp = new TicketProperties();
        tp.setSigningSecret("walletconfiggate-test-signing-secret");
        return new AppleWalletPassService(props, tickets, orders, events, new QrPayloadSigner(tp));
    }

    /** Runs {@code work} with a listening appender attached to the service's logger. */
    private static List<ILoggingEvent> captureWhile(Runnable work) {
        Logger logger = (Logger) LoggerFactory.getLogger(AppleWalletPassService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            work.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Set<String> listZipEntries(byte[] zipBytes) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) names.add(e.getName());
        }
        return names;
    }
}
