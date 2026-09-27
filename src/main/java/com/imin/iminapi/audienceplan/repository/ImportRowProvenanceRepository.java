package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface ImportRowProvenanceRepository extends Repository<ImportRowProvenance, UUID> {

    ImportRowProvenance save(ImportRowProvenance row);

    List<ImportRowProvenance> findByMembershipIdOrderByCreatedAtAsc(UUID membershipId);

    List<ImportRowProvenance> findByImportId(UUID importId);

    boolean existsByMembershipIdAndAcceptedTrue(UUID membershipId);

    /** Provenance rows still on file per import, grouped by outcome; no row-level data. */
    @Query("select p.importId as importId, p.accepted as accepted, p.rejectReason as rejectReason,"
            + " count(p) as rowCount from ImportRowProvenance p where p.importId in :importIds"
            + " group by p.importId, p.accepted, p.rejectReason")
    List<OutcomeCount> countOutcomesByImportIds(@Param("importIds") List<UUID> importIds);

    interface OutcomeCount {
        UUID getImportId();
        boolean getAccepted();
        String getRejectReason();
        long getRowCount();
    }

    @Modifying
    @Transactional
    @Query("delete from ImportRowProvenance p where p.membershipId = :membershipId")
    int deleteByMembershipId(@Param("membershipId") UUID membershipId);
}
