package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.model.VibeStyle;
import com.imin.iminapi.repository.VibeStyleRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Trains and persists per-vibe image-provider styles.
 *
 * <p>Training loads the vibe's curated flyer bytes from {@link ReferenceImageLibrary}
 * and calls the provider's style-training endpoint (currently Recraft
 * {@code POST /styles}), then upserts the returned {@code style_id} into the
 * {@code vibe_style} table keyed by {@code (vibeId, provider)}. The orchestrator
 * reads the trained id back via {@link #resolveStyleId} at generation time,
 * falling back to {@code Vibe.styleId()} from vibes.yaml when no row exists.
 */
@Service
public class VibeStyleTrainingService {

    private static final Logger log = LoggerFactory.getLogger(VibeStyleTrainingService.class);

    private final VibeLibrary vibeLibrary;
    private final ReferenceImageLibrary referenceLibrary;
    private final RecraftClient recraftClient;
    private final VibeStyleRepository vibeStyleRepository;

    public VibeStyleTrainingService(
            VibeLibrary vibeLibrary,
            ReferenceImageLibrary referenceLibrary,
            RecraftClient recraftClient,
            VibeStyleRepository vibeStyleRepository) {
        this.vibeLibrary = vibeLibrary;
        this.referenceLibrary = referenceLibrary;
        this.recraftClient = recraftClient;
        this.vibeStyleRepository = vibeStyleRepository;
    }

    public record TrainResult(String vibeId, ImageProvider provider, String styleId, LocalDateTime trainedAt) {}

    /**
     * Train (or re-train) the Recraft style for one vibe from its curated flyer
     * bytes, persisting the returned style id.
     */
    @Transactional
    public TrainResult trainRecraftStyle(String vibeId) {
        Vibe vibe = vibeLibrary.byId(vibeId)
                .orElseThrow(() -> ApiException.notFound("Vibe '" + vibeId + "'"));

        List<byte[]> refs = referenceLibrary.loadAllBytes(vibeId);
        if (refs.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.INVALID_STATE,
                    "Vibe '" + vibe.id() + "' has no curated reference flyers to train a style from");
        }

        String styleId = recraftClient.createStyle(refs);

        VibeStyle row = vibeStyleRepository
                .findByVibeIdAndProvider(vibeId, ImageProvider.RECRAFT)
                .orElseGet(VibeStyle::new);
        row.setVibeId(vibeId);
        row.setProvider(ImageProvider.RECRAFT);
        row.setStyleId(styleId);
        row.setTrainedAt(LocalDateTime.now());
        vibeStyleRepository.save(row);

        log.info("Trained Recraft style for vibe '{}': style_id={} (from {} references)",
                vibeId, styleId, refs.size());
        return new TrainResult(vibeId, ImageProvider.RECRAFT, styleId, row.getTrainedAt());
    }

    /**
     * Resolve the style id for a vibe + provider: prefer the trained row in the
     * {@code vibe_style} table, else fall back to {@code Vibe.styleId()} from
     * vibes.yaml, else {@code null} (the client then uses its base style).
     */
    @Transactional(readOnly = true)
    public String resolveStyleId(String vibeId, ImageProvider provider) {
        if (vibeId == null) return null;
        Optional<VibeStyle> trained = vibeStyleRepository.findByVibeIdAndProvider(vibeId, provider);
        if (trained.isPresent() && trained.get().getStyleId() != null
                && !trained.get().getStyleId().isBlank()) {
            return trained.get().getStyleId();
        }
        return vibeLibrary.byId(vibeId).map(Vibe::styleId).orElse(null);
    }
}
