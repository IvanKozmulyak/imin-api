package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.LanguageTier;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot;
import com.imin.iminapi.predictor.service.Stage0Scorer;
import com.imin.iminapi.predictor.service.Stage0Scorer.Stage0Output;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Stage 0 scorer (task 86cav474p): one structured call, model-id fallback, honest prompt content. */
class Stage0ScorerTest {

    private final ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final PredictorProperties props = new PredictorProperties();
    private final Stage0Scorer sut = new Stage0Scorer(chat, props, "platform/default-model");

    private PredictionInputSnapshot snap() {
        return new PredictionInputSnapshot(
                PredictionInputSnapshot.SNAPSHOT_VERSION, UUID.randomUUID(),
                null, "NL", "techno", 250, "B101_300",
                "2026-07-18T20:00:00Z", 6, "SUMMER", 47, "EUR",
                List.of(new PredictionInputSnapshot.TierLine("Door", 2400, 250, null, null)),
                List.of(), null, 4, false, List.of(),
                new PredictionInputSnapshot.CorpusLine("DROP_SEASON", 6, 0, 6, List.of(), null));
    }

    @Test
    void modelIdFallsBackToPlatformDefaultWhenUnset() {
        assertThat(sut.modelId()).isEqualTo("platform/default-model");
        props.setModel("override/model");
        assertThat(sut.modelId()).isEqualTo("override/model");
    }

    @Test
    void scoreSendsOneStructuredCallWithHonestPrompt() {
        Stage0Output out = new Stage0Output(new PredictionResult.Band(20, 55), null, null,
                List.of(), List.of());
        when(chat.prompt().options(any()).user(anyString()).call().entity(Stage0Output.class)).thenReturn(out);

        Stage0Output got = sut.score(snap(), LanguageTier.C,
                List.of("previous error: attendance over capacity"));

        assertThat(got).isSameAs(out);
        // Deep-stub note: the when(...) arrangement itself registers one .user() invocation,
        // so the real call is the SECOND one — capture both, assert on the last.
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chat.prompt().options(any()), org.mockito.Mockito.times(2)).user(prompt.capture());
        String p = prompt.getAllValues().get(prompt.getAllValues().size() - 1);
        assertThat(p).contains("RANGES ONLY");                        // hard instruction
        assertThat(p).contains("LANGUAGE TIER").contains("C");        // tier passed in, not chosen
        assertThat(p).contains("WHAT IS UNKNOWN");                    // unknowns enumerated
        assertThat(p).contains("city (draft has no venue city yet)"); // null field listed
        assertThat(p).contains("Density: 6 comparable");              // corpus honesty
        assertThat(p).contains("previous error: attendance over capacity"); // retry feedback
        assertThat(p).contains("holiday");                            // coverage gap named
    }

    @Test
    void nullEntityIsAFailureNotASilentPass() {
        when(chat.prompt().options(any()).user(anyString()).call().entity(Stage0Output.class)).thenReturn(null);
        assertThatThrownBy(() -> sut.score(snap(), LanguageTier.B, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
