package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdeogramClientTest {

    @Mock ReplicateClient replicateClient;

    @Test
    void generate_buildsExpectedInputMap() {
        when(replicateClient.runAndAwaitImageUrl(eq("ideogram-ai/ideogram-v3-turbo"), any()))
                .thenReturn("https://replicate.delivery/xyz.png");

        IdeogramClient client = new IdeogramClient(
                replicateClient,
                "ideogram-ai/ideogram-v3-turbo",
                "ideogram-ai/ideogram-v3-quality");

        IdeogramClient.IdeogramResult result = client.generate(
                "a \"VOID\" poster",
                "3:4",
                List.of("https://r2.example/ref1.jpg", "https://r2.example/ref2.jpg"),
                42L,
                "Design");

        assertThat(result.imageUrl()).isEqualTo("https://replicate.delivery/xyz.png");
        assertThat(result.seed()).isEqualTo(42L);
        assertThat(result.model()).isEqualTo("ideogram-ai/ideogram-v3-turbo");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).runAndAwaitImageUrl(eq("ideogram-ai/ideogram-v3-turbo"), captor.capture());
        Map<String, Object> input = captor.getValue();

        assertThat(input).containsEntry("prompt", "a \"VOID\" poster");
        assertThat(input).containsEntry("aspect_ratio", "3:4");
        // Ideogram V3 requires "Auto"/"General" when style_reference_images is used,
        // so the requested "Design" is overridden at the API boundary.
        assertThat(input).containsEntry("style_type", "Auto");
        assertThat(input).containsEntry("magic_prompt_option", "Off");
        assertThat(input).containsEntry("seed", 42L);
        assertThat(input).containsEntry("style_reference_images",
                List.of("https://r2.example/ref1.jpg", "https://r2.example/ref2.jpg"));
    }

    @Test
    void generate_keepsRequestedStyleTypeWhenNoReferences() {
        when(replicateClient.runAndAwaitImageUrl(any(), any())).thenReturn("https://x/y.png");

        IdeogramClient client = new IdeogramClient(replicateClient, "turbo", "quality");
        client.generate("prompt", "1:1", List.of(), 7L, "Design");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).runAndAwaitImageUrl(eq("turbo"), captor.capture());
        assertThat(captor.getValue()).containsEntry("style_type", "Design");
    }

    @Test
    void generate_omitsStyleReferenceImagesWhenEmpty() {
        when(replicateClient.runAndAwaitImageUrl(any(), any())).thenReturn("https://x/y.png");

        IdeogramClient client = new IdeogramClient(replicateClient, "turbo", "quality");
        client.generate("prompt", "1:1", List.of(), 7L, "Design");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).runAndAwaitImageUrl(eq("turbo"), captor.capture());
        assertThat(captor.getValue()).doesNotContainKey("style_reference_images");
    }

    @Test
    void generateWithQualityTier_usesQualityModel() {
        when(replicateClient.runAndAwaitImageUrl(eq("quality-model"), any())).thenReturn("https://x/q.png");

        IdeogramClient client = new IdeogramClient(replicateClient, "turbo-model", "quality-model");
        IdeogramClient.IdeogramResult result = client.generateWithQualityTier(
                "p", "4:5", List.of(), 1L, "Design");

        assertThat(result.model()).isEqualTo("quality-model");
    }
}
