package com.imin.iminapi.model;

import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.repository.PosterGenerationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PosterVariantVerdictPersistenceTest {

    @Autowired
    PosterGenerationRepository repo;

    @Autowired
    GeneratedEventRepository eventRepo;

    @Test
    void persistsValidationVerdictAndAttemptsJson() {
        // poster_generations.generated_event_id is NOT NULL with a FK to generated_event, so seed a parent.
        GeneratedEvent event = new GeneratedEvent();
        event.setStatus(GeneratedEventStatus.DRAFT);
        event.setCreatedAt(LocalDateTime.now());
        UUID eventId = eventRepo.saveAndFlush(event).getId();

        PosterGeneration g = new PosterGeneration();
        g.setGeneratedEventId(eventId);
        g.setStatus(PosterGenerationStatus.COMPLETE);
        g.setSubStyleTag("brutalist_techno");
        g.setCreativeSeed(1L);

        PosterVariantEntity v = new PosterVariantEntity();
        v.setPosterGeneration(g);
        v.setVariantStyle("people");
        v.setIdeogramPrompt("p");
        v.setStatus(PosterVariantStatus.COMPLETE);
        v.setValidationVerdict("BEST_EFFORT");
        v.setValidationAttemptsJson("[{\"attempt\":0,\"mode\":\"generate\"}]");
        g.getVariants().add(v);

        UUID id = repo.saveAndFlush(g).getId();
        repo.flush();

        PosterGeneration reloaded = repo.findById(id).orElseThrow();
        PosterVariantEntity rv = reloaded.getVariants().get(0);
        assertThat(rv.getValidationVerdict()).isEqualTo("BEST_EFFORT");
        assertThat(rv.getValidationAttemptsJson()).contains("generate");
    }
}
