package com.imin.iminapi.predictor.rules;

import java.time.LocalDate;
import java.util.Map;

/** A dated to-do for the organizer; {@code params} are the finding's facts, for the action template. */
public record ActionItem(String key, LocalDate dueDate, String questionId, Map<String, Object> params) {

    public ActionItem {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
