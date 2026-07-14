package com.imin.iminapi.marketing.model;

/** Momentum trigger taxonomy (spec §6.1). v1 = these four; conversion(v2) deferred. */
public enum MomentumTriggerType {
    LAUNCH_PUSH, SLUMP, URGENCY_72H, SOLD_OUT;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static MomentumTriggerType fromWire(String s) {
        return valueOf(s.toUpperCase());
    }
}
