package com.imin.iminapi.service.poster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.Base64;
import java.util.List;

@Component
public class ReferenceImageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImageAnalyzer.class);

    private static final String SYSTEM_INSTRUCTION = """
            You are a senior art director. Look at the supplied poster reference images and write
            a SINGLE 2-4 sentence descriptor capturing the shared visual style:
            palette (specific colours, contrast), typography (weight, treatment, family character),
            mood/atmosphere, and composition cues (layout patterns, focal points, framing).
            No bullet points, no headings, no preamble. Plain prose only. Be concrete and concise.
            """;

    private final ChatClient chatClient;
    private final String modelId;

    public ReferenceImageAnalyzer(
            ChatClient chatClient,
            @Value("${imin.reference-analyzer.model-id:openai/gpt-4o-mini}") String modelId) {
        this.chatClient = chatClient;
        this.modelId = modelId;
    }

    public String modelId() {
        return modelId;
    }

    public String analyze(String subStyleTag, List<String> referenceUrls) {
        if (referenceUrls == null || referenceUrls.isEmpty()) {
            log.warn("No reference images for tag '{}' — returning empty descriptor", subStyleTag);
            return "";
        }
        log.info("Analyzing {} reference image(s) for tag '{}'", referenceUrls.size(), subStyleTag);
        return chatClient.prompt()
                .user(u -> {
                    u.text(SYSTEM_INSTRUCTION);
                    for (String ref : referenceUrls) {
                        u.media(toMedia(ref));
                    }
                })
                .call()
                .content();
    }

    private Media toMedia(String urlOrDataUri) {
        if (urlOrDataUri.startsWith("data:")) {
            int comma = urlOrDataUri.indexOf(',');
            int semicolon = urlOrDataUri.indexOf(';');
            String mime = urlOrDataUri.substring(5, semicolon);
            byte[] bytes = Base64.getDecoder().decode(urlOrDataUri.substring(comma + 1));
            return Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType(mime))
                    .data(new ByteArrayResource(bytes))
                    .build();
        }
        try {
            return Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(java.net.URI.create(urlOrDataUri))
                    .build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to wrap reference URL as Media: " + urlOrDataUri, e);
        }
    }
}
