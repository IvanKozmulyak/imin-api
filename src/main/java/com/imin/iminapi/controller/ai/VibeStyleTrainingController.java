package com.imin.iminapi.controller.ai;

import com.imin.iminapi.dto.ai.VibeStyleTrainResponse;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.RoleGuard;
import com.imin.iminapi.service.poster.VibeStyleTrainingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin tool to train a per-vibe Recraft style from the vibe's curated flyers.
 * Calls Recraft live, so it requires {@code RECRAFT_API_KEY}; run it once per
 * curated vibe to populate the {@code vibe_style} table.
 *
 * <p><b>OWNER/ADMIN only.</b> "Authenticated" was the whole gate here for a while, which is
 * not what "admin tool" means: {@code SecurityConfig} covers the organizer surface with a bare
 * {@code .authenticated()} matcher, so any MEMBER of any org could loop this endpoint, spend
 * imin's Recraft credits, and overwrite the {@code vibe_style} row every OTHER org's posters
 * resolve against — the write has no org dimension at all. The seniority check is the same
 * {@link RoleGuard} axis the org's other privileged endpoints got.
 *
 * <p>Two things this deliberately does NOT claim to fix: there is still no rate-limit bucket
 * (an ADMIN can loop it), and the table it writes is still platform-wide rather than
 * per-org. Both are structural — the honest fix is to take the endpoint off the HTTP surface,
 * which is a contract removal and not this change.
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
        RoleGuard.requireAtLeast(principal, UserRole.ADMIN, "train a vibe style");
        log.info("Vibe style training requested for '{}' by {}", vibeId, principal.actorLabel());
        VibeStyleTrainingService.TrainResult r = trainingService.trainRecraftStyle(vibeId);
        return new VibeStyleTrainResponse(r.vibeId(), r.provider(), r.styleId(), r.trainedAt());
    }
}
