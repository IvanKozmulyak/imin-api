package com.imin.iminapi.marketing.unsubscribe;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UnsubscribeTokenServiceTest {

    final UnsubscribeTokenService svc = new UnsubscribeTokenService("test-secret-32-bytes-of-entropy-xx");

    @Test
    void roundTripsAllFields() {
        UUID org = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        String token = svc.sign(org, member, campaign, "email");

        Optional<UnsubscribeTokenService.Claims> claims = svc.verify(token);
        assertThat(claims).isPresent();
        assertThat(claims.get().orgId()).isEqualTo(org);
        assertThat(claims.get().membershipId()).isEqualTo(member);
        assertThat(claims.get().channel()).isEqualTo("email");
    }

    @Test
    void rejectsTamperedToken() {
        String token = svc.sign(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "email");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThat(svc.verify(tampered)).isEmpty();
    }

    @Test
    void rejectsGarbage() {
        assertThat(svc.verify("not-a-token")).isEmpty();
        assertThat(svc.verify("")).isEmpty();
    }
}
