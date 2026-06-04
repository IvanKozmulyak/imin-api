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
        PosterTextValidationService service = new PosterTextValidationService(client, true);
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
        PosterTextValidationService service = new PosterTextValidationService(client, true);
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
        PosterTextValidationService service = new PosterTextValidationService(client, false);
        PosterTextSpec spec = new PosterTextSpec(List.of("A"), List.of("A"), "prompt");

        var decision = service.validateOrExplain(new byte[]{1}, spec);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isNull();
        assertThat(client.calls).isZero();
    }

    @Test
    void acceptsWithoutCallingClient_whenNoRequiredText() {
        FakeValidationClient client = new FakeValidationClient(
                new PosterTextValidationClient.ValidationResult(false, List.of("A"), List.of()));
        PosterTextValidationService service = new PosterTextValidationService(client, true);
        PosterTextSpec spec = new PosterTextSpec(List.of(), List.of("BERLIN"), "prompt");

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
}
