package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.PosterTextSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The HARD text gate. The vision model transcribes the poster; {@link PosterTextMatcher} decides
 * what is missing or extra. A clean main read gets a second read of the enlarged edge strips, where
 * fine print and vertical credit lines hide.
 */
@Service
public class PosterTextValidationService {
    private static final Logger log = LoggerFactory.getLogger(PosterTextValidationService.class);

    private final PosterTextValidationClient client;
    private final boolean enabled;
    private final int maxExtraTextItems;
    private final boolean edgePassEnabled;

    public PosterTextValidationService(
            PosterTextValidationClient client,
            @Value("${poster.text-validation.enabled:true}") boolean enabled,
            @Value("${poster.text-validation.max-extra-text-items:0}") int maxExtraTextItems,
            @Value("${poster.text-validation.edge-pass.enabled:true}") boolean edgePassEnabled) {
        this.client = client;
        this.enabled = enabled;
        this.maxExtraTextItems = Math.max(0, maxExtraTextItems);
        this.edgePassEnabled = edgePassEnabled;
    }

    public ValidationDecision validateOrExplain(byte[] imageBytes, PosterTextSpec spec) {
        // Run whenever there is any text contract, so invented text cannot ship on a poster with only optional text.
        if (!enabled || (spec.required().isEmpty() && spec.allowed().isEmpty())) {
            return ValidationDecision.pass();
        }

        PosterTextValidationClient.Transcript transcript;
        try {
            transcript = client.read(imageBytes);
        } catch (RuntimeException e) {
            // Never fail open: an unread poster is a non-acceptance, so it is regenerated or ships best-effort.
            ReadOutcome read = e instanceof PosterTextValidationClient.UnusableReadException
                    ? ReadOutcome.UNUSABLE : ReadOutcome.FAILED;
            log.warn("Text gate could not read the poster ({}); treating as not-accepted: {}", read.wire(), e.toString());
            return new ValidationDecision(false, "text validation could not be evaluated: " + e.getMessage(),
                    List.of(), List.of(), false, read, EdgeOutcome.SKIPPED);
        }

        PosterTextMatcher.Match match = PosterTextMatcher.match(transcript, spec.required(), spec.allowed());
        if (!match.missing().isEmpty() || match.extra().size() > maxExtraTextItems) {
            return rejected(match.missing(), match.extra(), EdgeOutcome.SKIPPED);
        }
        if (!edgePassEnabled) {
            return ValidationDecision.accepted(EdgeOutcome.OFF);
        }

        List<String> edgeExtras;
        try {
            edgeExtras = PosterTextMatcher.edgeExtras(
                    client.readEdges(PosterEdgeBands.crop(imageBytes)), spec.required(), spec.allowed());
        } catch (PosterTextValidationClient.UnusableReadException e) {
            // The edge pass only adds checks: when it cannot answer, the main read's verdict stands.
            log.warn("Text gate edge pass returned an unusable read; keeping the main verdict: {}", e.getMessage());
            return ValidationDecision.accepted(EdgeOutcome.UNUSABLE);
        } catch (RuntimeException e) {
            log.warn("Text gate edge pass failed; keeping the main verdict: {}", e.toString());
            return ValidationDecision.accepted(EdgeOutcome.FAILED);
        }

        List<String> allExtra = new ArrayList<>(match.extra());
        allExtra.addAll(edgeExtras);
        if (allExtra.size() > maxExtraTextItems) {
            return rejected(List.of(), allExtra, EdgeOutcome.MARKS);
        }
        return ValidationDecision.accepted(edgeExtras.isEmpty() ? EdgeOutcome.CLEAN : EdgeOutcome.MARKS);
    }

    private static ValidationDecision rejected(List<String> missing, List<String> extra, EdgeOutcome edge) {
        return new ValidationDecision(false, "missing required text: " + missing + "; extra text: " + extra,
                missing, extra, true, ReadOutcome.READ, edge);
    }

    /** What the full-poster read produced; journaled as {@code text.read}. */
    public enum ReadOutcome {
        NOT_RUN, READ, UNUSABLE, FAILED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the edge-strip read produced; journaled as {@code text.edge}. */
    public enum EdgeOutcome {
        OFF, SKIPPED, CLEAN, MARKS, UNUSABLE, FAILED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code evaluated} is false when the gate could not judge the image (no verdict at all). */
    public record ValidationDecision(
            boolean accepted, String reason,
            List<String> missingRequired, List<String> extraText,
            boolean evaluated, ReadOutcome read, EdgeOutcome edge) {

        public ValidationDecision(boolean accepted, String reason,
                                  List<String> missingRequired, List<String> extraText, boolean evaluated) {
            this(accepted, reason, missingRequired, extraText, evaluated,
                    evaluated ? ReadOutcome.READ : ReadOutcome.FAILED, EdgeOutcome.SKIPPED);
        }

        public ValidationDecision(boolean accepted, String reason,
                                  List<String> missingRequired, List<String> extraText) {
            this(accepted, reason, missingRequired, extraText, true);
        }

        /** The gate did not run (disabled, or no text contract). */
        public static ValidationDecision pass() {
            return new ValidationDecision(true, null, List.of(), List.of(), true, ReadOutcome.NOT_RUN, EdgeOutcome.SKIPPED);
        }

        public static ValidationDecision accepted(EdgeOutcome edge) {
            return new ValidationDecision(true, null, List.of(), List.of(), true, ReadOutcome.READ, edge);
        }
    }
}
