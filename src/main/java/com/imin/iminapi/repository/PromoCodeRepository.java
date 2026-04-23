package com.imin.iminapi.repository;

import com.imin.iminapi.model.PromoCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface PromoCodeRepository extends JpaRepository<PromoCode, UUID> {
    List<PromoCode> findByEventId(UUID eventId);
}
