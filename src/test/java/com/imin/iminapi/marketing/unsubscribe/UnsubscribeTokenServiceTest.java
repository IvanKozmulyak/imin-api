package com.imin.iminapi.marketing.unsubscribe;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opt-out token used to be signed with {@code imin.ticket.signing-secret} —
 * the key that tamper-proofs ticket QR payloads. One key, two unrelated trust
 * domains: rotating it to fix a ticket problem silently broke every unsubscribe
 * link already in someone's inbox, and anyone who could forge one could forge
 * the other.
 */
class UnsubscribeTokenServiceTest {

    private static final String OWN = "unsubscribe-key-0123456789abcdef";
    private static final String LEGACY = "ticket-signing-key-0123456789ab";

    @Test
    void a_token_signed_with_the_new_key_verifies() {
        UnsubscribeTokenService sut = new UnsubscribeTokenService(OWN, LEGACY);
        UUID org = UUID.randomUUID(), member = UUID.randomUUID(), campaign = UUID.randomUUID();

        Optional<UnsubscribeTokenService.Claims> claims =
                sut.verify(sut.sign(org, member, campaign, "email"));

        assertThat(claims).isPresent();
        assertThat(claims.get().orgId()).isEqualTo(org);
        assertThat(claims.get().membershipId()).isEqualTo(member);
        assertThat(claims.get().channel()).isEqualTo("email");
    }

    /**
     * Links already sitting in inboxes were signed with the ticket key and must
     * keep working — indefinitely. An unsubscribe link that expires is an
     * unsubscribe link that fails exactly when someone finally gets round to
     * using it, which is the opposite of the legal obligation it discharges.
     */
    @Test
    void a_token_signed_with_the_legacy_key_still_verifies_after_the_cutover() {
        UnsubscribeTokenService old = new UnsubscribeTokenService("", LEGACY);
        String issuedBeforeTheCutover = old.sign(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "email");

        UnsubscribeTokenService now = new UnsubscribeTokenService(OWN, LEGACY);

        assertThat(now.verify(issuedBeforeTheCutover)).isPresent();
    }

    /** A blank own-key means "not configured yet" and must not change behaviour. */
    @Test
    void a_blank_own_key_falls_back_to_the_legacy_key_for_signing_too() {
        UnsubscribeTokenService unconfigured = new UnsubscribeTokenService("", LEGACY);
        UnsubscribeTokenService legacyOnly = new UnsubscribeTokenService("  ", LEGACY);

        String token = unconfigured.sign(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "email");

        assertThat(legacyOnly.verify(token)).isPresent();
    }

    @Test
    void a_token_signed_with_neither_key_is_rejected() {
        UnsubscribeTokenService stranger = new UnsubscribeTokenService("some-other-key-entirely", "and-another");
        String forged = stranger.sign(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "email");

        assertThat(new UnsubscribeTokenService(OWN, LEGACY).verify(forged)).isEmpty();
        assertThat(new UnsubscribeTokenService(OWN, LEGACY).verify("garbage.token")).isEmpty();
        assertThat(new UnsubscribeTokenService(OWN, LEGACY).verify(null)).isEmpty();
    }

    // ---- notify-me opt-out -------------------------------------------------

    @Test
    void a_notify_token_round_trips_and_is_scoped_to_one_subscription() {
        UnsubscribeTokenService sut = new UnsubscribeTokenService(OWN, LEGACY);
        UUID subscription = UUID.randomUUID();

        assertThat(sut.verifyNotify(sut.signNotify(subscription))).contains(subscription);
        assertThat(sut.verifyNotify(sut.signNotify(UUID.randomUUID())))
                .isNotEqualTo(Optional.of(subscription));
    }

    /**
     * A notify token must not be accepted by the marketing verifier and vice
     * versa: they resolve to different things, and a token that crosses over
     * would let one opt-out act on another's subject.
     */
    @Test
    void notify_tokens_and_marketing_tokens_do_not_cross_over() {
        UnsubscribeTokenService sut = new UnsubscribeTokenService(OWN, LEGACY);

        String notify = sut.signNotify(UUID.randomUUID());
        String marketing = sut.sign(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "email");

        assertThat(sut.verify(notify)).isEmpty();
        assertThat(sut.verifyNotify(marketing)).isEmpty();
    }

    @Test
    void a_forged_notify_token_is_rejected() {
        UnsubscribeTokenService sut = new UnsubscribeTokenService(OWN, LEGACY);
        String forged = new UnsubscribeTokenService("other", "other").signNotify(UUID.randomUUID());

        assertThat(sut.verifyNotify(forged)).isEmpty();
        assertThat(sut.verifyNotify("nonsense")).isEmpty();
    }
}
