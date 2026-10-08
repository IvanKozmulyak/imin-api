package com.imin.iminapi.marketing.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * (De)serializes {@link TemplateTokens} to/from JSON stored in a TEXT column.
 *
 * <p>TEXT, not native {@code jsonb}, like every prior JSON column in this schema (V33 stripe
 * lists, V38 brand colours, V51 segment rules, V52 exclusion_summary), each read through a
 * manual JSON converter.
 */
@Converter
public class TemplateTokensJsonConverter implements AttributeConverter<TemplateTokens, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(TemplateTokens tokens) {
        if (tokens == null) return null;
        try {
            return MAPPER.writeValueAsString(tokens);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize template tokens", e);
        }
    }

    @Override
    public TemplateTokens convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, TemplateTokens.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse template tokens: " + json, e);
        }
    }
}
