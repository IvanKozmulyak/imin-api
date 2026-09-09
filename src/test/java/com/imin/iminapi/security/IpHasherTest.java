package com.imin.iminapi.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code order_recovery_attempts.ip_hash} was a bare {@code SHA-256(ip)}. IPv4 is
 * 2³² values, so a complete rainbow table is hours of work — an unsalted digest
 * is a reversible record of which address asked about which order, stored as if
 * it were pseudonymised.
 */
class IpHasherTest {

    @Test
    void the_hash_is_keyed_so_a_rainbow_table_does_not_reverse_it() {
        String plain = new IpHasher("").hash("203.0.113.7");
        String keyed = new IpHasher("a-real-secret").hash("203.0.113.7");

        assertThat(keyed).isNotEqualTo(plain).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void two_keys_disagree_and_one_key_is_stable() {
        assertThat(new IpHasher("k1").hash("203.0.113.7"))
                .isNotEqualTo(new IpHasher("k2").hash("203.0.113.7"))
                .isEqualTo(new IpHasher("k1").hash("203.0.113.7"));
    }

    @Test
    void distinct_addresses_hash_distinctly_and_null_is_survivable() {
        IpHasher sut = new IpHasher("k");
        assertThat(sut.hash("203.0.113.7")).isNotEqualTo(sut.hash("203.0.113.8"));
        assertThat(sut.hash(null)).hasSize(64);
    }

    /**
     * A blank secret degrades to the previous unkeyed digest rather than failing
     * startup: the only thing these hashes gate is a rate-limit count, and
     * refusing to boot over it would trade a privacy improvement for an outage.
     */
    @Test
    void a_blank_secret_degrades_rather_than_throwing() {
        assertThat(new IpHasher("").hash("203.0.113.7")).hasSize(64);
        assertThat(new IpHasher(null).hash("203.0.113.7")).hasSize(64);
    }

    /**
     * infra-8: the javadoc above documents the knob as {@code IMIN_IP_HASH_SECRET},
     * but Boot's {@code SystemEnvironmentPropertySource} only ever resolves
     * {@code imin.security.ip-hash-secret} from {@code IMIN_SECURITY_IP_HASH_SECRET}
     * — {@code IMIN_IP_HASH_SECRET} can satisfy {@code imin.ip-hash-secret}, which
     * nothing reads. An operator following the doc to rotate away from the ticket
     * signing secret got no error and kept HMAC-ing with the old key. The fix is a
     * yaml line, so this resolves the real placeholder against the real file.
     */
    @Test
    void the_documented_env_var_rotates_the_key() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("IMIN_IP_HASH_SECRET", "rotated-key",
                        "IMIN_TICKET_SIGNING_SECRET", "the-old-ticket-key")));
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                "application", new FileSystemResource("src/main/resources/application.yaml"))) {
            env.getPropertySources().addLast(source);
        }

        // The exact placeholder IpHasher's constructor is annotated with.
        String resolved = env.resolvePlaceholders(
                "${imin.security.ip-hash-secret:${imin.ticket.signing-secret:}}");

        assertThat(resolved)
                .as("IMIN_IP_HASH_SECRET is documented as the rotation knob; it has to bind")
                .isEqualTo("rotated-key");
    }
}
