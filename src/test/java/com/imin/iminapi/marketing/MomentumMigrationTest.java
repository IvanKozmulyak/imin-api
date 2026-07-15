package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies V58 created momentum_suggestions with the expected columns.
 * Flyway runs on the H2 (PG-compat) test context at startup, so a passing
 * boot already means the DDL is valid; this asserts the shape explicitly.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class MomentumMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void momentumSuggestionsTableExistsWithExpectedColumns() {
        List<String> columns = jdbc.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns " +
                "WHERE LOWER(table_name) = 'momentum_suggestions'",
                String.class);
        assertThat(columns).contains(
                "id", "org_id", "event_id", "trigger_type", "status",
                "metrics_snapshot", "draft_payload", "campaign_id",
                "suggested_at", "acted_at", "created_at");
    }
}
