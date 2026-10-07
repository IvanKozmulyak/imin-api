package com.imin.iminapi.predictor.sources.openevents;

import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.City;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenEventCitiesTest {

    private static OpenEventCities parse(String yaml) {
        return OpenEventCities.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String VALID = """
            version: 1
            verified_on: 2026-10-01
            cities:
              lille:
                country: FR
                aliases: [lille, lomme]
                openagenda:
                  - { uid: 57621068, slug: ville-de-lille, name: "Ville de Lille", licence: "Licence Ouverte 2.0" }
              paris:
                country: FR
                aliases: [paris]
                quefaireaparis: true
                openagenda: []
            """;

    private static void rejects(String yaml, String fragment) {
        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(IllegalStateException.class).hasMessageContaining(fragment);
    }

    @Test
    void validFileLoads() {
        OpenEventCities cities = parse(VALID);

        assertThat(cities.version()).isEqualTo(1);
        assertThat(cities.verifiedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
        City lille = cities.city("lille").orElseThrow();
        assertThat(lille.aliases()).containsExactly("lille", "lomme");
        assertThat(lille.openagenda()).singleElement().satisfies(a -> {
            assertThat(a.uid()).isEqualTo(57621068L);
            assertThat(a.slug()).isEqualTo("ville-de-lille");
            assertThat(a.licence()).isEqualTo("Licence Ouverte 2.0");
        });
        assertThat(lille.quefaireaparis()).isFalse();
        assertThat(cities.city("paris").orElseThrow().quefaireaparis()).isTrue();
        assertThat(cities.city("metz")).isEmpty();
    }

    @Test
    void resolveMatchesKeyOrAlias() {
        OpenEventCities cities = parse(VALID);

        assertThat(cities.resolve("lille", "FR")).map(City::key).hasValue("lille");
        assertThat(cities.resolve("lomme", "FR")).map(City::key).hasValue("lille");
        assertThat(cities.resolve("  LOMME ", "FR")).map(City::key).hasValue("lille");
        assertThat(cities.resolve("paris", "FR")).map(City::key).hasValue("paris");
        assertThat(cities.resolve("metz", "FR")).isEmpty();
        assertThat(cities.resolve(null, "FR")).isEmpty();
        assertThat(cities.resolve("  ", "FR")).isEmpty();
        // a Paris outside France is another city
        assertThat(cities.resolve("paris", "US")).isEmpty();
    }

    @Test
    void shippedFileLoads() {
        OpenEventCities cities = OpenEventCities.load(new DefaultResourceLoader());

        assertThat(cities.cities()).isNotEmpty();
        for (City c : cities.cities()) {
            assertThat(c.key().length()).isLessThanOrEqualTo(100);
            assertThat(c.country()).isEqualTo("FR");
            c.openagenda().forEach(a -> assertThat(OpenEventSource.LICENCES).contains(a.licence()));
        }
        // Only agendas checked live on verified_on ship; Paris is covered by Que Faire à Paris.
        assertThat(cities.city("paris").orElseThrow().quefaireaparis()).isTrue();
        assertThat(cities.city("lille").orElseThrow().openagenda()).extracting(OpenEventCities.Agenda::uid)
                .containsExactly(57621068L, 89904399L);
        assertThat(cities.verifiedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    static Stream<Arguments> invalidFiles() {
        return Stream.of(
                Arguments.of("duplicate key",
                        VALID + "  lille:\n    country: FR\n    aliases: [x]\n    quefaireaparis: false\n", "invalid YAML"),
                Arguments.of("unknown root key", VALID.replace("version: 1", "version: 1\nextra: 1"), "unknown key 'extra'"),
                Arguments.of("unknown city key",
                        VALID.replace("    aliases: [paris]", "    aliases: [paris]\n    region: IDF"), "unknown key 'region'"),
                Arguments.of("unknown agenda key",
                        VALID.replace("name: \"Ville de Lille\",", "name: \"Ville de Lille\", official: true,"),
                        "unknown key 'official'"),
                Arguments.of("non-FR country",
                        VALID.replace("    country: FR\n    aliases: [paris]", "    country: BE\n    aliases: [paris]"), "country"),
                Arguments.of("licence outside the table check",
                        VALID.replace("\"Licence Ouverte 2.0\"", "\"CC-BY\""), "licence"),
                // ODbL is in the table CHECK but no OpenAgenda agenda is credited under it
                Arguments.of("ODbL agenda", VALID.replace("\"Licence Ouverte 2.0\"", "\"ODbL 1.0\""), "licence"),
                Arguments.of("duplicate uid", VALID.replace("    openagenda: []", "    openagenda:\n"
                        + "      - { uid: 57621068, slug: other, name: Other, licence: \"Licence Ouverte 2.0\" }"), "uid"),
                Arguments.of("alias in two cities", VALID.replace("aliases: [paris]", "aliases: [paris, lomme]"), "alias"),
                Arguments.of("quefaireaparis outside Paris", VALID.replace("    aliases: [lille, lomme]",
                        "    aliases: [lille, lomme]\n    quefaireaparis: true"), "quefaireaparis"),
                Arguments.of("city without source", VALID.replace("    quefaireaparis: true\n", ""), "no source"),
                Arguments.of("overlong city key", VALID.replace("  paris:\n", "  " + "a".repeat(101) + ":\n")
                        .replace("quefaireaparis: true", "quefaireaparis: false"), "longer than 100"),
                Arguments.of("missing version", VALID.replace("version: 1\n", ""), "version"),
                Arguments.of("zero version", VALID.replace("version: 1", "version: 0"), "version"),
                Arguments.of("unnormalised key", VALID.replace("  lille:\n", "  Lille:\n"), "normalised"),
                Arguments.of("unnormalised alias", VALID.replace("aliases: [lille, lomme]", "aliases: [Lille]"), "normalised"),
                Arguments.of("no aliases", VALID.replace("aliases: [lille, lomme]", "aliases: []"), "aliases"),
                Arguments.of("negative uid", VALID.replace("uid: 57621068", "uid: -3"), "uid"),
                Arguments.of("blank slug", VALID.replace("slug: ville-de-lille", "slug: \" \""), "slug"),
                Arguments.of("bad verified_on", VALID.replace("verified_on: 2026-10-01", "verified_on: \"soon\""),
                        "verified_on"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFiles")
    void invalidFileRejected(String name, String yaml, String fragment) {
        rejects(yaml, fragment);
    }
}
