package com.imin.iminapi.audienceplan.opendata;

import java.time.Duration;

/** Blocking wait, injectable so throttle and backoff tests run without real sleeps. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    Sleeper REAL = d -> Thread.sleep(d.toMillis());
}
