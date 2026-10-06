package com.imin.iminapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt cost from {@code imin.auth.bcrypt-strength}: 12 by default, lowered only under the {@code test} profile.
 * Stored hashes carry their own cost, so changing this never invalidates an existing password.
 */
@Configuration
public class PasswordEncoderConfig {

    static final int DEFAULT_STRENGTH = 12;
    static final int MIN_PROD_STRENGTH = 10;

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder(
            @Value("${imin.auth.bcrypt-strength:" + DEFAULT_STRENGTH + "}") int strength,
            Environment env) {
        if (strength < MIN_PROD_STRENGTH && !env.matchesProfiles("test")) {
            throw new IllegalStateException("imin.auth.bcrypt-strength=" + strength
                    + " is below " + MIN_PROD_STRENGTH + " and only allowed under the 'test' profile");
        }
        return new BCryptPasswordEncoder(strength);
    }
}
