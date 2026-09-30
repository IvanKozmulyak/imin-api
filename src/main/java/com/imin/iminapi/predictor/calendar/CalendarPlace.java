package com.imin.iminapi.predictor.calendar;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a date is checked: country, Alsace-Moselle holiday region ({@code FR-57}…) and French school
 * zone ({@code FR-ZA|ZB|ZC}), each null when it does not apply.
 */
public record CalendarPlace(String country, String holidayRegion, String schoolZone) {

    /** "" (the whole country) plus the place's region and zone, as stored in {@code reference_calendar.region}. */
    public List<String> regions() {
        List<String> out = new ArrayList<>();
        out.add("");
        if (holidayRegion != null) out.add(holidayRegion);
        if (schoolZone != null) out.add(schoolZone);
        return out;
    }
}
