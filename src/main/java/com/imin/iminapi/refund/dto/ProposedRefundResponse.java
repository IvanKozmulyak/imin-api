package com.imin.iminapi.refund.dto;

import java.util.List;
import java.util.UUID;

public record ProposedRefundResponse(
    long amountMinor,
    long appFeeRefundMinor,
    String currency,
    List<UUID> ticketIds
) {}
