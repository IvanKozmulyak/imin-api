package com.imin.iminapi.dispute;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

/**
 * Persists {@link DisputeStatus} as its lowercase wire form so the stored VARCHAR matches
 * the V128 migration's status literals. {@code @Enumerated(EnumType.STRING)} would write
 * the uppercase constant name instead.
 */
@Converter(autoApply = false)
public class DisputeStatusConverter implements AttributeConverter<DisputeStatus, String> {

    @Override
    public String convertToDatabaseColumn(DisputeStatus attribute) {
        return attribute == null ? null : attribute.toWire();
    }

    @Override
    public DisputeStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return DisputeStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
