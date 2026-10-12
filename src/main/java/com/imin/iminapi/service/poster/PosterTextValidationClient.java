package com.imin.iminapi.service.poster;

import java.util.List;

/** Reads the text drawn on a poster verbatim; it never judges it ({@link PosterTextMatcher} does). */
public interface PosterTextValidationClient {

    /** Every text block on the whole poster. */
    Transcript read(byte[] png);

    /** The text in each edge strip, in the order given (top, bottom, left, right). */
    List<Transcript> readEdges(List<PosterEdgeBands.Band> bands);

    /** One text block as drawn, and how many separate places it appears. */
    record Seen(String text, int count) {}

    /** {@code unreadable} holds short descriptions of letter-like marks that could not be read. */
    record Transcript(List<Seen> texts, List<String> unreadable) {}

    /** The reader answered, but not with a transcript (truncated, malformed or empty reply). */
    class UnusableReadException extends RuntimeException {
        public UnusableReadException(String message) {
            super(message);
        }

        public UnusableReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
