package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.audienceplan.opendata.OpenDataCity;
import com.imin.iminapi.predictor.service.PublicHolidayCalendar;
import com.imin.iminapi.util.EventNormalization;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves a venue to the calendar regions it belongs to. */
@Component
public class CalendarRegions {

    private static final Pattern FR_POSTCODE = Pattern.compile("\\d{5}");

    /**
     * Metropolitan département → school zone, from the académie lists. Corsica (2A/2B) and overseas
     * have their own calendars and no zone.
     * ponytail: static; a zone reform needs a code change.
     */
    static final Map<String, String> SCHOOL_ZONE_BY_DEPT = new HashMap<>();

    private static void zone(String zone, String... depts) {
        for (String d : depts) {
            if (SCHOOL_ZONE_BY_DEPT.put(d, zone) != null) throw new IllegalStateException("département twice: " + d);
        }
    }

    static {
        // Zone A: Besançon, Bordeaux, Clermont-Ferrand, Dijon, Grenoble, Limoges, Lyon, Poitiers
        zone("FR-ZA", "25", "39", "70", "90", "24", "33", "40", "47", "64", "03", "15", "43", "63",
                "21", "58", "71", "89", "07", "26", "38", "73", "74", "19", "23", "87", "01", "42", "69",
                "16", "17", "79", "86");
        // Zone B: Aix-Marseille, Amiens, Lille, Nancy-Metz, Nantes, Nice, Normandie, Orléans-Tours, Reims, Rennes, Strasbourg
        zone("FR-ZB", "04", "05", "13", "84", "02", "60", "80", "59", "62", "54", "55", "57", "88",
                "44", "49", "53", "72", "85", "06", "83", "14", "27", "50", "61", "76", "18", "28", "36",
                "37", "41", "45", "08", "10", "51", "52", "22", "29", "35", "56", "67", "68");
        // Zone C: Créteil, Montpellier, Paris, Toulouse, Versailles
        zone("FR-ZC", "77", "93", "94", "11", "30", "34", "48", "66", "75", "09", "12", "31", "32",
                "46", "65", "81", "82", "78", "91", "92", "95");
    }

    private final OpenDataCities cities;

    public CalendarRegions(OpenDataCities cities) {
        this.cities = cities;
    }

    public CalendarPlace of(String country, String postalCode, String city) {
        String cc = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        if (!cc.equals("FR")) return new CalendarPlace(cc, null, null);
        String dept = departement(postalCode, city);
        return new CalendarPlace(cc, PublicHolidayCalendar.regionOf(cc, postalCode, city),
                dept == null ? null : SCHOOL_ZONE_BY_DEPT.get(dept));
    }

    /** Two-digit département from a 5-digit postcode, else from the known city's INSEE code; null when unknown. */
    private String departement(String postalCode, String city) {
        String pc = postalCode == null ? "" : postalCode.replaceAll("\\s+", "");
        if (FR_POSTCODE.matcher(pc).matches()) return pc.substring(0, 2);
        return cities.find(EventNormalization.cityKey(city))
                .filter(c -> "FR".equals(c.country()))
                .map(OpenDataCity::inseeCode)
                .filter(code -> code != null && code.length() >= 2)
                .map(code -> code.substring(0, 2))
                .orElse(null);
    }
}
