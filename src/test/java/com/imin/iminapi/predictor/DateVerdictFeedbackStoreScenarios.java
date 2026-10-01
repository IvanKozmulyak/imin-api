package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateVerdictAnswer;
import com.imin.iminapi.predictor.rules.DateResult;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.service.DateVerdictFeedbackStore;
import com.imin.iminapi.predictor.service.DateVerdictFeedbackStore.Row;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code date_verdict_feedback} table and its store, on H2 and on Postgres 17. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
abstract class DateVerdictFeedbackStoreScenarios {

    private static final Instant T1 = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-27T09:30:00Z");
    private static final LocalDate OCT24 = LocalDate.of(2026, 10, 24);
    private static final LocalDate NOV14 = LocalDate.of(2026, 11, 14);

    @Autowired DateVerdictFeedbackStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;

    private Event event;
    private DateCheck check;

    @BeforeEach
    void seed() {
        clean();
        Organization o = new Organization();
        o.setName("Verdict Store Org");
        o.setSlug("vs-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("vs@example.test");
        o.setCountry("FR");
        o = orgs.save(o);
        User u = new User();
        u.setOrgId(o.getId());
        u.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        u.setRole(UserRole.OWNER);
        u = users.save(u);
        Event e = new Event();
        e.setOrgId(o.getId());
        e.setCreatedBy(u.getId());
        e.setName("Verdict Night");
        e.setSlug("ev-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStartsAt(Instant.parse("2026-10-24T20:00:00Z"));
        event = events.save(e);
        DateCheck c = new DateCheck();
        c.setOrgId(o.getId());
        c.setCreatedBy(u.getId());
        c.setCity("Paris");
        c.setCountry("FR");
        c.setGenreFamily("house & techno");
        c.setStatus("done");
        c.setQuestionBankVersion("test");
        c.setEventId(event.getId());
        check = checks.save(c);
    }

    @AfterEach
    void after() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM date_verdict_feedback");
        checks.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM date_verdict_feedback", Integer.class);
    }

    @Test
    void insertIfAbsentKeepsFirstSnapshot() {
        store.insertIfAbsent(UUID.randomUUID(), event.getId(), check.getId(), OCT24, "good", "yes", null, T1, T1);
        store.insertIfAbsent(UUID.randomUUID(), event.getId(), null, NOV14, "move", "no", "second", T2, T2);

        assertThat(rows()).isEqualTo(1);
        Row first = store.find(event.getId()).orElseThrow();
        assertThat(first.dateCheckId()).isEqualTo(check.getId());
        assertThat(first.forDate()).isEqualTo(OCT24);
        assertThat(first.verdict()).isEqualTo("good");
        assertThat(first.answer()).isEqualTo("yes");
        assertThat(first.comment()).isNull();
        assertThat(first.createdAt()).isEqualTo(T1);
        assertThat(first.answeredAt()).isEqualTo(T1);

        assertThat(store.update(event.getId(), "no", "It rained", T2)).isEqualTo(1);

        Row after = store.find(event.getId()).orElseThrow();
        assertThat(after.answer()).isEqualTo("no");
        assertThat(after.comment()).isEqualTo("It rained");
        assertThat(after.answeredAt()).isEqualTo(T2);
        assertThat(after.createdAt()).isEqualTo(T1);
        assertThat(after.dateCheckId()).isEqualTo(check.getId());
        assertThat(after.forDate()).isEqualTo(OCT24);
        assertThat(after.verdict()).isEqualTo("good");
    }

    @Test
    void everyAnswerFitsTheCheckConstraint() {
        store.insertIfAbsent(UUID.randomUUID(), event.getId(), check.getId(), OCT24, "good", "yes", null, T1, T1);
        for (DateVerdictAnswer a : DateVerdictAnswer.values()) {
            store.update(event.getId(), a.wire(), null, T2);
            assertThat(store.find(event.getId()).orElseThrow().answer()).isEqualTo(a.wire());
        }
        assertThatThrownBy(() -> store.update(event.getId(), "maybe", null, T2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyRatedVerdictFitsTheCheckConstraintAndNotEnoughDataDoesNot() {
        for (DateResult.Verdict v : DateResult.Verdict.values()) {
            if (v == DateResult.Verdict.NOT_ENOUGH_DATA) continue;
            jdbc.update("DELETE FROM date_verdict_feedback");
            store.insertIfAbsent(UUID.randomUUID(), event.getId(), check.getId(), OCT24, v.dbValue(), "yes", null,
                    T1, T1);
            assertThat(store.find(event.getId()).orElseThrow().verdict()).isEqualTo(v.dbValue());
        }
        jdbc.update("DELETE FROM date_verdict_feedback");
        assertThatThrownBy(() -> store.insertIfAbsent(UUID.randomUUID(), event.getId(), check.getId(), OCT24,
                DateResult.Verdict.NOT_ENOUGH_DATA.dbValue(), "yes", null, T1, T1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void eventDeleteCascades() {
        store.insertIfAbsent(UUID.randomUUID(), event.getId(), check.getId(), OCT24, "good", "yes", "c", T1, T1);

        jdbc.update("DELETE FROM events WHERE id = ?", event.getId());

        assertThat(rows()).isZero();
    }

    @Test
    void checkDeleteKeepsAnswer() {
        store.insertIfAbsent(UUID.randomUUID(), event.getId(), check.getId(), OCT24, "adjust", "partly", "c", T1, T1);

        jdbc.update("DELETE FROM date_check WHERE id = ?", check.getId());

        Row r = store.find(event.getId()).orElseThrow();
        assertThat(r.dateCheckId()).isNull();
        assertThat(r.forDate()).isEqualTo(OCT24);
        assertThat(r.verdict()).isEqualTo("adjust");
        assertThat(r.answer()).isEqualTo("partly");
        assertThat(r.comment()).isEqualTo("c");
    }
}
