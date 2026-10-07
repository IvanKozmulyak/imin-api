package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.service.BuyerPreferencesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consent proof text is a legal record, and it must be the sentence the
 * buyer actually read.
 *
 * <p>{@code ConsentService.capture} persists whatever it is handed onto
 * {@code consent_records.proof_text}, which is imin's Art. 7(1) evidence that a
 * given buyer agreed to a given thing. If that string is not the one rendered
 * beside the toggle in {@code imin-public}, the record describes an agreement
 * that never happened — which is what it did until 2026-08-13, when the stored
 * text was "Turned organizer updates on from my imin account preferences." and
 * the screen said something with no words in common.
 *
 * <p>These strings live in two repositories and no build spans both, so this
 * test is the seam. Each one is
 * {@code profile.notifications.organizers.body} in
 * {@code imin-public/lib/i18n/&lt;locale&gt;.ts}. <b>Changing the screen means
 * changing this file in the same breath</b>; a failure here is the reminder,
 * not the bug.
 */
class BuyerPreferencesProofTextTest {

    private static final String ENGLISH =
            "Marketing from organizers you've bought from."
                    + " Turning this on won't undo an unsubscribe you've already made.";

    /**
     * One row per screen locale. An unknown or absent locale falls back to English rather than
     * throwing — a buyer whose account predates the locale column must still be able to use the toggle.
     */
    static Stream<Arguments> screenSentences() {
        return Stream.of(
                Arguments.of("en", ENGLISH),
                Arguments.of("es", "Marketing de organizadores a los que has comprado."
                        + " Activarlo no deshará una baja que ya hayas hecho."),
                Arguments.of("fr", "Le marketing des organisateurs chez qui tu as acheté."
                        + " L’activer n’annulera pas une désinscription déjà faite."),
                Arguments.of("uk", "Розсилки від організаторів, у яких ти вже маєш покупки."
                        + " Увімкнення не скасує відписку, яку ти вже оформив(ла)."),
                Arguments.of(null, ENGLISH),
                Arguments.of("de", ENGLISH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("screenSentences")
    void theProofTextIsTheSentenceOnTheScreen(String locale, String sentence) {
        assertThat(BuyerPreferencesService.proofText(locale)).isEqualTo(sentence);
    }

    /** Four distinct sentences — a copy-paste that left two locales identical is a defect. */
    @Test
    void theFourLocalesAreActuallyFourDifferentSentences() {
        assertThat(java.util.Set.of(
                        BuyerPreferencesService.proofText("en"),
                        BuyerPreferencesService.proofText("es"),
                        BuyerPreferencesService.proofText("fr"),
                        BuyerPreferencesService.proofText("uk")))
                .hasSize(4);
    }
}
