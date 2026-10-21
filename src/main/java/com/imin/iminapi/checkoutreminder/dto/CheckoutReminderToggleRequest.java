package com.imin.iminapi.checkoutreminder.dto;

import jakarta.validation.constraints.NotNull;

/** Turns the organizer's reminder on or off. */
public record CheckoutReminderToggleRequest(@NotNull Boolean enabled) {}
