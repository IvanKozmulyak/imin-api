package com.imin.iminapi.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for outbound push ({@code imin.push.*}). */
@ConfigurationProperties(prefix = "imin.push")
public class PushProperties {

    /**
     * Master switch ({@code IMIN_PUSH_ENABLED}). Default false: the feature is
     * dark until the app ships and real tokens exist, and a dark sender is
     * better than one firing at an empty registry.
     */
    private boolean enabled = false;

    private String baseUrl = "https://exp.host/--/api/v2/push/send";

    /**
     * Optional Expo access token ({@code EXPO_ACCESS_TOKEN}). Expo accepts
     * unauthenticated sends; with enhanced security enabled on the Expo project
     * this becomes required, and setting it is the safer default.
     */
    private String accessToken = "";

    /**
     * Connect and read timeout for the Expo POST, in seconds.
     *
     * <p>Load-bearing, not decorative — see {@code PushConfig.httpClientSettings}
     * for what a hung connection to {@code exp.host} would stall.
     */
    private int timeoutSeconds = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
