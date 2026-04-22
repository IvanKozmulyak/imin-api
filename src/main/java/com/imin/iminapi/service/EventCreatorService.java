package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.EventCreatorResponse;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.exception.EventCreationException;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import com.imin.iminapi.service.poster.PosterOrchestrator.OrchestrationResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventCreatorService {

    private static final Logger log = LoggerFactory.getLogger(EventCreatorService.class);

    private final AiEventDescriptionService aiEventDescriptionService;
    private final PosterOrchestrator posterOrchestrator;
    private final GeneratedEventRepository repository;

    public EventCreatorResponse create(EventCreatorRequest request) {
        long startedAt = System.currentTimeMillis();
        GeneratedEvent event = initDraft(request);
        repository.save(event);
        log.info("Poster generation started: genre={}, city={}, date={}, title={}",
                request.genre(), request.city(), request.date(), request.title());

        try {
            PosterConcept concept = aiEventDescriptionService.generateConcept(request);
            if (request.subStyleTag() != null && !request.subStyleTag().isBlank()) {
                concept = new PosterConcept(
                        request.subStyleTag(),
                        concept.colorPaletteDescription(),
                        concept.variants());
                log.info("Sub-style tag overridden by request: {}", concept.subStyleTag());
            }
            OrchestrationResult result = posterOrchestrator.run(event.getId(), request, concept);

            String finalUrls = result.posters().stream()
                    .map(p -> p.finalUrl() != null ? p.finalUrl() : p.rawUrl())
                    .filter(u -> u != null)
                    .collect(Collectors.joining(","));
            event.setPosterUrls(finalUrls);
            event.setStatus(GeneratedEventStatus.COMPLETE);
            repository.save(event);

            long elapsed = System.currentTimeMillis() - startedAt;
            log.info("Poster generation completed in {} ms: variants={}, tag={}",
                    elapsed, result.posters().size(), result.subStyleTag());

            return new EventCreatorResponse(
                    event.getId(),
                    event.getStatus().name(),
                    result.generationId(),
                    result.subStyleTag(),
                    result.posters(),
                    event.getCreatedAt());

        } catch (Exception e) {
            event.setStatus(GeneratedEventStatus.FAILED);
            try {
                repository.save(event);
            } catch (Exception ignored) {
                log.warn("Unable to persist failure status");
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            log.error("Poster generation failed after {} ms: {}", elapsed, e.getMessage(), e);
            throw new EventCreationException("Event creation failed: " + e.getMessage(), e);
        }
    }

    private GeneratedEvent initDraft(EventCreatorRequest request) {
        GeneratedEvent event = new GeneratedEvent();
        event.setVibe(request.vibe());
        event.setTone(request.tone());
        event.setGenre(request.genre());
        event.setCity(request.city());
        event.setEventDate(request.date());
        event.setPlatforms(String.join(",", request.platforms()));
        event.setStatus(GeneratedEventStatus.DRAFT);
        return event;
    }
}
