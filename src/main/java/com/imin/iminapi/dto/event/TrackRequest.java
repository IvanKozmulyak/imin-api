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
        @JsonProperty("referrer_host") String referrerHost) {

    /** Back-compat 2-arg constructor (no UTM tags) — kept for existing call sites. */
    public TrackRequest(String stage, String anonId) {
        this(stage, anonId, null, null, null, null, null);
    }
}
