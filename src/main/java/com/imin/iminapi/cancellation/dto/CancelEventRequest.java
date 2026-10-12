package com.imin.iminapi.cancellation.dto;

/**
 * Body of {@code POST /api/v1/events/{eventId}/cancel}. {@code expectedOrders} is the preview's
 * {@code orderCount}; a different count now answers 409 {@code EVENT_CANCEL_PLAN_CHANGED}.
 */
public record CancelEventRequest(boolean confirm, Integer expectedOrders) {}
