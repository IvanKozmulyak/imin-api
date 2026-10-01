package com.imin.iminapi.predictor.sources.wikimedia;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Wikimedia Pageviews sync for bank question 9.1. {@code enabled} is bound to
 * {@code ${PREDICTOR_WIKIMEDIA_ENABLED:false}}; {@code userAgent} to {@code ${PREDICTOR_WIKIMEDIA_USER_AGENT}},
 * which must carry a contact (Wikimedia User-Agent policy). Defaults here match {@code application.yaml}.
 */
@ConfigurationProperties(prefix = "imin.predictor.wikimedia")
public class WikimediaProperties {

    public static final String DEFAULT_USER_AGENT = "imin-api/1.0 (+https://imin.wtf; ops@imin.wtf) predictor-trends";

    private boolean enabled = false;

    private String userAgent = DEFAULT_USER_AGENT;

    public boolean isEnabled() { return enabled; }
    /** Blank binds null; only an explicit true turns the sync on. */
    public void setEnabled(Boolean v) { this.enabled = Boolean.TRUE.equals(v); }
    public String getUserAgent() { return userAgent; }
    /** Blank binds the default, so an empty variable never sends an anonymous User-Agent. */
    public void setUserAgent(String v) { this.userAgent = v == null || v.isBlank() ? DEFAULT_USER_AGENT : v.strip(); }
}
