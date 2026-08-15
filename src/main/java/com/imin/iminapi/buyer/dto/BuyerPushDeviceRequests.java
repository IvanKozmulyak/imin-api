package com.imin.iminapi.buyer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class BuyerPushDeviceRequests {

    private BuyerPushDeviceRequests() {}

    /**
     * @param expoToken an {@code ExponentPushToken[…]} value from
     *                  {@code expo-notifications}. Length-capped to the column
     *                  so an over-long value is a 400, not a truncated insert.
     */
    public record Register(@NotBlank @Size(max = 255) String expoToken,
                           @NotBlank @Pattern(regexp = "ios|android") String platform,
                           @Size(max = 8) String locale,
                           @Size(max = 32) String appVersion) {}

    /** Sign-out. The token is a body field because it contains {@code [} and {@code ]} — see the controller. */
    public record Revoke(@NotBlank @Size(max = 255) String expoToken) {}
}
