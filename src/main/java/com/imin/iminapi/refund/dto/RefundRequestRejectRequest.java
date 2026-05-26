package com.imin.iminapi.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundRequestRejectRequest(
    @NotBlank @Size(max = 1000) String note
) {}
