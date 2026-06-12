package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resolved org brand for one generation. Stamped on {@code poster_generations.brand_snapshot}
 * at creation, so the corrective remix path and any audit read the snapshot, not live org state.
 *
 * @param colors  ordered accent hex (#rrggbb); index 0 leads. Empty when brandless.
 * @param logoUrl the org logo URL, or null when none.
 * @param logoOn  whether the logo should be composited for this generation.
 */
public record BrandSnapshot(List<String> colors, String logoUrl, boolean logoOn) {

    private static final Logger log = LoggerFactory.getLogger(BrandSnapshot.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Serialize to the brand_snapshot JSON shape {colors, logoUrl, logoOn}. */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("colors", colors == null ? List.of() : colors);
        m.put("logoUrl", logoUrl);
        m.put("logoOn", logoOn);
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            log.warn("Could not serialize BrandSnapshot: {}", e.getMessage());
            return null;
        }
    }

    /** Parse a brand_snapshot JSON string; null/blank/malformed → null (brandless). */
    public static BrandSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<String> colors = (List<String>) m.getOrDefault("colors", List.of());
            String logoUrl = (String) m.get("logoUrl");
            Object logoOn = m.get("logoOn");
            return new BrandSnapshot(colors == null ? List.of() : colors, logoUrl,
                    logoOn instanceof Boolean b ? b : false);
        } catch (Exception e) {
            log.warn("Malformed brand_snapshot, treating as brandless: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Pack all colours into the single free-text {@code EventCreatorRequest.accentColor} line:
     * {@code "#ec4899 (lead); supporting: #f6c04a, #a78bfa"}. Null when there are no colours.
     */
    public String packedAccentColor() {
        if (colors == null || colors.isEmpty()) return null;
        String lead = colors.get(0) + " (lead)";
        if (colors.size() == 1) return lead;
        String supporting = String.join(", ", colors.subList(1, colors.size()));
        return lead + "; supporting: " + supporting;
    }
}
