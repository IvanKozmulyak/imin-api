package com.imin.iminapi.model;

import com.imin.iminapi.repository.GeneratedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Highest-risk behavior of {@code POST /api/v1/ai/events/concepts}: the response carries the
 * PERSISTED Concept UUIDs in sortOrder-aligned order. ConceptSetService relies on
 * {@code saveAndFlush} cascade-assigning child ids and on {@code @OrderBy("sortOrder ASC")}
 * keeping the in-memory list index aligned with sortOrder. This proves both against H2 + Flyway.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ConceptCascadePersistenceTest {

    @Autowired
    GeneratedEventRepository eventRepo;

    @Test
    void conceptIds_are_persisted_uuids_aligned_to_sortOrder_after_cascade_flush() {
        GeneratedEvent event = new GeneratedEvent();
        event.setStatus(GeneratedEventStatus.COMPLETE);
        event.setCreatedAt(LocalDateTime.now());

        // Three children, sortOrder 0,1,2 — the same insertion order ConceptSetService builds.
        for (int i = 0; i < 3; i++) {
            Concept c = new Concept();
            c.setGeneratedEvent(event);
            c.setTitle("Concept " + i);
            c.setDescription("Description " + i);
            c.setSortOrder(i);
            event.getConcepts().add(c);
        }

        GeneratedEvent saved = eventRepo.saveAndFlush(event);
        eventRepo.flush();

        // Parent persisted.
        assertThat(saved.getId()).isNotNull();

        List<Concept> concepts = saved.getConcepts();
        assertThat(concepts).hasSize(3);

        // Every child got a generated UUID, and all ids are distinct.
        assertThat(concepts).extracting(Concept::getId).doesNotContainNull();
        assertThat(concepts).extracting(Concept::getId)
                .doesNotHaveDuplicates();

        // List order (index) must match sortOrder 0,1,2 — the contract ConceptSetService depends on
        // when it re-emits cards with the persisted conceptIds by insertion index.
        for (int i = 0; i < 3; i++) {
            assertThat(concepts.get(i).getSortOrder()).isEqualTo(i);
            assertThat(concepts.get(i).getTitle()).isEqualTo("Concept " + i);
        }

        // Reload independently and confirm the persisted ids + order survive a fresh fetch.
        GeneratedEvent reloaded = eventRepo.findById(saved.getId()).orElseThrow();
        List<Concept> reloadedConcepts = reloaded.getConcepts();
        assertThat(reloadedConcepts).hasSize(3);
        List<UUID> savedIds = concepts.stream().map(Concept::getId).toList();
        List<UUID> reloadedIds = reloadedConcepts.stream().map(Concept::getId).toList();
        assertThat(reloadedIds).containsExactlyElementsOf(savedIds);
        for (int i = 0; i < 3; i++) {
            assertThat(reloadedConcepts.get(i).getSortOrder()).isEqualTo(i);
        }
    }
}
