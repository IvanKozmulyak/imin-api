package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mkt-core-6 (P2): the per-member frequency floor runs one
 * {@code countRecentSendsForMembership} per candidate, and V53 indexed only
 * {@code (campaign_id, status)} / {@code (provider_message_id)} — the UNIQUE constraint's
 * leading column is campaign_id, so nothing served a membership_id-only predicate.
 * Materialising a 50k-member segment was 50k sequential scans of a table that grows with
 * every send. V116 adds the index those counts need.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignRecipientMembershipIndexTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void v116AddsTheMembershipIndexTheFrequencyFloorNeeds() {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM INFORMATION_SCHEMA.INDEXES
                 WHERE UPPER(TABLE_NAME) = 'CAMPAIGN_RECIPIENTS'
                   AND UPPER(INDEX_NAME) = 'IX_CAMPAIGN_RECIPIENTS_MEMBERSHIP'
                """, Integer.class);
        assertThat(n).isNotNull().isGreaterThan(0);
    }
}
