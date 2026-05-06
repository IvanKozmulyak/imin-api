package com.imin.iminapi.dto.publicapi;

public record PublicVenueDto(
        String name,
        String street,
        String city,
        String postalCode,
        String country
) {}
