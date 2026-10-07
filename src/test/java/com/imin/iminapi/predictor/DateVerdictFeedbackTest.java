package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.service.DateVerdictFeedbackStore;
import com.imin.iminapi.predictor.service.DateVerdictFeedbackStore.Row;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.PredictorRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The after-event "did the date verdict match?" answer, gate open, clock at 26 Oct 2026. The org is French, so the
 * check scores in Europe/Paris (UTC+2 until 25 Oct): a 20:00Z start on 24 Oct is the night of 24 Oct.
 */
@IminIntegrationTest
class DateVerdictFeedbackTest {

    private static final LocalDate OCT24 = LocalDate.of(2026, 10, 24);
    private static final LocalDate NOV14 = LocalDate.of(2026, 11, 14);
    private static final Instant T1 = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-27T08:15:00Z");

    @Autowired MutableClock clock;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired DateCheckProperties props;
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;
    @Autowired DateVerdictFeedbackStore store;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper om = new ObjectMapper();
    private final String city = "Paris" + DateCheckControllerTest.letters();
    private final List<UUID> createdOrgs = new ArrayList<>();

    private Organization org;
    private User owner;

    @BeforeEach
    void seed() {
        clock.setInstant(T1);
        flips.set(props, "enabled", true);
        flips.set(props, "allOrgs", true);
        org = org();
        owner = fx.owner(org);
    }

    @AfterEach
    void after() {
        PredictorRows.delete(jdbc, createdOrgs);
    }

    private Organization org() {
        Organization o = fx.org();
        createdOrgs.add(o.getId());
        o.setCountry("FR");
        return orgs.save(o);
    }

    private UUID ownCheckId() {
        return jdbc.queryForObject("SELECT id FROM date_check WHERE org_id = ?", UUID.class, org.getId());
    }

    private long ownCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " t JOIN events e ON e.id = t.event_id"
                + " WHERE e.org_id = ?", Long.class, org.getId());
    }

    private Authentication mine() {
        AuthPrincipal p = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    private Event event(Organization o, User u, EventStatus status) {
        Event e = new Event();
        e.setOrgId(o.getId());
        e.setCreatedBy(u.getId());
        e.setName("Verdict Night");
        e.setSlug("ev-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStartsAt(OCT24.atTime(20, 0).toInstant(ZoneOffset.UTC));
        e.setStatus(status);
        return events.save(e);
    }

    /** A check linked to the event, one row per (date, verdict) pair, no findings. */
    private DateCheck seedCheck(Organization o, User u, UUID eventId, Object... dateVerdict) {
        DateCheck c = new DateCheck();
        c.setOrgId(o.getId());
        c.setCreatedBy(u.getId());
        c.setCity(city);
        c.setCountry("FR");
        c.setGenreFamily("house & techno");
        c.setStatus("done");
        c.setQuestionBankVersion("test");
        c.setEventId(eventId);
        c.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
        c = checks.save(c);
        for (int i = 0; i < dateVerdict.length; i += 2) {
            DateCheckDate d = new DateCheckDate();
            d.setDateCheckId(c.getId());
            d.setCandidateDate((LocalDate) dateVerdict[i]);
            d.setVerdict((String) dateVerdict[i + 1]);
            d.setRiskScore((short) 2);
            d.setOppScore((short) 6);
            d.setCoverage(new BigDecimal("0.800"));
            d.setRankOrder((short) (i / 2 + 1));
            checkDates.save(d);
        }
        return c;
    }

    private Event pastEventWithGoodCheck() {
        Event e = event(org, owner, EventStatus.PAST);
        seedCheck(org, owner, e.getId(), OCT24, "good");
        return e;
    }

    private ResultActions answer(UUID eventId, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/events/" + eventId + "/prediction/feedback").with(authentication(mine()))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private static Map<String, Object> body(String answer, String comment) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "date_verdict_match");
        if (answer != null) b.put("answer", answer);
        if (comment != null) b.put("comment", comment);
        return b;
    }

    /** Answers on this test's orgs' events. */
    private int rows() {
        String in = String.join(",", java.util.Collections.nCopies(createdOrgs.size(), "?"));
        return jdbc.queryForObject("SELECT COUNT(*) FROM date_verdict_feedback f JOIN events e ON e.id = f.event_id"
                + " WHERE e.org_id IN (" + in + ")", Integer.class, createdOrgs.toArray());
    }

    @Test
    void verdictFeedbackStoredOncePerEvent() throws Exception {
        Event e = pastEventWithGoodCheck();

        answer(e.getId(), body("yes", "Packed by midnight")).andExpect(status().isNoContent());
        // The check is re-rated after the first answer; the stored snapshot must not follow it.
        jdbc.update("UPDATE date_check_date SET verdict = 'move' WHERE date_check_id = ?", ownCheckId());
        clock.setInstant(T2);
        answer(e.getId(), body("no", "Half empty after all")).andExpect(status().isNoContent());

        assertThat(rows()).isEqualTo(1);
        Row r = store.find(e.getId()).orElseThrow();
        assertThat(r.answer()).isEqualTo("no");
        assertThat(r.comment()).isEqualTo("Half empty after all");
        assertThat(r.verdict()).isEqualTo("good");
        assertThat(r.forDate()).isEqualTo(OCT24);
        assertThat(r.dateCheckId()).isEqualTo(ownCheckId());
        assertThat(r.createdAt()).isEqualTo(T1);
        assertThat(r.answeredAt()).isEqualTo(T2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_ledger WHERE org_id = ?", Long.class,
                org.getId())).isZero();
        assertThat(ownCount("prediction_feedback")).isZero();
    }

    @Test
    void reAnswerKeepsWorkingAfterTheCheckBecomesIneligible() throws Exception {
        Event e = pastEventWithGoodCheck();
        UUID checkId = ownCheckId();
        answer(e.getId(), body("yes", "first")).andExpect(status().isNoContent());

        jdbc.update("UPDATE date_check_date SET verdict = 'not_enough_data' WHERE date_check_id = ?", checkId);
        clock.setInstant(T2);
        answer(e.getId(), body("partly", "second")).andExpect(status().isNoContent());

        assertThat(rows()).isEqualTo(1);
        Row r = store.find(e.getId()).orElseThrow();
        assertThat(r.answer()).isEqualTo("partly");
        assertThat(r.comment()).isEqualTo("second");
        assertThat(r.answeredAt()).isEqualTo(T2);
        assertThat(r.verdict()).isEqualTo("good");
        assertThat(r.forDate()).isEqualTo(OCT24);
        assertThat(r.dateCheckId()).isEqualTo(checkId);
    }

    @Test
    void verdictQuestionRefusedBeforeEventEnds() throws Exception {
        Event e = event(org, owner, EventStatus.LIVE);
        seedCheck(org, owner, e.getId(), OCT24, "good");

        answer(e.getId(), body("yes", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message").value("The event has not ended"));

        assertThat(rows()).isZero();
    }

    @Test
    void verdictQuestionRefusedWithoutDateCheck() throws Exception {
        Event e = event(org, owner, EventStatus.PAST);

        answer(e.getId(), body("yes", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message").value("No date verdict to rate for this event"));

        assertThat(rows()).isZero();
    }

    @Test
    void verdictQuestionRefusedForStaleCheck() throws Exception {
        Event e = event(org, owner, EventStatus.PAST);
        seedCheck(org, owner, e.getId(), NOV14, "good");

        answer(e.getId(), body("yes", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("No date verdict to rate for this event"));

        assertThat(rows()).isZero();
    }

    @Test
    void verdictQuestionRefusedForNotEnoughData() throws Exception {
        Event e = event(org, owner, EventStatus.PAST);
        seedCheck(org, owner, e.getId(), OCT24, "not_enough_data");

        answer(e.getId(), body("yes", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("No date verdict to rate for this event"));

        assertThat(rows()).isZero();
    }

    @Test
    void verdictAnswerRequired() throws Exception {
        Event e = pastEventWithGoodCheck();

        answer(e.getId(), body(null, "no answer")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.answer").value("required"));
        answer(e.getId(), body("  ", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.answer").value("required"));

        assertThat(rows()).isZero();
    }

    @Test
    void verdictAnswerOutsideTheSetIsRejected() throws Exception {
        Event e = pastEventWithGoodCheck();

        answer(e.getId(), body("maybe", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.answer").value("invalid"));
        answer(e.getId(), body("YES", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.answer").value("invalid"));

        assertThat(rows()).isZero();
    }

    static Stream<Arguments> comments() {
        return Stream.of(
                Arguments.of("x".repeat(1001), false, null),
                Arguments.of("y".repeat(1000), true, "y".repeat(1000)),
                Arguments.of("   ", true, null),
                Arguments.of("  Queue round the block \n", true, "Queue round the block"));
    }

    /** Over 1000 characters is a 400 that stores nothing; a blank comment is stored as null, others trimmed. */
    @ParameterizedTest
    @MethodSource("comments")
    void verdictCommentIsBoundedAndTrimmed(String comment, boolean accepted, String stored) throws Exception {
        Event e = pastEventWithGoodCheck();

        ResultActions r = answer(e.getId(), body("partly", comment));

        if (!accepted) {
            r.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                    .andExpect(jsonPath("$.error.fields.comment").exists());
            assertThat(rows()).isZero();
        } else {
            r.andExpect(status().isNoContent());
            assertThat(store.find(e.getId()).orElseThrow().comment()).isEqualTo(stored);
        }
    }

    @Test
    void verdictFeedbackCrossOrgIsNotFound() throws Exception {
        Organization other = org();
        User otherOwner = fx.owner(other);
        Event theirs = event(other, otherOwner, EventStatus.PAST);
        seedCheck(other, otherOwner, theirs.getId(), OCT24, "good");

        answer(theirs.getId(), body("yes", null)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(rows()).isZero();
    }

    @Test
    void statusCarriesVerdictFeedback() throws Exception {
        Event e = pastEventWithGoodCheck();
        UUID checkId = ownCheckId();
        answer(e.getId(), body("partly", "Late crowd")).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck.forDate").value("2026-10-24"))
                .andExpect(jsonPath("$.verdictFeedback.answer").value("partly"))
                .andExpect(jsonPath("$.verdictFeedback.comment").value("Late crowd"))
                .andExpect(jsonPath("$.verdictFeedback.verdict").value("good"))
                .andExpect(jsonPath("$.verdictFeedback.forDate").value("2026-10-24"))
                .andExpect(jsonPath("$.verdictFeedback.dateCheckId").value(checkId.toString()))
                .andExpect(jsonPath("$.verdictFeedback.answeredAt").value(T1.toString()));
    }

    @Test
    void verdictFeedbackHiddenUntilPast() throws Exception {
        Event e = event(org, owner, EventStatus.LIVE);
        DateCheck c = seedCheck(org, owner, e.getId(), OCT24, "good");
        store.insertIfAbsent(UUID.randomUUID(), e.getId(), c.getId(), OCT24, "good", "yes", null, T1, T1);

        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateCheck").exists())
                .andExpect(jsonPath("$.verdictFeedback").doesNotExist());

        e.setStatus(EventStatus.PAST);
        events.save(e);
        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdictFeedback.answer").value("yes"));
    }
}
