package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.marketing.model.MomentumTriggerType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Generates Momentum campaign copy once, via Spring AI (Claude over OpenRouter).
 * Mirrors ConceptOverviewLlm: prompt -> typed entity. Text-only (spec §6.3); the
 * event's existing 4:5 poster is carried through as the email header where present.
 */
@Service
public class MomentumCopyGenerator {

    private final ChatClient chat;

    public MomentumCopyGenerator(ChatClient chat) {
        this.chat = chat;
    }

    public MomentumDraftPayload generate(MomentumTriggerType trigger,
                                         String eventName,
                                         String eventDate,
                                         String venue,
                                         String triggerContext,
                                         String posterUrl,
                                         UUID segmentId) {
        String prompt = """
                You are drafting a marketing email for an event on a ticketing app.
                Trigger: %s. Context: %s.
                Write concise, on-brand copy. Return JSON ONLY matching this schema:

                {
                  "subject": "<email subject, max 60 chars, no emoji spam>",
                  "preheader": "<preheader line, max 90 chars>",
                  "bodyMd": "<markdown body, 2-4 short paragraphs, second person>",
                  "why": "<one sentence explaining this send to the organizer>"
                }

                Event name: %s
                Event date: %s
                Venue: %s
                """.formatted(
                        trigger.wireValue(),
                        triggerContext,
                        eventName,
                        eventDate == null ? "(unspecified)" : eventDate,
                        venue == null ? "(unspecified)" : venue);

        MomentumDraftPayload llm = chat.prompt().user(prompt).call().entity(MomentumDraftPayload.class);

        // The LLM sets subject/preheader/bodyMd/why; we own posterUrl + segmentId.
        return new MomentumDraftPayload(
                llm.subject(),
                llm.preheader(),
                llm.bodyMd(),
                segmentId == null ? null : segmentId.toString(),
                posterUrl,
                llm.why());
    }
}
