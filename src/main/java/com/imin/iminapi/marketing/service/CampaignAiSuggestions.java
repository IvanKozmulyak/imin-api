package com.imin.iminapi.marketing.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Fingerprints of the model-written copy offered for a campaign (V145, ADR-0005), so the server
 * can tell that a saved subject or body is AI output without trusting the client to say so.
 */
@Service
public class CampaignAiSuggestions {

    public static final String SUBJECT = "subject";
    public static final String BODY = "body";

    private final JdbcTemplate jdbc;

    public CampaignAiSuggestions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the offered texts. Own transaction: the generators run read-only, and a failed
     * write here must not cost the organizer the suggestions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID campaignId, String part, Collection<String> texts) {
        Set<String> hashes = new LinkedHashSet<>();
        for (String t : texts) {
            String h = fingerprint(t);
            if (h != null) hashes.add(h);
        }
        // ON CONFLICT: a duplicate (repeat or concurrent request) must not abort the Postgres tx.
        for (String h : hashes) {
            jdbc.update("INSERT INTO campaign_ai_suggestions (campaign_id, part, text_sha256) VALUES (?, ?, ?)"
                    + " ON CONFLICT DO NOTHING", campaignId, part, h);
        }
    }

    /** True when {@code text} is, after whitespace normalisation, copy a model offered for this campaign. */
    @Transactional(readOnly = true)
    public boolean wasOffered(UUID campaignId, String part, String text) {
        String h = fingerprint(text);
        if (h == null) return false;
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM campaign_ai_suggestions WHERE campaign_id = ? AND part = ? AND text_sha256 = ?",
                Integer.class, campaignId, part, h);
        return n != null && n > 0;
    }

    /** SHA-256 of the trimmed text with CRLF folded to LF; null for null or blank. */
    static String fingerprint(String text) {
        if (text == null) return null;
        String norm = text.replace("\r\n", "\n").strip();
        if (norm.isEmpty()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(norm.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
