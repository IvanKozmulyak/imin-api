package com.imin.iminapi.dto.publicapi;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/public/app-config} — the mobile app's first request.
 *
 * <p>Two jobs in one round trip. The version gate ({@link #status},
 * {@link #minSupportedVersion}, {@link #latestVersion}, {@link #storeUrl}) is
 * the one thing that <b>cannot</b> be added after v1.0.0 is in a store: an
 * install that never learned to ask can never be told to stop. The reference
 * data ({@link #cities}, {@link #genres}) rides along because a cold launch
 * needs both and three requests on a cell network is two too many.
 *
 * @param status {@code "ok"} | {@code "update_recommended"} (below the latest
 *               released build) | {@code "update_required"} (below the minimum
 *               supported build). <b>An unreadable or absent client version
 *               answers {@code "ok"}</b> — see {@code AppVersions}.
 * @param storeUrl null when unconfigured. Never a placeholder: a client that
 *                 gets null renders no update button rather than a dead one.
 * @param flags reserved for remote feature flags. Empty today, and deliberately
 *              present so the app's parser has always known the key.
 */
public record AppConfigResponse(
        String status,
        String minSupportedVersion,
        String latestVersion,
        String storeUrl,
        List<PublicCityItem> cities,
        List<String> genres,
        Map<String, Object> flags) {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_UPDATE_RECOMMENDED = "update_recommended";
    public static final String STATUS_UPDATE_REQUIRED = "update_required";
}
