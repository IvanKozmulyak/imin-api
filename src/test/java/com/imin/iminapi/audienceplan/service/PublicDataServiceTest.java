package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.model.CityOpenData;
import com.imin.iminapi.audienceplan.opendata.FetchedFigure;
import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.audienceplan.opendata.OpenDataCity;
import com.imin.iminapi.audienceplan.opendata.OpenDataFetchException;
import com.imin.iminapi.audienceplan.opendata.OpenDataFetcher;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDataServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T08:00:00Z");
    private static final OpenDataCity METZ = new OpenDataCity("metz", "Metz", "FR", "57463", "Moselle");
    private static final OpenDataCity LUXEMBOURG = new OpenDataCity("luxembourg", "Luxembourg", "LU", null, null);
    private static final String URL = "https://api.insee.fr/melodi/data/DS_RP_TD_POPULATION_AGESEX_PRINC?GEO=COM-57463&SEX=_T&maxResult=10000";

    private final InMemoryCityOpenDataRepository repo = new InMemoryCityOpenDataRepository();
    private final FakeFetcher insee = new FakeFetcher(OpenDataset.INSEE_AGE);
    private final MutableClock clock = new MutableClock(NOW);
    private final PublicDataService service = new PublicDataService(repo, new OpenDataCities(List.of(METZ)),
            List.of(insee), clock);

    // --- request-time read: never calls a source

    @Test
    void readingAnUnknownCityIsEmpty() {
        assertThat(service.get("paris", OpenDataset.INSEE_AGE)).isEmpty();
        assertThat(insee.calls).isZero();
    }

    @Test
    void readingAFreshRowServesItNotStale() {
        repo.rows.add(row(OpenDataset.INSEE_AGE, 38_065L, NOW.plus(Duration.ofDays(10))));

        OpenDataValue v = service.get("metz", OpenDataset.INSEE_AGE).orElseThrow();

        assertThat(v.headline()).isEqualTo(38_065L);
        assertThat(v.stale()).isFalse();
        assertThat(insee.calls).isZero();
        assertThat(repo.saves).isZero();
    }

    @Test
    void readingAnExpiredRowServesItStaleWithoutFetching() {
        repo.rows.add(row(OpenDataset.INSEE_AGE, 37_000L, NOW));
        insee.answer = figure(38_065L);

        OpenDataValue v = service.get("metz", OpenDataset.INSEE_AGE).orElseThrow();

        assertThat(v.headline()).isEqualTo(37_000L);
        assertThat(v.stale()).isTrue();
        assertThat(insee.calls).isZero();
        assertThat(repo.saves).isZero();
    }

    @Test
    void readingAMissingRowIsEmptyWithoutFetching() {
        insee.answer = figure(38_065L);

        assertThat(service.get("metz", OpenDataset.INSEE_AGE)).isEmpty();
        assertThat(insee.calls).isZero();
        assertThat(repo.rows).isEmpty();
    }

    // --- refresh path, used by the job

    @Test
    void anUnknownCityIsEmptyWithoutAnyCall() {
        assertThat(service.refresh("paris", OpenDataset.INSEE_AGE)).isEmpty();
        assertThat(insee.calls).isZero();
    }

    @Test
    void aFreshRowIsAHitAndSkipsTheCall() {
        repo.rows.add(row(OpenDataset.INSEE_AGE, 38_065L, NOW.plus(Duration.ofDays(10))));

        OpenDataValue v = service.refresh("metz", OpenDataset.INSEE_AGE).orElseThrow();

        assertThat(v.headline()).isEqualTo(38_065L);
        assertThat(v.stale()).isFalse();
        assertThat(insee.calls).isZero();
        assertThat(repo.saves).isZero();
    }

    @Test
    void aMissingRowIsFetchedAndStoredWithSourceLicenceAndExpiry() {
        insee.answer = figure(38_065L);

        OpenDataValue v = service.refresh("metz", OpenDataset.INSEE_AGE).orElseThrow();

        assertThat(insee.calls).isEqualTo(1);
        assertThat(v.headline()).isEqualTo(38_065L);
        assertThat(v.refPeriod()).isEqualTo("2023");
        assertThat(v.figures()).containsEntry("pop_18_35", 38_065L).containsEntry("pop_total", null);
        assertThat(v.stale()).isFalse();
        CityOpenData stored = repo.findByCityKeyAndDataset("metz", "insee_age").orElseThrow();
        assertThat(stored.getSourceUrl()).isEqualTo(URL);
        assertThat(stored.getLicence()).isEqualTo("Licence Ouverte 2.0");
        assertThat(stored.getAttribution()).isEqualTo(OpenDataset.INSEE_AGE.attribution());
        assertThat(stored.getRefPeriod()).isEqualTo("2023");
        assertThat(stored.getHeadline()).isEqualTo(38_065L);
        assertThat(stored.getPayload()).isEqualTo("{\"pop_18_35\":38065,\"pop_total\":null}");
        assertThat(stored.getFetchedAt()).isEqualTo(NOW);
        assertThat(stored.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(365)));
    }

    @Test
    void anExpiredRowIsRefetchedAndUpdatedInPlace() {
        CityOpenData old = row(OpenDataset.INSEE_AGE, 37_000L, NOW.minusSeconds(1));
        repo.rows.add(old);
        insee.answer = figure(38_065L);

        OpenDataValue v = service.refresh("metz", OpenDataset.INSEE_AGE).orElseThrow();

        assertThat(insee.calls).isEqualTo(1);
        assertThat(v.headline()).isEqualTo(38_065L);
        assertThat(repo.rows).containsExactly(old);
        assertThat(old.getHeadline()).isEqualTo(38_065L);
        assertThat(old.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(365)));
    }

    @Test
    void aRowExpiringExactlyNowCountsAsExpired() {
        repo.rows.add(row(OpenDataset.INSEE_AGE, 37_000L, NOW));
        insee.answer = figure(38_065L);

        service.refresh("metz", OpenDataset.INSEE_AGE);

        assertThat(insee.calls).isEqualTo(1);
    }

    @Test
    void anExpiredRowIsServedStaleWhenTheSourceFails() {
        repo.rows.add(row(OpenDataset.INSEE_AGE, 37_000L, NOW.minusSeconds(1)));
        insee.failure = new OpenDataFetchException("down");

        OpenDataValue v = service.refresh("metz", OpenDataset.INSEE_AGE).orElseThrow();

        assertThat(v.headline()).isEqualTo(37_000L);
        assertThat(v.stale()).isTrue();
        assertThat(repo.saves).isZero();
    }

    @Test
    void nothingStoredAndTheSourceFailingGivesEmptyNeverZero() {
        insee.failure = new OpenDataFetchException("down");

        assertThat(service.refresh("metz", OpenDataset.INSEE_AGE)).isEmpty();
        assertThat(repo.rows).isEmpty();
    }

    @Test
    void aFetchedFigureIsStillServedWhenStoringItFails() {
        insee.answer = figure(38_065L);
        repo.failNextSave = true;

        assertThat(service.refresh("metz", OpenDataset.INSEE_AGE)).map(OpenDataValue::headline).contains(38_065L);
    }

    @Test
    void aDatasetWithoutAFetcherIsEmptyWhenNothingIsStored() {
        assertThat(service.refresh("metz", OpenDataset.OSM_VENUES)).isEmpty();
    }

    @Test
    void aDatasetWithoutAFetcherServesItsExpiredRowStale() {
        repo.rows.add(row(OpenDataset.OSM_VENUES, null, NOW.minusSeconds(1)));

        OpenDataValue v = service.refresh("metz", OpenDataset.OSM_VENUES).orElseThrow();

        assertThat(v.stale()).isTrue();
        assertThat(v.headline()).isNull();
        assertThat(insee.calls).isZero();
    }

    @Test
    void aStoredRowForAnUnknownDatasetFailsLoudly() {
        CityOpenData r = row(OpenDataset.INSEE_AGE, 1L, NOW.plusSeconds(60));
        r.setDataset("sirene");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PublicDataService.toValue(r, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sirene");
    }

    @Test
    void aFailedPairIsNotFetchedAgainWithinTheHourAndServesStale() {
        repo.rows.add(row(OpenDataset.INSEE_AGE, 37_000L, NOW.minusSeconds(1)));
        insee.failure = new OpenDataFetchException("down");
        service.refresh("metz", OpenDataset.INSEE_AGE);

        clock.now = NOW.plus(PublicDataService.FAILURE_BACKOFF).minusSeconds(1);
        OpenDataValue v = service.refresh("metz", OpenDataset.INSEE_AGE).orElseThrow();

        assertThat(insee.calls).isEqualTo(1);
        assertThat(v.headline()).isEqualTo(37_000L);
        assertThat(v.stale()).isTrue();
    }

    @Test
    void aFailedPairWithNothingStoredStaysEmptyWithinTheHour() {
        insee.failure = new OpenDataFetchException("down");
        service.refresh("metz", OpenDataset.INSEE_AGE);

        assertThat(service.refresh("metz", OpenDataset.INSEE_AGE)).isEmpty();
        assertThat(insee.calls).isEqualTo(1);
    }

    @Test
    void aFailedPairIsFetchedAgainOnceTheHourIsOver() {
        insee.failure = new OpenDataFetchException("down");
        service.refresh("metz", OpenDataset.INSEE_AGE);

        clock.now = NOW.plus(PublicDataService.FAILURE_BACKOFF);
        insee.failure = null;
        insee.answer = figure(38_065L);

        assertThat(service.refresh("metz", OpenDataset.INSEE_AGE)).map(OpenDataValue::headline).contains(38_065L);
        assertThat(insee.calls).isEqualTo(2);
    }

    @Test
    void aFailureIsRememberedPerCityAndDataset() {
        OpenDataCity nancy = new OpenDataCity("nancy", "Nancy", "FR", "54395", "Meurthe-et-Moselle");
        PublicDataService two = new PublicDataService(repo, new OpenDataCities(List.of(METZ, nancy)),
                List.of(insee), clock);
        insee.failure = new OpenDataFetchException("down");
        two.refresh("metz", OpenDataset.INSEE_AGE);

        two.refresh("nancy", OpenDataset.INSEE_AGE);

        assertThat(insee.calls).isEqualTo(2);
        assertThat(insee.asked).containsExactly(METZ, nancy);
    }

    @Test
    void theBackoffIsOneHour() {
        assertThat(PublicDataService.FAILURE_BACKOFF).isEqualTo(Duration.ofHours(1));
    }

    // --- datasets that do not cover a town's country

    @Test
    void readingADatasetThatDoesNotCoverTheCountryIsEmptyEvenWithAStoredRow() {
        PublicDataService withLux = new PublicDataService(repo, new OpenDataCities(List.of(METZ, LUXEMBOURG)),
                List.of(insee), clock);
        CityOpenData r = row(OpenDataset.INSEE_AGE, 1L, NOW.plus(Duration.ofDays(10)));
        r.setCityKey("luxembourg");
        repo.rows.add(r);

        assertThat(withLux.get("luxembourg", OpenDataset.INSEE_AGE)).isEmpty();
    }

    @Test
    void refreshingADatasetThatDoesNotCoverTheCountryNeverCallsItsSource() {
        PublicDataService withLux = new PublicDataService(repo, new OpenDataCities(List.of(METZ, LUXEMBOURG)),
                List.of(insee), clock);
        insee.answer = figure(38_065L);

        assertThat(withLux.refresh("luxembourg", OpenDataset.INSEE_AGE)).isEmpty();
        assertThat(insee.calls).isZero();
        assertThat(repo.saves).isZero();
    }

    @Test
    void aDatasetCoveringTheCountryIsReadForATownAbroad() {
        PublicDataService withLux = new PublicDataService(repo, new OpenDataCities(List.of(METZ, LUXEMBOURG)),
                List.of(insee), clock);
        CityOpenData r = row(OpenDataset.CENTROID, null, NOW.plus(Duration.ofDays(10)));
        r.setCityKey("luxembourg");
        repo.rows.add(r);

        assertThat(withLux.get("luxembourg", OpenDataset.CENTROID)).isPresent();
    }

    @Test
    void knownCitiesAreTheRegistry() {
        assertThat(service.knownCities()).containsExactly(METZ);
    }

    private static FetchedFigure figure(long youth) {
        Map<String, Object> figures = new LinkedHashMap<>();
        figures.put("pop_18_35", youth);
        figures.put("pop_total", null);
        return new FetchedFigure("2023", youth, figures, URL);
    }

    private static CityOpenData row(OpenDataset d, Long headline, Instant expiresAt) {
        CityOpenData r = new CityOpenData();
        r.setId(java.util.UUID.randomUUID());
        r.setCityKey("metz");
        r.setDataset(d.key());
        r.setRefPeriod("2022");
        r.setHeadline(headline);
        r.setPayload("{}");
        r.setSourceUrl(URL);
        r.setLicence(d.licence());
        r.setAttribution(d.attribution());
        r.setFetchedAt(NOW.minus(Duration.ofDays(400)));
        r.setExpiresAt(expiresAt);
        return r;
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) { this.now = now; }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FakeFetcher implements OpenDataFetcher {
        private final OpenDataset dataset;
        int calls;
        FetchedFigure answer;
        OpenDataFetchException failure;
        final List<OpenDataCity> asked = new ArrayList<>();

        FakeFetcher(OpenDataset dataset) { this.dataset = dataset; }

        @Override public OpenDataset dataset() { return dataset; }

        @Override
        public FetchedFigure fetch(OpenDataCity city) {
            calls++;
            asked.add(city);
            if (failure != null) throw failure;
            return Optional.ofNullable(answer).orElseThrow();
        }
    }
}
