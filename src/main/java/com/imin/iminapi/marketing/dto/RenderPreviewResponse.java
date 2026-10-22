package com.imin.iminapi.marketing.dto;

import java.util.List;

/**
 * The campaign email as the send renders it, for the dashboard preview.
 *
 * @param fromName         the display name in the From header; null when neither an organizer name nor a configured name exists
 * @param fromAddress      the configured sending address; null when sending is not set up
 * @param subject          subject with merge tags filled
 * @param previewFirstName the first name the email greets; null renders the no-name greeting
 * @param notices          what the renderer leaves out: POSTER_MISSING, TICKETS_BUTTON_DROPPED, or EVENT_UNAVAILABLE alone
 * @param links            every link in the HTML, in order
 * @param locale           language of everything IMIN adds around the organizer's text
 */
public record RenderPreviewResponse(
        String html,
        String fromName,
        String fromAddress,
        String subject,
        String preheader,
        String previewFirstName,
        List<String> notices,
        List<RenderedLink> links,
        String locale
) {

    /** {@code kind} is {@code link} or {@code unsubscribe}; an unsubscribe link has a null href (each fan gets their own). */
    public record RenderedLink(String kind, String text, String href) {}
}
