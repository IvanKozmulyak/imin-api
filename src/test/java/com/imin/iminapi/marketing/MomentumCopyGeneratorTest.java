package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.marketing.model.MomentumTriggerType;
import com.imin.iminapi.marketing.service.MomentumCopyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MomentumCopyGeneratorTest {

    @Test
    void carriesPosterUrlAndSegmentIntoPayloadAndUsesLlmCopy() {
        // Stub the ChatClient fluent chain to return LLM copy without a real call.
        ChatClient chat = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.entity(any(Class.class))).thenReturn(
                new MomentumDraftPayload("40 tickets left", "Doors 9pm",
                        "Only 40 tickets remain — grab yours.", null, null, null));

        MomentumCopyGenerator gen = new MomentumCopyGenerator(chat);

        MomentumDraftPayload out = gen.generate(
                MomentumTriggerType.URGENCY_72H,
                "Neon Nights",
                "2026-08-01",
                "Warehouse 9, Kyiv",
                "72 hours left, 40 tickets remain",
                "https://cdn.imin.wtf/ai-posters/abc.png",  // event poster
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")); // segmentId

        assertThat(out.subject()).isEqualTo("40 tickets left");
        assertThat(out.bodyMd()).contains("40 tickets remain");
        // The generator overrides posterUrl/segmentId onto the LLM output (LLM never sets them).
        assertThat(out.posterUrl()).isEqualTo("https://cdn.imin.wtf/ai-posters/abc.png");
        assertThat(out.segmentId()).isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    @Test
    void nullPosterUrlStaysNull() {
        ChatClient chat = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.entity(any(Class.class))).thenReturn(
                new MomentumDraftPayload("Announcing Neon Nights", null, "We're live.", null, null, null));

        MomentumCopyGenerator gen = new MomentumCopyGenerator(chat);
        MomentumDraftPayload out = gen.generate(
                MomentumTriggerType.LAUNCH_PUSH, "Neon Nights", "2026-08-01",
                "Warehouse 9, Kyiv", "on-sale 48h ago", null, null);

        assertThat(out.posterUrl()).isNull();
        assertThat(out.subject()).isEqualTo("Announcing Neon Nights");
    }
}
