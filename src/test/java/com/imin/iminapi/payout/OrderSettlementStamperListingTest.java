package com.imin.iminapi.payout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A listing the database refuses must not throw out of the payout tick; it is logged against the org. */
class OrderSettlementStamperListingTest {

    @Test
    void a_failed_listing_never_throws_and_reads_nothing_from_stripe() {
        StripeClient stripe = mock(StripeClient.class);
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findUnstampedPaidByOrgId(any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("simulated database failure"));
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        OrderSettlementStamper stamper = new OrderSettlementStamper(stripe, new StripeProperties(), orders);

        Logger logger = (Logger) LoggerFactory.getLogger(OrderSettlementStamper.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatCode(() -> stamper.stampOrg(org)).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(logs);
        }

        verify(stripe, never()).paymentIntents();
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains(org.getId().toString()))
                .hasSize(1);
    }
}
