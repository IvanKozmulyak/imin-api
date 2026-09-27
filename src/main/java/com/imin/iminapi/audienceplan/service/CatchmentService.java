package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.engine.GreatCircle;
import com.imin.iminapi.audienceplan.opendata.OpenDataCity;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import com.imin.iminapi.model.Event;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where guests can come from: the known towns whose centre is within the logic file's straight-line
 * radius of the venue. Reads stored centroids only; never geocodes or calls a source.
 */
@Service
public class CatchmentService {

    private final PublicDataService publicData;
    private final AudiencePlanLogic logic;

    public CatchmentService(PublicDataService publicData, AudiencePlanLogic logic) {
        this.publicData = publicData;
        this.logic = logic;
    }

    /** The event's catchment; empty when its venue has no coordinates yet. */
    public Optional<Catchment> forEvent(Event event) {
        return around(event.getVenueLatitude(), event.getVenueLongitude());
    }

    /** Empty for missing or invalid coordinates and when no known town is within the radius. */
    public Optional<Catchment> around(Double latitude, Double longitude) {
        if (!valid(latitude, longitude)) return Optional.empty();
        int radiusKm = logic.logic().catchment().radiusKm();
        List<Catchment.Town> towns = new ArrayList<>();
        // ponytail: one row read per registered town; fine for tens of towns, needs a bounding-box query beyond that.
        for (OpenDataCity city : publicData.knownCities()) {
            Optional<OpenDataValue> centroid = publicData.get(city.cityKey(), OpenDataset.CENTROID);
            if (centroid.isEmpty()) continue;
            Long latE6 = centroid.get().figures().get("lat_e6");
            Long lonE6 = centroid.get().figures().get("lon_e6");
            if (latE6 == null || lonE6 == null) continue;
            // Compare the rounded km, so a shown distance never contradicts the radius.
            long km = Math.round(GreatCircle.km(latitude, longitude, latE6 / 1e6, lonE6 / 1e6));
            if (km > radiusKm) continue;
            towns.add(new Catchment.Town(city.cityKey(), city.name(), city.country(), (int) km, null));
        }
        if (towns.isEmpty()) return Optional.empty();
        towns.sort(Comparator.comparingInt(Catchment.Town::kmStraight).thenComparing(Catchment.Town::cityKey));
        return Optional.of(new Catchment(radiusKm, List.copyOf(towns)));
    }

    private static boolean valid(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
    }
}
