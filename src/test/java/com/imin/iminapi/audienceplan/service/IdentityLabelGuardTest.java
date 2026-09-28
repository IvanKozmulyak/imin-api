package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityLabelGuardTest {

    private final IdentityLabelGuard guard = new IdentityLabelGuard(List.of());

    @ParameterizedTest
    @ValueSource(strings = {
            "Muslim techno fans in Metz", "The African diaspora of Nancy", "Queer ravers", "LGBTQ collective members",
            "Latino families", "Arab students", "Black clubbers", "Catholic youth groups", "Jewish community",
            "People with disabilities", "Mental health support circles", "Left-wing activists",
            "Communauté africaine de Metz", "Étudiants musulmans", "Immigrés de Thionville", "Trans ravers",
            "Refugees in Nancy", "Political campaigners", "Gay bars crowd", "Religious youth",
    })
    void identityLabels_areRefused(String text) {
        assertThat(guard.labelsIdentity(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Techno regulars of Metz", "Students of the University of Lorraine", "Cross-border workers in Luxembourg",
            "Afrobeats and amapiano fans", "Latin & afrobeats night-goers", "Trans-border commuters",
            "Arabic music lovers", "Blackout party crowd", "Healthy brunch-rave crowd",
            "Young professionals aged 25 to 35",
    })
    void tasteGeographyAgeAndWork_pass(String text) {
        assertThat(guard.labelsIdentity(text)).as(text).isFalse();
    }

    @Test
    void nullOrBlank_isClean() {
        assertThat(guard.labelsIdentity(null)).isFalse();
        assertThat(guard.labelsIdentity("  ")).isFalse();
    }

    @Test
    void caseAndAccents_areIgnored() {
        assertThat(guard.labelsIdentity("MUSULMANS")).isTrue();
        assertThat(guard.labelsIdentity("Santé mentale")).isTrue();
    }

    @Test
    void theGenresFileForbiddenTerms_areRefusedToo() throws Exception {
        IdentityLabelGuard onlyExtra = new IdentityLabelGuard(List.of("zorblax"));
        assertThat(onlyExtra.labelsIdentity("The zorblax crowd")).isTrue();

        AudiencePlanLogic logic;
        try (InputStream l = res("audienceplan/logic-v1.yaml"); InputStream p = res("audienceplan/priors-v1.yaml");
             InputStream g = res("audienceplan/genres-v1.yaml")) {
            logic = LogicLoader.parse(l, p, g);
        }
        IdentityLabelGuard fromLogic = new IdentityLabelGuard(logic);
        assertThat(logic.genres().forbiddenTerms()).contains("halal");
        assertThat(fromLogic.labelsIdentity("Halal food truck crowd")).isTrue();
    }

    private static InputStream res(String location) {
        return IdentityLabelGuardTest.class.getClassLoader().getResourceAsStream(location);
    }
}
