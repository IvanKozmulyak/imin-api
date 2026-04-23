package com.imin.iminapi.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(12));

    @Test
    void hashed_password_is_not_plaintext() {
        String hash = hasher.hash("hunter22pwd");
        assertThat(hash).isNotEqualTo("hunter22pwd").startsWith("$2");
    }

    @Test
    void verify_returns_true_for_correct_password() {
        String hash = hasher.hash("hunter22pwd");
        assertThat(hasher.verify("hunter22pwd", hash)).isTrue();
        assertThat(hasher.verify("nope", hash)).isFalse();
    }
}
