package com.imin.iminapi.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
    private final BCryptPasswordEncoder encoder;

    public PasswordHasher(BCryptPasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public String hash(String raw) { return encoder.encode(raw); }
    public boolean verify(String raw, String hash) { return encoder.matches(raw, hash); }
}
