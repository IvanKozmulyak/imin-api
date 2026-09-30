package com.imin.iminapi.predictor.rules;

/** An input the check assumed, from the organizer or the genre profile, with the profile's provenance. */
public record Assumption(Field field, Object value, Source source, boolean estimate, String sourcedUrl) {

    public enum Field { AUDIENCE_AGE, COMMUNITIES, PRICE_MINOR, START_HOUR, BUYING_LEAD_DAYS }

    public enum Source { PROFILE, ORGANIZER }
}
