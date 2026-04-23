package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.ai.ConceptOverview;
import com.imin.iminapi.dto.ai.ConceptRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ConceptOverviewLlm {

    private final ChatClient chat;

    public ConceptOverviewLlm(ChatClient chat) { this.chat = chat; }

    public ConceptOverview generate(ConceptRequest req, PosterConcept poster) {
        String prompt = """
                You are naming and describing an event for a ticketing app.
                Return JSON ONLY matching this schema:

                {
                  "name": "<short evocative event name, max 6 words>",
                  "description": "<one paragraph, 30-60 words, second person>",
                  "palette_hexes": ["#rrggbb", "#rrggbb", "#rrggbb", "#rrggbb"],
                  "suggested_capacity": <integer 50-2000>,
                  "confidence_pct": <integer 50-95>
                }

                Vibe: %s
                Genre: %s
                City: %s
                Capacity hint: %s
                Visual sub-style tag: %s
                Color palette description: %s
                """.formatted(
                        req.vibe(),
                        req.genre() == null ? "(unspecified)" : req.genre(),
                        req.city() == null ? "(unspecified)" : req.city(),
                        req.capacity() == null ? "(unspecified)" : req.capacity().toString(),
                        poster.subStyleTag(),
                        poster.colorPaletteDescription());

        return chat.prompt().user(prompt).call().entity(ConceptOverview.class);
    }
}
