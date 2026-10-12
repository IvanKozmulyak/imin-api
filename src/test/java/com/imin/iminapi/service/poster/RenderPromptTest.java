package com.imin.iminapi.service.poster;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RenderPromptTest {

    // Catches: text placed late in the prompt is dropped from the render (the date vanished in every
    // lab render built that way), and a topless hero ships without the closing clothing clause.
    @ParameterizedTest(name = "typeStyle=[{0}]")
    @CsvSource(value = {
            "'Ultra-condensed grotesque, all caps.'|S1 Gritty concrete room under one hard light. Type: Ultra-condensed grotesque, all caps. Every person shown is fully clothed.",
            "'  '|S1 Gritty concrete room under one hard light. Every person shown is fully clothed.",
            "|S1 Gritty concrete room under one hard light. Every person shown is fully clothed."
    }, delimiter = '|')
    void composes_textSentenceFirst_visual_typeClause_clothingLast(String typeStyle, String expected) {
        assertThat(RenderPrompt.compose("S1", "  Gritty concrete room under one hard light. ", typeStyle))
                .isEqualTo(expected);
    }
}
