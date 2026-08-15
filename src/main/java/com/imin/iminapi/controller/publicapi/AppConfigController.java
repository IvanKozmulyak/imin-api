package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.app.AppReleaseProperties;
import com.imin.iminapi.app.AppVersions;
import com.imin.iminapi.dto.publicapi.AppConfigResponse;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.event.PublicEventService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /api/v1/public/app-config} — the mobile app's launch call.
 *
 * <h2>Why this endpoint exists before the app does</h2>
 *
 * <p>A shipped binary cannot be force-updated. If v1.0.0 goes to a store with
 * no version check, <b>those installs can never be gated off</b> — and an
 * over-the-air JS update cannot fix a native module or a breaking contract
 * change. Every other compatibility problem is expensive later; this one is
 * impossible later, so the endpoint ships first and the app is built against it.
 *
 * <p>Unauthenticated: it is covered by the blanket
 * {@code GET /api/v1/public/**} permitAll in {@code SecurityConfig}, and it
 * discloses nothing a store listing does not.
 *
 * <h2>The header the app should also send</h2>
 *
 * <p>Every native request should carry {@code X-Imin-App-Version}. It is a
 * <b>separate</b> header from {@code X-Imin-Client} on purpose:
 * {@code BuyerClientKind.isNative} is an exact {@code equalsIgnoreCase("native")}
 * match, so folding a version into that value ({@code native/1.2.3}) would
 * silently stop being recognised and every native mutation would start 403ing
 * on the CSRF guard.
 */
@RestController
@RequestMapping("/api/v1/public")
public class AppConfigController {

    private static final String IOS = "ios";
    private static final String ANDROID = "android";

    private final AppReleaseProperties releases;
    private final PublicEventService publicEventService;

    public AppConfigController(AppReleaseProperties releases, PublicEventService publicEventService) {
        this.releases = releases;
        this.publicEventService = publicEventService;
    }

    @GetMapping("/app-config")
    public ResponseEntity<AppConfigResponse> appConfig(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String version) {

        AppReleaseProperties.Platform release = resolve(platform);

        String min = blankToNull(release == null ? null : release.getMinSupportedVersion());
        String latest = blankToNull(release == null ? null : release.getLatestVersion());
        String storeUrl = blankToNull(release == null ? null : release.getStoreUrl());

        return ResponseEntity.ok()
                // Same shared-cache policy as the /events/cities and /events/genres
                // siblings whose payloads this folds in. Shared caches key on the
                // whole URL, so the per-version answers do not collide.
                .header(HttpHeaders.CACHE_CONTROL, "public, s-maxage=60, stale-while-revalidate=30")
                .body(new AppConfigResponse(
                        status(version, min, latest),
                        min,
                        latest,
                        storeUrl,
                        publicEventService.listCities(),
                        publicEventService.listGenres(),
                        Map.of()));
    }

    /**
     * The gate's verdict. Every uncertain case answers {@code ok}:
     * {@link AppVersions#isAtLeast} treats an absent, blank or unparseable
     * version as satisfying any requirement, so a header we failed to read can
     * never brick an install.
     */
    private static String status(String version, String min, String latest) {
        if (min != null && !AppVersions.isAtLeast(version, min)) {
            return AppConfigResponse.STATUS_UPDATE_REQUIRED;
        }
        if (latest != null && !AppVersions.isAtLeast(version, latest)) {
            return AppConfigResponse.STATUS_UPDATE_RECOMMENDED;
        }
        return AppConfigResponse.STATUS_OK;
    }

    /**
     * Null for a caller that named no platform — the web and any smoke check,
     * which still get the reference data and an {@code ok} verdict. A platform
     * we do not recognise is a 400 rather than a silent {@code ok}: a typo'd
     * platform would otherwise disable the gate for that whole client build,
     * and we would never hear about it.
     */
    private AppReleaseProperties.Platform resolve(String platform) {
        if (platform == null || platform.isBlank()) return null;
        String p = platform.trim().toLowerCase();
        if (IOS.equals(p)) return releases.getIos();
        if (ANDROID.equals(p)) return releases.getAndroid();
        throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "platform must be one of: ios, android");
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
