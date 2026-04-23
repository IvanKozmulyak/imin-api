package com.imin.iminapi.dto;

import com.imin.iminapi.model.Organization;

import java.util.UUID;

public record OrganizationDto(
        UUID id,
        String name,
        String contactEmail,
        String country,
        String timezone,
        String plan,
        int planMonthlyEuros,
        String currency
) {
    public static OrganizationDto from(Organization o) {
        return new OrganizationDto(o.getId(), o.getName(), o.getContactEmail(),
                o.getCountry(), o.getTimezone(), o.getPlan(),
                o.getPlanMonthlyEuros(), o.getCurrency());
    }
}
