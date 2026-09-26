package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class FanFeatureRecomputeH2Test extends FanFeatureRecomputeQueriesContract {
}
