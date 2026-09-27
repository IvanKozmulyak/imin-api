package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceImport;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.AudienceImportRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RetentionJob against the real ConsentGate query. Run on H2 ({@link RetentionJobTest}) and Postgres
 * ({@link RetentionJobPostgresTest}). The clock sits in 2040 so rows other tests leave behind are never fresh.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@ExtendWith(OutputCaptureExtension.class)
abstract class RetentionJobScenarios {

    static final Instant NOW = Instant.parse("2040-03-15T10:00:00Z");
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    /** Paris date 2040-03-15 − 1095 days: contact on this date is within, the day before is not. */
    static final LocalDate CUTOFF_DATE = LocalDate.parse("2040-03-15").minusDays(1095);
    static final Instant ON_CUTOFF = CUTOFF_DATE.atTime(12, 0).atZone(PARIS).toInstant();
    static final Instant DAY_BEFORE_CUTOFF = CUTOFF_DATE.minusDays(1).atTime(12, 0).atZone(PARIS).toInstant();
    static final Instant RECENT = NOW.minus(Duration.ofDays(10));

    @Autowired FanFeatureRepository fanRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AudiencePlanLogic logic;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired ConsentService consentService;
    @Autowired AudienceImportRepository importRepo;
    @Autowired ImportRowProvenanceRepository provenanceRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AuditLogger auditLogger;
    // Live recomputes run on the real clock; the calculator's retention rule has its own tests.
    @MockitoBean FanFeatureProjector projector;

    UUID orgA;
    private final List<UUID> orgs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orgA = org("Europe/Paris");
    }

    @AfterEach
    void tearDown() {
        for (UUID org : orgs) {
            List<UUID> mids = jdbc.queryForList("select membership_id from memberships where org_id = ?", UUID.class, org);
            List<UUID> cids = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, org);
            for (UUID mid : mids) {
                jdbc.update("delete from consent_records where membership_id = ?", mid);
                jdbc.update("delete from import_row_provenance where membership_id = ?", mid);
                jdbc.update("delete from fan_features where membership_id = ?", mid);
            }
            jdbc.update("delete from audience_imports where org_id = ?", org);
            jdbc.update("delete from marketing_optouts where org_id = ?", org);
            jdbc.update("delete from memberships where org_id = ?", org);
            for (UUID cid : cids) jdbc.update("delete from consumers where consumer_id = ?", cid);
            jdbc.update("delete from tickets where order_id in (select id from orders where org_id = ?)", org);
            jdbc.update("delete from orders where org_id = ?", org);
            jdbc.update("delete from events where org_id = ?", org);
            jdbc.update("delete from users where org_id = ?", org);
            jdbc.update("delete from organizations where id = ?", org);
        }
        orgs.clear();
    }

    // ── the 3-year rule ────────────────────────────────────────────────────

    @Test
    void contact1096DaysAgo_basisRemoved_retentionRecord_profilingCleared_notStickyNorObjected() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);

        RetentionJob.Result r = job(true).run();

        assertThat(r.cleared()).isEqualTo(1);
        assertCleared(mid);
    }

    @Test
    void contact1095DaysAgo_untouched() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, ON_CUTOFF);

        job(true).run();

        assertUntouched(mid);
    }

    @Test
    void recentEmailOpen_with1096DayContact_stillCleared() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", null, Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        jdbc.update("update memberships set last_email_open = ?, last_email_click = ? where membership_id = ?",
                Timestamp.from(NOW.minus(Duration.ofDays(1))), Timestamp.from(NOW.minus(Duration.ofDays(1))), mid);

        job(true).run();

        assertCleared(mid);
    }

    @Test
    void importedContact_nullLastContact_noProvenanceDate_cleared() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "organizer_import_row", "2040-03-01", RECENT);
        provenance(mid, null);
        contact(mid, null);

        job(true).run();

        assertCleared(mid);
    }

    @Test
    void importedContact_provenanceLastPurchaseOnCutoffDay_untouched_dayBefore_cleared() {
        UUID onCutoff = member(orgA);
        consent(onCutoff, "explicit", "organizer_import_row", "2040-03-01", RECENT);
        provenance(onCutoff, CUTOFF_DATE);
        contact(onCutoff, null);
        UUID dayBefore = member(orgA);
        consent(dayBefore, "explicit", "organizer_import_row", "2040-03-01", RECENT);
        provenance(dayBefore, CUTOFF_DATE.minusDays(1));
        contact(dayBefore, null);

        job(true).run();

        assertUntouched(onCutoff);
        assertCleared(dayBefore);
    }

    @Test
    void personConsentWithinWindow_untouched_evenWithOldFeatureContact() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", RECENT);
        contact(mid, DAY_BEFORE_CUTOFF);

        job(true).run();

        assertUntouched(mid);
    }

    @Test
    void retentionCutoff_usesTheOrgTimezone() {
        // 23:30 UTC the day before is already the cutoff day in Paris.
        Instant lateEvening = CUTOFF_DATE.minusDays(1).atTime(23, 30).toInstant(ZoneOffset.UTC);
        UUID paris = member(orgA);
        consent(paris, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(paris, lateEvening);
        UUID utcOrg = org("UTC");
        UUID utc = member(utcOrg);
        consent(utc, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(utc, lateEvening);

        job(true).run();

        assertUntouched(paris);
        assertCleared(utc);
    }

    // ── who is never a target ──────────────────────────────────────────────

    @Test
    void neverSubscribed_notTouched() {
        UUID mid = member(orgA);
        contact(mid, DAY_BEFORE_CUTOFF);

        job(true).run();

        assertThat(consentStatus(mid)).isEqualTo("never");
        assertThat(retentionRecords(mid)).isZero();
    }

    @Test
    void erasePending_notTouched() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        jdbc.update("update memberships set status = 'erase_pending' where membership_id = ?", mid);

        job(true).run();

        assertUntouched(mid);
    }

    @Test
    void staleFeatureRow_skipped() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        jdbc.update("update fan_features set updated_at = ? where membership_id = ?",
                Timestamp.from(NOW.minus(RetentionJob.FRESHNESS).minusSeconds(1)), mid);

        RetentionJob.Result r = job(true).run();

        assertThat(r.expired()).isZero();
        assertUntouched(mid);
    }

    @Test
    void noFeatureRow_skipped() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));

        job(true).run();

        assertUntouched(mid);
    }

    @Test
    void killSwitchOff_orgSkipped() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        AudiencePlanProperties off = props(true);
        off.setEnabled(false);

        RetentionJob.Result r = job(off).run();

        assertThat(r.expired()).isZero();
        assertUntouched(mid);
    }

    // ── dry run and idempotency ────────────────────────────────────────────

    @Test
    void flagOff_nothingWritten_countReturnedAndLogged(CapturedOutput output) {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);

        RetentionJob.Result r = job(false).run();

        assertThat(r).isEqualTo(new RetentionJob.Result(false, 1, 1, 0, 0, 0));
        assertUntouched(mid);
        assertThat(taste(mid)).isEqualTo("{\"house & techno\":1.0}");
        assertThat(output.getOut())
                .contains("RetentionJob: dry run, 1 memberships past the retention window in 1 orgs, 0 imported without provenance skipped, nothing written");
    }

    @Test
    void secondRun_writesNothingMore(CapturedOutput output) {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        RetentionJob job = job(true);

        assertThat(job.run()).isEqualTo(new RetentionJob.Result(true, 1, 1, 0, 1, 0));
        RetentionJob.Result second = job.run();

        assertThat(second).isEqualTo(new RetentionJob.Result(true, 0, 0, 0, 0, 0));
        assertThat(retentionRecords(mid)).isEqualTo(1);
        assertThat(output.getOut()).contains("RetentionJob: done, 1 memberships past the retention window in 1 orgs, 0 imported without provenance skipped, 1 cleared, 0 failed");
    }

    @Test
    void ordersAndTicketsUntouched() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        UUID orderId = paidOrder(orgA, emailOf(mid));

        job(true).run();

        assertCleared(mid);
        assertThat(jdbc.queryForObject("select total_minor from orders where id = ?", Long.class, orderId))
                .isEqualTo(2000L);
        assertThat(jdbc.queryForList("select state from tickets where order_id = ?", String.class, orderId))
                .containsExactly(Ticket.STATE_ISSUED);
    }

    // ── projection lag: orders read at write time ──────────────────────────

    @Test
    void paidOrderOnCutoffDay_missingFromStaleProjection_untouched() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        UUID orderId = paidOrder(orgA, emailOf(mid));
        orderAt(orderId, CUTOFF_DATE.atStartOfDay(PARIS).toInstant(), false);

        RetentionJob.Result r = job(true).run();

        assertThat(r).isEqualTo(new RetentionJob.Result(true, 1, 1, 0, 0, 0));
        assertUntouched(mid);
    }

    @Test
    void paidOrderJustBeforeCutoff_cleared() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        UUID orderId = paidOrder(orgA, emailOf(mid));
        orderAt(orderId, CUTOFF_DATE.atStartOfDay(PARIS).toInstant().minusSeconds(1), false);

        job(true).run();

        assertCleared(mid);
    }

    @Test
    void recentTestModeOrder_notContact_cleared() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        orderAt(paidOrder(orgA, emailOf(mid)), RECENT, true);

        job(true).run();

        assertCleared(mid);
    }

    @Test
    void recentPaidOrderAtAnotherOrg_cleared() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);
        orderAt(paidOrder(org("Europe/Paris"), emailOf(mid)), RECENT, false);

        job(true).run();

        assertCleared(mid);
    }

    // ── pre-provenance bulk imports ────────────────────────────────────────

    @Test
    void legacyBulkImportOnly_skippedAndCountedSeparately_dryRun(CapturedOutput output) {
        UUID legacy = member(orgA);
        consent(legacy, "explicit", "organizer_import", null, Instant.parse("2035-01-01T12:00:00Z"));
        contact(legacy, null);
        UUID expired = member(orgA);
        consent(expired, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(expired, DAY_BEFORE_CUTOFF);

        RetentionJob.Result r = job(false).run();

        assertThat(r).isEqualTo(new RetentionJob.Result(false, 1, 1, 1, 0, 0));
        assertUntouched(legacy);
        assertUntouched(expired);
        assertThat(output.getOut()).contains("RetentionJob: dry run, 1 memberships past the retention window in 1 orgs,"
                + " 1 imported without provenance skipped, nothing written");
    }

    @Test
    void legacyBulkImportOnly_neverUnsubscribed_whenEnabled(CapturedOutput output) {
        UUID legacy = member(orgA);
        consent(legacy, "explicit", "organizer_import", null, Instant.parse("2035-01-01T12:00:00Z"));
        contact(legacy, DAY_BEFORE_CUTOFF);
        UUID expired = member(orgA);
        consent(expired, "explicit", "door_qr", "door-v1", Instant.parse("2035-01-01T12:00:00Z"));
        contact(expired, DAY_BEFORE_CUTOFF);

        RetentionJob.Result r = job(true).run();

        assertThat(r).isEqualTo(new RetentionJob.Result(true, 1, 1, 1, 1, 0));
        assertUntouched(legacy);
        assertThat(taste(legacy)).isEqualTo("{\"house & techno\":1.0}");
        assertCleared(expired);
        assertThat(output.getOut()).contains("RetentionJob: done, 1 memberships past the retention window in 1 orgs,"
                + " 1 imported without provenance skipped, 1 cleared, 0 failed");
    }

    @Test
    void legacyBulkImportPlusAnotherGrant_cleared() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "organizer_import", null, Instant.parse("2035-01-01T12:00:00Z"));
        consent(mid, "explicit", "door_qr", "door-v1", Instant.parse("2035-02-01T12:00:00Z"));
        contact(mid, DAY_BEFORE_CUTOFF);

        RetentionJob.Result r = job(true).run();

        assertThat(r.importedWithoutProvenance()).isZero();
        assertCleared(mid);
    }

    @Test
    void legacyBulkImportWithProvenanceRow_cleared() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "organizer_import", null, Instant.parse("2035-01-01T12:00:00Z"));
        provenance(mid, null);
        contact(mid, DAY_BEFORE_CUTOFF);

        RetentionJob.Result r = job(true).run();

        assertThat(r.importedWithoutProvenance()).isZero();
        assertCleared(mid);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    void orderAt(UUID orderId, Instant createdAt, boolean testMode) {
        jdbc.update("update orders set created_at = ?, test_mode = ? where id = ?",
                Timestamp.from(createdAt), testMode, orderId);
    }

    RetentionJob job(boolean enabled) {
        return job(props(enabled));
    }

    @SuppressWarnings("unchecked")
    RetentionJob job(AudiencePlanProperties props) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ConsentGate gate = new ConsentGate(fanRepo, orgRepo, logic, props, clock);
        return new RetentionJob(fanRepo, gate, consentService, membershipRepo, new AudiencePlanAccess(props), props,
                txManager, clock, mock(ObjectProvider.class));
    }

    static AudiencePlanProperties props(boolean retentionEnabled) {
        AudiencePlanProperties p = new AudiencePlanProperties();
        p.setRetentionJobEnabled(retentionEnabled);
        return p;
    }

    void assertCleared(UUID mid) {
        Map<String, Object> m = jdbc.queryForMap(
                "select consent_status, consent_basis, objected_profiling from memberships where membership_id = ?", mid);
        assertThat(m.get("consent_status")).isEqualTo("unsubscribed");
        assertThat(m.get("consent_basis")).isNull();
        assertThat(m.get("objected_profiling")).isEqualTo(false);
        ConsentRecord latest = consentRepo.findByMembershipIdIn(List.of(mid)).stream()
                .filter(c -> RetentionJob.SOURCE.equals(c.getSource())).findFirst().orElseThrow();
        assertThat(latest.getStatus()).isEqualTo("unsubscribed");
        assertThat(latest.getChannel()).isEqualTo("email");
        assertThat(latest.getLawfulBasis()).isNull();
        assertThat(retentionRecords(mid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from marketing_optouts where org_id = ?", Integer.class,
                orgOf(mid))).isZero();
        Map<String, Object> f = jdbc.queryForMap(
                "select taste, cities, formats from fan_features where membership_id = ?", mid);
        assertThat(f.get("taste")).isEqualTo("{}");
        assertThat(f.get("cities")).isEqualTo("[]");
        assertThat(f.get("formats")).isEqualTo("[]");
    }

    void assertUntouched(UUID mid) {
        assertThat(consentStatus(mid)).isEqualTo("subscribed");
        assertThat(retentionRecords(mid)).isZero();
    }

    String consentStatus(UUID mid) {
        return jdbc.queryForObject("select consent_status from memberships where membership_id = ?", String.class, mid);
    }

    int retentionRecords(UUID mid) {
        return jdbc.queryForObject("select count(*) from consent_records where membership_id = ? and source = ?",
                Integer.class, mid, RetentionJob.SOURCE);
    }

    UUID orgOf(UUID mid) {
        return jdbc.queryForObject("select org_id from memberships where membership_id = ?", UUID.class, mid);
    }

    String taste(UUID mid) {
        return jdbc.queryForObject("select taste from fan_features where membership_id = ?", String.class, mid);
    }

    String emailOf(UUID mid) {
        return jdbc.queryForObject("select c.normalized_email from consumers c join memberships m "
                + "on m.consumer_id = c.consumer_id where m.membership_id = ?", String.class, mid);
    }

    UUID paidOrder(UUID orgId, String email) {
        User owner = new User();
        owner.setEmail("retention-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        owner = userRepo.save(owner);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Retention Night");
        e.setSlug("retention-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(DAY_BEFORE_CUTOFF);
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        e = eventRepo.save(e);
        Order o = new Order();
        o.setToken("retention-" + UUID.randomUUID());
        o.setEventId(e.getId());
        o.setOrgId(orgId);
        o.setEmail(email);
        o.setTotalMinor(2000L);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o = orderRepo.save(o);
        Ticket t = new Ticket();
        t.setToken("retention-t-" + UUID.randomUUID());
        t.setOrderId(o.getId());
        t.setEventId(e.getId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor(2000);
        t.setState(Ticket.STATE_ISSUED);
        ticketRepo.save(t);
        return o.getId();
    }

    UUID org(String timezone) {
        Organization o = new Organization();
        o.setName("Retention Org");
        o.setSlug("retention-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("retention@example.com");
        o.setCountry("FR");
        o.setTimezone(timezone);
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    UUID member(UUID orgId) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("retention-" + UUID.randomUUID() + "@example.com");
        c = consumerRepo.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        return membershipRepo.save(m).getMembershipId();
    }

    void consent(UUID mid, String basis, String source, String textVersion, Instant at) {
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(mid);
        r.setStatus("subscribed");
        r.setLawfulBasis(basis);
        r.setSource(source);
        r.setProofText("proof");
        r.setTextVersion(textVersion);
        r.setOccurredAt(at);
        consentRepo.save(r);
        jdbc.update("update memberships set consent_status = 'subscribed', consent_basis = ? where membership_id = ?",
                basis, mid);
    }

    /** A fresh feature row (written an hour before the job) with this last contact, or none when null. */
    void contact(UUID mid, Instant lastContact) {
        FanFeature f = new FanFeature();
        f.setMembershipId(mid);
        f.setOrgId(orgOf(mid));
        f.setLastContactFromPersonAt(lastContact);
        f.setTaste("{\"house & techno\":1.0}");
        f.setCities("[\"metz\"]");
        f.setFormats("[\"club\"]");
        f.setLogicVersion(1);
        fanRepo.save(f);
        jdbc.update("update fan_features set updated_at = ? where membership_id = ?",
                Timestamp.from(NOW.minus(Duration.ofHours(1))), mid);
    }

    void provenance(UUID mid, LocalDate lastPurchaseDate) {
        AudienceImport imp = new AudienceImport();
        imp.setOrgId(orgOf(mid));
        imp = importRepo.save(imp);
        ImportRowProvenance p = new ImportRowProvenance();
        p.setImportId(imp.getId());
        p.setMembershipId(mid);
        p.setRowNumber(1);
        p.setSourcePlatform("shotgun");
        p.setExportDate(LocalDate.parse("2040-03-01"));
        p.setLastPurchaseDate(lastPurchaseDate);
        p.setMarketingStatus("opted_in");
        p.setProofRef("export.csv");
        p.setAccepted(true);
        provenanceRepo.save(p);
    }
}
