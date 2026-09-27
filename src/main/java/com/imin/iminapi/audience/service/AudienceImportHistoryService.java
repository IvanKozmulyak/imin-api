package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.AudienceImportSummary;
import com.imin.iminapi.audienceplan.model.AudienceImport;
import com.imin.iminapi.audienceplan.repository.AudienceImportRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.RoleGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Past contact imports of the caller's org, with a count-only per-row provenance summary. */
@Service
public class AudienceImportHistoryService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    static final int HASH_PREFIX_LENGTH = 12;
    /** Key used when a not-accepted provenance row carries no reason. */
    static final String UNSPECIFIED_REASON = "unspecified";

    private final AudienceImportRepository importRepo;
    private final ImportRowProvenanceRepository provenanceRepo;

    public AudienceImportHistoryService(AudienceImportRepository importRepo,
                                        ImportRowProvenanceRepository provenanceRepo) {
        this.importRepo = importRepo;
        this.provenanceRepo = provenanceRepo;
    }

    /** OWNER/ADMIN only; newest first; {@code limit} null means the default, else clamped. */
    @Transactional(readOnly = true)
    public List<AudienceImportSummary> list(AuthPrincipal principal, Integer limit) {
        RoleGuard.requireAtLeast(principal, UserRole.ADMIN, "view import history");
        int size = clampLimit(limit);
        List<AudienceImport> imports = importRepo.findByOrgIdOrderByCreatedAtDescIdDesc(
                principal.orgId(), PageRequest.of(0, size));
        if (imports.isEmpty()) return List.of();

        Map<UUID, Counts> counts = new HashMap<>();
        for (ImportRowProvenanceRepository.OutcomeCount oc :
                provenanceRepo.countOutcomesByImportIds(imports.stream().map(AudienceImport::getId).toList())) {
            counts.computeIfAbsent(oc.getImportId(), k -> new Counts()).add(oc);
        }
        return imports.stream()
                .map(i -> toSummary(i, counts.getOrDefault(i.getId(), new Counts())))
                .toList();
    }

    static int clampLimit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    static String hashPrefix(String sha256) {
        if (sha256 == null || sha256.length() < HASH_PREFIX_LENGTH) return null;
        return sha256.substring(0, HASH_PREFIX_LENGTH);
    }

    private static AudienceImportSummary toSummary(AudienceImport i, Counts c) {
        return new AudienceImportSummary(
                i.getId(),
                i.getCreatedAt(),
                hashPrefix(i.getOriginalFileSha256()),
                hashPrefix(i.getUploadedFileSha256()),
                i.getSourcePlatform(),
                i.getExportDate(),
                i.getProofRef() != null && !i.getProofRef().isBlank(),
                i.getAttestationVersion(),
                i.getRowsTotal(),
                i.getRowsExplicit(),
                i.getRowsNoBasis(),
                i.getRowsRejected(),
                new AudienceImportSummary.ProvenanceSummary(c.recorded, c.accepted, Map.copyOf(c.byReason)));
    }

    private static final class Counts {
        int recorded;
        int accepted;
        final Map<String, Integer> byReason = new TreeMap<>();

        void add(ImportRowProvenanceRepository.OutcomeCount oc) {
            int n = Math.toIntExact(oc.getRowCount());
            recorded += n;
            if (oc.getAccepted()) {
                accepted += n;
            } else {
                String reason = oc.getRejectReason() == null ? UNSPECIFIED_REASON : oc.getRejectReason();
                byReason.merge(reason, n, Integer::sum);
            }
        }
    }
}
