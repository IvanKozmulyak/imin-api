package com.imin.iminapi.service.poster;

import com.imin.iminapi.service.poster.PosterTextValidationClient.Seen;
import com.imin.iminapi.service.poster.PosterTextValidationClient.Transcript;
import com.imin.iminapi.service.poster.PosterTextValidationClient.UnusableReadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenRouterPosterTextValidationClientTest {

    // Catches: a ghost-repeated title counted once (or zero times) because the model omitted or
    // garbled "count", and a truncated reply read as "no text on the poster".
    @Test
    void parseTranscript_and_parseEdges() {
        Transcript t = OpenRouterPosterTextValidationClient.parseTranscript("""
                {"texts":[{"text":"BEAN SOCIETY","count":5},{"text":"31 OCT 2026"},
                          {"text":"A","count":0},{"text":"B","count":99}],
                 "unreadable":["fine print, bottom-left"]}""");

        assertThat(t.texts()).containsExactly(new Seen("BEAN SOCIETY", 5), new Seen("31 OCT 2026", 1),
                new Seen("A", 1), new Seen("B", 10));
        assertThat(t.unreadable()).containsExactly("fine print, bottom-left");

        List<Transcript> edges = OpenRouterPosterTextValidationClient.parseEdges("""
                {"top":{"texts":[],"unreadable":[]},
                 "bottom":{"texts":[{"text":"© ODD MOOD CO.","count":1}],"unreadable":[]},
                 "left":{"texts":[],"unreadable":["vertical marks"]},
                 "right":{"texts":[],"unreadable":[]}}""");

        assertThat(edges).hasSize(4);
        assertThat(edges.get(1).texts()).containsExactly(new Seen("© ODD MOOD CO.", 1));
        assertThat(edges.get(2).unreadable()).containsExactly("vertical marks");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"unreadable\":[]}",
            "{\"texts\":[{\"text\":\"BEAN",
            "not json"})
    void unusableTranscript_isRejected_notReadAsEmpty(String content) {
        assertThatThrownBy(() -> OpenRouterPosterTextValidationClient.parseTranscript(content))
                .isInstanceOf(UnusableReadException.class);
    }

    @Test
    void edgeReplyMissingABand_isUnusable() {
        assertThatThrownBy(() -> OpenRouterPosterTextValidationClient.parseEdges(
                "{\"top\":{\"texts\":[],\"unreadable\":[]},\"bottom\":{\"texts\":[],\"unreadable\":[]},"
                        + "\"left\":{\"texts\":[],\"unreadable\":[]}}"))
                .isInstanceOf(UnusableReadException.class);
    }
}
