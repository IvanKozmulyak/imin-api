package com.imin.iminapi.controller.ai;

import com.imin.iminapi.dto.ai.VibeSummary;
import com.imin.iminapi.service.poster.VibeLibrary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Serves the Vibe Library catalog to the AI Studio picker (Stage 1: "Вибір вайбу"). */
@RestController
@RequestMapping("/api/v1/ai")
public class VibeCatalogController {

    private final VibeLibrary vibeLibrary;

    public VibeCatalogController(VibeLibrary vibeLibrary) {
        this.vibeLibrary = vibeLibrary;
    }

    @GetMapping("/vibes")
    public List<VibeSummary> vibes() {
        return vibeLibrary.all().stream()
                .map(v -> new VibeSummary(v.id(), v.name(), v.genres(), v.palette(), v.textOnly()))
                .toList();
    }
}
