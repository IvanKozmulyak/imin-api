package com.imin.iminapi.marketing.service;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.moderation.ModerationHoldService;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * IMIN staff lift a breaker pause. The resume instant becomes the breaker's baseline, so complaints and bounces from
 * before it cannot pause the org again; queued campaigns resume on the dispatcher's next passes.
 */
@Service
public class MarketingPauseService {

    static final int MAX_NOTE = 1000;

    private final ModerationHoldService staff;
    private final OrganizationRepository orgs;
    private final AuditLogger audit;

    public MarketingPauseService(ModerationHoldService staff, OrganizationRepository orgs, AuditLogger audit) {
        this.staff = staff;
        this.orgs = orgs;
        this.audit = audit;
    }

    public record Resumed(UUID orgId, String liftedReason, Instant resumedAt) {}

    /** 404 unless the caller is on the staff allow-list; checked before anything else is read. */
    public void requireStaff(AuthPrincipal p) {
        staff.requireStaff(p);
    }

    /** 404 for non-staff or an unknown org, 400 for a note over 1000 characters, 409 when the org is not paused. */
    @Transactional
    public Resumed resume(AuthPrincipal p, UUID orgId, String note) {
        staff.requireStaff(p);
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Invalid resume",
                    Map.of("note", "at most " + MAX_NOTE + " characters"));
        }
        Organization org = orgs.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization"));
        if (org.getMarketingPausedAt() == null) {
            throw ApiException.invalidState("Marketing sending is not paused for this organization");
        }
        String reason = org.getMarketingPausedReason();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Conditional: a concurrent resume already lifted it, and this one gets the same 409.
        if (orgs.resumeMarketing(orgId, now) == 0) {
            throw ApiException.invalidState("Marketing sending is not paused for this organization");
        }
        // The staff member is the actor; the row lives in the org whose sending was resumed.
        audit.record(new AuthPrincipal(p.userId(), orgId, p.role(), p.sessionId()),
                AuditActions.MARKETING_SENDING_RESUMED, "organization", orgId,
                "Marketing sending resumed by IMIN staff (paused for " + (reason == null ? "unknown" : reason) + ")"
                        + (cleanNote == null ? "" : ": " + cleanNote));
        return new Resumed(orgId, reason, now);
    }
}
