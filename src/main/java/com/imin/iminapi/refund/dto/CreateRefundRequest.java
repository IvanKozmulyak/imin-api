package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.RefundReason;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateRefundRequest(
    @NotEmpty List<UUID> ticketIds,
    @NotNull RefundReason reason
) {}
