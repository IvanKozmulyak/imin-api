package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.QuestionBank.Question;

/** Reads a required numeric {@code params} entry of a bank question. */
final class Params {

    private Params() {}

    static Number of(Question q, String key) {
        Number n = q.params().get(key);
        if (n == null) throw new IllegalStateException("predictor question " + q.id() + ": missing params." + key);
        return n;
    }
}
