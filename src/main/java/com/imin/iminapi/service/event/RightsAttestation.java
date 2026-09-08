package com.imin.iminapi.service.event;

/**
 * The wording an uploader attests to when they upload a third party's likeness.
 *
 * <p>A timestamp alone proves nothing — "attested on 2026-09-08" is only
 * meaningful alongside what was attested to, and that text will change. The
 * version constant is stored next to the timestamp so a later dispute can be
 * answered with the exact wording that was on screen.
 *
 * <p><b>Never edit a version's text.</b> Add a new constant and bump
 * {@link #CURRENT_VERSION}; the dashboard copy must move in the same release.
 */
public final class RightsAttestation {

    private RightsAttestation() {}

    /** Bump alongside any change to the attestation wording shown in the dashboard. */
    public static final String CURRENT_VERSION = "v1-2026-09-08";

    /**
     * The v1 wording, for the record. The canonical copy the organizer reads
     * lives in the dashboard; this is here so the server side of the contract is
     * reviewable next to the column it fills.
     */
    public static final String V1_TEXT =
            "I confirm I own or have permission to use this image, including the "
                    + "consent of any person depicted, and that it may be processed by "
                    + "automated image-generation services to produce event artwork.";
}
