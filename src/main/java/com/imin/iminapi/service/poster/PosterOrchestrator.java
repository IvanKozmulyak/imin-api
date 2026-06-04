package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterTextSpec;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.ReferenceImageSet;
import com.imin.iminapi.model.ImageProvider;
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
    private final OpenAiImageClient openAiImageClient;
    private final RecraftClient recraftClient;
    private final VibeStyleTrainingService vibeStyleTrainingService;
    private final ReferenceImageLibrary referenceLibrary;
    private final OverlayCompositor overlayCompositor;
    private final PosterTextCompositorClient textCompositor;
    private final PosterTextSpecFactory textSpecFactory;
    private final PosterTextValidationService textValidation;
    private final PosterImageStorage storage;
    private final PosterGenerationRepository generationRepository;
    private final Semaphore replicateCap;
    private final ExecutorService variantPool;

    public PosterOrchestrator(
            IdeogramClient ideogramClient,
            OpenAiImageClient openAiImageClient,
            RecraftClient recraftClient,
            VibeStyleTrainingService vibeStyleTrainingService,
            ReferenceImageLibrary referenceLibrary,
            OverlayCompositor overlayCompositor,
            PosterTextCompositorClient textCompositor,
            PosterTextSpecFactory textSpecFactory,
            PosterTextValidationService textValidation,
            PosterImageStorage storage,
            PosterGenerationRepository generationRepository,
            @Value("${replicate.max-concurrent:6}") int maxConcurrent) {
        this.ideogramClient = ideogramClient;
        this.openAiImageClient = openAiImageClient;
        this.recraftClient = recraftClient;
        this.vibeStyleTrainingService = vibeStyleTrainingService;
        this.referenceLibrary = referenceLibrary;
        this.overlayCompositor = overlayCompositor;
        this.textCompositor = textCompositor;
        this.textSpecFactory = textSpecFactory;
        this.textValidation = textValidation;
        this.storage = storage;
        this.generationRepository = generationRepository;
        this.replicateCap = new Semaphore(maxConcurrent, true);
        // Small fixed pool sized for the 3 variants of one generation; the
        // Semaphore (shared across all in-flight generations) is the real upstream
        // concurrency cap. Daemon threads so the pool never blocks JVM shutdown.
        this.variantPool = Executors.newFixedThreadPool(
                Math.min(VARIANT_POOL_SIZE, Math.max(1, maxConcurrent)),
                r -> {
                    Thread t = new Thread(r, "poster-variant-" + POOL_THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    private static final int VARIANT_POOL_SIZE = 3;
    private static final java.util.concurrent.atomic.AtomicInteger POOL_THREAD_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

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
        final PosterGeneration gen = generation;
        // Dispatch the variants concurrently on the bounded pool; the Semaphore
        // inside generateOne is the real upstream concurrency cap. Submit in order,
        // collect Futures in order, then join in order so results preserve the
        // variant order regardless of completion order. Each task does only
        // in-memory mutations + external calls on the pool thread; the single
        // generationRepository.save happens on the main thread after join.
        List<Future<GeneratedPoster>> futures = new ArrayList<>(variants.size());
        for (PosterVariant v : variants) {
            futures.add(variantPool.submit(() -> generateOne(gen, v, refs, request)));
        }

        List<GeneratedPoster> results = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            PosterVariant v = variants.get(i);
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("Variant task threw unexpectedly", cause);
                results.add(failedPoster(null, v.variantStyle(), cause.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while awaiting variant task", e);
                results.add(failedPoster(null, v.variantStyle(), "interrupted while awaiting variant"));
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
        // Shared mutable collection across the variant pool threads — synchronize the add.
        synchronized (generation) {
            generation.getVariants().add(entity);
        }

        ImageProvider provider = request.effectiveImageProvider();
        try {
            replicateCap.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason("interrupted while waiting for replicate slot");
            return toDto(entity);
        }
        try {
            byte[] rawBytes = renderVariant(provider, variant, refs, seed);
            String rawUrl = storage.writePng(rawBytes);
            entity.setRawUrl(rawUrl);
            entity.setStatus(PosterVariantStatus.RAW_READY);

            // Recraft owns visible event typography; keep Satori full-text compositing for fallback providers.
            byte[] finalBytes = provider == ImageProvider.RECRAFT
                    ? validateAndApplyQrOnly(rawBytes, request)
                    : applyTextLayer(rawBytes, refs, request);
            String finalUrl = finalBytes == rawBytes
                    ? rawUrl
                    : storage.writePng(finalBytes);
            entity.setFinalUrl(finalUrl);
            entity.setStatus(PosterVariantStatus.COMPLETE);

            return toDto(entity);
        } catch (RuntimeException e) {
            log.error("Variant generation failed: provider={}, style={}, seed={}",
                    provider, variant.variantStyle(), seed, e);
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason(e.getMessage());
            return toDto(entity);
        } finally {
            replicateCap.release();
        }
    }

    private byte[] validateAndApplyQrOnly(byte[] rawBytes, EventCreatorRequest request) {
        PosterTextSpec spec = textSpecFactory.from(request);
        PosterTextValidationService.ValidationDecision decision =
                textValidation.validateOrExplain(rawBytes, spec);
        if (!decision.accepted()) {
            throw new IllegalStateException("Poster text validation failed: " + decision.reason());
        }
        return overlayCompositor.applyOverlays(new OverlayCompositor.Input(
                rawBytes, request.rsvpUrl(), null));
    }

    /**
     * Composite the text layer onto the background art. For curated vibes with real event text and
     * the compositor enabled, this calls the imin-public Satori route (full real-font text layer).
     * If that configured path fails, fail the variant rather than returning a poster without event
     * text. Unsupported/disabled compositor paths still fall back to the Java2D QR + address overlay.
     */
    private byte[] applyTextLayer(byte[] rawBytes, ReferenceImageSet refs, EventCreatorRequest request) {
        String vibeId = refs.subStyleTag();
        if (textCompositor.supports(vibeId) && hasEventText(request)) {
            try {
                return textCompositor.composite(
                        rawBytes, vibeId, PosterTextCompositorClient.EventText.from(request));
            } catch (RuntimeException e) {
                log.error("Text compositor failed for vibe {} — failing variant to avoid poster without event text: {}",
                        vibeId, e.getMessage());
                throw e;
            }
        }
        return overlayCompositor.applyOverlays(new OverlayCompositor.Input(
                rawBytes, request.rsvpUrl(), request.address()));
    }

    /** The text layer is only meaningful when there is a real event title to render. */
    private static boolean hasEventText(EventCreatorRequest request) {
        return request.title() != null && !request.title().isBlank();
    }

    private byte[] renderVariant(
            ImageProvider provider,
            PosterVariant variant,
            ReferenceImageSet refs,
            long seed) {
        if (provider == ImageProvider.OPENAI) {
            List<byte[]> refBytes = referenceLibrary.loadAllBytes(refs.subStyleTag());
            return openAiImageClient.generate(
                    variant.ideogramPrompt(),
                    variant.aspectRatio(),
                    refBytes,
                    seed).imageBytes();
        }
        if (provider == ImageProvider.RECRAFT) {
            // The sub-style tag is the vibe id; resolve the trained style_id (vibe_style
            // table, falling back to Vibe.styleId() from vibes.yaml) and the curated
            // reference bytes for that vibe.
            String styleId = vibeStyleTrainingService.resolveStyleId(
                    refs.subStyleTag(), ImageProvider.RECRAFT);
            List<byte[]> refBytes = referenceLibrary.loadAllBytes(refs.subStyleTag());
            return recraftClient.generate(
                    variant.ideogramPrompt(),
                    variant.aspectRatio(),
                    styleId,
                    refBytes).imageBytes();
        }
        IdeogramClient.IdeogramResult ideogram = ideogramClient.generate(
                variant.ideogramPrompt(),
                variant.aspectRatio(),
                refs.referenceUrls(),
                seed,
                variant.styleType());
        return storage.download(ideogram.imageUrl());
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
