package com.imin.iminapi.predictor.sources.openweather;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenWeather free-plan access for the re-forecast weather signal. {@code apiKey} is bound to
 * {@code ${OPENWEATHER_API_KEY:}}, {@code baseUrl} to {@code ${PREDICTOR_OPENWEATHER_BASE_URL}};
 * defaults here match {@code application.yaml}. The flag is {@code PREDICTOR_WEATHER_ENABLED}.
 */
@ConfigurationProperties(prefix = "imin.predictor.openweather")
public class OpenWeatherProperties {

    public static final String DEFAULT_BASE_URL = "https://api.openweathermap.org";

    private String apiKey = "";

    private String baseUrl = DEFAULT_BASE_URL;

    public String getApiKey() { return apiKey; }
    /** Blank or null binds "". */
    public void setApiKey(String v) { this.apiKey = v == null ? "" : v.strip(); }
    public String getBaseUrl() { return baseUrl; }
    /** Blank binds the default; trailing slashes are dropped so paths join cleanly. */
    public void setBaseUrl(String v) {
        String s = v == null ? "" : v.strip();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        this.baseUrl = s.isEmpty() ? DEFAULT_BASE_URL : s;
    }

    @Override
    public String toString() {
        return "OpenWeatherProperties{baseUrl=" + baseUrl + ", apiKey=" + (apiKey.isEmpty() ? "<blank>" : "<set>") + "}";
    }
}
