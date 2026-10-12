package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.PosterTextSpec;
import com.imin.iminapi.service.poster.PosterTextValidationClient.Seen;
import com.imin.iminapi.service.poster.PosterTextValidationClient.Transcript;
import com.imin.iminapi.service.poster.PosterTextValidationClient.UnusableReadException;
import com.imin.iminapi.service.poster.PosterTextValidationService.EdgeOutcome;
import com.imin.iminapi.service.poster.PosterTextValidationService.ReadOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PosterTextValidationServiceTest {

    private static final PosterTextSpec SPEC = new PosterTextSpec(
            List.of("BIG NIGHT", "7 JUN 2026"), List.of("BIG NIGHT", "7 JUN 2026", "BERLIN"), "prompt");
    private static final Transcript CLEAN_READ = transcript("BIG NIGHT", "7 JUN 2026");
    private static final Transcript NOTHING = new Transcript(List.of(), List.of());
    private static final List<Transcript> CLEAN_EDGES = List.of(NOTHING, NOTHING, NOTHING, NOTHING);
    private static final byte[] PNG = png();

    private static Transcript transcript(String... texts) {
        return new Transcript(Stream.of(texts).map(t -> new Seen(t, 1)).toList(), List.of());
    }

    private static byte[] png() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(100, 125, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void disabledValidation_doesNotCallClient() {
        FakeClient client = new FakeClient(() -> { throw new IllegalStateException("boom"); }, () -> CLEAN_EDGES);

        var decision = new PosterTextValidationService(client, false, 0, true).validateOrExplain(PNG, SPEC);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.read()).isEqualTo(ReadOutcome.NOT_RUN);
        assertThat(client.reads).isZero();
    }

    @Test
    void acceptsWithoutCallingClient_whenNoTextContractAtAll() {
        FakeClient client = new FakeClient(() -> transcript("GLITCH"), () -> CLEAN_EDGES);

        var decision = new PosterTextValidationService(client, true, 0, true)
                .validateOrExplain(PNG, new PosterTextSpec(List.of(), List.of(), "prompt"));

        assertThat(decision.accepted()).isTrue();
        assertThat(client.reads).isZero();
    }

    // Catches: invented text shipping on a poster that carries only optional text.
    @Test
    void runsGate_whenRequiredEmptyButOptionalTextAllowed() {
        FakeClient client = new FakeClient(() -> transcript("BERLIN", "GLITCH"), () -> CLEAN_EDGES);

        var decision = new PosterTextValidationService(client, true, 0, true)
                .validateOrExplain(PNG, new PosterTextSpec(List.of(), List.of("BERLIN"), "prompt"));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.extraText()).containsExactly("GLITCH");
        assertThat(client.reads).isEqualTo(1);
    }

    // Catches: a lowercase date passing because the gate dropped the spec's exact list.
    @Test
    void exactStrings_fromTheSpec_reachTheMatcher() {
        PosterTextSpec spec = new PosterTextSpec(SPEC.required(), SPEC.allowed(), "prompt", List.of("7 JUN 2026"));
        FakeClient client = new FakeClient(() -> transcript("BIG NIGHT", "7 jun 2026"), () -> CLEAN_EDGES);

        var decision = new PosterTextValidationService(client, true, 0, false).validateOrExplain(PNG, spec);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.missingRequired()).containsExactly("7 JUN 2026");
    }

    // Catches: a truncated or failed read accepted as "no text found", or recorded without saying why.
    @ParameterizedTest(name = "{0}")
    @MethodSource("readFailures")
    void unreadableMainPass_isNotAccepted_andRecordsWhy(String name, RuntimeException failure, ReadOutcome expected) {
        FakeClient client = new FakeClient(() -> { throw failure; }, () -> CLEAN_EDGES);

        var decision = new PosterTextValidationService(client, true, 0, true).validateOrExplain(PNG, SPEC);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.evaluated()).isFalse();
        assertThat(decision.read()).isEqualTo(expected);
        assertThat(decision.edge()).isEqualTo(EdgeOutcome.SKIPPED);
        assertThat(client.edgeReads).isZero();
    }

    static Stream<Arguments> readFailures() {
        return Stream.of(
                Arguments.of("unusable reply", new UnusableReadException("truncated"), ReadOutcome.UNUSABLE),
                Arguments.of("transport error", new ResourceAccessException("timeout"), ReadOutcome.FAILED));
    }

    // Catches: a paid edge call spent on a poster the main pass already rejected.
    @Test
    void mainPassRejects_edgePassNeverSent() {
        FakeClient client = new FakeClient(() -> transcript("BIG NIGHT"), () -> CLEAN_EDGES);

        var decision = new PosterTextValidationService(client, true, 0, true).validateOrExplain(PNG, SPEC);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.missingRequired()).containsExactly("7 JUN 2026");
        assertThat(decision.edge()).isEqualTo(EdgeOutcome.SKIPPED);
        assertThat(client.edgeReads).isZero();
    }

    // Catches: margin fine print shipping on a clean main read, and an edge-pass outage rejecting
    // every poster (the edge pass only adds checks; when it cannot answer the main verdict stands).
    @ParameterizedTest(name = "{0}")
    @MethodSource("edgeOutcomes")
    void mainPassClean_edgeOutcomeDecides(String name, boolean edgeEnabled, byte[] image,
                                          Supplier<List<Transcript>> edges, boolean accepted, EdgeOutcome expected) {
        FakeClient client = new FakeClient(() -> CLEAN_READ, edges);

        var decision = new PosterTextValidationService(client, true, 0, edgeEnabled).validateOrExplain(image, SPEC);

        assertThat(decision.accepted()).isEqualTo(accepted);
        assertThat(decision.evaluated()).isTrue();
        assertThat(decision.read()).isEqualTo(ReadOutcome.READ);
        assertThat(decision.edge()).isEqualTo(expected);
        assertThat(decision.missingRequired()).isEmpty();
        assertThat(decision.extraText()).isEqualTo(accepted ? List.of() : List.of("[edge bottom: © ODD MOOD CO.]"));
        assertThat(client.edgeReads).isEqualTo(edgeEnabled && image == PNG ? 1 : 0);
    }

    static Stream<Arguments> edgeOutcomes() {
        Supplier<List<Transcript>> clean = () -> CLEAN_EDGES;
        Supplier<List<Transcript>> credit = () -> List.of(NOTHING, transcript("© ODD MOOD CO."), NOTHING, NOTHING);
        Supplier<List<Transcript>> unusable = () -> { throw new UnusableReadException("truncated"); };
        Supplier<List<Transcript>> down = () -> { throw new ResourceAccessException("timeout"); };
        return Stream.of(
                Arguments.of("edge pass off", false, PNG, clean, true, EdgeOutcome.OFF),
                Arguments.of("edges clean", true, PNG, clean, true, EdgeOutcome.CLEAN),
                Arguments.of("credit line in an edge", true, PNG, credit, false, EdgeOutcome.MARKS),
                Arguments.of("edge reply unusable", true, PNG, unusable, true, EdgeOutcome.UNUSABLE),
                Arguments.of("edge call failed", true, PNG, down, true, EdgeOutcome.FAILED),
                Arguments.of("image not decodable", true, new byte[]{1, 2, 3}, clean, true, EdgeOutcome.FAILED));
    }

    // Catches: one stray word in the main read and one in the margins both slipping under a tolerance of one.
    @Test
    void extraTolerance_countsMainAndEdgeTogether() {
        FakeClient client = new FakeClient(() -> transcript("BIG NIGHT", "7 JUN 2026", "GLITCH"),
                () -> List.of(NOTHING, transcript("© ODD MOOD CO."), NOTHING, NOTHING));
        FakeClient mainOnly = new FakeClient(() -> transcript("BIG NIGHT", "7 JUN 2026", "GLITCH"), () -> CLEAN_EDGES);

        var both = new PosterTextValidationService(client, true, 1, true).validateOrExplain(PNG, SPEC);
        var one = new PosterTextValidationService(mainOnly, true, 1, true).validateOrExplain(PNG, SPEC);

        assertThat(both.accepted()).isFalse();
        assertThat(both.extraText()).containsExactly("GLITCH", "[edge bottom: © ODD MOOD CO.]");
        assertThat(both.edge()).isEqualTo(EdgeOutcome.MARKS);
        assertThat(one.accepted()).isTrue();
        assertThat(one.extraText()).containsExactly("GLITCH");
    }

    private static final class FakeClient implements PosterTextValidationClient {
        private final Supplier<Transcript> read;
        private final Supplier<List<Transcript>> edges;
        private int reads;
        private int edgeReads;

        private FakeClient(Supplier<Transcript> read, Supplier<List<Transcript>> edges) {
            this.read = read;
            this.edges = edges;
        }

        @Override
        public Transcript read(byte[] png) {
            reads++;
            return read.get();
        }

        @Override
        public List<Transcript> readEdges(List<PosterEdgeBands.Band> bands) {
            edgeReads++;
            return edges.get();
        }
    }
}
