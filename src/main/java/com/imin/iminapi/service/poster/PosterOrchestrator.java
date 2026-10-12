package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterTextSpec;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.StyleReferencePart;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.model.PosterGeneration;
import com.imin.iminapi.model.PosterGenerationStatus;
import com.imin.iminapi.model.PosterVariantEntity;
import com.imin.iminapi.model.PosterVariantStatus;
import com.imin.iminapi.repository.PosterGenerationRepository;
import com.imin.iminapi.service.ai.CreativeDirection;
import com.imin.iminapi.service.ai.provenance.ImageAiMarker;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders the art director's 3 concept variants via the native Ideogram V3 API, validates each
 * with the vision gates, and persists the result.
 *
 * <p>Per variant: generate once; run the HARD text gate; on any failure regenerate from the original
 * prompt with a new seed, up to {@code poster.validation.max-regenerations} times (a remix keeps the
 * layout that broke the text, so it is used only for the DJ palette regrade). When every attempt
 * fails, the attempt with the fewest text errors ships best-effort, and a retry render that throws
 * ships the best attempt so far. The SOFT style gate is advisory — a
 * style-only failure is accepted best-effort without another render. Text is fully baked by the
 * model; there is no QR/address/Satori overlay.
 */
@Service
public class PosterOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PosterOrchestrator.class);

    private static final int VARIANT_POOL_SIZE = 3;
    private static final long MAX_REF_TOTAL_BYTES = 10L * 1024 * 1024; // Ideogram cap
    private static final String VERDICT_ACCEPTED = "ACCEPTED";
    private static final String VERDICT_BEST_EFFORT = "BEST_EFFORT";
    private static final String MODE_GENERATE = "generate";
    private static final String MODE_REGENERATE = "regenerate";
    private static final AtomicInteger POOL_THREAD_SEQ = new AtomicInteger();

    private final IdeogramV3Client ideogramClient;
    private final VibeLibrary vibeLibrary;
    private final StyleCardLibrary styleCardLibrary;
    private final ReferenceImageLibrary referenceLibrary;
    private final PosterTextSpecFactory textSpecFactory;
    private final PosterTextValidationService textValidation;
    private final PosterStyleValidationService styleValidation;
    private final PosterImageStorage storage;
    private final BrandLogoCompositor logoCompositor;
    private final PosterGenerationRepository generationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRegenerations;
    private final boolean paletteRegradeEnabled;
    private final int paletteRegradeWeight;
    private final int maxReferences;
    private final ExecutorService variantPool;

    public PosterOrchestrator(
            IdeogramV3Client ideogramClient,
            VibeLibrary vibeLibrary,
            StyleCardLibrary styleCardLibrary,
            ReferenceImageLibrary referenceLibrary,
            PosterTextSpecFactory textSpecFactory,
            PosterTextValidationService textValidation,
            PosterStyleValidationService styleValidation,
            PosterImageStorage storage,
            BrandLogoCompositor logoCompositor,
            PosterGenerationRepository generationRepository,
            @Value("${poster.validation.max-regenerations:2}") int maxRegenerations,
            @Value("${poster.palette-regrade.enabled:true}") boolean paletteRegradeEnabled,
            @Value("${poster.palette-regrade.image-weight:85}") int paletteRegradeWeight,
            @Value("${ideogram.max-references:3}") int maxReferences,
            @Value("${poster.render.max-concurrent:6}") int maxConcurrent) {
        this.ideogramClient = ideogramClient;
        this.vibeLibrary = vibeLibrary;
        this.styleCardLibrary = styleCardLibrary;
        this.referenceLibrary = referenceLibrary;
        this.textSpecFactory = textSpecFactory;
        this.textValidation = textValidation;
        this.styleValidation = styleValidation;
        this.storage = storage;
        this.logoCompositor = logoCompositor;
        this.generationRepository = generationRepository;
        this.maxRegenerations = Math.max(0, maxRegenerations);
        this.paletteRegradeEnabled = paletteRegradeEnabled;
        this.paletteRegradeWeight = paletteRegradeWeight;
        this.maxReferences = Math.max(0, maxReferences);
        // maxConcurrent sizes the variant pool and nothing else: the pool is capped at 3 (one thread
        // per concept variant) and never exceeds maxConcurrent, so a second per-render permit could
        // not block for ANY value and only pretended to cap in-flight renders.
        this.variantPool = Executors.newFixedThreadPool(
                Math.min(VARIANT_POOL_SIZE, Math.max(1, maxConcurrent)),
                r -> {
                    Thread t = new Thread(r, "poster-variant-" + POOL_THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    public record OrchestrationResult(UUID generationId, String subStyleTag, List<GeneratedPoster> posters) {}

    /** Style control resolved once per generation: curated parts (win) + the preset fallback. */
    private record StyleControl(List<StyleReferencePart> parts, String preset) {
        boolean hasRefs() { return parts != null && !parts.isEmpty(); }
        List<String> ids() { return hasRefs() ? parts.stream().map(StyleReferencePart::filename).toList() : List.of(); }
    }

    private record RenderContext(Vibe vibe, StyleCard card, StyleControl style) {}

    /**
     * @param organizerId the user this paid render is attributed to (may be null in tests). Stamped
     *                    on the generation row: {@code poster_generations.organizer_id} has existed
     *                    since V3 with no writer, so every row carried no actor at all, while the
     *                    AI-Act provenance work already stamps model_id per variant.
     */
    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept,
                                   long creativeSeed, List<CreativeDirection> directions, BrandSnapshot brand,
                                   DjPhotoSnapshot djPhoto, UUID organizerId) {
        PosterGeneration generation = new PosterGeneration();
        generation.setGeneratedEventId(generatedEventId);
        generation.setOrganizerId(organizerId);
        generation.setStatus(PosterGenerationStatus.PENDING);
        generation.setSubStyleTag(concept.subStyleTag());
        generation.setCreativeSeed(creativeSeed);
        if (brand != null) {
            generation.setBrandSnapshot(brand.toJson());
        }
        if (djPhoto != null) {
            generation.setDjPhotoUrl(djPhoto.url());
        }
        generation = generationRepository.save(generation);

        String tag = concept.subStyleTag();
        List<StyleReferencePart> parts = referenceLibrary.topReferenceParts(tag, maxReferences, MAX_REF_TOTAL_BYTES);
        String preset = vibeLibrary.ideogramStylePreset(tag);
        if (parts.isEmpty() && (preset == null || preset.isBlank())) {
            log.warn("Vibe '{}' has no reference images and no ideogram_style_preset — rendering with no style control", tag);
        }
        RenderContext ctx = new RenderContext(
                vibeLibrary.byId(tag).orElse(null),
                styleCardLibrary.get(tag).orElse(null),
                new StyleControl(parts, preset));

        List<PosterVariant> variants = concept.variants();
        final PosterGeneration gen = generation;
        final BrandSnapshot brandFinal = brand;
        final StyleReferencePart characterRef = djPhoto == null ? null : djPhoto.toPart();
        List<Future<GeneratedPoster>> futures = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            final PosterVariant v = variants.get(i);
            final CreativeDirection dir = i < directions.size() ? directions.get(i) : null;
            final long seed = deriveSeed(creativeSeed, i);
            futures.add(variantPool.submit(() -> generateOne(gen, v, dir, seed, request, ctx, brandFinal, characterRef)));
        }

        List<GeneratedPoster> results = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            PosterVariant v = variants.get(i);
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("Variant task threw unexpectedly", cause);
                results.add(failedPoster(null, v.heroType(), cause.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while awaiting variant task", e);
                results.add(failedPoster(null, v.heroType(), "interrupted while awaiting variant"));
            }
        }

        boolean allFailed = results.stream().allMatch(p -> "FAILED".equals(p.status()));
        generation.setRawReadyAt(LocalDateTime.now());
        generation.setCompletedAt(LocalDateTime.now());
        generation.setStatus(allFailed ? PosterGenerationStatus.FAILED : PosterGenerationStatus.COMPLETE);
        generationRepository.save(generation);

        if (allFailed) {
            throw new IllegalStateException("All 3 poster variants failed — check upstream logs. "
                    + "First failure: " + results.get(0).failureReason());
        }
        return new OrchestrationResult(generation.getId(), tag, results);
    }

    private GeneratedPoster generateOne(
            PosterGeneration generation, PosterVariant variant, CreativeDirection direction,
            long seed, EventCreatorRequest request, RenderContext ctx, BrandSnapshot brand,
            StyleReferencePart characterRef) {
        PosterVariantEntity entity = new PosterVariantEntity();
        entity.setPosterGeneration(generation);
        entity.setVariantStyle(variant.heroType());
        entity.setIdeogramPrompt(variant.ideogramPrompt());
        entity.setReferenceImagesUsed(String.join(",", ctx.style().ids()));
        entity.setSeed(seed);
        entity.setCreativeDirectionJson(serialize(direction));
        // AI Act Art.50 provenance (V100): stamped at creation, alongside the
        // prompt and seed, so it is recorded even for a variant that then fails.
        entity.setModelId(IdeogramV3Client.MODEL_ID);
        entity.setStatus(PosterVariantStatus.PENDING);
        synchronized (generation) {
            generation.getVariants().add(entity);
        }

        try {
            return renderWithValidation(entity, variant, seed, request, ctx, brand, characterRef);
        } catch (RuntimeException e) {
            log.error("Variant generation failed: hero_type={}, seed={}", variant.heroType(), seed, e);
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason(e.getMessage());
            return toDto(entity);
        }
    }

    /**
     * A failed attempt kept as a best-effort fallback. A class, not a record: it carries the render
     * bytes, and records compare arrays by reference.
     */
    static final class Candidate {
        final byte[] bytes;
        final long seed;
        final int journalIndex;
        final int missing;
        final int extra;
        final boolean evaluated;
        final int attempt;

        Candidate(byte[] bytes, long seed, int journalIndex, int missing, int extra, boolean evaluated, int attempt) {
            this.bytes = bytes;
            this.seed = seed;
            this.journalIndex = journalIndex;
            this.missing = missing;
            this.extra = extra;
            this.evaluated = evaluated;
            this.attempt = attempt;
        }
    }

    /**
     * Whether {@code challenger} should replace {@code incumbent} as the best-effort render: an
     * evaluated attempt beats an unevaluated one, then fewer text errors, then fewer missing
     * required strings (a stray word hurts less than a missing date), then the earlier attempt.
     */
    static boolean isBetter(Candidate challenger, Candidate incumbent) {
        if (incumbent == null) return true;
        if (challenger.evaluated != incumbent.evaluated) return challenger.evaluated;
        int byTotal = Integer.compare(challenger.missing + challenger.extra, incumbent.missing + incumbent.extra);
        if (byTotal != 0) return byTotal < 0;
        int byMissing = Integer.compare(challenger.missing, incumbent.missing);
        if (byMissing != 0) return byMissing < 0;
        return challenger.attempt < incumbent.attempt;
    }

    /**
     * Generate → text gate (hard: regenerate on every failure, best-of on exhaustion) → style gate (soft).
     */
    private GeneratedPoster renderWithValidation(
            PosterVariantEntity entity, PosterVariant variant, long baseSeed,
            EventCreatorRequest request, RenderContext ctx, BrandSnapshot brand,
            StyleReferencePart characterRef) {
        PosterTextSpec spec = textSpecFactory.from(request);
        HeroType heroType = HeroType.fromWire(variant.heroType());
        StyleControl style = ctx.style();
        List<String> brandPalette = brand == null || brand.colors() == null ? List.of() : brand.colors();
        List<Map<String, Object>> attempts = new ArrayList<>();

        long seed = baseSeed;
        byte[] image = null;
        String mode = MODE_GENERATE;
        Candidate best = null;

        for (int attempt = 0; attempt <= maxRegenerations; attempt++) {
            boolean last = attempt == maxRegenerations;
            entity.setSeed(seed);

            log.info("[ideogram call] path={} hero_type={} attempt={}/{} seed={}",
                    mode, variant.heroType(), attempt, maxRegenerations, seed);
            IdeogramV3Client.IdeogramResult render;
            try {
                render = ideogramClient.generate(variant.ideogramPrompt(), seed, style.parts(), style.preset(),
                        brandPalette, characterRef);
            } catch (RuntimeException e) {
                if (attempt == 0 || best == null) throw e;
                attempts.add(failedRenderJson(attempt, seed, mode, e));
                log.warn("Retry render failed on attempt {}; shipping the best attempt so far: {}",
                        attempt, e.getMessage());
                return acceptBest(entity, best, attempts, brand);
            }
            image = render.imageBytes();
            // Deliberately NOT stored yet: every superseded retry render used to leave an
            // unreferenced, immutable R2 object that nothing ever reclaimed. accept() performs the
            // single raw write, for the one render that actually ships.
            entity.setStatus(PosterVariantStatus.RAW_READY);

            PosterTextValidationService.ValidationDecision text = textValidation.validateOrExplain(image, spec);
            if (!text.accepted()) {
                int missing = sizeOf(text.missingRequired());
                attempts.add(attemptJson(attempt, seed, mode, text, null));
                Candidate candidate = new Candidate(image, seed, attempts.size() - 1, missing,
                        sizeOf(text.extraText()), text.evaluated(), attempt);
                if (isBetter(candidate, best)) best = candidate;
                if (last) {
                    log.warn("Text gate still failing after {} regenerations; shipping the best attempt ({}): {}",
                            maxRegenerations, best.attempt, text.reason());
                    return acceptBest(entity, best, attempts, brand);
                }
                // A remix keeps the layout that broke the text (it lost the date in prod), so every
                // failure gets a fresh render from the original prompt.
                mode = MODE_REGENERATE;
                seed = nextSeed(seed);
                continue;
            }

            // DJ mode: the char-ref render couldn't carry color_palette (Ideogram rejects the
            // combination), so apply the brand palette in a separate regrade pass BEFORE the
            // style gate, so the gate judges the colours that actually ship.
            boolean regradeShipped = false;
            if (characterRef != null && paletteRegradeEnabled && !brandPalette.isEmpty()) {
                long regradeSeed = nextSeed(seed);
                byte[] graded = paletteRegrade(image, regradeSeed, brandPalette, spec, attempts);
                // paletteRegrade hands back the very same array when it falls back to the pre-regrade render.
                regradeShipped = graded != image;
                image = graded;
                if (regradeShipped) entity.setSeed(regradeSeed);
            }

            PosterStyleValidationService.ValidationDecision styleDecision =
                    styleValidation.validateOrExplain(image, ctx.card(), heroType);
            Map<String, Object> entry = attemptJson(attempt, seed, mode, text, styleDecision);
            attempts.add(entry);
            if (!regradeShipped) entry.put("shipped", true);
            if (styleDecision.accepted()) {
                return accept(entity, image, VERDICT_ACCEPTED, attempts, brand);
            }
            // Text is correct; style is soft — accept best-effort without spending more renders.
            log.warn("Style gate soft-failed (text OK); accepting best-effort: {}", styleDecision.reason());
            return accept(entity, image, VERDICT_BEST_EFFORT, attempts, brand);
        }
        throw new IllegalStateException("render-with-validation loop exhausted");
    }

    /** Ships the best failed attempt: its bytes, its seed, and the journal marker on its entry. */
    private GeneratedPoster acceptBest(PosterVariantEntity entity, Candidate best,
                                       List<Map<String, Object>> attempts, BrandSnapshot brand) {
        entity.setSeed(best.seed);
        attempts.get(best.journalIndex).put("shipped", true);
        return accept(entity, best.bytes, VERDICT_BEST_EFFORT, attempts, brand);
    }

    private static int sizeOf(List<String> items) {
        return items == null ? 0 : items.size();
    }

    /**
     * DJ-mode palette pass: re-grade the text-accepted render in one extra TURBO remix WITHOUT
     * the character reference but WITH the structural {@code color_palette} — the combination the
     * generate call cannot carry — at high image weight so composition, the DJ, and the text stay
     * put. No style refs/preset: the input image is the only style anchor. The text gate re-checks
     * the regrade; if the colours came at the cost of the typography (or the call fails), the
     * pre-regrade render ships instead — this pass can improve colour, never lose a poster.
     */
    private byte[] paletteRegrade(byte[] image, long regradeSeed, List<String> brandPalette,
                                  PosterTextSpec spec, List<Map<String, Object>> attempts) {
        try {
            IdeogramV3Client.IdeogramResult regraded = ideogramClient.remix(
                    image, buildPaletteRegradePrompt(brandPalette), paletteRegradeWeight, regradeSeed,
                    List.of(), null, brandPalette);
            PosterTextValidationService.ValidationDecision text =
                    textValidation.validateOrExplain(regraded.imageBytes(), spec);
            Map<String, Object> entry = attemptJson(attempts.size(), regradeSeed, "palette-regrade", text, null);
            attempts.add(entry);
            if (text.accepted()) {
                entry.put("shipped", true);
                return regraded.imageBytes();
            }
            log.warn("Palette regrade broke the text; shipping the pre-regrade render: {}", text.reason());
        } catch (RuntimeException e) {
            log.warn("Palette regrade failed; shipping the pre-regrade render: {}", e.getMessage());
        }
        return image;
    }

    static String buildPaletteRegradePrompt(List<String> hexes) {
        StringBuilder p = new StringBuilder("Re-grade this finished poster's colours only: make ")
                .append(hexes.get(0)).append(" visibly dominate");
        if (hexes.size() > 1) {
            p.append(", with ").append(String.join(", ", hexes.subList(1, hexes.size())))
             .append(" as secondary accents");
        }
        p.append(". Keep the composition, the featured person, every text element, and the layout ")
         .append("identical — change nothing but the colour grading.");
        return p.toString();
    }

    /**
     * The single funnel that stores the accepted render and sets raw_url/final_url for every
     * acceptance path. This is the ONLY raw write: retry and regrade renders that were superseded
     * are never stored, so no unreferenced objects accumulate. Composites the brand logo when the
     * snapshot says to, as a SECOND storage write (final_url = composited URL; raw_url keeps the
     * un-composited render). Failure isolation is absolute: any composite error → final_url =
     * raw_url + Sentry warning + status FAILED. Generation never fails over the logo.
     */
    private GeneratedPoster accept(PosterVariantEntity entity, byte[] rawBytes,
                                   String verdict, List<Map<String, Object>> attempts,
                                   BrandSnapshot brand) {
        String rawUrl = storage.writePng(rawBytes, ImageAiMarker.SourceType.TRAINED_ALGORITHMIC_MEDIA);
        entity.setRawUrl(rawUrl);
        entity.setValidationVerdict(verdict);
        entity.setValidationAttemptsJson(serialize(attempts));
        entity.setStatus(PosterVariantStatus.COMPLETE);

        String finalUrl = rawUrl;
        String compositeStatus;
        if (brand == null || !brand.logoOn() || brand.logoUrl() == null || brand.logoUrl().isBlank()) {
            compositeStatus = "SKIPPED";
        } else {
            try {
                // The render bytes are still in hand — re-fetching the object we just wrote adds an
                // R2 round trip per branded variant and a failure mode (a transient read error ships
                // the poster un-composited) that holding the bytes cannot have.
                byte[] composited = logoCompositor.composite(rawBytes, brand.logoUrl());
                finalUrl = storage.writePng(composited,
                        ImageAiMarker.SourceType.COMPOSITE_WITH_TRAINED_ALGORITHMIC_MEDIA); // SECOND write → distinct object/URL
                compositeStatus = "APPLIED";
            } catch (RuntimeException e) {
                log.warn("Logo composite failed; shipping un-composited poster: {}", e.getMessage());
                Sentry.withScope(scope -> {
                    scope.setLevel(SentryLevel.WARNING);
                    scope.setTag("subsystem", "brand-logo-composite");
                    Sentry.captureException(e);
                });
                finalUrl = rawUrl;
                compositeStatus = "FAILED";
            }
        }
        entity.setFinalUrl(finalUrl);
        entity.setLogoCompositeStatus(compositeStatus);
        return toDto(entity);
    }

    private Map<String, Object> attemptJson(int attempt, long seed, String mode,
                                            PosterTextValidationService.ValidationDecision text,
                                            PosterStyleValidationService.ValidationDecision style) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempt", attempt);
        m.put("seed", seed);
        m.put("mode", mode);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("accepted", text.accepted());
        t.put("reason", text.reason() == null ? "" : text.reason());
        t.put("evaluated", text.evaluated());
        t.put("missing", sizeOf(text.missingRequired()));
        t.put("extra", sizeOf(text.extraText()));
        m.put("text", t);
        Map<String, Object> s = new LinkedHashMap<>();
        if (style == null) {
            s.put("skipped", true);
        } else {
            s.put("accepted", style.accepted());
            s.put("reason", style.reason() == null ? "" : style.reason());
        }
        m.put("style", s);
        return m;
    }

    private static Map<String, Object> failedRenderJson(int attempt, long seed, String mode, RuntimeException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempt", attempt);
        m.put("seed", seed);
        m.put("mode", mode);
        m.put("render", "failed");
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("accepted", false);
        t.put("evaluated", false);
        t.put("reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        m.put("text", t);
        return m;
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialize {}: {}", value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static long deriveSeed(long creativeSeed, int index) {
        long s = creativeSeed * 1_000_003L + index;
        return Math.floorMod(s, 1_000_000_000L) + 1L;
    }

    private static long nextSeed(long seed) {
        long s = seed * 6364136223846793005L + 1442695040888963407L;
        return Math.floorMod(s, 1_000_000_000L) + 1L;
    }

    private GeneratedPoster toDto(PosterVariantEntity e) {
        List<String> refs = e.getReferenceImagesUsed() == null || e.getReferenceImagesUsed().isBlank()
                ? List.of() : List.of(e.getReferenceImagesUsed().split(","));
        return new GeneratedPoster(
                e.getId(), e.getVariantStyle(), e.getRawUrl(), e.getFinalUrl(),
                e.getSeed() != null ? e.getSeed() : 0L, refs,
                e.getStatus().name(), e.getFailureReason());
    }

    private GeneratedPoster failedPoster(UUID id, String style, String reason) {
        return new GeneratedPoster(id, style, null, null, 0L, List.of(), "FAILED", reason);
    }
}
