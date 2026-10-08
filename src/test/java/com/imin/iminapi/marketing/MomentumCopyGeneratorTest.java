package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.marketing.model.MomentumTriggerType;
import com.imin.iminapi.marketing.service.MomentumCopyGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MomentumCopyGeneratorTest {

    /** The generator sets posterUrl and segmentId itself, so the model never picks the recipients; null stays null. */
    @ParameterizedTest(name = "poster={0} segment={1}")
    @CsvSource({
            "https://cdn.imin.wtf/ai-posters/abc.png, 00000000-0000-0000-0000-000000000001",
            ",", // an empty column is null
    })
    void carriesPosterUrlAndSegmentIntoPayloadAndUsesLlmCopy(String posterUrl, UUID segmentId) {
        // Stub the ChatClient fluent chain to return LLM copy without a real call; the LLM sets neither field.
        ChatClient chat = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.entity(any(Class.class))).thenReturn(
                new MomentumDraftPayload("40 tickets left", "Doors 9pm",
                        "Only 40 tickets remain — grab yours.", "llm-segment", "https://llm.example/poster.png", null));

        MomentumCopyGenerator gen = new MomentumCopyGenerator(chat);

        MomentumDraftPayload out = gen.generate(
                MomentumTriggerType.URGENCY_72H,
                "Neon Nights",
                "2026-08-01",
                "Warehouse 9, Kyiv",
                "72 hours left, 40 tickets remain",
                posterUrl,   // event poster
                segmentId);

        assertThat(out.subject()).isEqualTo("40 tickets left");
        assertThat(out.bodyMd()).contains("40 tickets remain");
        assertThat(out.posterUrl()).isEqualTo(posterUrl);
        assertThat(out.segmentId()).isEqualTo(segmentId == null ? null : segmentId.toString());
    }
}
