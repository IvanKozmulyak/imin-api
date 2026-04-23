package com.imin.iminapi.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private final TokenService svc = new TokenService();

    @Test
    void issue_returns_url_safe_token_at_least_32_chars() {
        TokenService.IssuedToken t = svc.issue();
        assertThat(t.token()).matches("[A-Za-z0-9_-]{32,}");
        assertThat(t.tokenHash()).hasSize(64);
    }

    @Test
    void hash_is_deterministic() {
        String h1 = svc.hashOf("abc");
        String h2 = svc.hashOf("abc");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void hash_differs_for_different_tokens() {
        assertThat(svc.hashOf("aaa")).isNotEqualTo(svc.hashOf("bbb"));
    }
}
