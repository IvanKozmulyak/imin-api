package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryTemplatesTest {

    private static final Instant NOW = Instant.parse("2026-09-26T09:00:00Z");

    @Test
    void warm_en_readsEveryRangeFromThePlan_roundedOutwardAtPrior() {
        AudiencePlanResponse.Summary s = SummaryTemplates.summary(SummaryFixtures.warm(), "en", NOW);

        assertThat(s.headline()).isEqualTo("Your list could bring 25–95 of the 255 tickets you are aiming for.");
        assertThat(s.segmentLines()).containsExactly(
                "Loyal (same genre as this event): 40 can be emailed, 5–30 tickets expected.",
                "Repeat (same genre as this event): 70 can be emailed, 5–25 tickets expected.",
                "First-timers (same genre as this event): 235 can be emailed, 10–45 tickets expected.");
        assertThat(s.gapLine()).isEqualTo("Still to find beyond your list: 160–230 tickets.");
        assertThat(s.actions()).containsExactly(
                "Invite the Loyal group (same genre as this event) on September 26, 2026 and October 21, 2026.",
                "Invite the Repeat group (same genre as this event) on September 26, 2026 and October 21, 2026.",
                "Invite the First-timers group (same genre as this event) on September 26, 2026 and October 21, 2026."
                        + " 15% are held back to measure the effect.");
        assertThat(s.assumptions()).containsExactly("Target: 85% of 300 tickets = 255.", "1.6 tickets per order.",
                "These are estimated ranges, not promises.");
        assertThat(s.locale()).isEqualTo("en");
        assertThat(s.aiGenerated()).isFalse();
        assertThat(s.aiDisclosure()).isNull();
        assertThat(s.model()).isNull();
        assertThat(s.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void ownAndIminConfidence_keepTheWholeBounds() {
        AudiencePlanResponse plan = SummaryFixtures.withConfidence(SummaryFixtures.warm(), "own", "imin", "own");

        AudiencePlanResponse.Summary s = SummaryTemplates.summary(plan, "en", NOW);

        assertThat(s.headline()).isEqualTo("Your list could bring 26–93 of the 255 tickets you are aiming for.");
        assertThat(s.segmentLines()).containsExactly(
                "Loyal (same genre as this event): 40 can be emailed, 8–26 tickets expected.",
                "Repeat (same genre as this event): 70 can be emailed, 7–22 tickets expected.",
                "First-timers (same genre as this event): 235 can be emailed, 11–45 tickets expected.");
        assertThat(s.gapLine()).isEqualTo("Still to find beyond your list: 162–229 tickets.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "es", "fr", "uk"})
    void everyLocale_writesOnlyNumbersThePlanHolds(String locale) {
        for (AudiencePlanResponse plan : List.of(SummaryFixtures.warm(), SummaryFixtures.cold(),
                SummaryFixtures.withGap(SummaryFixtures.warm(), 0, 0, List.of("dormant")),
                SummaryFixtures.withConfidence(SummaryFixtures.warm(), "own", "imin", "own"))) {
            AudiencePlanResponse.Summary s = SummaryTemplates.summary(plan, locale, NOW);
            assertThat(SummaryNumbers.invented(String.join("\n", texts(s)), SummaryNumbers.allowed(Summarizer.data(plan))))
                    .as(locale + " " + plan.mode()).isEmpty();
            assertThat(s.segmentLines()).hasSameSizeAs(plan.segments());
            assertThat(s.actions()).hasSameSizeAs(plan.actions());
            assertThat(texts(s)).allSatisfy(t -> assertThat(t).isNotBlank());
        }
    }

    @Test
    void decimals_useTheLocalesSeparator() {
        assertThat(SummaryTemplates.summary(SummaryFixtures.warm(), "fr", NOW).assumptions())
                .contains("1,6 billets par commande.");
        assertThat(SummaryTemplates.summary(SummaryFixtures.warm(), "uk", NOW).assumptions())
                .contains("Квитків на замовлення: 1,6.");
    }

    @Test
    void cold_saysThereIsNoListYet() {
        AudiencePlanResponse.Summary s = SummaryTemplates.summary(SummaryFixtures.cold(), "es", NOW);

        assertThat(s.headline()).isEqualTo("Aún no tienes una lista a la que escribir para este evento; las 255"
                + " entradas tendrán que venir de gente nueva.");
        assertThat(s.segmentLines()).isEmpty();
        assertThat(s.gapLine()).isEqualTo("Faltan por encontrar fuera de tu lista: 255 entradas.");
        assertThat(s.actions()).containsExactly("Importa contactos que aceptaron recibir tus mensajes, con prueba de"
                + " su consentimiento.");
    }

    @Test
    void zeroGap_andExcludedClasses() {
        AudiencePlanResponse plan = SummaryFixtures.withGap(SummaryFixtures.warm(), 0, 0, List.of("dormant", "lapsing"));

        AudiencePlanResponse.Summary s = SummaryTemplates.summary(plan, "en", NOW);

        assertThat(s.gapLine()).isEqualTo("At this estimate your list covers the target.");
        assertThat(s.assumptions()).contains("Left out: Dormant, Lapsing.");
    }

    @Test
    void rethinkTarget_andInviteWithoutDates() {
        AudiencePlanResponse plan = SummaryFixtures.withActions(SummaryFixtures.warm(), List.of(
                new AudiencePlanResponse.Action("invite", "loyal", "adjacent", List.of(), null, List.of()),
                new AudiencePlanResponse.Action("rethink_target", null, null, List.of(), null,
                        List.of("smaller_room", "other_date", "stronger_lineup"))));

        assertThat(SummaryTemplates.summary(plan, "fr", NOW).actions()).containsExactly(
                "Invitez le groupe Fidèles (proche du genre de cet événement).",
                "L’écart dépasse le public local de ce genre : envisagez une salle plus petite, une autre date ou une"
                        + " affiche plus forte.");
    }

    @Test
    void zeroHoldout_saysNothingIsHeldBack() {
        AudiencePlanResponse plan = SummaryFixtures.withActions(SummaryFixtures.warm(), List.of(
                new AudiencePlanResponse.Action("invite", "loyal", "same", List.of(), 0, List.of())));

        assertThat(SummaryTemplates.summary(plan, "en", NOW).actions())
                .containsExactly("Invite the Loyal group (same genre as this event).");
    }

    @Test
    void unknownLocale_throws() {
        assertThatThrownBy(() -> SummaryTemplates.summary(SummaryFixtures.warm(), "de", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> texts(AudiencePlanResponse.Summary s) {
        List<String> all = new ArrayList<>();
        all.add(s.headline());
        all.addAll(s.segmentLines());
        all.add(s.gapLine());
        all.addAll(s.actions());
        all.addAll(s.assumptions());
        return all;
    }
}
