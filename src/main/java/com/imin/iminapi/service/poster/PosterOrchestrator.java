package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.ReferenceImageSet;
import com.imin.iminapi.model.PosterGeneration;
import com.imin.iminapi.model.PosterGenerationStatus;
import com.imin.iminapi.model.PosterVariantEntity;
import com.imin.iminapi.model.PosterVariantStatus;
import com.imin.iminapi.repository.PosterGenerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class PosterOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PosterOrchestrator.class);

    private final IdeogramClient ideogramClient;
    private final ReferenceImageLibrary referenceLibrary;
    private final OverlayCompositor overlayCompositor;
    private final PosterImageStorage storage;
    private final PosterGenerationRepository generationRepository;
    private final Semaphore replicateCap;

    public PosterOrchestrator(
            IdeogramClient ideogramClient,
            ReferenceImageLibrary referenceLibrary,
            OverlayCompositor overlayCompositor,
            PosterImageStorage storage,
            PosterGenerationRepository generationRepository,
            @Value("${replicate.max-concurrent:6}") int maxConcurrent) {
        this.ideogramClient = ideogramClient;
        this.referenceLibrary = referenceLibrary;
        this.overlayCompositor = overlayCompositor;
        this.storage = storage;
        this.generationRepository = generationRepository;
        this.replicateCap = new Semaphore(maxConcurrent, true);
    }

    public record OrchestrationResult(UUID generationId, String subStyleTag, List<GeneratedPoster> posters) {}

    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept) {
        PosterGeneration generation = new PosterGeneration();
        generation.setGeneratedEventId(generatedEventId);
        generation.setStatus(PosterGenerationStatus.PENDING);
        generation.setSubStyleTag(concept.subStyleTag());
        generation = generationRepository.save(generation);

        ReferenceImageSet refs = referenceLibrary.forTag(concept.subStyleTag());
        if (refs.referenceUrls().isEmpty()) {
            log.warn("No reference images seeded for tag '{}' — Ideogram will run without style_reference_images",
                    concept.subStyleTag());
        }

        List<PosterVariant> variants = concept.variants();
        List<Future<GeneratedPoster>> futures = new ArrayList<>(variants.size());

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, variants.size()));
        try {
            final PosterGeneration gen = generation;
            for (PosterVariant v : variants) {
                Callable<GeneratedPoster> task = () -> generateOne(gen, v, refs, request);
                futures.add(executor.submit(task));
            }
        } finally {
            executor.shutdown();
        }

        List<GeneratedPoster> results = new ArrayList<>(futures.size());
        for (Future<GeneratedPoster> f : futures) {
            try {
                results.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while collecting variant results", e);
            } catch (ExecutionException e) {
                log.error("Variant task threw unexpectedly", e.getCause());
                results.add(failedPoster(null, "unknown", e.getCause() != null ? e.getCause().getMessage() : "unknown error"));
            }
        }

        boolean allFailed  = results.stream().allMatch(p -> "FAILED".equals(p.status()));
        boolean anyOk      = results.stream().anyMatch(p -> !"FAILED".equals(p.status()));

        generation.setRawReadyAt(LocalDateTime.now());
        generation.setCompletedAt(LocalDateTime.now());
        generation.setStatus(allFailed ? PosterGenerationStatus.FAILED
                : anyOk ? PosterGenerationStatus.COMPLETE
                : PosterGenerationStatus.FAILED);
        generationRepository.save(generation);

        if (allFailed) {
            throw new IllegalStateException("All 3 poster variants failed — check upstream logs. "
                    + "First failure: " + results.get(0).failureReason());
        }

        return new OrchestrationResult(generation.getId(), concept.subStyleTag(), results);
    }

    private GeneratedPoster generateOne(
            PosterGeneration generation,
            PosterVariant variant,
            ReferenceImageSet refs,
            EventCreatorRequest request) {
        long seed = ThreadLocalRandom.current().nextLong(1L, 1_000_000_000L);
        PosterVariantEntity entity = new PosterVariantEntity();
        entity.setPosterGeneration(generation);
        entity.setVariantStyle(variant.variantStyle());
        entity.setIdeogramPrompt(variant.ideogramPrompt());
        entity.setReferenceImagesUsed(String.join(",", refs.referenceIds()));
        entity.setSeed(seed);
        entity.setStatus(PosterVariantStatus.PENDING);
        generation.getVariants().add(entity);

        try {
            replicateCap.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason("interrupted while waiting for replicate slot");
            return toDto(entity);
        }
        try {
            IdeogramClient.IdeogramResult ideogram = ideogramClient.generate(
                    variant.ideogramPrompt(),
                    variant.aspectRatio(),
                    refs.referenceUrls(),
                    seed,
                    variant.styleType());

            byte[] rawBytes = storage.download(ideogram.imageUrl());
            String rawUrl = storage.writePng(rawBytes);
            entity.setRawUrl(rawUrl);
            entity.setStatus(PosterVariantStatus.RAW_READY);

            // TODO(phase-2): OCR sanity check on rawBytes; auto-regen once on failure.

            byte[] finalBytes = overlayCompositor.applyOverlays(new OverlayCompositor.Input(
                    rawBytes,
                    request.rsvpUrl(),
                    request.address()));
            String finalUrl = finalBytes == rawBytes
                    ? rawUrl
                    : storage.writePng(finalBytes);
            entity.setFinalUrl(finalUrl);
            entity.setStatus(PosterVariantStatus.COMPLETE);

            return toDto(entity);
        } catch (RuntimeException e) {
            log.error("Variant generation failed: style={}, seed={}", variant.variantStyle(), seed, e);
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason(e.getMessage());
            return toDto(entity);
        } finally {
            replicateCap.release();
        }
    }

    private GeneratedPoster toDto(PosterVariantEntity e) {
        Map<String, Object> overlays = new HashMap<>();
        overlays.put("qr_code", e.getStatus() == PosterVariantStatus.COMPLETE);
        overlays.put("address", e.getStatus() == PosterVariantStatus.COMPLETE);
        List<String> refs = e.getReferenceImagesUsed() == null || e.getReferenceImagesUsed().isBlank()
                ? List.of()
                : List.of(e.getReferenceImagesUsed().split(","));
        return new GeneratedPoster(
                e.getId(),
                e.getVariantStyle(),
                e.getRawUrl(),
                e.getFinalUrl(),
                e.getSeed() != null ? e.getSeed() : 0L,
                e.getIdeogramPrompt(),
                refs,
                overlays,
                e.getStatus().name(),
                e.getFailureReason());
    }

    private GeneratedPoster failedPoster(UUID id, String style, String reason) {
        return new GeneratedPoster(id, style, null, null, 0L, "", List.of(), Map.of(), "FAILED", reason);
    }
}
