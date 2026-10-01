package com.imin.iminapi.predictor.calendar;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * football-data.org fixtures for bank question 3.2. {@code enabled} is bound to
 * {@code ${PREDICTOR_FOOTBALL_ENABLED:false}}, {@code apiKey} to {@code ${FOOTBALL_DATA_API_KEY:}};
 * defaults here match {@code application.yaml}. The key alone never turns the source on.
 */
@ConfigurationProperties(prefix = "imin.predictor.football")
public class FootballDataProperties {

    private boolean enabled = false;

    private String apiKey = "";

    public boolean isEnabled() { return enabled; }
    /** Blank binds null; only an explicit true turns the source on. */
    public void setEnabled(Boolean v) { this.enabled = Boolean.TRUE.equals(v); }
    public String getApiKey() { return apiKey; }
    /** Blank or null binds "". */
    public void setApiKey(String v) { this.apiKey = v == null ? "" : v.strip(); }

    /** The flag and a key: the only state in which football-data is called. */
    public boolean isOn() { return enabled && !apiKey.isEmpty(); }

    @Override
    public String toString() {
        return "FootballDataProperties{enabled=" + enabled + ", apiKey=" + (apiKey.isEmpty() ? "<blank>" : "<set>") + "}";
    }
}
