package com.imin.iminapi.service;

import com.imin.iminapi.model.Concept;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final ImageModel imageModel;

    public String generatePoster(Concept concept, String primaryColor) {
        String prompt = """
                Professional event poster design.
                Event title: %s
                Description: %s
                Tagline: %s
                Primary accent color: %s
                Style: modern, bold typography, atmospheric lighting, concert/event aesthetic.
                """.formatted(
                concept.getTitle(),
                concept.getDescription(),
                concept.getTagline(),
                primaryColor
        );
        return imageModel.call(new ImagePrompt(prompt))
                .getResult()
                .getOutput()
                .getUrl();
    }
}
