package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.BrandBookDto;
import com.imin.iminapi.dto.org.BrandUpdateRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Brand-book reads/writes. Validation is hand-written (not bean validation) so we can emit
 * per-index field keys — {@code fields: {"accentColors[1]": "..."}} — that the FE highlights on
 * the offending swatch. Hex is lowercase-normalized, deduped case-insensitively (a duplicate
 * wastes a slot), capped at 3; brand_name is trimmed, max 120, blank → NULL.
 */
@Service
public class OrgBrandService {

    private static final int MAX_COLORS = 3;
    private static final int MAX_NAME_LEN = 120;
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final OrganizationRepository orgs;

    public OrgBrandService(OrganizationRepository orgs) {
        this.orgs = orgs;
    }

    @Transactional(readOnly = true)
    public BrandBookDto get(AuthPrincipal p) {
        return BrandBookDto.from(load(p));
    }

    @Transactional
    public BrandBookDto put(AuthPrincipal p, BrandUpdateRequest body) {
        Organization o = load(p);
        o.setBrandName(normalizeName(body.brandName()));
        o.setBrandAccentColors(validateColors(body.accentColors()));
        o.setBrandLogoOnPosters(body.logoOnPosters() == null ? true : body.logoOnPosters());
        // updated_at is owned by Organization's @PreUpdate hook (onUpdate → Times.nowMicros()),
        // which fires on every flush — do NOT set it here (it would be overwritten anyway, and
        // Instant.now() is not micro-truncated like the rest of the entity).
        return BrandBookDto.from(orgs.save(o));
    }

    @Transactional
    public void setLogoUrl(AuthPrincipal p, String url) {
        Organization o = load(p);
        o.setBrandLogoUrl(url);
        orgs.save(o); // @PreUpdate stamps updated_at on flush
    }

    @Transactional
    public void clearLogoUrl(AuthPrincipal p) {
        Organization o = load(p);
        o.setBrandLogoUrl(null); // toggle (brandLogoOnPosters) intentionally left untouched
        orgs.save(o); // @PreUpdate stamps updated_at on flush
    }

    private Organization load(AuthPrincipal p) {
        return orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private static String normalizeName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_NAME_LEN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "Validation failed", Map.of("brandName", "must be ≤ " + MAX_NAME_LEN + " characters"));
        }
        return trimmed;
    }

    private static List<String> validateColors(List<String> raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        if (raw.size() > MAX_COLORS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "Validation failed", Map.of("accentColors", "at most " + MAX_COLORS + " colours"));
        }
        // LinkedHashMap keyed on the lowercase hex preserves first-seen order and dedupes case-insensitively.
        Map<String, String> seen = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            String c = raw.get(i);
            if (c == null || !HEX.matcher(c).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                        "Validation failed",
                        Map.of("accentColors[" + i + "]", "must be a 6-digit hex colour like #ec4899"));
            }
            seen.putIfAbsent(c.toLowerCase(), c.toLowerCase());
        }
        return new ArrayList<>(seen.values());
    }
}
