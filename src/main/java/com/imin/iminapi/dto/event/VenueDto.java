package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VenueDto(String name, String street, String city, String postalCode, String country) {}
