package com.imin.iminapi.audienceplan.dto;

/** Same body for every accepted answer, consent recorded or not, so it never reveals an address's state. */
public record SurveyResponseResult(boolean received) {

    public static SurveyResponseResult ok() {
        return new SurveyResponseResult(true);
    }
}
