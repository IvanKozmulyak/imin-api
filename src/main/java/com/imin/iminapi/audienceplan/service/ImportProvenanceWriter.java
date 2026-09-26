package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.SuppressionService;
import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes one imported row's consent effect together with its provenance row, in one
 * transaction, so an {@code organizer_import_row} consent never exists without its proof.
 */
@Service
public class ImportProvenanceWriter {

    /** Consent source for a row whose own provenance was accepted. */
    public static final String SOURCE = "organizer_import_row";

    private final ConsentService consentService;
    private final SuppressionService suppressionService;
    private final ImportRowProvenanceRepository provenanceRepo;

    public ImportProvenanceWriter(ConsentService consentService,
                                  SuppressionService suppressionService,
                                  ImportRowProvenanceRepository provenanceRepo) {
        this.consentService = consentService;
        this.suppressionService = suppressionService;
        this.provenanceRepo = provenanceRepo;
    }

    /** Explicit consent with source {@link #SOURCE} plus its accepted provenance row. */
    @Transactional
    public void recordExplicit(UUID orgId, UUID membershipId, UUID importId, int rowNumber,
                               ImportValidator.Decision d, String proofText, String textVersion,
                               AuthPrincipal principal) {
        UUID consentRecordId = consentService.capture(orgId, membershipId, "explicit", SOURCE,
                proofText, "email", textVersion, null, principal);
        ImportRowProvenance p = row(importId, membershipId, rowNumber, d, true, null);
        p.setConsentRecordId(consentRecordId);
        provenanceRepo.save(p);
    }

    /** The organizer's export says the person unsubscribed: suppress marketing for this org. */
    @Transactional
    public void recordUnsubscribed(UUID orgId, UUID membershipId, UUID importId, int rowNumber,
                                   ImportValidator.Decision d, AuthPrincipal principal) {
        suppressionService.addMarketing(orgId, membershipId, "unsubscribe", principal);
        provenanceRepo.save(row(importId, membershipId, rowNumber, d, false, d.reason()));
    }

    /** A row kept without a basis; the membership's consent is left as it was. */
    @Transactional
    public void recordRejected(UUID importId, UUID membershipId, int rowNumber,
                               ImportValidator.Decision d, String reason) {
        provenanceRepo.save(row(importId, membershipId, rowNumber, d, false, reason));
    }

    private static ImportRowProvenance row(UUID importId, UUID membershipId, int rowNumber,
                                           ImportValidator.Decision d, boolean accepted,
                                           String reason) {
        ImportRowProvenance p = new ImportRowProvenance();
        p.setImportId(importId);
        p.setMembershipId(membershipId);
        p.setRowNumber(rowNumber);
        p.setSourcePlatform(d.sourcePlatform());
        p.setExportDate(d.exportDate());
        p.setEvents(d.events());
        p.setLastPurchaseDate(d.lastPurchaseDate());
        p.setMarketingStatus(d.marketingStatus());
        p.setProofRef(d.proofRef());
        p.setAccepted(accepted);
        p.setRejectReason(reason);
        return p;
    }
}
