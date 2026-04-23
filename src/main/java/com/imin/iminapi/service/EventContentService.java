package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventContentRequest;
import com.imin.iminapi.dto.EventContentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventContentService {

    private final ChatClient chatClient;

    public EventContentResponse generate(EventContentRequest request) {
        return chatClient.prompt()
                .user(buildPrompt(request))
                .call()
                .entity(EventContentResponse.class);
    }

    private String buildPrompt(EventContentRequest request) {
        return """
                You are an expert nightlife event marketer. Based on the description below, \
                generate creative content for the event.

                Event description:
                %s

                Return a JSON object with exactly these fields:
                - names: array of 3 creative event name options
                - taglines: array of 3 punchy one-liner taglines
                - genre: the primary music genre (single word or short phrase, e.g. "techno", "afro house")
                - tone: the overall mood/tone (short phrase, e.g. "dark and hypnotic", "euphoric and uplifting")
                - vibe: the atmosphere/energy descriptor (short phrase, e.g. "underground warehouse rave")
                - description_long: 150-word SEO-optimised event description
                - description_short: 3-line punchy variant of the description
                - instagram_caption: caption with emojis and relevant hashtags
                - story_text: short punchy text for an Instagram/TikTok Story overlay
                - facebook_post: full Facebook event post
                - x_post: post under 280 characters for X (Twitter)
                - pricing_suggestion: object with floor (number), ceiling (number), reasoning (string) — suggest ticket prices in EUR
                - optimal_timing: recommended day and time slot (e.g. "Saturday 11pm–6am")
                - color_palette: array of exactly 3 hex color codes that match the vibe
                """.formatted(request.prompt());
    }
}
