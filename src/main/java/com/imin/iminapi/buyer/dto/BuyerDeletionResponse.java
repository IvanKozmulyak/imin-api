package com.imin.iminapi.buyer.dto;

import java.time.Instant;

/**
 * What {@code POST /buyer/account/delete} answers (§3.3, §7.1).
 *
 * <p>Only the deadline. The response deliberately does <b>not</b> restate what
 * is deleted or what is retained: {@code imin-public} owns that copy in four
 * locales, and §7.3's retention figure is still blocked on counsel. A number
 * invented here would be a legal claim the backend has no standing to make.
 */
public record BuyerDeletionResponse(Instant deleteAt) {}
