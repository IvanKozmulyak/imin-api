package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceImageAnalyzerTest {

    @Test
    void analyze_buildsMultimodalPromptAndReturnsResponse() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(
                "Black and magenta neon palette with distressed serif title type. " +
                "Smoky underground atmosphere, asymmetric composition with strong rule-of-thirds.");

        ReferenceImageAnalyzer analyzer = new ReferenceImageAnalyzer(chatClient, "openai/gpt-4o-mini");

        String descriptor = analyzer.analyze("neon_underground", List.of(
                "data:image/png;base64,AAAA",
                "data:image/png;base64,BBBB"));

        assertThat(descriptor).contains("magenta neon").contains("distressed");
        verify(chatClient).prompt();
        verify(requestSpec).user(any(Consumer.class));
        verify(requestSpec).call();
    }

    @Test
    void analyze_emptyReferenceList_returnsEmptyDescriptorWithoutCallingClient() {
        ChatClient chatClient = mock(ChatClient.class);
        ReferenceImageAnalyzer analyzer = new ReferenceImageAnalyzer(chatClient, "openai/gpt-4o-mini");

        String descriptor = analyzer.analyze("nonexistent", List.of());

        assertThat(descriptor).isEmpty();
        verify(chatClient, never()).prompt();
    }
}
