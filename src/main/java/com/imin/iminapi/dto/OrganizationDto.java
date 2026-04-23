package com.imin.iminapi.dto;

import com.imin.iminapi.model.Organization;

import java.time.Instant;
import java.util.UUID;

public record OrganizationDto(
        UUID id,
        String name,
        String contactEmail,
        String country,
        String timezone,
        String plan,
        int planMonthlyEuros,
        String currency,
        Instant updatedAt
) {
    public static OrganizationDto from(Organization o) {
        return new OrganizationDto(o.getId(), o.getName(), o.getContactEmail(),
                o.getCountry(), o.getTimezone(), o.getPlan(),
                o.getPlanMonthlyEuros(), o.getCurrency(), o.getUpdatedAt());
    }
}
