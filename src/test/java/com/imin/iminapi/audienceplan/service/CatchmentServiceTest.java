package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.audienceplan.model.CityOpenData;
import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import com.imin.iminapi.model.Event;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Runs over the committed seed rows, so the §5 Metz fixture is checked against the shipped centroids. */
class CatchmentServiceTest {

    // Venue pinned at the Wikidata centre of Metz.
    private static final double VENUE_LAT = 49.119722;
    private static final double VENUE_LON = 6.176944;
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private final InMemoryCityOpenDataRepository repo = seeded();

    @Test
    void metzFixture_townsAndStraightLineKmNearestFirst() {
        Catchment c = service(repo, 70, NOW).around(VENUE_LAT, VENUE_LON).orElseThrow();

        assertThat(c.radiusKm()).isEqualTo(70);
        assertThat(c.towns()).extracting(Catchment.Town::cityKey)
                .containsExactly("metz", "thionville", "nancy", "luxembourg", "saarbrücken");
        assertThat(km(c, "metz")).isZero();
        assertThat((double) km(c, "thionville")).isCloseTo(27, within(1.0));
        assertThat((double) km(c, "nancy")).isCloseTo(48, within(1.0));
        assertThat((double) km(c, "luxembourg")).isCloseTo(55, within(1.0));
        assertThat((double) km(c, "saarbrücken")).isCloseTo(61, within(1.0));
        assertThat(c.towns()).allSatisfy(t -> assertThat(t.ownScene()).isNull());
        assertThat(town(c, "luxembourg").name()).isEqualTo("Luxembourg");
        assertThat(town(c, "luxembourg").country()).isEqualTo("LU");
        assertThat(town(c, "saarbrücken").country()).isEqualTo("DE");
    }

    @Test
    void nullLatitude_isNoCatchment() {
        assertThat(service(repo, 70, NOW).around(null, VENUE_LON)).isEmpty();
    }

    @Test
    void nullLongitude_isNoCatchment() {
        assertThat(service(repo, 70, NOW).around(VENUE_LAT, null)).isEmpty();
    }

    @Test
    void nonFiniteOrOutOfRangeCoordinates_areNoCatchment() {
        CatchmentService s = service(repo, 70, NOW);
        assertThat(s.around(Double.NaN, VENUE_LON)).isEmpty();
        assertThat(s.around(VENUE_LAT, Double.POSITIVE_INFINITY)).isEmpty();
        assertThat(s.around(90.5, VENUE_LON)).isEmpty();
        assertThat(s.around(VENUE_LAT, -180.5)).isEmpty();
    }

    @Test
    void radiusBoundary_comparesTheRoundedKmInclusively() {
        // Saarbrücken is 61.15 km away: shown as 61, so a 61 km radius includes it and 60 does not.
        assertThat(service(repo, 61, NOW).around(VENUE_LAT, VENUE_LON).orElseThrow().towns())
                .extracting(Catchment.Town::cityKey).contains("saarbrücken");
        assertThat(service(repo, 60, NOW).around(VENUE_LAT, VENUE_LON).orElseThrow().towns())
                .extracting(Catchment.Town::cityKey).doesNotContain("saarbrücken").contains("luxembourg");
    }

    @Test
    void aTownWithoutAStoredCentroid_isLeftOut() {
        repo.rows.removeIf(r -> r.getCityKey().equals("nancy") && r.getDataset().equals("centroid"));

        assertThat(service(repo, 70, NOW).around(VENUE_LAT, VENUE_LON).orElseThrow().towns())
                .extracting(Catchment.Town::cityKey).doesNotContain("nancy").contains("thionville");
    }

    @Test
    void aCentroidMissingACoordinate_isLeftOut() {
        CityOpenData nancy = repo.findByCityKeyAndDataset("nancy", "centroid").orElseThrow();
        nancy.setPayload("{\"lat_e6\":48692778,\"lon_e6\":null}");

        assertThat(service(repo, 70, NOW).around(VENUE_LAT, VENUE_LON).orElseThrow().towns())
                .extracting(Catchment.Town::cityKey).doesNotContain("nancy");
    }

    @Test
    void noKnownTownWithinTheRadius_isNoCatchmentNotAnEmptyOne() {
        // Paris: every known town is over 280 km away.
        assertThat(service(repo, 70, NOW).around(48.8566, 2.3522)).isEmpty();
    }

    @Test
    void anExpiredCentroid_isStillUsed() {
        Instant muchLater = Instant.parse("2040-01-01T00:00:00Z");

        assertThat(service(repo, 70, muchLater).around(VENUE_LAT, VENUE_LON).orElseThrow().towns()).hasSize(5);
    }

    @Test
    void forEvent_readsTheVenueCoordinates() {
        Event event = new Event();
        event.setVenueLatitude(VENUE_LAT);
        event.setVenueLongitude(VENUE_LON);

        assertThat(service(repo, 70, NOW).forEvent(event)).get()
                .extracting(c -> c.towns().get(0).cityKey()).isEqualTo("metz");
    }

    @Test
    void forEvent_withoutGeocodedVenue_isNoCatchment() {
        assertThat(service(repo, 70, NOW).forEvent(new Event())).isEmpty();
    }

    private static int km(Catchment c, String key) {
        return town(c, key).kmStraight();
    }

    private static Catchment.Town town(Catchment c, String key) {
        return c.towns().stream().filter(t -> t.cityKey().equals(key)).findFirst().orElseThrow();
    }

    private static InMemoryCityOpenDataRepository seeded() {
        InMemoryCityOpenDataRepository repo = new InMemoryCityOpenDataRepository();
        new CityOpenDataSeeder(repo, OpenDataCities.load()).seed();
        assertThat(repo.rows).anyMatch(r -> r.getDataset().equals(OpenDataset.CENTROID.key()));
        return repo;
    }

    private static CatchmentService service(InMemoryCityOpenDataRepository repo, int radiusKm, Instant now) {
        PublicDataService publicData = new PublicDataService(repo, OpenDataCities.load(), List.of(),
                Clock.fixed(now, ZoneOffset.UTC));
        return new CatchmentService(publicData, logic(radiusKm));
    }

    private static AudiencePlanLogic logic(int radiusKm) {
        String yaml = read("audienceplan/logic-v1.yaml");
        String edited = yaml.replace("radius_km: 70", "radius_km: " + radiusKm);
        assertThat(edited).contains("radius_km: " + radiusKm);
        return LogicLoader.parse(stream(edited), stream(read("audienceplan/priors-v1.yaml")),
                stream(read("audienceplan/genres-v1.yaml")));
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(String location) {
        try (InputStream in = CatchmentServiceTest.class.getClassLoader().getResourceAsStream(location)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
