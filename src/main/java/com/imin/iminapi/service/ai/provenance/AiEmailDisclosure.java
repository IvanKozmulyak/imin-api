package com.imin.iminapi.service.ai.provenance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable AI marker for an email (AI Act Art.50(2), ADR-0005): the AI-generated parts,
 * as MIME headers and HTML meta tags. Nothing visible is added.
 *
 * @param subject the subject line came from a model
 * @param body    the preheader or Markdown body came from a model
 */
public record AiEmailDisclosure(boolean subject, boolean body) {

    public static final AiEmailDisclosure NONE = new AiEmailDisclosure(false, false);

    /** Per-message disclosure header (IETF draft-abaris-aicdh). */
    public static final String DISCLOSURE_HEADER = "AI-Disclosure";
    public static final String DISCLOSURE_VALUE = "mode=ai-originated";
    /** Names the AI-generated parts, since the draft header is per message only. */
    public static final String PARTS_HEADER = "X-IMIN-AI-Generated";

    public boolean any() {
        return subject || body;
    }

    /** "subject, body" style list of the AI-generated parts; empty when none. */
    public String parts() {
        List<String> p = new ArrayList<>(2);
        if (subject) p.add("subject");
        if (body) p.add("body");
        return String.join(", ", p);
    }

    /** Headers to add to the outgoing message; empty when nothing is AI-generated. */
    public Map<String, String> headers() {
        if (!any()) return Map.of();
        Map<String, String> h = new LinkedHashMap<>();
        h.put(DISCLOSURE_HEADER, DISCLOSURE_VALUE);
        h.put(PARTS_HEADER, parts());
        return h;
    }

    /** Meta tags for the HTML part's head; empty when nothing is AI-generated. */
    public String htmlMeta() {
        if (!any()) return "";
        return "<meta name=\"ai-disclosure\" content=\"" + DISCLOSURE_VALUE + "\"/>"
                + "<meta name=\"imin-ai-generated\" content=\"" + parts() + "\"/>";
    }
}
