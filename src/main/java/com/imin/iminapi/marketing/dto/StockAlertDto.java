package com.imin.iminapi.marketing.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One stock alert: status ready|drafted|dismissed|expired|withdrawn, its draft campaign's fields (null without one) and
 * a reason from StockAlertReason; revenue is null until the campaign has sent.
 */
public record StockAlertDto(UUID id, String kind, UUID tierId, String tierName, String status, UUID campaignId,
                            String campaignStatus, Instant scheduledAt, Instant sentAt, Integer recipientCount,
                            Long attributedRevenueMinor, StockFactsDto facts, StockFactsDto factsAtSend,
                            int reachAtPrepare, String withdrawnReason, String expiredReason, Instant createdAt,
                            Instant actedAt) {}
