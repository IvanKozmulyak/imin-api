package com.imin.iminapi.migration;

import com.imin.iminapi.audience.service.ConsentConfirmationReconciler;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Re-runs V155's data statements over seeded rows on the shared Postgres, rolled back so nothing leaks. */
@IminIntegrationTest
@Transactional
class ConsentConfirmationMigrationTest {

    static final Instant T1 = Instant.parse("2026-09-01T10:00:00Z");
    static final Instant T2 = Instant.parse("2026-09-20T10:00:00Z");
    static final Instant T3 = Instant.parse("2026-09-27T10:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired ConsentConfirmationReconciler reconciler;

    UUID orgId;

    @Test
    void signUpsAwaitConfirmation_membersFallBackToTheirOtherRecords_momentumSnapshotsMoveToOrigin() throws Exception {
        orgId = org();
        UUID doorOnly = member("never", null);
        record(doorOnly, "subscribed", "explicit", "door_qr", T2);
        memberState(doorOnly, "subscribed", "explicit");

        UUID checkoutThenDoor = member("never", null);
        record(checkoutThenDoor, "subscribed", "explicit", "checkout", T1);
        record(checkoutThenDoor, "subscribed", "explicit", "door_qr", T2);
        memberState(checkoutThenDoor, "subscribed", "explicit");

        UUID softThenSurvey = member("never", null);
        record(softThenSurvey, "subscribed", "soft_opt_in", "checkout", T1);
        record(softThenSurvey, "subscribed", "explicit", "survey", T2);
        memberState(softThenSurvey, "subscribed", "explicit");

        UUID doorThenCheckout = member("never", null);
        record(doorThenCheckout, "subscribed", "explicit", "door_qr", T1);
        record(doorThenCheckout, "subscribed", "explicit", "checkout", T2);
        memberState(doorThenCheckout, "subscribed", "explicit");

        UUID doorThenUnsubscribed = member("never", null);
        record(doorThenUnsubscribed, "subscribed", "explicit", "door_qr", T1);
        record(doorThenUnsubscribed, "unsubscribed", null, "one_click", T2);
        memberState(doorThenUnsubscribed, "unsubscribed", null);

        UUID doorThenSmsOnly = member("never", null);
        record(doorThenSmsOnly, "subscribed", "explicit", "door_qr", T1);
        UUID sms = record(doorThenSmsOnly, "subscribed", "explicit", "order_confirmation", T3);
        jdbc.update("update consent_records set channel = 'sms' where id = ?", sms);
        memberState(doorThenSmsOnly, "subscribed", "explicit");

        UUID checkoutOnly = member("never", null);
        UUID checkout = record(checkoutOnly, "subscribed", "explicit", "checkout", T1);
        memberState(checkoutOnly, "subscribed", "explicit");

        UUID snapshot = segment("MOMENTUM_PLAN", "organizer");
        UUID repeat = segment("REPEAT", "organizer");

        runDataStatements();

        assertThat(jdbc.queryForObject("select count(*) from consent_records r join memberships m"
                + " on m.membership_id = r.membership_id where m.org_id = ? and r.source in ('door_qr', 'survey')"
                + " and r.confirmation_required = FALSE", Integer.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("select confirmation_required from consent_records where id = ?",
                Boolean.class, checkout)).isFalse();
        assertState(doorOnly, "never", null);
        assertState(checkoutThenDoor, "subscribed", "explicit");
        assertState(softThenSurvey, "subscribed", "soft_opt_in");
        assertState(doorThenCheckout, "subscribed", "explicit");
        assertState(doorThenUnsubscribed, "unsubscribed", null);
        assertState(doorThenSmsOnly, "never", null);
        assertState(checkoutOnly, "subscribed", "explicit");
        Map<String, Object> s = jdbc.queryForMap("select origin, prebuilt_key from segments where id = ?", snapshot);
        assertThat(s.get("origin")).isEqualTo("momentum");
        assertThat(s.get("prebuilt_key")).isNull();
        Map<String, Object> r = jdbc.queryForMap("select origin, prebuilt_key from segments where id = ?", repeat);
        assertThat(r.get("origin")).isEqualTo("organizer");
        assertThat(r.get("prebuilt_key")).isEqualTo("REPEAT");
    }

    @Test
    void anAlreadyConfirmedSignUp_isLeftAlone() throws Exception {
        orgId = org();
        UUID confirmed = member("never", null);
        UUID door = record(confirmed, "subscribed", "explicit", "door_qr", T1);
        jdbc.update("update consent_records set confirmation_required = TRUE, confirmed_at = ? where id = ?",
                Timestamp.from(T2), door);
        memberState(confirmed, "subscribed", "explicit");

        runDataStatements();

        assertState(confirmed, "subscribed", "explicit");
    }

    @Test
    void reconciler_flagsASignUpWrittenUnflaggedAfterTheMigration_andRestoresOnlyItsMember() {
        orgId = org();
        UUID checkoutThenDoor = member("never", null);
        record(checkoutThenDoor, "subscribed", "soft_opt_in", "checkout", T1);
        UUID door = record(checkoutThenDoor, "subscribed", "explicit", "door_qr", T2);
        memberState(checkoutThenDoor, "subscribed", "explicit");

        UUID surveyOnly = member("never", null);
        UUID survey = record(surveyOnly, "subscribed", "explicit", "survey", T2);
        memberState(surveyOnly, "subscribed", "explicit");

        // State set without a consent record (not a sign-up) must survive the pass.
        UUID untouched = member("never", null);
        record(untouched, "subscribed", "explicit", "checkout", T1);
        memberState(untouched, "unsubscribed", null);

        // At least: the shared database may hold other suites' committed fixtures.
        assertThat(reconciler.run()).hasValueSatisfying(n -> assertThat(n).isGreaterThanOrEqualTo(2));

        assertThat(jdbc.queryForObject("select confirmation_required from consent_records where id = ?",
                Boolean.class, door)).isTrue();
        assertThat(jdbc.queryForObject("select confirmation_required from consent_records where id = ?",
                Boolean.class, survey)).isTrue();
        assertState(checkoutThenDoor, "subscribed", "soft_opt_in");
        assertState(surveyOnly, "never", null);
        assertState(untouched, "unsubscribed", null);

        memberState(surveyOnly, "unsubscribed", null);
        assertThat(reconciler.run()).contains(0);
        assertState(checkoutThenDoor, "subscribed", "soft_opt_in");
        assertState(surveyOnly, "unsubscribed", null);
        assertState(untouched, "unsubscribed", null);
    }

    private void runDataStatements() throws Exception {
        String sql;
        try (var in = new ClassPathResource(
                "db/migration/V155__consent_confirmation_and_momentum_segments.sql").getInputStream()) {
            sql = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        String withoutComments = Arrays.stream(sql.split("\n"))
                .filter(l -> !l.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + b + "\n");
        int run = 0;
        for (String statement : withoutComments.split(";")) {
            String st = statement.strip();
            if (st.isEmpty() || st.startsWith("ALTER")) continue;
            jdbc.execute(st);
            run++;
        }
        assertThat(run).isEqualTo(3);
    }

    private UUID org() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organizations (id, name, slug, contact_email, country) values (?, ?, ?, ?, ?)",
                id, "V155 Org", "v155-" + id.toString().substring(0, 12), "v155@example.com", "FR");
        return id;
    }

    private UUID member(String status, String basis) {
        UUID consumer = UUID.randomUUID();
        jdbc.update("insert into consumers (consumer_id, normalized_email) values (?, ?)",
                consumer, "v155-" + consumer + "@example.com");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into memberships (membership_id, org_id, consumer_id, consent_status, consent_basis)"
                + " values (?, ?, ?, ?, ?)", id, orgId, consumer, status, basis);
        return id;
    }

    private UUID record(UUID membershipId, String status, String basis, String source, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into consent_records (id, membership_id, channel, status, lawful_basis, source, occurred_at)"
                + " values (?, ?, 'email', ?, ?, ?, ?)", id, membershipId, status, basis, source, Timestamp.from(at));
        return id;
    }

    private void memberState(UUID membershipId, String status, String basis) {
        jdbc.update("update memberships set consent_status = ?, consent_basis = ? where membership_id = ?",
                status, basis, membershipId);
    }

    private void assertState(UUID membershipId, String status, String basis) {
        Map<String, Object> m = jdbc.queryForMap(
                "select consent_status, consent_basis from memberships where membership_id = ?", membershipId);
        assertThat(m.get("consent_status")).as("status of " + membershipId).isEqualTo(status);
        assertThat(m.get("consent_basis")).as("basis of " + membershipId).isEqualTo(basis);
    }

    private UUID segment(String prebuiltKey, String origin) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into segments (id, org_id, name, kind, prebuilt, prebuilt_key, origin)"
                + " values (?, ?, ?, 'static', FALSE, ?, ?)", id, orgId, "seg " + prebuiltKey, prebuiltKey, origin);
        return id;
    }
}
