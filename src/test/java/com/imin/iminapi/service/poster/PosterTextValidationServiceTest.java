package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.PosterTextSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PosterTextValidationServiceTest {

    @Test
    void accepts_whenClientAcceptsPosterText() {
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(true, List.of(), List.of()));
        PosterTextValidationService service = new PosterTextValidationService(client, true, 0);
        PosterTextSpec spec = new PosterTextSpec(
                List.of("BIG NIGHT - BERLIN", "7 JUN 2026"),
                List.of("BIG NIGHT - BERLIN", "7 JUN 2026", "BERLIN"),
                "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isNull();
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void rejects_whenRequiredTextMissing() {
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(
                        false, List.of("BIG NIGHT - BERLIN"), List.of("BAG NIGKT")));
        PosterTextValidationService service = new PosterTextValidationService(client, true, 0);
        PosterTextSpec spec = new PosterTextSpec(
                List.of("BIG NIGHT - BERLIN"),
                List.of("BIG NIGHT - BERLIN"),
                "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.reason()).contains("missing required text");
        assertThat(decision.reason()).contains("BIG NIGHT - BERLIN");
        assertThat(decision.reason()).contains("extra text");
        assertThat(decision.reason()).contains("BAG NIGKT");
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void disabledValidationAcceptsWithoutCallingClient() {
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(false, List.of("A"), List.of()));
        PosterTextValidationService service = new PosterTextValidationService(client, false, 0);
        PosterTextSpec spec = new PosterTextSpec(List.of("A"), List.of("A"), "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isNull();
        assertThat(client.calls).isZero();
    }

    @Test
    void decision_carriesStructuredMissingAndExtra() {
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(
                        false, List.of("BIG NIGHT - BERLIN"), List.of("BAG NIGKT")));
        PosterTextValidationService service = new PosterTextValidationService(client, true, 0);
        PosterTextSpec spec = new PosterTextSpec(
                List.of("BIG NIGHT - BERLIN"),
                List.of("BIG NIGHT - BERLIN"),
                "prompt");

        PosterTextValidationService.ValidationDecision decision =
                service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.missingRequired()).containsExactly("BIG NIGHT - BERLIN");
        assertThat(decision.extraText()).containsExactly("BAG NIGKT");
    }

    @Test
    void rejects_whenModelAcceptsButInventedTextPresent() {
        // The exact failure from the journal: model returns accepted:true over a poster with garbage text.
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(true, List.of(), List.of("GLITCHWORD")));
        PosterTextValidationService service = new PosterTextValidationService(client, true, 0);
        PosterTextSpec spec = new PosterTextSpec(
                List.of("BIG NIGHT - BERLIN"), List.of("BIG NIGHT - BERLIN"), "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.extraText()).containsExactly("GLITCHWORD");
    }

    @Test
    void rejects_whenModelAcceptsButRequiredMissing() {
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(true, List.of("7 JUN 2026"), List.of()));
        PosterTextValidationService service = new PosterTextValidationService(client, true, 0);
        PosterTextSpec spec = new PosterTextSpec(
                List.of("BIG NIGHT - BERLIN", "7 JUN 2026"),
                List.of("BIG NIGHT - BERLIN", "7 JUN 2026"), "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.missingRequired()).containsExactly("7 JUN 2026");
    }

    @Test
    void toleratesInventedText_upToConfiguredThreshold() {
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(true, List.of(), List.of("X")));
        PosterTextValidationService service = new PosterTextValidationService(client, true, 1);
        PosterTextSpec spec = new PosterTextSpec(
                List.of("BIG NIGHT"), List.of("BIG NIGHT"), "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isTrue();
    }

    @Test
    void runsGate_whenRequiredEmptyButOptionalTextAllowed() {
        // Bypass fix: invented text must still be caught on posters that carry only optional text.
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(false, List.of(), List.of("GLITCH")));
        PosterTextValidationService service = new PosterTextValidationService(client, true, 0);
        PosterTextSpec spec = new PosterTextSpec(List.of(), List.of("BERLIN"), "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.extraText()).containsExactly("GLITCH");
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void shipsAsReject_whenClientThrows() {
        // The journal failure: the text-validation LLM returned truncated JSON (runaway OCR transcription
        // on a texture-heavy poster), so the client threw IllegalStateException. That must NOT abort the
        // variant — it becomes a non-acceptance, which the orchestrator handles via corrective remix /
        // best-effort, exactly like any other gate rejection.
        ThrowingValidationClient client = new ThrowingValidationClient(
                new IllegalStateException("OpenRouter poster text validation returned malformed JSON"));
        PosterTextValidationService service = new PosterTextValidationService(client, true, 0);
        PosterTextSpec spec = new PosterTextSpec(
                List.of("BIG NIGHT - BERLIN"), List.of("BIG NIGHT - BERLIN"), "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.reason()).contains("could not be evaluated");
        assertThat(decision.missingRequired()).isEmpty();
        assertThat(decision.extraText()).isEmpty();
    }

    @Test
    void disabledValidation_doesNotCallClient_evenIfClientWouldThrow() {
        ThrowingValidationClient client = new ThrowingValidationClient(new IllegalStateException("boom"));
        PosterTextValidationService service = new PosterTextValidationService(client, false, 0);
        PosterTextSpec spec = new PosterTextSpec(List.of("A"), List.of("A"), "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isTrue();
    }

    @Test
    void acceptsWithoutCallingClient_whenNoTextContractAtAll() {
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(false, List.of("A"), List.of()));
        PosterTextValidationService service = new PosterTextValidationService(client, true, 0);
        PosterTextSpec spec = new PosterTextSpec(List.of(), List.of(), "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isNull();
        assertThat(client.calls).isZero();
    }

    private static final class FakeValidationClient implements PosterTextValidationClient {
        private final ValidationResult result;
        private int calls;

        private FakeValidationClient(ValidationResult result) {
            this.result = result;
        }

        @Override
        public ValidationResult validate(byte[] imageBytes, PosterTextSpec spec) {
            calls++;
            return result;
        }
    }

    private static final class ThrowingValidationClient implements PosterTextValidationClient {
        private final RuntimeException toThrow;

        private ThrowingValidationClient(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public ValidationResult validate(byte[] imageBytes, PosterTextSpec spec) {
            throw toThrow;
        }
    }
}
