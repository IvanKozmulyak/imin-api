package com.imin.iminapi.web;

import com.imin.iminapi.security.ApiException;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class IfMatchSupport {

    /**
     * Compare the header (which the FE serializes from the entity's updatedAt) to the
     * current entity timestamp. Throws 409 STALE_WRITE on mismatch. A null/blank header
     * is treated as the FE opting out of the check.
     */
    public void requireMatch(String header, Instant entityUpdatedAt) {
        if (header == null || header.isBlank()) return;
        String trimmed = header.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        Instant headerInstant;
        try {
            headerInstant = Instant.parse(trimmed);
        } catch (Exception e) {
            throw ApiException.staleWrite();
        }
        if (!headerInstant.equals(entityUpdatedAt)) {
            throw ApiException.staleWrite();
        }
    }
}
