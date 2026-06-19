package com.imin.iminapi.audience.dto;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Membership;

import java.time.Instant;
import java.util.List;

public record ConsentDto(
        String status,
        String basis,
        List<ProofEntry> proofs
) {
    public record ProofEntry(String status, String basis, String source, Instant occurredAt) {
        public static ProofEntry from(ConsentRecord r) {
            return new ProofEntry(r.getStatus(), r.getLawfulBasis(), r.getSource(), r.getOccurredAt());
        }
    }

    public static ConsentDto from(Membership m, List<ConsentRecord> records) {
        return new ConsentDto(
                m.getConsentStatus(),
                m.getConsentBasis(),
                records.stream().map(ProofEntry::from).toList()
        );
    }
}
