package com.imin.iminapi.marketing.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.util.AccessPattern;

import java.util.UUID;

/**
 * A PATCH field that can be <i>cleared</i> — the three-state UUID a partial update needs.
 *
 * <p>mkt-edge-8: a plain {@code UUID} record component collapses two different requests into
 * one value. {@code {"name":"x"}} (leave the link alone) and {@code {"segmentId":null}}
 * (unlink it) both arrive as {@code null}, so {@code if (req.segmentId() != null)} could only
 * ever implement "absent" — and the composer, which really does PATCH
 * {@code {segmentId: null, eventId: null}} when the organizer de-selects, silently kept the
 * old link and went on rendering that event's poster and tickets button into the sent mail.
 *
 * <p>The three states, as {@code CampaignService.patch} reads the field:
 * <ul>
 *   <li>property absent → the component is {@code null} → leave unchanged. (No deserializer
 *       is invoked for a property that is not in the body.)</li>
 *   <li>property present and null → {@link #NULL} (via {@link Deserializer#getNullValue}) →
 *       clear the column.</li>
 *   <li>property present with a value → an instance wrapping it → set the column.</li>
 * </ul>
 *
 * <p>This is the mechanism {@code JsonNullable} uses, written out locally rather than adding
 * a dependency for two fields. The wire shape is unchanged — the {@code @Schema} on each
 * component keeps it documented as a nullable uuid string, not as an object.
 *
 * <p><b>Jackson 3, deliberately.</b> Spring Boot 4 wires HTTP message conversion to
 * {@code tools.jackson}, while Jackson 2 ({@code com.fasterxml}) is still on the classpath
 * transitively — the same split {@code GoogleWalletModels} documents. A
 * {@code com.fasterxml.jackson.databind.annotation.JsonDeserialize} here is simply ignored by
 * the converter: the record then binds as a nested object and every request carrying a real
 * uuid 400s. The imports below are the whole contract.
 */
@JsonDeserialize(using = PatchableUuid.Deserializer.class)
public record PatchableUuid(UUID value) {

    /** The "present, and explicitly null" instance — i.e. "clear this field". */
    public static final PatchableUuid NULL = new PatchableUuid(null);

    public static PatchableUuid of(UUID value) {
        return value == null ? NULL : new PatchableUuid(value);
    }

    static final class Deserializer extends ValueDeserializer<PatchableUuid> {
        @Override
        public PatchableUuid deserialize(JsonParser p, DeserializationContext ctx) {
            String raw = p.getValueAsString();
            if (raw == null || raw.isBlank()) return NULL;
            try {
                return new PatchableUuid(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException e) {
                // The same 400 INVALID_REQUEST ("Malformed request body") a plain UUID
                // component produced before this type existed.
                throw InvalidFormatException.from(p, "Not a valid UUID", raw, UUID.class);
            }
        }

        /** Called for an explicit JSON null — the whole point of this class. */
        @Override
        public Object getNullValue(DeserializationContext ctx) {
            return NULL;
        }

        /**
         * Load-bearing. The default is {@code ALWAYS_NULL}, which lets the binder short-circuit
         * a null token straight to Java null and never ask {@link #getNullValue} — i.e. the
         * explicit null would arrive indistinguishable from an absent field, which is the
         * entire bug this class exists to fix.
         */
        @Override
        public AccessPattern getNullAccessPattern() {
            return AccessPattern.CONSTANT;
        }

        /**
         * The other half, and just as load-bearing: a MISSING creator property falls back to
         * {@code getAbsentValue}, whose default delegates to {@link #getNullValue}. Left alone,
         * an absent field would also arrive as {@link #NULL} and every single-field PATCH
         * would silently unlink the other two columns.
         */
        @Override
        public Object getAbsentValue(DeserializationContext ctx) {
            return null;
        }
    }
}
