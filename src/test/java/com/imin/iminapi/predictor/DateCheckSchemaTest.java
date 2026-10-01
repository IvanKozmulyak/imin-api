package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.model.DateCheckFinding;
import com.imin.iminapi.predictor.model.GenreWeekCount;
import com.imin.iminapi.predictor.model.OrgConnector;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckFindingRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.repository.GenreWeekCountRepository;
import com.imin.iminapi.predictor.repository.OrgConnectorRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every date-check entity saved, flushed, cleared and read back through its finder. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class DateCheckSchemaTest {

    private static final Instant T = Instant.parse("2026-09-30T12:00:00.123456Z");

    @Autowired EntityManager em;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository dateChecks;
    @Autowired DateCheckDateRepository dates;
    @Autowired DateCheckFindingRepository findings;
    @Autowired ReferenceCalendarEntryRepository calendar;
    @Autowired PredictorJobRepository jobs;
    @Autowired GenreWeekCountRepository weekCounts;
    @Autowired OrgConnectorRepository connectors;
    @Autowired PredictionLedgerRepository ledger;

    private UUID orgId;
    private UUID userId;

    @BeforeEach
    void seed() {
        Organization o = new Organization();
        o.setName("Date Check Org");
        o.setSlug("date-check-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("dc@example.com");
        o.setCountry("FR");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("dc-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        userId = users.save(u).getId();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private UUID event() {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setSlug("dc-" + UUID.randomUUID().toString().substring(0, 8));
        e.setCreatedBy(userId);
        return events.save(e).getId();
    }

    private DateCheck dateCheck() {
        DateCheck d = new DateCheck();
        d.setOrgId(orgId);
        d.setCreatedBy(userId);
        d.setCity("Paris");
        d.setCountry("FR");
        d.setGenreFamily("electronic");
        d.setStatus("pending");
        d.setQuestionBankVersion("qb-1");
        return d;
    }

    @Test
    void dateCheckRoundTrip() {
        UUID eventId = event();
        DateCheck d = dateCheck();
        d.setSubGenre("techno");
        d.setCapacity(400);
        d.setPriceMinor(2500L);
        d.setFormat("club");
        d.setStartHour((short) 23);
        d.setEndHour((short) 6);
        d.setLineupJson("[\"A\"]");
        d.setKnownEventsJson("[{\"n\":1}]");
        d.setAssumptionsJson("[\"x\"]");
        d.setResearch(true);
        d.setStatus("done");
        d.setEventId(eventId);
        d.setCreatedAt(T);
        d.setUpdatedAt(T);
        UUID id = dateChecks.save(d).getId();
        flushAndClear();

        List<DateCheck> page = dateChecks.findByOrgIdAndOriginOrderByCreatedAtDesc(orgId, DateCheck.ORIGIN_ORGANIZER,
                PageRequest.of(0, 10));
        assertThat(page).hasSize(1);
        DateCheck r = page.get(0);
        assertThat(r.getId()).isEqualTo(id);
        assertThat(r.getOrgId()).isEqualTo(orgId);
        assertThat(r.getCreatedBy()).isEqualTo(userId);
        assertThat(r.getCity()).isEqualTo("Paris");
        assertThat(r.getCountry()).isEqualTo("FR");
        assertThat(r.getGenreFamily()).isEqualTo("electronic");
        assertThat(r.getSubGenre()).isEqualTo("techno");
        assertThat(r.getCapacity()).isEqualTo(400);
        assertThat(r.getPriceMinor()).isEqualTo(2500L);
        assertThat(r.getFormat()).isEqualTo("club");
        assertThat(r.getStartHour()).isEqualTo((short) 23);
        assertThat(r.getEndHour()).isEqualTo((short) 6);
        assertThat(r.getLineupJson()).isEqualTo("[\"A\"]");
        assertThat(r.getKnownEventsJson()).isEqualTo("[{\"n\":1}]");
        assertThat(r.getAssumptionsJson()).isEqualTo("[\"x\"]");
        assertThat(r.isResearch()).isTrue();
        assertThat(r.getStatus()).isEqualTo("done");
        assertThat(r.getQuestionBankVersion()).isEqualTo("qb-1");
        assertThat(r.getEventId()).isEqualTo(eventId);
        assertThat(r.getCreatedAt()).isEqualTo(T);
        assertThat(r.getUpdatedAt()).isEqualTo(T);
    }

    @Test
    void dateCheckDefaultsAndUpdateRefreshesUpdatedAt() {
        DateCheck d = dateChecks.save(dateCheck());
        flushAndClear();
        DateCheck r = dateChecks.findById(d.getId()).orElseThrow();
        assertThat(r.getAssumptionsJson()).isEqualTo("[]");
        assertThat(r.isResearch()).isFalse();
        assertThat(r.getLineupJson()).isNull();
        assertThat(r.getCreatedAt()).isNotNull().isEqualTo(r.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        Instant before = r.getUpdatedAt();

        r.setUpdatedAt(before.minusSeconds(60));
        r.setStatus("running");
        flushAndClear();
        DateCheck again = dateChecks.findById(d.getId()).orElseThrow();
        assertThat(again.getStatus()).isEqualTo("running");
        assertThat(again.getUpdatedAt()).isAfter(before.minusSeconds(60));
    }

    @Test
    void dateCheckDateAndFindingRoundTrip() {
        UUID dcId = dateChecks.save(dateCheck()).getId();
        DateCheckDate late = new DateCheckDate();
        late.setDateCheckId(dcId);
        late.setCandidateDate(LocalDate.of(2026, 11, 21));
        late.setVerdict("move");
        late.setRiskScore((short) 8);
        late.setOppScore((short) 1);
        late.setCoverage(new BigDecimal("0.500"));
        dates.save(late);
        DateCheckDate early = new DateCheckDate();
        early.setDateCheckId(dcId);
        early.setCandidateDate(LocalDate.of(2026, 11, 14));
        early.setVerdict("good");
        early.setRiskScore((short) 2);
        early.setOppScore((short) 7);
        early.setCoverage(new BigDecimal("0.875"));
        early.setRankOrder((short) 1);
        early.setActionsJson("[\"promo\"]");
        UUID earlyId = dates.save(early).getId();

        DateCheckFinding f = new DateCheckFinding();
        f.setDateCheckDateId(earlyId);
        f.setQuestionId("Q7");
        f.setKind("opportunity");
        f.setStatus("found");
        f.setStrength((short) 3);
        f.setWeight((short) 2);
        f.setSourceKind("structured");
        f.setTimeWindow("week");
        f.setStopFactor(true);
        f.setFactsJson("{\"a\":1}");
        f.setUrl("https://example.org/x");
        f.setQuote("quoted");
        f.setFetchedAt(T);
        UUID fId = findings.save(f).getId();
        flushAndClear();

        List<DateCheckDate> rows = dates.findByDateCheckIdOrderByCandidateDateAsc(dcId);
        assertThat(rows).extracting(DateCheckDate::getCandidateDate)
                .containsExactly(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 21));
        DateCheckDate r = rows.get(0);
        assertThat(r.getId()).isEqualTo(earlyId);
        assertThat(r.getDateCheckId()).isEqualTo(dcId);
        assertThat(r.getVerdict()).isEqualTo("good");
        assertThat(r.getRiskScore()).isEqualTo((short) 2);
        assertThat(r.getOppScore()).isEqualTo((short) 7);
        assertThat(r.getCoverage()).isEqualByComparingTo("0.875");
        assertThat(r.getRankOrder()).isEqualTo((short) 1);
        assertThat(r.getActionsJson()).isEqualTo("[\"promo\"]");
        assertThat(rows.get(1).getRankOrder()).isNull();
        assertThat(rows.get(1).getActionsJson()).isEqualTo("[]");

        List<DateCheckFinding> fs = findings.findByDateCheckDateIdIn(List.of(earlyId, rows.get(1).getId()));
        assertThat(fs).hasSize(1);
        DateCheckFinding rf = fs.get(0);
        assertThat(rf.getId()).isEqualTo(fId);
        assertThat(rf.getDateCheckDateId()).isEqualTo(earlyId);
        assertThat(rf.getQuestionId()).isEqualTo("Q7");
        assertThat(rf.getKind()).isEqualTo("opportunity");
        assertThat(rf.getStatus()).isEqualTo("found");
        assertThat(rf.getStrength()).isEqualTo((short) 3);
        assertThat(rf.getWeight()).isEqualTo((short) 2);
        assertThat(rf.getSourceKind()).isEqualTo("structured");
        assertThat(rf.getTimeWindow()).isEqualTo("week");
        assertThat(rf.isStopFactor()).isTrue();
        assertThat(rf.getFactsJson()).isEqualTo("{\"a\":1}");
        assertThat(rf.getUrl()).isEqualTo("https://example.org/x");
        assertThat(rf.getQuote()).isEqualTo("quoted");
        assertThat(rf.getFetchedAt()).isEqualTo(T);
    }

    @Test
    void findingDefaults() {
        UUID dcId = dateChecks.save(dateCheck()).getId();
        DateCheckDate d = new DateCheckDate();
        d.setDateCheckId(dcId);
        d.setCandidateDate(LocalDate.of(2026, 11, 14));
        d.setVerdict("not_enough_data");
        d.setCoverage(BigDecimal.ZERO);
        UUID dId = dates.save(d).getId();
        DateCheckFinding f = new DateCheckFinding();
        f.setDateCheckDateId(dId);
        f.setQuestionId("Q1");
        f.setKind("risk");
        f.setStatus("not_checked");
        f.setWeight((short) 1);
        f.setSourceKind("web");
        f.setTimeWindow("night");
        findings.save(f);
        flushAndClear();

        DateCheckFinding r = findings.findByDateCheckDateIdIn(List.of(dId)).get(0);
        assertThat(r.isStopFactor()).isFalse();
        assertThat(r.getFactsJson()).isEqualTo("{}");
        assertThat(r.getStrength()).isZero();
        assertThat(r.getUrl()).isNull();
        assertThat(r.getFetchedAt()).isNull();
    }

    @Test
    void referenceCalendarRoundTripMatchesCountryWideAndRegionRows() {
        ReferenceCalendarEntry national = new ReferenceCalendarEntry();
        national.setCountry("FR");
        national.setCalendarDate(LocalDate.of(2026, 12, 25));
        national.setKind("holiday");
        national.setName("Noel");
        national.setSourceUrl("https://example.org/h");
        national.setSyncedAt(T);
        UUID nationalId = calendar.save(national).getId();
        ReferenceCalendarEntry school = new ReferenceCalendarEntry();
        school.setCountry("FR");
        school.setRegion("C");
        school.setCalendarDate(LocalDate.of(2026, 12, 19));
        school.setEndDate(LocalDate.of(2027, 1, 4));
        school.setKind("school");
        school.setName("Vacances de Noel");
        school.setSourceUrl("https://example.org/s");
        school.setSyncedAt(T);
        calendar.save(school);
        ReferenceCalendarEntry otherRegion = new ReferenceCalendarEntry();
        otherRegion.setCountry("FR");
        otherRegion.setRegion("A");
        otherRegion.setCalendarDate(LocalDate.of(2026, 12, 20));
        otherRegion.setKind("school");
        otherRegion.setName("Vacances de Noel");
        otherRegion.setSourceUrl("https://example.org/s");
        otherRegion.setSyncedAt(T);
        calendar.save(otherRegion);
        flushAndClear();

        List<ReferenceCalendarEntry> rows = calendar.findByCountryAndRegionInAndCalendarDateBetween(
                "FR", List.of("", "C"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
        assertThat(rows).extracting(ReferenceCalendarEntry::getName)
                .containsExactlyInAnyOrder("Noel", "Vacances de Noel");
        ReferenceCalendarEntry r = rows.stream().filter(x -> x.getId().equals(nationalId)).findFirst().orElseThrow();
        assertThat(r.getCountry()).isEqualTo("FR");
        assertThat(r.getRegion()).isEmpty();
        assertThat(r.getCalendarDate()).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(r.getEndDate()).isNull();
        assertThat(r.getKind()).isEqualTo("holiday");
        assertThat(r.getSourceUrl()).isEqualTo("https://example.org/h");
        assertThat(r.getSyncedAt()).isEqualTo(T);
        ReferenceCalendarEntry s = rows.stream().filter(x -> !x.getId().equals(nationalId)).findFirst().orElseThrow();
        assertThat(s.getRegion()).isEqualTo("C");
        assertThat(s.getEndDate()).isEqualTo(LocalDate.of(2027, 1, 4));
    }

    @Test
    void predictorJobRoundTrip() {
        PredictorJob j = new PredictorJob();
        j.setKind("calendar_sync");
        j.setPayloadJson("{\"country\":\"FR\"}");
        j.setStatus("running");
        j.setAttempts(2);
        j.setRunAfter(T);
        j.setLockedUntil(T.plusSeconds(300));
        j.setLastError("boom");
        j.setCreatedAt(T);
        j.setUpdatedAt(T);
        UUID id = jobs.save(j).getId();
        flushAndClear();

        PredictorJob r = jobs.findById(id).orElseThrow();
        assertThat(r.getKind()).isEqualTo("calendar_sync");
        assertThat(r.getPayloadJson()).isEqualTo("{\"country\":\"FR\"}");
        assertThat(r.getStatus()).isEqualTo("running");
        assertThat(r.getAttempts()).isEqualTo(2);
        assertThat(r.getRunAfter()).isEqualTo(T);
        assertThat(r.getLockedUntil()).isEqualTo(T.plusSeconds(300));
        assertThat(r.getLastError()).isEqualTo("boom");
        assertThat(r.getCreatedAt()).isEqualTo(T);
        assertThat(r.getUpdatedAt()).isEqualTo(T);
    }

    @Test
    void predictorJobDefaults() {
        PredictorJob j = new PredictorJob();
        j.setKind("calendar_sync");
        j.setStatus("queued");
        UUID id = jobs.save(j).getId();
        flushAndClear();

        PredictorJob r = jobs.findById(id).orElseThrow();
        assertThat(r.getPayloadJson()).isEqualTo("{}");
        assertThat(r.getAttempts()).isZero();
        assertThat(r.getRunAfter()).isNotNull();
        assertThat(r.getLockedUntil()).isNull();
        assertThat(r.getCreatedAt()).isNotNull();
        assertThat(r.getUpdatedAt()).isNotNull();
    }

    @Test
    void genreWeekCountRoundTrip() {
        GenreWeekCount g = new GenreWeekCount();
        g.setCityKey("paris");
        g.setGenreFamily("electronic");
        g.setWeekStart(LocalDate.of(2026, 10, 5));
        g.setEventCount(4);
        g.setSourcesJson("[\"osm\"]");
        g.setUpdatedAt(T);
        UUID id = weekCounts.save(g).getId();
        GenreWeekCount sub = new GenreWeekCount();
        sub.setCityKey("paris");
        sub.setGenreFamily("electronic");
        sub.setSubGenre("techno");
        sub.setWeekStart(LocalDate.of(2026, 10, 5));
        sub.setEventCount(1);
        weekCounts.save(sub);
        flushAndClear();

        GenreWeekCount r = weekCounts.findByCityKeyAndGenreFamilyAndSubGenreAndWeekStart(
                "paris", "electronic", "", LocalDate.of(2026, 10, 5)).orElseThrow();
        assertThat(r.getId()).isEqualTo(id);
        assertThat(r.getCityKey()).isEqualTo("paris");
        assertThat(r.getGenreFamily()).isEqualTo("electronic");
        assertThat(r.getSubGenre()).isEmpty();
        assertThat(r.getWeekStart()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(r.getEventCount()).isEqualTo(4);
        assertThat(r.getSourcesJson()).isEqualTo("[\"osm\"]");
        assertThat(r.getUpdatedAt()).isEqualTo(T);
        GenreWeekCount rs = weekCounts.findByCityKeyAndGenreFamilyAndSubGenreAndWeekStart(
                "paris", "electronic", "techno", LocalDate.of(2026, 10, 5)).orElseThrow();
        assertThat(rs.getEventCount()).isEqualTo(1);
        assertThat(rs.getSourcesJson()).isEqualTo("[]");
        assertThat(rs.getUpdatedAt()).isNotNull();
    }

    @Test
    void orgConnectorRoundTripSkipsRevoked() {
        OrgConnector live = new OrgConnector();
        live.setOrgId(orgId);
        live.setKind("shotgun");
        live.setTokenEnc("enc-1");
        live.setScopes("events:read");
        live.setConnectedBy(userId);
        live.setConnectedAt(T);
        UUID liveId = connectors.save(live).getId();
        OrgConnector revoked = new OrgConnector();
        revoked.setOrgId(orgId);
        revoked.setKind("dice");
        revoked.setTokenEnc("enc-2");
        revoked.setConnectedBy(userId);
        revoked.setConnectedAt(T);
        revoked.setRevokedAt(T.plusSeconds(10));
        connectors.save(revoked);
        flushAndClear();

        List<OrgConnector> rows = connectors.findByOrgIdAndRevokedAtIsNull(orgId);
        assertThat(rows).hasSize(1);
        OrgConnector r = rows.get(0);
        assertThat(r.getId()).isEqualTo(liveId);
        assertThat(r.getOrgId()).isEqualTo(orgId);
        assertThat(r.getKind()).isEqualTo("shotgun");
        assertThat(r.getTokenEnc()).isEqualTo("enc-1");
        assertThat(r.getScopes()).isEqualTo("events:read");
        assertThat(r.getConnectedBy()).isEqualTo(userId);
        assertThat(r.getConnectedAt()).isEqualTo(T);
        assertThat(r.getRevokedAt()).isNull();
    }

    @Test
    void ledgerEntityWritesDateCheckSurfaceWithoutEvent() {
        UUID dcId = dateChecks.save(dateCheck()).getId();
        PredictionLedger l = ledgerRow(null, PredictionSurface.DATE_CHECK);
        l.setDateCheckId(dcId);
        l.setQuestionBankVersion("qb-1");
        l.setTokensIn(1200);
        l.setTokensOut(300);
        l.setCostUsd(new BigDecimal("0.012345"));
        l.setSearches(4);
        UUID id = ledger.save(l).getId();
        flushAndClear();

        PredictionLedger r = ledger.findById(id).orElseThrow();
        assertThat(r.getEventId()).isNull();
        assertThat(r.getSurface()).isEqualTo(PredictionSurface.DATE_CHECK);
        assertThat(r.getDateCheckId()).isEqualTo(dcId);
        assertThat(r.getQuestionBankVersion()).isEqualTo("qb-1");
        assertThat(r.getTokensIn()).isEqualTo(1200);
        assertThat(r.getTokensOut()).isEqualTo(300);
        assertThat(r.getCostUsd()).isEqualByComparingTo("0.012345");
        assertThat(r.getSearches()).isEqualTo(4);
    }

    @Test
    void ledgerEntityRejectsNullEventForPrePublish() {
        ledger.save(ledgerRow(null, PredictionSurface.PRE_PUBLISH));
        assertThatThrownBy(this::flushAndClear)
                .satisfies(ex -> assertThat(rootMessage(ex)).containsIgnoringCase("ck_prediction_ledger_event_or_date_check"));
    }

    @Test
    void eventsSubGenreAndDateCheckIdPersist() {
        UUID dcId = dateChecks.save(dateCheck()).getId();
        UUID eventId = event();
        Event e = events.findById(eventId).orElseThrow();
        e.setSubGenre("techno");
        e.setDateCheckId(dcId);
        flushAndClear();

        Event r = events.findById(eventId).orElseThrow();
        assertThat(r.getSubGenre()).isEqualTo("techno");
        assertThat(r.getDateCheckId()).isEqualTo(dcId);
    }

    private PredictionLedger ledgerRow(UUID eventId, PredictionSurface surface) {
        PredictionLedger l = new PredictionLedger();
        l.setEventId(eventId);
        l.setOrgId(orgId);
        l.setSurface(surface);
        l.setStage((short) 0);
        l.setModelId("m");
        l.setPromptVersion("1.0.0");
        l.setInputSnapshotHash("h");
        return l;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
