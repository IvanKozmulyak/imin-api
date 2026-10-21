package com.imin.iminapi.checkoutreminder.model;

/** PENDING waits for its due time, SENDING is claimed by the sender, SENT and NOT_SENT are final. */
public enum CheckoutReminderStatus {
    PENDING, SENDING, SENT, NOT_SENT
}
