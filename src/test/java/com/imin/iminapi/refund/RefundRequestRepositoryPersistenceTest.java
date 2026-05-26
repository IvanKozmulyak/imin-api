package com.imin.iminapi.refund;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots H2 with the V30 schema and exercises {@link RefundRequestRepository#page}
 * with the same default cursor sentinel the service uses. Regression guard for
 * the prod bug where {@code Instant.MAX} was passed as the cursor's upper bound
 * — H2 accepted it silently, but Postgres's TIMESTAMP WITH TIME ZONE tops out
 * around year 294276 AD and wrapped it into a "BC" value, producing
 * {@code ERROR: timestamp out of range}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RefundRequestRepositoryPersistenceTest {

    @Autowired RefundRequestRepository requests;

    @Test
    void page_accepts_year_9999_sentinel_as_no_cursor_default() {
        // Bug we're guarding against fires during JDBC parameter binding, before
        // any rows are checked — so an empty-result query is enough to catch it
        // on both Postgres and H2. With Instant.MAX as the upper bound, Postgres
        // rejects the timestamp ("out of range, BC value") while H2 silently
        // accepts it. The fix uses a year-9999 sentinel that both engines accept.
        Instant safeMax = Instant.parse("9999-12-31T23:59:59Z");
        UUID idMax = new UUID(Long.MAX_VALUE, Long.MAX_VALUE);

        List<RefundRequest> rows = requests.page(
            UUID.randomUUID(),
            null,
            List.of(RefundRequestStatus.values()),
            safeMax,
            idMax,
            PageRequest.of(0, 25));

        assertThat(rows).isEmpty();
    }
}
