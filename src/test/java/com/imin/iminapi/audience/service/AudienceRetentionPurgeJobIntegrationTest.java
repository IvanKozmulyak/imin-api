package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceImport;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.AudienceImportRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.audienceplan.service.RetentionCutoff;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The retention purge against Postgres. A hand-built job sees only this test's orgs; its cutoff comes from a
 * clock fixed at the start of the test, so fixtures placed around the cutoff day cannot drift across midnight.
 */
@IminIntegrationTest
class AudienceRetentionPurgeJobIntegrationTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Autowired MembershipRepository membershipRepo;
    @Autowired MarketingOptOutRepository optOutRepo;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired MembershipGraphDeleter deleter;
    @Autowired ConsentGate gate;
    @Autowired AudiencePlanLogic logic;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AuditLogger auditLogger;
    @Autowired AuditRows auditRows;
    @Autowired IminFixtures fx;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired FanFeatureRepository fanRepo;
    @Autowired AudienceImportRepository importRepo;
    @Autowired ImportRowProvenanceRepository provenanceRepo;
    @Autowired JdbcTemplate jdbc;

    final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final List<UUID> orgs = new ArrayList<>();
    final List<UUID> consumers = new ArrayList<>();
    final Map<UUID, Event> events = new HashMap<>();

    @AfterEach
    void tearDown() {
        CampaignRows.delete(jdbc, orgs);
        for (UUID org : orgs) {
            jdbc.update("delete from audience_experiments where org_id = ?", org);
            jdbc.update("delete from memberships where org_id = ?", org);
            jdbc.update("delete from audience_imports where org_id = ?", org);
            jdbc.update("delete from marketing_optouts where org_id = ?", org);
        }
        for (UUID c : consumers) {
            jdbc.update("delete from consumers c where consumer_id = ? and not exists "
                    + "(select 1 from memberships m where m.consumer_id = c.consumer_id)", c);
        }
        OrgRows.delete(jdbc, orgs);
    }

    // ── (a) the whole graph of an expired member ──────────────────────────

    @Test
    void expiredMember_graphDeleted_recipientRedacted_orphanConsumerGone_audited() {
        UUID org = org("Europe/Paris");
        Instant old = cutoff(org).at().minus(Duration.ofDays(30));
        UUID mid = member(org, fx.email("expired"), old);
        UUID consumerId = consumerOf(mid);
        consent(mid, "door_qr", "door-v1", old);
        UUID record = jdbc.queryForObject("select id from consent_records where membership_id = ?", UUID.class, mid);
        jdbc.update("insert into consent_confirmation_tokens (id, org_id, membership_id, consent_record_id, locale,"
                + " sent_at, expires_at, nps_score) values (?, ?, ?, ?, 'fr', ?, ?, 9)", UUID.randomUUID(), org, mid,
                record, Timestamp.from(old), Timestamp.from(old.plus(Duration.ofDays(7))));
        contact(mid, old);
        provenance(mid, cutoff(org).date().minusDays(30));
        UUID experiment = experiment(org);
        jdbc.update("insert into audience_assignments (experiment_id, membership_id, arm) values (?, ?, 'launch')",
                experiment, mid);
        jdbc.update("insert into suppression_entries (id, scope, org_id, membership_id, reason)"
                + " values (?, 'marketing', ?, ?, 'manual')", UUID.randomUUID(), org, mid);
        UUID recipient = recipient(campaign(org, "sent", now), mid, "sent");
        UUID queued = recipient(campaign(org, "sending", now), mid, "pending");

        AudienceRetentionPurgeJob.Result r = job(true).run();

        assertThat(r.membershipsExpired()).isEqualTo(1);
        assertThat(r.membershipsDeleted()).isEqualTo(1);
        assertThat(r.failed()).isZero();
        assertThat(count("memberships", "membership_id", mid)).isZero();
        assertThat(count("consent_records", "membership_id", mid)).isZero();
        assertThat(count("consent_confirmation_tokens", "membership_id", mid)).isZero();
        assertThat(count("fan_features", "membership_id", mid)).isZero();
        assertThat(count("import_row_provenance", "membership_id", mid)).isZero();
        assertThat(count("audience_assignments", "membership_id", mid)).isZero();
        assertThat(count("suppression_entries", "membership_id", mid)).isZero();
        assertThat(count("consumers", "consumer_id", consumerId)).isZero();
        assertRedacted(recipient);
        assertRedacted(queued);
        assertThat(jdbc.queryForMap("select status, skip_reason from campaign_recipients where id = ?", queued))
                .containsEntry("status", "skipped").containsEntry("skip_reason", "retention_purged");
        auditRows.assertRecorded(org, AuditActions.AUDIENCE_RETENTION_PURGED, "org", org);
    }

    // ── (b) every keep reason; each kept member is older, so it would come back first ──

    @ParameterizedTest
    @ValueSource(strings = {"recent_order", "checkout_consent", "door_qr_consent", "survey_consent",
            "provenance_on_cutoff_date", "fan_features_contact", "last_attended", "last_purchase",
            "erase_pending", "no_contact_created_within_window"})
    void keepReason_keepsTheMember_whileAnExpiredOneGoes(String reason) {
        UUID org = org("Europe/Paris");
        RetentionCutoff.Cutoff c = cutoff(org);
        Instant recent = now.minus(Duration.ofDays(10));
        Instant keptCreated = reason.equals("no_contact_created_within_window")
                ? recent : c.at().minus(Duration.ofDays(400));
        String keptEmail = fx.email("kept");
        UUID kept = member(org, keptEmail, keptCreated);
        switch (reason) {
            case "recent_order" -> order(org, keptEmail, recent);
            case "checkout_consent" -> consent(kept, "checkout", "checkout-v1", recent);
            case "door_qr_consent" -> consent(kept, "door_qr", "door-v1", recent);
            case "survey_consent" -> consent(kept, "survey", "survey-v1", recent);
            case "provenance_on_cutoff_date" -> provenance(kept, c.date());
            case "fan_features_contact" -> contact(kept, recent);
            case "last_attended" -> jdbc.update("update memberships set last_attended = ? where membership_id = ?",
                    Timestamp.from(recent), kept);
            case "last_purchase" -> jdbc.update("update memberships set last_purchase = ? where membership_id = ?",
                    Timestamp.from(recent), kept);
            case "erase_pending" -> jdbc.update("update memberships set status = 'erase_pending', erase_at = ?"
                    + " where membership_id = ?", Timestamp.from(now.plus(Duration.ofDays(5))), kept);
            case "no_contact_created_within_window" -> { }
            default -> throw new IllegalArgumentException(reason);
        }
        UUID expired = member(org, fx.email("expired"), c.at().minus(Duration.ofDays(300)));

        AudienceRetentionPurgeJob.Result r = job(true).run();

        assertThat(r.membershipsDeleted()).isEqualTo(1);
        assertThat(count("memberships", "membership_id", kept)).isEqualTo(1);
        assertThat(count("memberships", "membership_id", expired)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"organizer_import_row", "unsubscribed", "sms"})
    void recentConsentThatIsNotASubscribingEmailFromThePerson_doesNotKeepTheMember(String kind) {
        UUID org = org("Europe/Paris");
        UUID mid = member(org, fx.email("not-contact"), cutoff(org).at().minus(Duration.ofDays(30)));
        ConsentRecord rec = new ConsentRecord();
        rec.setMembershipId(mid);
        rec.setStatus(kind.equals("unsubscribed") ? "unsubscribed" : "subscribed");
        rec.setLawfulBasis(kind.equals("unsubscribed") ? null : "explicit");
        rec.setSource(kind.equals("organizer_import_row") ? "organizer_import_row" : "door_qr");
        rec.setChannel(kind.equals("sms") ? "sms" : "email");
        rec.setProofText("proof");
        rec.setTextVersion("door-v1");
        rec.setOccurredAt(now.minus(Duration.ofDays(10)));
        consentRepo.save(rec);

        AudienceRetentionPurgeJob.Result r = job(true).run();

        assertThat(r.membershipsDeleted()).isEqualTo(1);
        assertThat(count("memberships", "membership_id", mid)).isZero();
    }

    @Test
    void expiredPersonSourcedConsentAndProvenance_doNotKeepTheMember() {
        UUID org = org("Europe/Paris");
        RetentionCutoff.Cutoff c = cutoff(org);
        Instant dayBefore = c.date().minusDays(1).atTime(LocalTime.NOON).atZone(PARIS).toInstant();
        UUID mid = member(org, fx.email("old-contact"), now.minus(Duration.ofDays(1)));
        consent(mid, "door_qr", "door-v1", dayBefore);
        provenance(mid, c.date().minusDays(1));

        AudienceRetentionPurgeJob.Result r = job(true).run();

        assertThat(r.membershipsDeleted()).isEqualTo(1);
        assertThat(count("memberships", "membership_id", mid)).isZero();
    }

    // ── (c) the cutoff day is the org's, not UTC's ────────────────────────

    @Test
    void parisOrg_orderOnTheUtcCutoffDay_isPastTheParisCutoff() {
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        Clock late = Clock.fixed(todayUtc.atTime(23, 30).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        UUID org = org("Europe/Paris");
        String email = fx.email("zone");
        UUID mid = member(org, email, now.minus(Duration.ofDays(1)));
        order(org, email, todayUtc.minusDays(1095).atTime(12, 0).toInstant(ZoneOffset.UTC));

        AudienceRetentionPurgeJob.Result r = job(true, late).run();

        assertThat(r.membershipsDeleted()).isEqualTo(1);
        assertThat(count("memberships", "membership_id", mid)).isZero();
    }

    // ── (d) one org's retention never reaches another org's member ────────

    @Test
    void sameConsumerInTwoOrgs_onlyTheExpiredOrgsMembershipGoes_consumerStays() {
        UUID orgA = org("Europe/Paris");
        UUID orgB = org("Europe/Paris");
        String email = fx.email("shared");
        UUID inB = member(orgB, email, now.minus(Duration.ofDays(1)));
        UUID inA = membership(orgA, consumerOf(inB), cutoff(orgA).at().minus(Duration.ofDays(30)));

        job(true).run();

        assertThat(count("memberships", "membership_id", inA)).isZero();
        assertThat(count("memberships", "membership_id", inB)).isEqualTo(1);
        assertThat(count("consumers", "consumer_id", consumerOf(inB))).isEqualTo(1);
    }

    // ── (e) dry run ───────────────────────────────────────────────────────

    @Test
    void flagOff_countsButDeletesNothing() {
        UUID org = org("Europe/Paris");
        RetentionCutoff.Cutoff c = cutoff(org);
        UUID mid = member(org, fx.email("dry"), c.at().minus(Duration.ofDays(30)));
        optOut(org, fx.email("dry-optout"), c.at().minus(Duration.ofDays(1)));
        UUID recipient = recipient(campaign(org, "sent", c.at().minus(Duration.ofDays(1))), null, "delivered");

        AudienceRetentionPurgeJob.Result r = job(false).run();

        assertThat(r).isEqualTo(new AudienceRetentionPurgeJob.Result(false, 1, 1, 0, 0, false, 1, 0, 1, 0));
        assertThat(count("memberships", "membership_id", mid)).isEqualTo(1);
        assertThat(count("marketing_optouts", "org_id", org)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select email from campaign_recipients where id = ?", String.class, recipient))
                .isNotNull();
    }

    // ── (f) one failing member never stops the rest ───────────────────────

    @Test
    void oneMemberFails_theNextIsStillDeleted() {
        UUID org = org("Europe/Paris");
        RetentionCutoff.Cutoff c = cutoff(org);
        UUID first = member(org, fx.email("fails"), c.at().minus(Duration.ofDays(60)));
        UUID second = member(org, fx.email("goes"), c.at().minus(Duration.ofDays(30)));
        recipient(campaign(org, "sent", now), first, "sent");

        AudienceRetentionPurgeJob.Result r;
        try (PgFaults.Fault fault = PgFaults.failWrites(jdbc, "campaign_recipients", "membership_id", first)) {
            r = job(true).run();
        }

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.membershipsDeleted()).isEqualTo(1);
        assertThat(count("memberships", "membership_id", first)).isEqualTo(1);
        assertThat(count("memberships", "membership_id", second)).isZero();
    }

    // ── (g) the opposition list is kept exactly 3 years ───────────────────

    @Test
    void optOuts_dayBeforeCutoffDeleted_onCutoffDayKept_otherOrgKept() {
        UUID org = org("Europe/Paris");
        UUID other = org("Europe/Paris");
        RetentionCutoff.Cutoff c = cutoff(org);
        String keptEmail = fx.email("optout-kept");
        String goneEmail = fx.email("optout-gone");
        optOut(org, keptEmail, c.at());
        optOut(org, goneEmail, c.at().minus(Duration.ofMinutes(1)));
        optOut(other, goneEmail, c.at().minus(Duration.ofDays(2)));

        AudienceRetentionPurgeJob.Result r = job(true, clock, Set.of(org)).run();

        assertThat(r.optOutsDeleted()).isEqualTo(1);
        assertThat(optOutRepo.findByEmailNormalized(keptEmail)).extracting(o -> o.getOrgId()).containsExactly(org);
        assertThat(optOutRepo.findByEmailNormalized(goneEmail)).extracting(o -> o.getOrgId()).containsExactly(other);
    }

    // ── (h) recipient rows of old campaigns lose their address ────────────

    @Test
    void recipients_finishedRowsOfOldCampaignsRedacted_pendingAndRecentUntouched() {
        UUID org = org("Europe/Paris");
        RetentionCutoff.Cutoff c = cutoff(org);
        UUID oldCampaign = campaign(org, "sent", c.at().minus(Duration.ofDays(1)));
        UUID pending = recipient(oldCampaign, null, "pending");
        UUID redacted = recipient(oldCampaign, null, "delivered");
        UUID recent = recipient(campaign(org, "sent", c.at()), null, "delivered");
        UUID stillSending = recipient(campaign(org, "sending", c.at().minus(Duration.ofDays(400))), null, "sent");
        UUID otherOrgOld = recipient(campaign(org("Europe/Paris"), "sent", c.at().minus(Duration.ofDays(400))),
                null, "delivered");

        AudienceRetentionPurgeJob.Result r = job(true, clock, Set.of(org)).run();

        assertThat(r.recipientRowsRedacted()).isEqualTo(1);
        assertRedacted(redacted);
        assertThat(jdbc.queryForObject("select email from campaign_recipients where id = ?", String.class, pending))
                .isNotNull();
        assertThat(jdbc.queryForObject("select email from campaign_recipients where id = ?", String.class, recent))
                .isNotNull();
        assertThat(jdbc.queryForObject("select email from campaign_recipients where id = ?", String.class,
                otherOrgOld)).isNotNull();
        assertThat(jdbc.queryForObject("select email from campaign_recipients where id = ?", String.class,
                stillSending)).isNotNull();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    AudienceRetentionPurgeJob job(boolean enabled) {
        return job(enabled, clock);
    }

    AudienceRetentionPurgeJob job(boolean enabled, Clock jobClock) {
        return job(enabled, jobClock, Set.copyOf(orgs));
    }

    @SuppressWarnings("unchecked")
    AudienceRetentionPurgeJob job(boolean enabled, Clock jobClock, Set<UUID> only) {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setRetentionPurgeEnabled(enabled);
        return new AudienceRetentionPurgeJob(membershipRepo, optOutRepo, recipientRepo, deleter,
                new RetentionCutoff(orgRepo, logic, jobClock), gate, props, auditLogger,
                mock(ObjectProvider.class), only::contains);
    }

    RetentionCutoff.Cutoff cutoff(UUID org) {
        return new RetentionCutoff(orgRepo, logic, clock).forOrg(org);
    }

    UUID org(String timezone) {
        Organization o = fx.org();
        o.setTimezone(timezone);
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    UUID member(UUID org, String email, Instant createdAt) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(email);
        c = consumerRepo.save(c);
        consumers.add(c.getConsumerId());
        return membership(org, c.getConsumerId(), createdAt);
    }

    UUID membership(UUID org, UUID consumerId, Instant createdAt) {
        Membership m = new Membership();
        m.setOrgId(org);
        m.setConsumerId(consumerId);
        UUID id = membershipRepo.save(m).getMembershipId();
        jdbc.update("update memberships set created_at = ? where membership_id = ?", Timestamp.from(createdAt), id);
        return id;
    }

    UUID consumerOf(UUID mid) {
        return jdbc.queryForObject("select consumer_id from memberships where membership_id = ?", UUID.class, mid);
    }

    void consent(UUID mid, String source, String textVersion, Instant at) {
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(mid);
        r.setStatus("subscribed");
        r.setLawfulBasis("explicit");
        r.setSource(source);
        r.setProofText("proof");
        r.setTextVersion(textVersion);
        r.setOccurredAt(at);
        consentRepo.save(r);
    }

    void contact(UUID mid, Instant lastContact) {
        FanFeature f = new FanFeature();
        f.setMembershipId(mid);
        f.setOrgId(jdbc.queryForObject("select org_id from memberships where membership_id = ?", UUID.class, mid));
        f.setLastContactFromPersonAt(lastContact);
        f.setTaste("{}");
        f.setCities("[]");
        f.setFormats("[]");
        f.setLogicVersion(1);
        fanRepo.save(f);
    }

    void provenance(UUID mid, LocalDate lastPurchaseDate) {
        AudienceImport imp = new AudienceImport();
        imp.setOrgId(jdbc.queryForObject("select org_id from memberships where membership_id = ?", UUID.class, mid));
        imp = importRepo.save(imp);
        ImportRowProvenance p = new ImportRowProvenance();
        p.setImportId(imp.getId());
        p.setMembershipId(mid);
        p.setRowNumber(1);
        p.setSourcePlatform("shotgun");
        p.setExportDate(LocalDate.now(clock));
        p.setLastPurchaseDate(lastPurchaseDate);
        p.setMarketingStatus("opted_in");
        p.setProofRef("export.csv");
        p.setAccepted(true);
        provenanceRepo.save(p);
    }

    Event event(UUID org) {
        return events.computeIfAbsent(org, id -> {
            Organization o = orgRepo.findById(id).orElseThrow();
            User owner = fx.owner(o);
            return fx.event(o, owner, EventStatus.LIVE, now.plus(Duration.ofDays(30)));
        });
    }

    void order(UUID org, String email, Instant createdAt) {
        UUID orderId = fx.order(event(org), email).getId();
        jdbc.update("update orders set created_at = ? where id = ?", Timestamp.from(createdAt), orderId);
    }

    UUID experiment(UUID org) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into audience_experiments (id, org_id, event_id, arm, seed) values (?, ?, ?, 'launch', 1)",
                id, org, event(org).getId());
        return id;
    }

    UUID campaign(UUID org, String status, Instant sentAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into campaigns (id, org_id, channel, name, status, sent_at, created_at)"
                + " values (?, ?, 'email', 'c', ?, ?, ?)", id, org, status, Timestamp.from(sentAt), Timestamp.from(sentAt));
        return id;
    }

    UUID recipient(UUID campaignId, UUID mid, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into campaign_recipients (id, campaign_id, membership_id, email, phone_e164,"
                + " status) values (?, ?, ?, ?, '+33600000000', ?)", id, campaignId, mid, fx.email("rcpt"), status);
        return id;
    }

    void optOut(UUID org, String email, Instant createdAt) {
        jdbc.update("insert into marketing_optouts (email_normalized, org_id, channel, source, created_at)"
                + " values (?, ?, 'email', 'footer_link', ?)", email, org, Timestamp.from(createdAt));
    }

    void assertRedacted(UUID recipient) {
        Map<String, Object> row = jdbc.queryForMap(
                "select email, phone_e164 from campaign_recipients where id = ?", recipient);
        assertThat(row.get("email")).isNull();
        assertThat(row.get("phone_e164")).isNull();
    }

    int count(String table, String column, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Integer.class, id);
    }
}
