package com.imin.iminapi.migration;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code orders.stripe_session_id} must be indexed.
 *
 * <p>{@code CheckoutStatusService.statusFor} runs
 * {@code orders.findByStripeSessionId} on <b>every</b> poll of the buyer's
 * success page — the page meta-refreshes until the webhook has issued — and V24
 * declared the column with only {@code idx_orders_event_id} and
 * {@code idx_orders_org_id} beside it. The {@code pi_} branch of the same method
 * is covered by {@code orders_stripe_payment_intent_id_unique} (V26); the
 * {@code cs_} branch had nothing, so it sequential-scanned {@code orders} on a
 * loop, for every buyer, at exactly the moment they have just paid.
 *
 * <p>The email_normalized control below is here so the information_schema query
 * cannot silently rot into one that matches nothing and passes.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class V104OrdersStripeSessionIdIndexTest {

    @Autowired JdbcTemplate jdbc;

    private List<String> indexesOn(String column) {
        return jdbc.queryForList(
                "SELECT LOWER(index_name) FROM information_schema.index_columns "
                        + "WHERE LOWER(table_name) = 'orders' AND LOWER(column_name) = ?",
                String.class, column);
    }

    @Test
    void stripe_session_id_is_indexed() {
        assertThat(indexesOn("stripe_session_id"))
                .as("the success-page poll scans orders without this index")
                .isNotEmpty();
    }

    /** Control: the query shape does find a known index, so an empty result means something. */
    @Test
    void the_information_schema_query_finds_the_v86_index() {
        assertThat(indexesOn("email_normalized")).isNotEmpty();
    }
}
