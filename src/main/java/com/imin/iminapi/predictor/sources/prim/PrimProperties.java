package com.imin.iminapi.predictor.sources.prim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IDFM PRIM traffic messages for 6.1/6.2 ({@code PREDICTOR_PRIM_*}, {@code IDFM_PRIM_API_KEY}); defaults match
 * {@code application.yaml}. A blank key keeps the source off without failing startup.
 */
@ConfigurationProperties(prefix = "imin.predictor.prim")
public class PrimProperties {

    public static final String DEFAULT_BASE_URL = "https://prim.iledefrance-mobilites.fr";
    public static final int DEFAULT_MAX_AGE_HOURS = 6;
    public static final int DEFAULT_STOP_RADIUS_M = 800;

    private boolean enabled = true;

    private String apiKey = "";

    private String baseUrl = DEFAULT_BASE_URL;

    private int maxAgeHours = DEFAULT_MAX_AGE_HOURS;

    private int stopRadiusM = DEFAULT_STOP_RADIUS_M;

    public boolean isEnabled() { return enabled; }
    /** A kill switch that defaults on: only an explicit false turns it off. */
    public void setEnabled(Boolean v) { this.enabled = !Boolean.FALSE.equals(v); }
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
    public int getMaxAgeHours() { return maxAgeHours; }
    /** Null or below 1 binds the default. */
    public void setMaxAgeHours(Integer v) { this.maxAgeHours = v == null || v < 1 ? DEFAULT_MAX_AGE_HOURS : v; }
    /** Metres from the venue within which a closed stop counts; read per call. */
    public int getStopRadiusM() { return stopRadiusM; }
    /** Null binds the default; an out-of-range value is kept so {@link PrimConfig} can refuse it. */
    public void setStopRadiusM(Integer v) { this.stopRadiusM = v == null ? DEFAULT_STOP_RADIUS_M : v; }

    /** On only with the flag and a key; read per call, so a runtime flip shows on the next request. */
    public boolean isOn() {
        return enabled && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "PrimProperties{enabled=" + enabled + ", apiKey=" + (apiKey.isEmpty() ? "<blank>" : "<set>")
                + ", baseUrl=" + baseUrl + ", maxAgeHours=" + maxAgeHours + ", stopRadiusM=" + stopRadiusM + "}";
    }
}
