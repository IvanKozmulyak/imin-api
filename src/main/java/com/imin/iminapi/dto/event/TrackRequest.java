package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of the public funnel beacon. {@code stage} must be one of the
 * client-instrumented stages (PAGE_VIEW | CHECKOUT_START); anything else is a
 * no-op. {@code anonId} is the buyer's per-session id from sessionStorage.
 *
 * <p>The {@code utm*} fields and {@code referrerHost} are OPTIONAL UTM
 * attribution tags (V43). Old beacon callers that omit them deserialize to
 * {@code null} and persist as before — fully backward compatible.
 *
 * <p>Wire contract: the buyer site (imin-public {@code lib/funnel-tracking.ts})
 * sends {@code stage} and {@code anonId} as-is (camelCase), but the attribution
 * tags as snake_case JSON keys ({@code utm_source}, {@code utm_medium},
 * {@code utm_campaign}, {@code utm_content}, {@code referrer_host}) — there is
 * no global Jackson snake_case strategy in this app, so the five attribution
 * fields are bound explicitly via {@link JsonProperty}.
 */
public record TrackRequest(
        String stage,
        String anonId,
        @JsonProperty("utm_source")    String utmSource,
        @JsonProperty("utm_medium")    String utmMedium,
        @JsonProperty("utm_campaign")  String utmCampaign,
        @JsonProperty("utm_content")   String utmContent,
        @JsonProperty("referrer_host") String referrerHost,
        /**
         * Which client sent the beacon: {@code "web"}, {@code "ios"} or
         * {@code "android"}. <b>Null reads as web</b>, so every caller that
         * predates the mobile app keeps its current meaning and no backfill is
         * needed.
         *
         * <p>Deliberately its own field and <b>not</b> folded into
         * {@code utm_source}: the shipped auto-tag feature already writes that
         * field, and colliding with it would corrupt live campaign attribution.
         *
         * <p>It has to exist before the app ships. {@code /analytics/attribution}
         * and the funnel are live and organizer-facing, so unlabelled app
         * traffic would merge indistinguishably into web "direct" and quietly
         * make already-shipped conversion numbers wrong.
         *
         * <p>Single word, so no {@link JsonProperty} is needed — unlike the five
         * snake_case attribution tags above.
         */
        String client) {

    /** Back-compat 2-arg constructor (no UTM tags) — kept for existing call sites. */
    public TrackRequest(String stage, String anonId) {
        this(stage, anonId, null, null, null, null, null, null);
    }
}
