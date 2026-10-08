package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.sources.prim.IdfmStopsClient.Stop;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Disruption;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.LineRef;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Period;
import com.imin.iminapi.predictor.sources.prim.PrimDisruptionsClient.Snapshot;
import com.imin.iminapi.predictor.sources.prim.PrimProperties;
import com.imin.iminapi.predictor.sources.prim.PrimWriter;
import com.imin.iminapi.predictor.sources.prim.TransitStopStore;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Check a date" with the PRIM source on: a stored rail closure reaches an IDF check and no other city's. */
@IminIntegrationTest
class TransitDateCheckFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final String NIGHT = "2026-10-10";
    private static final String URL = "https://prim.iledefrance-mobilites.fr/fr/apis/idfm-disruptions_bulk";
    private static final Instant FEED = Instant.parse("2026-10-07T09:58:00Z");

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired PropertyFlips flips;
    @Autowired MutableClock clock;
    @Autowired DateCheckProperties dateCheck;
    @Autowired PrimProperties prim;
    @Autowired PrimWriter writer;
    @Autowired TransitStopStore stops;
    @Autowired EventRepository events;

    private final ObjectMapper om = new ObjectMapper();
    private Authentication owner;
    private Organization org;
    private User user;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);
        flips.set(dateCheck, "enabled", true);
        flips.set(dateCheck, "allOrgs", true);
        flips.set(prim, "apiKey", "test-key");
        org = fx.org();
        user = fx.owner(org);
        owner = new UsernamePasswordAuthenticationToken(fx.principal(user), null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        // Saturday 23:00-01:00 Paris, inside the 22-02 night of 6.2
        Disruption closure = new Disruption("rer-b-" + UUID.randomUUID(), "TRAVAUX", "BLOQUANTE", "works",
                "RER B : Travaux - Trafic interrompu", FEED,
                List.of(new LineRef("line:IDFM:C01743", "RER B", "RapidTransit", "line", null)),
                List.of(new Period(Instant.parse("2026-10-10T21:00:00Z"), Instant.parse("2026-10-10T23:00:00Z"))));
        writer.replace(new Snapshot(FEED, List.of(closure), 0), NOW);
    }

    private JsonNode check(String city, String postalCode) throws Exception {
        return check(city, postalCode, null);
    }

    private JsonNode check(String city, String postalCode, UUID eventId) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("city", city);
        if (eventId != null) b.put("eventId", eventId);
        b.put("country", "FR");
        b.put("postalCode", postalCode);
        b.put("genreFamily", "house & techno");
        b.put("dates", List.of(NIGHT));
        b.put("research", false);
        String body = mvc.perform(post("/api/v1/predictions/date-checks").with(authentication(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(b)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("dates").get(0);
    }

    private static JsonNode finding(JsonNode date, String questionId) {
        for (JsonNode f : date.get("findings")) if (f.get("questionId").asText().equals(questionId)) return f;
        return null;
    }

    private static List<String> ids(JsonNode date, String field) {
        List<String> out = new ArrayList<>();
        date.get(field).forEach(n -> out.add(n.get("questionId").asText()));
        return out;
    }

    @Test
    void parisCheckGetsTheClosureAndLyonGetsNoTransitRow() throws Exception {
        JsonNode paris = check("Paris", "75011");

        JsonNode works = null;
        for (JsonNode f : paris.get("findings")) if (f.get("questionId").asText().equals("6.2")) works = f;
        assertThat(works).isNotNull();
        assertThat(works.get("status").asText()).isEqualTo("found");
        assertThat(works.get("templateKey").asText()).isEqualTo("predictor.q.6_2");
        assertThat(works.get("url").asText()).isEqualTo(URL);
        assertThat(Instant.parse(works.get("fetchedAt").asText())).isEqualTo(FEED);
        assertThat(works.get("facts").get("lines").asText()).isEqualTo("RER B");
        JsonNode line = null;
        for (JsonNode l : paris.get("breakdown")) if (l.get("questionId").asText().equals("6.2")) line = l;
        assertThat(line).isNotNull();
        assertThat(line.get("points").asInt()).isEqualTo(1);

        JsonNode lyon = check("Paris", "69001");
        assertThat(ids(lyon, "findings")).doesNotContain("6.1", "6.2");
        assertThat(ids(lyon, "notChecked")).doesNotContain("6.1", "6.2");
    }

    @Test
    void eventLinkedCheckGetsNearVenueClosure() throws Exception {
        double lat = 48.8606, lng = 2.3376;
        Event event = fx.event(org, user, EventStatus.DRAFT, null);
        events.updateVenueCoordinates(event.getId(), lat, lng);
        String ref = "IDFM:9" + ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999);
        // 300 m due north of the venue
        stops.replace(List.of(new Stop(ref, lat + Math.toDegrees(300 / 6_371_008.8), lng)), NOW);
        Disruption bus = new Disruption("bus-139-" + UUID.randomUUID(), "TRAVAUX", "BLOQUANTE", "works",
                "Bus 139 : Travaux - Arrêt non desservi", FEED,
                List.of(new LineRef("line:IDFM:C01139", "Bus 139", "Bus", "stop", ref)),
                List.of(new Period(Instant.parse("2026-10-10T21:00:00Z"), Instant.parse("2026-10-10T23:00:00Z"))));
        writer.replace(new Snapshot(FEED, List.of(bus), 0), NOW);

        JsonNode linked = check("Paris", "75011", event.getId());

        JsonNode works = finding(linked, "6.2");
        assertThat(works).isNotNull();
        assertThat(works.get("status").asText()).isEqualTo("found");
        assertThat(works.get("facts").get("scope").asText()).isEqualTo("near_venue");
        assertThat(works.get("facts").get("radiusM").asInt()).isEqualTo(800);
        assertThat(works.get("facts").get("lines").asText()).isEqualTo("Bus 139");
        JsonNode line = null;
        for (JsonNode l : linked.get("breakdown")) if (l.get("questionId").asText().equals("6.2")) line = l;
        assertThat(line).isNotNull();
        assertThat(line.get("points").asInt()).isEqualTo(1);

        // the same night without the event has no venue point, and no line-level rail closure
        JsonNode standalone = check("Paris", "75011");
        assertThat(finding(standalone, "6.2").get("status").asText()).isEqualTo("clear");
    }
}
