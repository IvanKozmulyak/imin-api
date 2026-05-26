package com.imin.iminapi.refund;

public enum RefundRequestStatus {
    PENDING, APPROVED, REJECTED, WITHDRAWN;

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == WITHDRAWN;
    }
}
