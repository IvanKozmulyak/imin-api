package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.dto.CampaignDto;      // Phase 2
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.Campaign;        // Phase 2
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.CampaignRepository;   // Phase 2
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Org-scoped read/approve/dismiss orchestration for Momentum suggestions (spec §6.4).
 * approve creates a {@code campaigns} row with {@code origin='momentum'} and returns it
 * (the FE then opens it in the composer). This service does NOT write suggestions or
 * notifications — the evaluator owns creation (avoids a bean cycle).
 */
@Service
public class MomentumService {

    private final MomentumSuggestionRepository suggestions;
    private final CampaignRepository campaigns;
    private final ObjectMapper json = new ObjectMapper();

    public MomentumService(MomentumSuggestionRepository suggestions, CampaignRepository campaigns) {
        this.suggestions = suggestions;
        this.campaigns = campaigns;
    }

    @Transactional(readOnly = true)
    public List<MomentumSuggestionDto> list(AuthPrincipal principal, String status) {
        String s = status == null || status.isBlank() ? "suggested" : status;
        return suggestions.findByOrgIdAndStatusOrderBySuggestedAtDesc(principal.orgId(), s)
                .stream().map(MomentumSuggestionDto::from).toList();
    }

    @Transactional
    public CampaignDto approve(AuthPrincipal principal, UUID suggestionId) {
        MomentumSuggestion s = loadOwned(principal, suggestionId);
        if (!"suggested".equals(s.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Suggestion is not live");
        }
        JsonNode draft = parse(s.getDraftPayload());

        Instant now = Instant.now();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(principal.orgId());
        c.setChannel("email");
        c.setName(text(draft, "subject", "Momentum campaign"));
        c.setStatus("draft");
        c.setEventId(s.getEventId());
        c.setSegmentId(uuidOrNull(text(draft, "segmentId", null)));
        c.setOrigin("momentum");
        c.setMomentumSuggestionId(s.getId());
        c.setSubject(text(draft, "subject", null));
        c.setPreheader(text(draft, "preheader", null));
        c.setBodyMd(text(draft, "bodyMd", null));
        c.setCreatedBy(principal.userId());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        campaigns.save(c);

        s.setStatus("approved");
        s.setCampaignId(c.getId());
        s.setActedAt(now);
        suggestions.save(s);

        return CampaignDto.from(c);
    }

    @Transactional
    public void dismiss(AuthPrincipal principal, UUID suggestionId) {
        MomentumSuggestion s = loadOwned(principal, suggestionId);
        s.setStatus("dismissed");
        s.setActedAt(Instant.now());
        suggestions.save(s);
    }

    private MomentumSuggestion loadOwned(AuthPrincipal principal, UUID id) {
        MomentumSuggestion s = suggestions.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.NOT_FOUND, "Suggestion not found"));
        if (!s.getOrgId().equals(principal.orgId())) { // no cross-org leak
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Suggestion not found");
        }
        return s;
    }

    private JsonNode parse(String s) {
        try { return json.readTree(s); }
        catch (Exception e) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL, "Bad draft payload"); }
    }
    private String text(JsonNode n, String field, String dflt) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? dflt : v.asText();
    }
    private UUID uuidOrNull(String s) { return s == null ? null : UUID.fromString(s); }
}
