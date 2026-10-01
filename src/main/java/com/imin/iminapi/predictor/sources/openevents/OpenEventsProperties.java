package com.imin.iminapi.predictor.sources.openevents;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Open-data event listings for the date check. {@code openagendaEnabled} is bound to
 * {@code ${PREDICTOR_OPENAGENDA_ENABLED:false}}, {@code openagendaApiKey} to {@code ${OPENAGENDA_API_KEY:}},
 * {@code quefaireaparisEnabled} to {@code ${PREDICTOR_QUEFAIREAPARIS_ENABLED:false}}; defaults here match
 * {@code application.yaml}. OpenAgenda on with a blank or non-public ({@code oa_pk_}) key fails startup.
 */
@ConfigurationProperties(prefix = "imin.predictor.open-events")
public class OpenEventsProperties implements InitializingBean {

    static final String PUBLIC_KEY_PREFIX = "oa_pk_";

    private boolean openagendaEnabled = false;

    private String openagendaApiKey = "";

    private boolean quefaireaparisEnabled = false;

    public boolean isOpenagendaEnabled() { return openagendaEnabled; }
    /** Blank binds null; only an explicit true turns the source on. */
    public void setOpenagendaEnabled(Boolean v) { this.openagendaEnabled = Boolean.TRUE.equals(v); }
    public String getOpenagendaApiKey() { return openagendaApiKey; }
    /** Blank or null binds "". */
    public void setOpenagendaApiKey(String v) { this.openagendaApiKey = v == null ? "" : v.strip(); }
    public boolean isQuefaireaparisEnabled() { return quefaireaparisEnabled; }
    /** Blank binds null; only an explicit true turns the source on. */
    public void setQuefaireaparisEnabled(Boolean v) { this.quefaireaparisEnabled = Boolean.TRUE.equals(v); }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /** A secret oa_sk_ key can write and reads unpublished events; imin needs neither. Never names the value. */
    public void validate() {
        if (!openagendaEnabled) return;
        if (openagendaApiKey.isEmpty()) {
            throw new IllegalStateException("PREDICTOR_OPENAGENDA_ENABLED is true but OPENAGENDA_API_KEY is blank");
        }
        if (!openagendaApiKey.startsWith(PUBLIC_KEY_PREFIX)) {
            throw new IllegalStateException("PREDICTOR_OPENAGENDA_ENABLED is true but OPENAGENDA_API_KEY is not a public "
                    + PUBLIC_KEY_PREFIX + " key");
        }
    }

    @Override
    public String toString() {
        return "OpenEventsProperties{openagendaEnabled=" + openagendaEnabled + ", openagendaApiKey="
                + (openagendaApiKey.isEmpty() ? "<blank>" : "<set>") + ", quefaireaparisEnabled=" + quefaireaparisEnabled + "}";
    }
}
