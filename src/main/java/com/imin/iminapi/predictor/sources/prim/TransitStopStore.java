package com.imin.iminapi.predictor.sources.prim;

import com.imin.iminapi.predictor.model.TransitSyncState;
import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
import com.imin.iminapi.predictor.sources.prim.IdfmStopsClient.Stop;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The IDFM stops reference (V176) and its sync state; internal only, never served raw. */
@Repository
public class TransitStopStore {

    private static final String SOURCE = TransitSyncState.IDFM_STOPS;
    static final int BATCH = 1_000;
    /** Mean Earth radius, metres. */
    static final double EARTH_RADIUS_M = 6_371_008.8;
    // The box is only a prefilter for the exact distance, so it is padded to never cut a point the circle keeps.
    private static final double BOX_PAD = 1.001;
    private static final String INSERT = "INSERT INTO transit_stop (source, stop_ref, lat, lng, synced_at) VALUES (?, ?, ?, ?, ?)";
    private static final String NEARBY = "SELECT stop_ref, lat, lng FROM transit_stop WHERE source = ?"
            + " AND lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?";

    record Box(double minLat, double maxLat, double minLng, double maxLng) {
        boolean contains(double lat, double lng) {
            return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
        }
    }

    private final JdbcTemplate jdbc;
    private final TransitSyncStateRepository states;

    public TransitStopStore(JdbcTemplate jdbc, TransitSyncStateRepository states) {
        this.jdbc = jdbc;
        this.states = states;
    }

    /** Swaps every stored stop for the download and stamps the state ok, in one transaction. */
    @Transactional
    public int replace(List<Stop> stops, Instant syncedAt) {
        jdbc.update("DELETE FROM transit_stop WHERE source = ?", SOURCE);
        OffsetDateTime at = syncedAt.atOffset(ZoneOffset.UTC);
        jdbc.batchUpdate(INSERT, stops, BATCH, (ps, s) -> {
            ps.setString(1, SOURCE);
            ps.setString(2, s.ref());
            ps.setDouble(3, s.lat());
            ps.setDouble(4, s.lng());
            ps.setObject(5, at);
        });
        TransitSyncState state = states.findById(SOURCE).orElseGet(TransitStopStore::fresh);
        state.setSyncedAt(syncedAt);
        state.setLastStatus(Status.OK.wire());
        state.setLastAttemptAt(syncedAt);
        states.save(state);
        return stops.size();
    }

    /** A sync that did not succeed: only the attempt is recorded; stored stops and {@code synced_at} stay. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(Status status, Instant attemptedAt) {
        TransitSyncState state = states.findById(SOURCE).orElseGet(TransitStopStore::fresh);
        state.setLastStatus(status.wire());
        state.setLastAttemptAt(attemptedAt);
        states.save(state);
    }

    /** Refs of the stored stops within {@code radiusM} metres of the point. */
    public Set<String> nearby(double lat, double lng, int radiusM) {
        Box b = box(lat, lng, radiusM);
        Set<String> out = new HashSet<>();
        jdbc.query(NEARBY, rs -> {
            if (meters(lat, lng, rs.getDouble("lat"), rs.getDouble("lng")) <= radiusM) out.add(rs.getString("stop_ref"));
        }, SOURCE, b.minLat(), b.maxLat(), b.minLng(), b.maxLng());
        return out;
    }

    /** Lat/lng bounds around the point: a degree of longitude shrinks with {@code cos(lat)}. */
    static Box box(double lat, double lng, int radiusM) {
        double dLat = Math.toDegrees(radiusM / EARTH_RADIUS_M) * BOX_PAD;
        double dLng = dLat / Math.cos(Math.toRadians(lat));
        return new Box(lat - dLat, lat + dLat, lng - dLng, lng + dLng);
    }

    /** Great-circle (haversine) distance in metres. */
    static double meters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private static TransitSyncState fresh() {
        TransitSyncState s = new TransitSyncState();
        s.setSource(SOURCE);
        return s;
    }
}
