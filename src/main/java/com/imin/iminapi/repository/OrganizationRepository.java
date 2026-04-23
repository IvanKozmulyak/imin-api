package com.imin.iminapi.repository;

import com.imin.iminapi.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
