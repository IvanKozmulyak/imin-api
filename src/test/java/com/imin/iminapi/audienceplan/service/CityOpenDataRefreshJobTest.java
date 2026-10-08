package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.opendata.OpenDataCity;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CityOpenDataRefreshJobTest {

    private static final OpenDataCity METZ = new OpenDataCity("metz", "Metz", "FR", "57463", "Moselle");
    private static final OpenDataCity NANCY = new OpenDataCity("nancy", "Nancy", "FR", "54395", "Meurthe-et-Moselle");

    private final PublicDataService publicData = mock(PublicDataService.class);
    private final CityOpenDataRefreshJob job = new CityOpenDataRefreshJob(publicData);

    @Test
    void aTownAbroadIsRefreshedOnlyForTheDatasetsCoveringItsCountry() {
        OpenDataCity lux = new OpenDataCity("luxembourg", "Luxembourg", "LU", null, null);
        when(publicData.knownCities()).thenReturn(List.of(lux));
        when(publicData.refresh(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        job.run();

        verify(publicData).refresh("luxembourg", OpenDataset.CENTROID);
        verify(publicData, times(1)).refresh(eq("luxembourg"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        when(publicData.knownCities()).thenReturn(List.of(METZ, NANCY));
        when(publicData.refresh(eq("metz"), org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("db down"));
        when(publicData.refresh(eq("nancy"), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        job.run();

        verify(publicData, times(OpenDataset.values().length)).refresh(eq("nancy"), org.mockito.ArgumentMatchers.any());
    }
}
