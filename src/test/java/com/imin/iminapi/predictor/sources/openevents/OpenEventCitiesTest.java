package com.imin.iminapi.predictor.sources.openevents;

import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.City;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

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

    @Test
    void duplicateKeyRejected() {
        rejects(VALID + "  lille:\n    country: FR\n    aliases: [x]\n    quefaireaparis: false\n", "invalid YAML");
    }

    @Test
    void unknownRootKeyRejected() {
        rejects(VALID.replace("version: 1", "version: 1\nextra: 1"), "unknown key 'extra'");
    }

    @Test
    void unknownCityKeyRejected() {
        rejects(VALID.replace("    aliases: [paris]", "    aliases: [paris]\n    region: IDF"), "unknown key 'region'");
    }

    @Test
    void unknownAgendaKeyRejected() {
        rejects(VALID.replace("name: \"Ville de Lille\",", "name: \"Ville de Lille\", official: true,"),
                "unknown key 'official'");
    }

    @Test
    void nonFrCountryRejected() {
        rejects(VALID.replace("    country: FR\n    aliases: [paris]", "    country: BE\n    aliases: [paris]"), "country");
    }

    @Test
    void licenceOutsideCheckRejected() {
        rejects(VALID.replace("\"Licence Ouverte 2.0\"", "\"CC-BY\""), "licence");
        // ODbL is in the table CHECK but no OpenAgenda agenda is credited under it.
        rejects(VALID.replace("\"Licence Ouverte 2.0\"", "\"ODbL 1.0\""), "licence");
    }

    @Test
    void duplicateUidRejected() {
        rejects(VALID.replace("    openagenda: []", "    openagenda:\n"
                + "      - { uid: 57621068, slug: other, name: Other, licence: \"Licence Ouverte 2.0\" }"), "uid");
    }

    @Test
    void aliasInTwoCitiesRejected() {
        rejects(VALID.replace("aliases: [paris]", "aliases: [paris, lomme]"), "alias");
    }

    @Test
    void quefaireOutsideParisRejected() {
        rejects(VALID.replace("    aliases: [lille, lomme]", "    aliases: [lille, lomme]\n    quefaireaparis: true"),
                "quefaireaparis");
    }

    @Test
    void cityWithoutSourceRejected() {
        rejects(VALID.replace("    quefaireaparis: true\n", ""), "no source");
    }

    @Test
    void overlongCityKeyRejected() {
        String key = "a".repeat(101);
        rejects(VALID.replace("  paris:\n", "  " + key + ":\n").replace("quefaireaparis: true", "quefaireaparis: false"),
                "longer than 100");
    }

    @Test
    void missingVersionRejected() {
        rejects(VALID.replace("version: 1\n", ""), "version");
        rejects(VALID.replace("version: 1", "version: 0"), "version");
    }

    @Test
    void unnormalisedKeyOrAliasRejected() {
        rejects(VALID.replace("  lille:\n", "  Lille:\n"), "normalised");
        rejects(VALID.replace("aliases: [lille, lomme]", "aliases: [Lille]"), "normalised");
        rejects(VALID.replace("aliases: [lille, lomme]", "aliases: []"), "aliases");
    }

    @Test
    void badUidSlugOrDateRejected() {
        rejects(VALID.replace("uid: 57621068", "uid: -3"), "uid");
        rejects(VALID.replace("slug: ville-de-lille", "slug: \" \""), "slug");
        rejects(VALID.replace("verified_on: 2026-10-01", "verified_on: \"soon\""), "verified_on");
    }
}
