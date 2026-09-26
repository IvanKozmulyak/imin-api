package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audience.service.ReservedConsentSources;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Every source the plan engine trusts as system evidence must be closed to organizer capture. */
class ReservedConsentSourcesSyncTest {

    @Test
    void everyExplicitSourceInShippedLogicIsReserved() throws IOException {
        ClassLoader cl = getClass().getClassLoader();
        try (InputStream logic = cl.getResourceAsStream("audienceplan/logic-v1.yaml");
             InputStream priors = cl.getResourceAsStream("audienceplan/priors-v1.yaml");
             InputStream genres = cl.getResourceAsStream("audienceplan/genres-v1.yaml")) {
            Set<String> explicit = LogicLoader.parse(logic, priors, genres).logic().legal().explicitSources().keySet();
            assertThat(explicit).isNotEmpty().allMatch(ReservedConsentSources::isReserved);
        }
    }

    @Test
    void everyPersonConsentSourceIsReserved() {
        assertThat(FanFeatureCalculator.PERSON_CONSENT_SOURCES)
                .isNotEmpty().allMatch(ReservedConsentSources::isReserved);
    }
}
