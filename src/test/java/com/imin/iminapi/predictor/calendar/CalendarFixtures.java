package com.imin.iminapi.predictor.calendar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Real answers recorded from the sources on 2026-09-30, trimmed to the rows the tests need. */
final class CalendarFixtures {

    private CalendarFixtures() {}

    static String text(String name) {
        try (InputStream in = CalendarFixtures.class.getClassLoader().getResourceAsStream("predictor/calendar/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static CalendarSyncProperties props(int yearsAhead) {
        CalendarSyncProperties p = new CalendarSyncProperties();
        p.setYearsAhead(yearsAhead);
        return p;
    }
}
