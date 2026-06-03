package com.imin.iminapi.repository;

import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.model.VibeStyle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;

@RepositoryRestResource(exported = false)
public interface VibeStyleRepository extends JpaRepository<VibeStyle, VibeStyle.Key> {

    Optional<VibeStyle> findByVibeIdAndProvider(String vibeId, ImageProvider provider);
}
