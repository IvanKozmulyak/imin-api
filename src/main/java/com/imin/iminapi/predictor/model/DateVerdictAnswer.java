package com.imin.iminapi.predictor.model;

import java.util.Locale;

/** The organizer's answer to "did the date verdict match?"; wire values match {@code ck_date_verdict_feedback_answer}. */
public enum DateVerdictAnswer {
    YES, PARTLY, NO;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Throws {@link IllegalArgumentException} for anything outside yes | partly | no. */
    public static DateVerdictAnswer fromWire(String s) {
        if (s == null) throw new IllegalArgumentException("answer is null");
        for (DateVerdictAnswer a : values()) {
            if (a.wire().equals(s)) return a;
        }
        throw new IllegalArgumentException("Unknown answer: " + s);
    }
}
