package com.imin.iminapi.checkoutreminder.model;

/** Why the checkout's address was kept: for a reminder, or to stop one (V208). */
public enum CheckoutReminderBasis {
    /** The buyer ticked the organizer-named marketing box. */
    EXPLICIT,
    /** On a paid checkout the buyer read the organizer-named notice and did not tick its refuse box. */
    NOTICE,
    /** The buyer ticked the refuse box: the row keeps no sentence and only stops a reminder for the night. */
    REFUSED
}
