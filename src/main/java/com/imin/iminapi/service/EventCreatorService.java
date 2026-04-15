package com.imin.iminapi.service;

import com.imin.iminapi.dto.*;
import com.imin.iminapi.exception.EventCreationException;
import com.imin.iminapi.model.Concept;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.SocialCopy;
import com.imin.iminapi.repository.GeneratedEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class EventCreatorService {

    private static final Logger log = LoggerFactory.getLogger(EventCreatorService.class);
    private static final String LIST_SEP = "\n";

    private final ChatClient chatClient;
    private final ImageGenerationService imageGenerationService;
    private final PricingService pricingService;
    private final GeneratedEventRepository repository;

    public EventCreatorResponse create(EventCreatorRequest request) {
        long startedAt = System.currentTimeMillis();
        GeneratedEvent event = initDraft(request);
        repository.save(event); // persist DRAFT immediately so failure path always has a row
        log.info("AI event creation started: genre={}, city={}, date={}, platforms={}",
                request.genre(), request.city(), request.date(), request.platforms().size());

        try {
            log.info("Step 1/3: calling LLM for concepts and social copy");
            LlmGenerationResult llmResult = callLlm(request);
            log.info("Step 1/3 complete: concepts={}, socialCopies={}, accentColors={}",
                    llmResult.concepts().size(), llmResult.socialCopy().size(), llmResult.accentColors().size());

            event.setAccentColors(String.join(LIST_SEP, llmResult.accentColors()));
            event.setConcepts(buildConcepts(llmResult.concepts(), event));
            event.setSocialCopies(buildSocialCopies(llmResult.socialCopy(), event));
            repository.save(event);
            log.info("Draft event updated with LLM results");

            String primaryColor = llmResult.accentColors().get(0);
            log.info("Step 2/3: generating posters in parallel for {} concepts", event.getConcepts().size());
            List<String> posterUrls = generatePosters(event.getConcepts(), primaryColor);
            event.setPosterUrls(String.join(LIST_SEP, posterUrls));
            log.info("Step 2/3 complete: generated {} posters", posterUrls.size());

            log.info("Step 3/3: calculating pricing recommendation");
            PricingRecommendation pricing = pricingService.recommend(
                    request.genre(), request.city(), request.date());
            applyPricing(event, pricing);
            log.info("Step 3/3 complete: minPrice={}, maxPrice={}",
                    pricing.suggestedMinPrice(), pricing.suggestedMaxPrice());

            event.setStatus(GeneratedEventStatus.COMPLETE);
            repository.save(event);
            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("AI event creation completed successfully in {} ms", elapsedMs);

            return toResponse(event, pricing);

        } catch (Exception e) {
            event.setStatus(GeneratedEventStatus.FAILED);
            try {
                repository.save(event);
                log.warn("AI event creation failed; failure status persisted");
            } catch (Exception ignored) {
                log.warn("AI event creation failed; unable to persist failure status");
            }
            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.error("AI event creation failed after {} ms: {}", elapsedMs, e.getMessage(), e);
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

    private LlmGenerationResult callLlm(EventCreatorRequest request) {
        String prompt = """
                You are an expert event creator. Generate exactly 3 unique event concepts based on:

                Vibe: %s
                Tone: %s
                Genre: %s
                City: %s
                Date: %s
                Platforms: %s

                Requirements:
                - Exactly 3 distinct event concepts, each with a title, 2-3 sentence description, and one punchy tagline
                - Exactly 5 HEX color codes (with # prefix) that match the mood and genre
                - Social media copy for each of these platforms: %s (platform field must match the name exactly as provided)
                """.formatted(
                request.vibe(), request.tone(), request.genre(),
                request.city(), request.date(), String.join(", ", request.platforms()),
                String.join(", ", request.platforms())
        );
        return chatClient.prompt()
                .user(prompt)
                .call()
                .entity(LlmGenerationResult.class);
    }

    private List<Concept> buildConcepts(List<LlmEventConcept> llmConcepts, GeneratedEvent event) {
        List<Concept> result = new ArrayList<>();
        for (int i = 0; i < llmConcepts.size(); i++) {
            LlmEventConcept lc = llmConcepts.get(i);
            Concept c = new Concept();
            c.setGeneratedEvent(event);
            c.setTitle(lc.title());
            c.setDescription(lc.description());
            c.setTagline(lc.tagline());
            c.setSortOrder(i + 1);
            result.add(c);
        }
        return result;
    }

    private List<SocialCopy> buildSocialCopies(List<LlmSocialCopy> llmCopies, GeneratedEvent event) {
        return llmCopies.stream().map(lc -> {
            SocialCopy sc = new SocialCopy();
            sc.setGeneratedEvent(event);
            sc.setPlatform(lc.platform());
            sc.setCopyText(lc.copyText());
            return sc;
        }).toList();
    }

    private List<String> generatePosters(List<Concept> concepts, String primaryColor) {
        List<CompletableFuture<String>> futures = concepts.stream()
                .map(c -> CompletableFuture.supplyAsync(
                        () -> imageGenerationService.generatePoster(c, primaryColor)))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private void applyPricing(GeneratedEvent event, PricingRecommendation pricing) {
        event.setSuggestedMinPrice(pricing.suggestedMinPrice());
        event.setSuggestedMaxPrice(pricing.suggestedMaxPrice());
        event.setPricingNotes(pricing.pricingNotes());
    }

    private EventCreatorResponse toResponse(GeneratedEvent event, PricingRecommendation pricing) {
        List<String> accentColors = List.of(event.getAccentColors().split(LIST_SEP));
        List<String> posterUrls = event.getPosterUrls() != null
                ? List.of(event.getPosterUrls().split(LIST_SEP)) : List.of();

        List<EventCreatorResponse.ConceptDto> conceptDtos = event.getConcepts().stream()
                .map(c -> new EventCreatorResponse.ConceptDto(
                        c.getTitle(), c.getDescription(), c.getTagline(), c.getSortOrder()))
                .toList();

        List<EventCreatorResponse.SocialCopyDto> socialDtos = event.getSocialCopies().stream()
                .map(sc -> new EventCreatorResponse.SocialCopyDto(sc.getPlatform(), sc.getCopyText()))
                .toList();

        EventCreatorResponse.PricingDto pricingDto = new EventCreatorResponse.PricingDto(
                pricing.suggestedMinPrice(), pricing.suggestedMaxPrice(),
                pricing.pricingNotes());

        return new EventCreatorResponse(
                event.getId(), event.getStatus().name(), accentColors, posterUrls,
                conceptDtos, socialDtos, pricingDto, event.getCreatedAt());
    }
}
