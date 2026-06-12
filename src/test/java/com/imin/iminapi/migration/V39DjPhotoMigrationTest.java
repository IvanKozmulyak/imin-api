package com.imin.iminapi.migration;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;

/** V39 adds nullable dj_photo_url to events and poster_generations. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class V39DjPhotoMigrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void eventsHasDjPhotoUrlColumn() {
        assertThatCode(() -> jdbc.queryForList("SELECT dj_photo_url FROM events WHERE 1=0"))
                .doesNotThrowAnyException();
    }

    @Test
    void posterGenerationsHasDjPhotoUrlColumn() {
        assertThatCode(() -> jdbc.queryForList("SELECT dj_photo_url FROM poster_generations WHERE 1=0"))
                .doesNotThrowAnyException();
    }
}
