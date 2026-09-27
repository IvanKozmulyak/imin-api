package com.imin.iminapi.audienceplan.dto;

/** Same body for every accepted sign-up, stored or not, so the answer never reveals an address's state. */
public record DoorOptInResponse(boolean received) {

    public static DoorOptInResponse ok() {
        return new DoorOptInResponse(true);
    }
}
