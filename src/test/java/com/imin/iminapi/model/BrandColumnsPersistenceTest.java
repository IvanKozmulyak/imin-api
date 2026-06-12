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
class BrandColumnsPersistenceTest {

    @Autowired PosterGenerationRepository repo;
    @Autowired GeneratedEventRepository eventRepo;

    @Test
    void brand_snapshot_and_logo_composite_status_round_trip() {
        // generated_event_id has a FK to generated_event, so we must seed a parent row first.
        GeneratedEvent event = new GeneratedEvent();
        event.setStatus(GeneratedEventStatus.DRAFT);
        event.setCreatedAt(LocalDateTime.now());
        UUID eventId = eventRepo.saveAndFlush(event).getId();

        PosterGeneration g = new PosterGeneration();
        g.setGeneratedEventId(eventId);
        g.setStatus(PosterGenerationStatus.PENDING);
        g.setBrandSnapshot("{\"colors\":[\"#ec4899\"],\"logoUrl\":\"https://cdn/l.png\",\"logoOn\":true}");

        PosterVariantEntity v = new PosterVariantEntity();
        v.setPosterGeneration(g);
        v.setVariantStyle("people");
        v.setIdeogramPrompt("prompt");
        v.setStatus(PosterVariantStatus.COMPLETE);
        v.setLogoCompositeStatus("APPLIED");
        g.getVariants().add(v);

        PosterGeneration saved = repo.saveAndFlush(g);
        repo.flush();

        PosterGeneration reloaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBrandSnapshot()).contains("#ec4899");
        assertThat(reloaded.getVariants().get(0).getLogoCompositeStatus()).isEqualTo("APPLIED");
    }
}
