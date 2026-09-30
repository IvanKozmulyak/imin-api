package com.imin.iminapi.predictor.jobs;

import com.imin.iminapi.predictor.model.PredictorJob;

/**
 * Runs one {@code predictor_job} kind. Must be idempotent: a job whose lease expires mid-run
 * is handed out again, so delivery is at-least-once.
 */
public interface PredictorJobHandler {

    String kind();

    void run(PredictorJob job);
}
