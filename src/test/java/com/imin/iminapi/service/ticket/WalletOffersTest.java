package com.imin.iminapi.service.ticket;

import com.imin.iminapi.dto.publicapi.TicketWallets;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rule behind the two-wallet contract, exhaustively.
 *
 * <p>{@code available} is a conjunction of exactly two independent facts — can
 * this deployment sign for that wallet, and is this ticket one we will mint for
 * — so the whole rule is a pure function of three inputs and can be driven over
 * every combination rather than sampled. {@code WalletContractTest} then proves
 * the same rule survives serialisation in the real container; this proves it is
 * the right rule.
 */
class WalletOffersTest {

    static final String BASE = "https://api.imin.test";

    /** Live by construction, including the legacy {@code 'pre'} the column may still carry. */
    static final String[] LIVE_STATES = {Ticket.STATE_ISSUED, Ticket.STATE_REDEEMED, "pre"};
    /** The door's "do not admit" set, and exactly the set refused here. */
    static final String[] DEAD_STATES = {Ticket.STATE_REFUNDED, Ticket.STATE_REVOKED};

    /**
     * Every wallet-config × ticket-state pair, with the two invariants that make
     * the contract usable asserted on each one: {@code available} is the
     * conjunction, and {@code url} is non-null <b>if and only if</b>
     * {@code available}.
     *
     * <p>The biconditional is the load-bearing half. Emitting the URL
     * unconditionally would be defensible — it is a routing constant — but it
     * gives a client two independently checkable encodings of one fact, and the
     * failure that produces is a button built from the URL alone, pointing at an
     * endpoint that answers 409 or 503. With this rule a client that forgets the
     * boolean still renders nothing.
     */
    @Test
    void availabilityIsTheConjunction_andTheUrlTracksItExactly() {
        for (boolean appleOn : new boolean[]{true, false}) {
            for (boolean googleOn : new boolean[]{true, false}) {
                WalletOffers offers = offers(appleOn, googleOn, BASE);

                for (String state : LIVE_STATES) {
                    assertPair(offers.forTicket(ticket("TKT_L", state)), "TKT_L",
                            appleOn, googleOn, state);
                }
                for (String state : DEAD_STATES) {
                    assertPair(offers.forTicket(ticket("TKT_D", state)), "TKT_D",
                            false, false, state);
                }
            }
        }
    }

    private static void assertPair(TicketWallets wallets, String token,
                                   boolean appleExpected, boolean googleExpected, String state) {
        assertOne(wallets.apple(), "apple", token, "/apple-wallet.pkpass", appleExpected, state);
        assertOne(wallets.google(), "google", token, "/google-wallet", googleExpected, state);
    }

    private static void assertOne(TicketWallets.WalletPass pass, String vendor, String token,
                                  String suffix, boolean expected, String state) {
        assertThat(pass.available())
                .as("%s availability for a '%s' ticket", vendor, state)
                .isEqualTo(expected);
        if (expected) {
            assertThat(pass.url())
                    .as("%s url for a '%s' ticket", vendor, state)
                    .isEqualTo(BASE + "/api/v1/public/tickets/" + token + suffix);
        } else {
            assertThat(pass.url())
                    .as("%s url must be null when unavailable ('%s' ticket)", vendor, state)
                    .isNull();
        }
    }

    /**
     * Neither wallet can move the other. Not a theoretical property: Apple is
     * gated on a Pass Type ID certificate and Google on an issuer account and a
     * separate publishing review, so "exactly one of them works" is the state
     * this product ships in for months, in both directions.
     */
    @Test
    void oneWalletBeingOffNeverChangesTheOther() {
        TicketWallets appleOnly = offers(true, false, BASE).forTicket(ticket("TKT_X", Ticket.STATE_ISSUED));
        assertThat(appleOnly.apple().available()).isTrue();
        assertThat(appleOnly.google().available()).isFalse();

        TicketWallets googleOnly = offers(false, true, BASE).forTicket(ticket("TKT_X", Ticket.STATE_ISSUED));
        assertThat(googleOnly.apple().available()).isFalse();
        assertThat(googleOnly.google().available()).isTrue();
    }

    /**
     * "AVAILABLE" MEANS THE SERVER CAN ACTUALLY SIGN, NOT THAT SOMEONE SET FIVE
     * ENV VARS.
     *
     * <p>This config is complete: every {@code APPLE_WALLET_*} value is present
     * and well-formed base64. It also cannot sign — the bytes are not a PKCS#12.
     * Until Task 8 the flag was {@code props.fullyConfigured()} alone, so this
     * deployment advertised a wallet CTA and answered the tap with a 500 out of
     * jpasskit, on an unauthenticated endpoint, for a fault
     * {@code WalletCredentialCheck} had already found and logged at boot.
     * Nothing consumed the boot check's answer; now availability does.
     *
     * <p>Real service, real properties, real credential check — the whole point
     * is that the difference between "set" and "loadable" is invisible to a
     * string comparison.
     */
    @Test
    void aCompleteButUnloadableCertificateIsNotAvailable() {
        AppleWalletProperties broken = new AppleWalletProperties();
        broken.setPassTypeId("pass.com.imin.ticket");
        broken.setTeamId("ABCDE12345");
        broken.setCertP12Base64("Zm9v");      // valid base64, not a p12
        broken.setCertPassword("pw");
        broken.setWwdrPemBase64("YmFy");
        assertThat(broken.fullyConfigured())
                .as("the config really is complete — otherwise this proves nothing")
                .isTrue();
        assertThat(WalletCredentialCheck.validate(broken))
                .as("and it really cannot sign")
                .isPresent();

        WalletOffers offers = new WalletOffers(appleService(broken), googleOff(), props(BASE));

        assertThat(offers.forTicket(ticket("TKT_X", Ticket.STATE_ISSUED)).apple().available())
                .isFalse();
    }

    /** The same service over a certificate that does load says yes. */
    @Test
    void aCertificateThatLoadsIsAvailable() {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate("");
        AppleWalletProperties good = new AppleWalletProperties();
        good.setPassTypeId("pass.test.imin");
        good.setTeamId("TESTTEAMID");
        good.setCertP12Base64(bundle.p12Base64());
        good.setCertPassword(bundle.password());
        good.setWwdrPemBase64(bundle.wwdrPemBase64());

        WalletOffers offers = new WalletOffers(appleService(good), googleOff(), props(BASE));

        assertThat(offers.forTicket(ticket("TKT_X", Ticket.STATE_ISSUED)).apple().available())
                .isTrue();
    }

    /**
     * A trailing slash on {@code IMIN_API_PUBLIC_BASE_URL} is a paste, not a
     * configuration error, and it must not produce {@code //api/v1/...} — which
     * some proxies normalise and some 404.
     */
    @Test
    void aTrailingSlashOnTheBaseUrlDoesNotDoubleUp() {
        TicketWallets w = offers(true, true, "https://api.imin.test/")
                .forTicket(ticket("TKT_X", Ticket.STATE_ISSUED));

        assertThat(w.apple().url()).isEqualTo(BASE + "/api/v1/public/tickets/TKT_X/apple-wallet.pkpass");
        assertThat(w.google().url()).isEqualTo(BASE + "/api/v1/public/tickets/TKT_X/google-wallet");
    }

    /** Defensive, and the only sane answer: no ticket, no offer, no NPE. */
    @Test
    void aNullTicketOffersNothing() {
        TicketWallets w = offers(true, true, BASE).forTicket(null);

        assertThat(w.apple()).isEqualTo(TicketWallets.WalletPass.UNAVAILABLE);
        assertThat(w.google()).isEqualTo(TicketWallets.WalletPass.UNAVAILABLE);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static WalletOffers offers(boolean appleOn, boolean googleOn, String base) {
        AppleWalletPassService apple = mock(AppleWalletPassService.class);
        when(apple.isConfigured()).thenReturn(appleOn);
        GoogleWalletPassService google = mock(GoogleWalletPassService.class);
        when(google.isConfigured()).thenReturn(googleOn);
        return new WalletOffers(apple, google, props(base));
    }

    /**
     * The real service, so {@code isConfigured()} runs the real credential
     * check. Everything it would need to actually build a pass is mocked and
     * never touched — {@code WalletOffers} asks one boolean and signs nothing.
     */
    private static AppleWalletPassService appleService(AppleWalletProperties props) {
        TicketProperties tp = new TicketProperties();
        tp.setSigningSecret("wallet-offers-test-signing-secret");
        return new AppleWalletPassService(props,
                mock(TicketRepository.class), mock(OrderRepository.class),
                mock(EventRepository.class), mock(OrganizationRepository.class),
                new QrPayloadSigner(tp), new EmailProperties());
    }

    private static GoogleWalletPassService googleOff() {
        GoogleWalletPassService google = mock(GoogleWalletPassService.class);
        when(google.isConfigured()).thenReturn(false);
        return google;
    }

    private static TicketProperties props(String base) {
        TicketProperties p = new TicketProperties();
        p.setSigningSecret("wallet-offers-test-signing-secret");
        p.setApiPublicBaseUrl(base);
        return p;
    }

    private static Ticket ticket(String token, String state) {
        Ticket t = new Ticket();
        t.setToken(token);
        t.setState(state);
        return t;
    }
}
