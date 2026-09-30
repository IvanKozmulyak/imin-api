package com.imin.iminapi.predictor.calendar;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Reference calendar sync: on/off and how many years past the current one to fetch. */
@ConfigurationProperties(prefix = "imin.predictor.calendar")
public class CalendarSyncProperties {

    static final int DEFAULT_YEARS_AHEAD = 2;

    private Boolean syncEnabled = Boolean.TRUE;

    private Integer yearsAhead = DEFAULT_YEARS_AHEAD;

    public Boolean getSyncEnabled() { return syncEnabled; }
    /** Blank binds null; only an explicit false turns the sync off. */
    public void setSyncEnabled(Boolean v) { this.syncEnabled = !Boolean.FALSE.equals(v); }
    public Integer getYearsAhead() { return yearsAhead; }
    public void setYearsAhead(Integer v) { this.yearsAhead = v == null || v < 0 ? DEFAULT_YEARS_AHEAD : v; }
}
