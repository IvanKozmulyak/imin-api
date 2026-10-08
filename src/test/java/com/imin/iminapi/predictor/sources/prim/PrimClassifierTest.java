package com.imin.iminapi.predictor.sources.prim;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PrimClassifierTest {

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "PERTURBATION | Tramway T14 -  Mouvement social du 6/10 au 9/10. | strike",
            "PERTURBATION | RER B : GRÈVE nationale                          | strike",
            "PERTURBATION | Métro 4 : Incident technique                     | other",
            "TRAVAUX      | RER A : Travaux - Trafic interrompu              | works",
            "INFORMATION  | Préavis de grève                                 | other"})
    void kind(String cause, String title, String expected) {
        assertThat(PrimClassifier.kind(cause, title)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(delimiter = '|', nullValues = "EXCLUDED", value = {
            "RapidTransit | A    | RER A",
            "Metro        | 3bis | M3bis",
            "Tramway      | T3a  | T3a",
            "LocalTrain   | H    | Transilien H",
            "Bus          | 211  | EXCLUDED",
            "Funicular    | FUN  | EXCLUDED"})
    void label(String mode, String shortName, String expected) {
        assertThat(PrimClassifier.label(mode, shortName)).isEqualTo(Optional.ofNullable(expected));
    }
}
