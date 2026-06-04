package com.imin.iminapi.service.poster.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-free unit tests for the pure helpers extracted from {@link StyleCardGenerator}. No Spring
 * context, no network — just {@code stripCodeFences} and {@code sniffMime}.
 */
class StyleCardGeneratorTest {

    @Test
    void stripCodeFences_unwrapsYamlFence() {
        String fenced = "```yaml\nmedium: photo\npalette:\n  - [10, 20, 30]\n```";

        String result = StyleCardGenerator.stripCodeFences(fenced);

        assertThat(result).isEqualTo("medium: photo\npalette:\n  - [10, 20, 30]");
    }

    @Test
    void stripCodeFences_unwrapsYmlFence() {
        String fenced = "```yml\nmedium: collage\n```";

        assertThat(StyleCardGenerator.stripCodeFences(fenced)).isEqualTo("medium: collage");
    }

    @Test
    void stripCodeFences_unwrapsBarePlainFence() {
        String fenced = "```\nmedium: illustration\n```";

        assertThat(StyleCardGenerator.stripCodeFences(fenced)).isEqualTo("medium: illustration");
    }

    @Test
    void stripCodeFences_passesThroughUnfencedContent() {
        String plain = "medium: mixed\naccents:\n  - heavy film grain";

        assertThat(StyleCardGenerator.stripCodeFences(plain)).isEqualTo(plain);
    }

    @Test
    void stripCodeFences_trimsSurroundingWhitespace() {
        String padded = "\n\n  ```yaml\nmedium: photo\n```  \n";

        assertThat(StyleCardGenerator.stripCodeFences(padded)).isEqualTo("medium: photo");
    }

    @Test
    void stripCodeFences_handlesNullAndBlank() {
        assertThat(StyleCardGenerator.stripCodeFences(null)).isEmpty();
        assertThat(StyleCardGenerator.stripCodeFences("   ")).isEmpty();
    }

    @Test
    void sniffMime_detectsJpeg() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};

        assertThat(StyleCardGenerator.sniffMime(jpeg)).isEqualTo("image/jpeg");
    }

    @Test
    void sniffMime_detectsPng() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

        assertThat(StyleCardGenerator.sniffMime(png)).isEqualTo("image/png");
    }

    @Test
    void sniffMime_detectsWebp() {
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

        assertThat(StyleCardGenerator.sniffMime(webp)).isEqualTo("image/webp");
    }

    @Test
    void sniffMime_defaultsToPngForUnknownOrShortInput() {
        assertThat(StyleCardGenerator.sniffMime(new byte[] {0x00, 0x01, 0x02, 0x03})).isEqualTo("image/png");
        assertThat(StyleCardGenerator.sniffMime(new byte[] {0x00})).isEqualTo("image/png");
        assertThat(StyleCardGenerator.sniffMime(null)).isEqualTo("image/png");
    }
}
