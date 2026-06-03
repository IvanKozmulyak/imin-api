package com.imin.iminapi.controller.ai;

import com.imin.iminapi.dto.ai.VibeStyleTrainResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.poster.VibeStyleTrainingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin tool to train a per-vibe Recraft style from the vibe's curated flyers.
 * Authenticated (covered by the {@code /api/v1/**} authenticated matcher). Calls
 * Recraft live, so it requires {@code RECRAFT_API_KEY}; run it once per curated
 * vibe to populate the {@code vibe_style} table.
 */
@RestController
@RequestMapping("/api/v1/ai/vibes")
public class VibeStyleTrainingController {

    private static final Logger log = LoggerFactory.getLogger(VibeStyleTrainingController.class);

    private final VibeStyleTrainingService trainingService;

    public VibeStyleTrainingController(VibeStyleTrainingService trainingService) {
        this.trainingService = trainingService;
    }

    @PostMapping("/{vibeId}/train-style")
    public VibeStyleTrainResponse trainStyle(@CurrentUser AuthPrincipal principal,
                                             @PathVariable String vibeId) {
        log.info("Vibe style training requested for '{}' by {}", vibeId, principal.actorLabel());
        VibeStyleTrainingService.TrainResult r = trainingService.trainRecraftStyle(vibeId);
        return new VibeStyleTrainResponse(r.vibeId(), r.provider(), r.styleId(), r.trainedAt());
    }
}
