package com.imin.iminapi.moderation;

import com.imin.iminapi.model.ModerationHold;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two predicates every enforcement point asks: one hold row in each scope, measure and state,
 * checked against the same org and event. The rows roll back with the test.
 */
@IminIntegrationTest
@Transactional
class ModerationHoldRepositoryTest {

    @Autowired ModerationHoldRepository holds;

    private final UUID org = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();

    @ParameterizedTest(name = "{0} hold, {1}, lifted {2} → sales suspended {3}")
    @CsvSource({
            "org,        sales,   false, true",
            "this-event, sales,   false, true",
            "other-event,sales,   false, false",
            "other-org,  sales,   false, false",
            "org,        sales,   true,  false",
            "this-event, both,    false, true",
            "org,        payouts, false, false"})
    void sales_suspended_matches_active_sales_holds_in_scope(String scope, String measure, boolean lifted,
                                                             boolean suspended) {
        hold(scope, measure, lifted);

        assertThat(holds.salesSuspended(org, event)).isEqualTo(suspended);
    }

    @ParameterizedTest(name = "{0} hold, {1}, lifted {2} → payouts held {3}")
    @CsvSource({
            "org,        payouts, false, true",
            "this-event, payouts, false, true",
            "other-event,payouts, false, false",
            "other-org,  payouts, false, false",
            "this-event, payouts, true,  false",
            "org,        both,    false, true",
            "org,        sales,   false, false"})
    void payouts_held_ignores_sales_only_and_lifted_holds(String scope, String measure, boolean lifted,
                                                          boolean held) {
        hold(scope, measure, lifted);

        assertThat(holds.payoutsHeld(org, event)).isEqualTo(held);
    }

    private void hold(String scope, String measure, boolean lifted) {
        ModerationHold h = new ModerationHold();
        h.setId(UUID.randomUUID());
        h.setOrgId("other-org".equals(scope) ? UUID.randomUUID() : org);
        h.setEventId(switch (scope) {
            case "this-event" -> event;
            case "other-event" -> UUID.randomUUID();
            default -> null;
        });
        h.setSuspendSales(!"payouts".equals(measure));
        h.setHoldPayouts(!"sales".equals(measure));
        h.setGround("safety");
        h.setFacts("fixture");
        h.setPlacedBy(UUID.randomUUID());
        h.setPlacedAt(Instant.parse("2026-10-01T10:00:00Z"));
        holds.saveAndFlush(h);
        if (lifted) {
            assertThat(holds.lift(h.getId(), UUID.randomUUID(), null, null, Instant.parse("2026-10-02T10:00:00Z")))
                    .isEqualTo(1);
        }
    }
}
