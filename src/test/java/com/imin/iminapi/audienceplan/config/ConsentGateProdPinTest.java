package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Prod mails only ConsentGate-proven contacts from every campaign, whatever the Railway variable says. */
class ConsentGateProdPinTest {

    static Stream<Arguments> railwayVariable() {
        return Stream.of(
                arguments("absent", new String[0]),
                arguments("false", new String[] {"IMIN_CONSENT_GATE_ALL_CAMPAIGNS=false"}),
                arguments("blank", new String[] {"IMIN_CONSENT_GATE_ALL_CAMPAIGNS="}));
    }

    @ParameterizedTest(name = "IMIN_CONSENT_GATE_ALL_CAMPAIGNS {0}")
    @MethodSource("railwayVariable")
    void prodBindsTheConsentGateOn(String name, String[] env) {
        ShippedYaml.prod().withPropertyValues(env).run(ctx -> assertThat(
                ctx.getBean(AudiencePlanProperties.class).getConsentGateAllCampaigns()).isTrue());
    }
}
