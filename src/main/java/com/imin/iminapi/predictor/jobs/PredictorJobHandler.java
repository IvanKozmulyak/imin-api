package com.imin.iminapi.predictor.jobs;

import com.imin.iminapi.predictor.model.PredictorJob;

/**
 * Runs one {@code predictor_job} kind. Must be idempotent: a job whose lease expires mid-run
 * is handed out again, so delivery is at-least-once.
 */
public interface PredictorJobHandler {

    String kind();

    void run(PredictorJob job);

    /**
     * Called after a job of this kind is failed for good outside {@link #run}: its lease expired on the last attempt.
     * Must be idempotent; an exception is logged and dropped.
     */
    default void onTerminalFailure(PredictorJob job) {}
}
