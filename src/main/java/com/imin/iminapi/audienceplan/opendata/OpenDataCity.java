package com.imin.iminapi.audienceplan.opendata;

/**
 * A city the open-data loaders know how to query.
 *
 * @param cityKey    {@code EventNormalization.cityKey} of {@code name}
 * @param inseeCode  INSEE commune code (COG), e.g. 57463 for Metz
 * @param department département name as IGSS spells it, to tell same-named communes apart
 */
public record OpenDataCity(String cityKey, String name, String country, String inseeCode, String department) {}
