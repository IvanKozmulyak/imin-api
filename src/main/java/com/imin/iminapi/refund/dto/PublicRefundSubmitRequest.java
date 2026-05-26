package com.imin.iminapi.refund.dto;

import com.imin.iminapi.refund.RefundRequestReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PublicRefundSubmitRequest(
    @NotNull RefundRequestReason reason,
    @NotNull @Size(min = 1, max = 2000) String explanation,
    @Size(max = 32) String phone
) {}
