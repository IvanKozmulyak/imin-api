package com.imin.iminapi.app;

/**
 * Dotted-numeric version comparison for the mobile force-upgrade gate.
 *
 * <p>Not {@code String.compareTo}: "1.10.0" sorts <i>below</i> "1.9.0"
 * lexically, which would lock out the newest build in the field.
 *
 * <p><b>Everything unparseable fails open.</b> A version we cannot read is a
 * version we must not block — the alternative is bricking an install over a
 * malformed header, and there is no way to push a fix to a blocked client.
 */
public final class AppVersions {

    private AppVersions() {}

    /**
     * True when {@code actual} is at least {@code required}. Blank or
     * unparseable input on either side answers true — see the class Javadoc: a
     * shipped binary we cannot identify must never be gated off.
     */
    public static boolean isAtLeast(String actual, String required) {
        if (actual == null || actual.isBlank() || required == null || required.isBlank()) return true;
        try {
            return compare(actual, required) >= 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Negative / zero / positive, comparing dotted numeric segments left to right. */
    public static int compare(String a, String b) {
        String[] left = core(a);
        String[] right = core(b);
        int n = Math.max(left.length, right.length);
        for (int i = 0; i < n; i++) {
            int l = segment(left, i);
            int r = segment(right, i);
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    /**
     * Drops any pre-release or build suffix: "1.2.3-beta.1" → "1.2.3".
     *
     * <p>This deliberately treats a pre-release as equal to its release rather
     * than below it (semver would order it below). The gate only ever asks
     * "is this build old enough to block", and blocking somebody's TestFlight
     * build of the version we just shipped is exactly the outcome to avoid.
     */
    private static String[] core(String v) {
        String s = v.trim();
        int cut = s.indexOf('-');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.indexOf('+');
        if (cut >= 0) s = s.substring(0, cut);
        return s.split("\\.");
    }

    private static int segment(String[] parts, int i) {
        if (i >= parts.length) return 0;      // "1.2" and "1.2.0" are the same version
        return Integer.parseInt(parts[i].trim());
    }
}
