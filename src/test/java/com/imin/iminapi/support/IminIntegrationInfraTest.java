package com.imin.iminapi.support;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import com.imin.iminapi.storage.MediaStorage;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shared context runs on Postgres and hands every test clean fakes. */
@IminIntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IminIntegrationInfraTest {

    private static final Instant PINNED = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired EmailProperties emailProperties;
    @Autowired PropertyFlips flips;
    @Autowired Clock clock;
    @Autowired MutableClock mutableClock;
    @Autowired EmailService email;
    @Autowired RecordingEmailService recordedEmail;
    @Autowired RateLimiter rateLimiter;
    @Autowired RecordingRateLimiter recordingLimiter;
    @Autowired MediaStorage media;
    @Autowired InMemoryMediaStorage inMemoryMedia;

    @Test
    @Order(0)
    void runsOnPostgres_notTheYamlH2() {
        String version = jdbc.queryForObject("select version()", String.class);
        System.out.println("IminIntegrationInfraTest database: " + version);
        assertThat(version).startsWith("PostgreSQL");
    }

    @Test
    @Order(1)
    void a_changesEveryResettableFake() {
        flips.set(emailProperties, "remindersEnabled", true);
        mutableClock.setInstant(PINNED);
        email.send("infra-" + System.nanoTime() + "@example.test", "s", "<p>h</p>", "t");
        recordingLimiter.limit("infra", 0);
        media.put("infra/blob", new byte[] {1}, "application/octet-stream");

        assertThat(emailProperties.isRemindersEnabled()).isTrue();
        assertThat(clock.instant()).isEqualTo(PINNED);
        assertThat(recordedEmail.sent()).hasSize(1);
        assertThatThrownBy(() -> rateLimiter.consume("infra", "k")).isNotNull();
        assertThat(inMemoryMedia.blobs()).containsKey("infra/blob");
    }

    @Test
    @Order(2)
    void b_findsEveryFakeBackAtItsDefault() {
        assertThat(emailProperties.isRemindersEnabled()).isFalse();
        assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(recordedEmail.sent()).isEmpty();
        assertThat(recordingLimiter.calls()).isEmpty();
        assertThatCode(() -> rateLimiter.consume("infra", "k")).doesNotThrowAnyException();
        assertThat(inMemoryMedia.blobs()).isEmpty();
    }
}
