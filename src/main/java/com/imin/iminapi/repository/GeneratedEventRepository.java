package com.imin.iminapi.repository;

import com.imin.iminapi.model.GeneratedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GeneratedEventRepository extends JpaRepository<GeneratedEvent, UUID> {

    @Query("""
            SELECT e FROM GeneratedEvent e
            WHERE e.genre = :genre
              AND e.city = :city
              AND e.eventDate BETWEEN :startDate AND :endDate
              AND e.status = 'COMPLETE'
            """)
    List<GeneratedEvent> findComparableEvents(
            @Param("genre") String genre,
            @Param("city") String city,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
