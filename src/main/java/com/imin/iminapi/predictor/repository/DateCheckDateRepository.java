package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.DateCheckDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Candidate dates of a date check, in calendar order. */
@RepositoryRestResource(exported = false)
public interface DateCheckDateRepository extends JpaRepository<DateCheckDate, UUID> {

    List<DateCheckDate> findByDateCheckIdOrderByCandidateDateAsc(UUID dateCheckId);

    List<DateCheckDate> findByDateCheckIdInOrderByCandidateDateAsc(Collection<UUID> dateCheckIds);

    void deleteByDateCheckId(UUID dateCheckId);
}
