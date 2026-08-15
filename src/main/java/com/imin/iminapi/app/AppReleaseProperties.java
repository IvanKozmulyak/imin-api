package com.imin.iminapi.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code imin.app.*} — the mobile force-upgrade gate, per store.
 *
 * <p>Bound from environment so a version bump is a Railway env edit rather than
 * a deploy: the day a shipped binary has to be gated off is not the day to be
 * waiting on a build.
 *
 * <p><b>The defaults are the safe state.</b> {@code 0.0.0} means "gate off" —
 * no build in the field is below it, so an unconfigured deploy blocks nobody.
 * That direction matters: this endpoint can only ever take installs away, and
 * a client we have locked out cannot be sent a fix.
 */
@ConfigurationProperties(prefix = "imin.app")
public class AppReleaseProperties {

    private Platform ios = new Platform();
    private Platform android = new Platform();

    public Platform getIos() { return ios; }
    public void setIos(Platform ios) { this.ios = ios; }
    public Platform getAndroid() { return android; }
    public void setAndroid(Platform android) { this.android = android; }

    /** One store's release state. */
    public static class Platform {

        /** Below this, the app must refuse to run. {@code 0.0.0} = gate off. */
        private String minSupportedVersion = "0.0.0";

        /** Below this, the app may nudge. {@code 0.0.0} = no nudge. */
        private String latestVersion = "0.0.0";

        /** Where to send the buyer to update. Blank ⇒ omitted from the response. */
        private String storeUrl = "";

        public String getMinSupportedVersion() { return minSupportedVersion; }
        public void setMinSupportedVersion(String v) { this.minSupportedVersion = v; }
        public String getLatestVersion() { return latestVersion; }
        public void setLatestVersion(String v) { this.latestVersion = v; }
        public String getStoreUrl() { return storeUrl; }
        public void setStoreUrl(String v) { this.storeUrl = v; }
    }
}
