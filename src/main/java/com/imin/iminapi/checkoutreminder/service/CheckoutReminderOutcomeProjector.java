package com.imin.iminapi.checkoutreminder.service;

import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Bought after it": a live-mode order for the same night from the same address, placed within the
 * outcome window after the reminder was sent, is stamped on the reminder. Runs after issuance
 * committed, in its own transaction, and never throws into it.
 */
@Component
public class CheckoutReminderOutcomeProjector {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReminderOutcomeProjector.class);

    private final OrderRepository orders;
    private final CheckoutReminderRepository reminders;
    private final CheckoutReminderProperties props;
    private final TransactionTemplate requiresNew;

    public CheckoutReminderOutcomeProjector(OrderRepository orders, CheckoutReminderRepository reminders,
                                            CheckoutReminderProperties props,
                                            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.reminders = reminders;
        this.props = props;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketsIssued(TicketsIssuedEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> project(event));
        } catch (Exception e) {
            log.error("[checkout-reminder] outcome failed for order {}: {}: {}", event.orderId(),
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    private void project(TicketsIssuedEvent event) {
        Order order = orders.findById(event.orderId()).orElse(null);
        if (order == null || order.getEmailNormalized() == null || order.getCreatedAt() == null) return;
        // The dashboard's sales leave test-mode orders out, so the row never claims one either.
        if (order.isTestMode()) return;
        int stamped = reminders.stampRecovered(order.getEventId(), order.getEmailNormalized(), order.getId(),
                order.getCreatedAt(), order.getCreatedAt().minus(props.outcomeWindow()));
        if (stamped > 0) log.info("[checkout-reminder] order {} bought after a reminder", order.getId());
    }
}
