package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class EventValidator {

    public void validateForPublish(Event e) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (isBlank(e.getName())) errors.put("name", "required");
        if (isBlank(e.getSlug())) errors.put("slug", "required");
        if (e.getStartsAt() == null) errors.put("startsAt", "required");
        if (e.getEndsAt() == null) errors.put("endsAt", "required");
        if (e.getStartsAt() != null && e.getEndsAt() != null && e.getEndsAt().isBefore(e.getStartsAt())) {
            errors.put("endsAt", "must be after startsAt");
        }
        if (isBlank(e.getVenueStreet())) errors.put("venue.street", "required");
        if (isBlank(e.getVenueCity())) errors.put("venue.city", "required");
        if (isBlank(e.getVenuePostalCode())) errors.put("venue.postalCode", "required");
        if (isBlank(e.getDescription())) errors.put("description", "required");
        if (e.getDescription() != null && e.getDescription().length() > 2000) {
            errors.put("description", "≤ 2000 chars");
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.PUBLISH_VALIDATION_FAILED,
                    "Event missing required fields", errors);
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
