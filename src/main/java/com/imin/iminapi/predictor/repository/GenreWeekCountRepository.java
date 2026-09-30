package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.GenreWeekCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Weekly genre event counts per city. */
@RepositoryRestResource(exported = false)
public interface GenreWeekCountRepository extends JpaRepository<GenreWeekCount, UUID> {

    /** Pass {@code ""} as sub-genre for the whole family. */
    Optional<GenreWeekCount> findByCityKeyAndGenreFamilyAndSubGenreAndWeekStart(
            String cityKey, String genreFamily, String subGenre, LocalDate weekStart);
}
