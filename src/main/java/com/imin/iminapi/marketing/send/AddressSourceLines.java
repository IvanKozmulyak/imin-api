package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Art. 14: the first email an org sends to an imported member names where the address came from.
 * Imported = an accepted import provenance row; first = no earlier email of this org reached them.
 */
@Component
public class AddressSourceLines {

    private final ImportRowProvenanceRepository provenance;
    private final CampaignRecipientRepository recipients;

    public AddressSourceLines(ImportRowProvenanceRepository provenance, CampaignRecipientRepository recipients) {
        this.provenance = provenance;
        this.recipients = recipients;
    }

    /** membershipId → source platforms (oldest first, comma-joined) for members due the line; others absent. */
    public Map<UUID, String> forFirstEmail(UUID orgId, Collection<UUID> membershipIds) {
        if (membershipIds.isEmpty()) return Map.of();
        List<ImportRowProvenance> rows = provenance.findAcceptedByMembershipIds(orgId, membershipIds);
        if (rows.isEmpty()) return Map.of();
        Map<UUID, Set<String>> platforms = new LinkedHashMap<>();
        for (ImportRowProvenance p : rows) {
            String platform = p.getSourcePlatform();
            if (platform == null || platform.isBlank()) continue;
            platforms.computeIfAbsent(p.getMembershipId(), k -> new LinkedHashSet<>()).add(platform.strip());
        }
        if (platforms.isEmpty()) return Map.of();
        Set<UUID> alreadyEmailed = new HashSet<>(recipients.findMembershipsAlreadyEmailed(orgId, platforms.keySet()));
        Map<UUID, String> out = new HashMap<>();
        platforms.forEach((id, names) -> {
            if (!alreadyEmailed.contains(id)) out.put(id, String.join(", ", names));
        });
        return out;
    }
}
