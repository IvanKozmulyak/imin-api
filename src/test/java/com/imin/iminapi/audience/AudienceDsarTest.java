package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.imin.iminapi.audience.dto.DsarRecords;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.model.AudienceImport;
import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.AudienceImportRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * DSAR (Data Subject Access Request) tests:
 * - Art.15 access returns membership
 * - Art.15 export returns membership
 * - Art.16 rectify updates mutable fields
 * - Art.21 object is synchronous (immediately unsubscribed + gate blocked)
 * - Art.17 erase schedules 30-day grace → erase_pending
 * - Art.17 execute: cascade (membership + consent + marketing-supp deleted), tombstone written
 * - Art.17 execute: shared consumer survives when another org still references it
 * - Art.17 execute: consumer deleted when last membership erased
 * - Cross-org DSAR → 404 (not 403)
 * - Audit: the persisted row for each action
 * - Erasure job candidate selection: only past-due erase_pending rows
 */
@IminIntegrationTest
class AudienceDsarTest {

    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired EventRepository eventRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotifySubscriptionRepository notifyRepo;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired DsarService dsarService;
    @Autowired ConsentService consentService;
    @Autowired SendGateService sendGateService;
    @Autowired AudienceService audienceService;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired AuditRows audit;

    @Autowired FanFeatureRepository fanFeatureRepo;
    @Autowired ImportRowProvenanceRepository provenanceRepo;
    @Autowired AudienceImportRepository importRepo;
    @Autowired AudienceAssignmentRepository assignmentRepo;
    @Autowired AudienceExperimentRepository experimentRepo;

    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal principalA;

    @BeforeEach
    void setUp() {
        orgA = fx.org().getId();
        orgB = fx.org().getId();
        principalA = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
    }

    /** The erasure job and the momentum sweep see every org; own memberships and orgs go. */
    @AfterEach
    void tearDown() {
        try {
            jdbc.update("delete from memberships where org_id in (?, ?)", orgA, orgB);
        } finally {
            OrgRows.delete(jdbc, List.of(orgA, orgB));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.15: access
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void access_returns_membership_and_audits() {
        UUID mid = seedMembership(orgA, fx.email("access"));

        Membership m = dsarService.access(orgA, mid, principalA);
        assertThat(m.getMembershipId()).isEqualTo(mid);

        audit.assertRecorded(orgA, AuditActions.DSAR_ACCESS, "membership", mid);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.15: export
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void export_returns_membership_and_audits() {
        UUID mid = seedMembership(orgA, fx.email("export"));

        Membership m = dsarService.export(orgA, mid, principalA);
        assertThat(m.getMembershipId()).isEqualTo(mid);

        audit.assertRecorded(orgA, AuditActions.DSAR_EXPORT, "membership", mid);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.15: consent trail
    //
    // consent_records has been written faithfully since Tier C and read by
    // nothing but a COUNT(*) metrics tile. A proof nobody can produce is not a
    // proof, so these assert the trail comes back with the fields that make it
    // one — when, on what basis, through which source, with what proof text.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void consent_history_returns_the_proof_rows_in_order() {
        UUID mid = seedMembership(orgA, fx.email("trail"));
        consentService.capture(orgA, mid, "soft_opt_in", "checkout",
                "Left the pre-ticked box ticked at checkout", principalA);
        consentService.unsubscribe(orgA, mid, "one_click", "email",
                ConsentOrigin.DATA_SUBJECT, principalA);

        List<com.imin.iminapi.audience.dto.ConsentHistoryEntry> history =
                dsarService.consentHistory(orgA, mid);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).granted()).isTrue();
        assertThat(history.get(0).lawfulBasis()).isEqualTo("soft_opt_in");
        assertThat(history.get(0).source()).isEqualTo("checkout");
        assertThat(history.get(0).channel()).isEqualTo("email");
        assertThat(history.get(0).proofText())
                .isEqualTo("Left the pre-ticked box ticked at checkout");
        assertThat(history.get(0).at()).isNotNull();
        assertThat(history.get(1).granted()).isFalse();
        assertThat(history.get(1).source()).isEqualTo("one_click");
    }

    @Test
    void consent_history_carries_text_version_and_order_id() {
        UUID mid = seedMembership(orgA, fx.email("trail-version"));
        UUID orderId = UUID.randomUUID();
        consentService.capture(orgA, mid, "explicit", "checkout", "Ticked the box", "email",
                "2026-10-01", orderId, principalA);
        consentService.capture(orgA, mid, "explicit", "manual", "Typed by organizer", principalA);

        List<com.imin.iminapi.audience.dto.ConsentHistoryEntry> history =
                dsarService.consentHistory(orgA, mid);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).textVersion()).isEqualTo("2026-10-01");
        assertThat(history.get(0).orderId()).isEqualTo(orderId);
        assertThat(history.get(1).textVersion()).isNull();
        assertThat(history.get(1).orderId()).isNull();
    }

    @Test
    void consent_history_marks_door_consent_awaiting_confirmation_and_names_the_event() {
        UUID mid = seedMembership(orgA, fx.email("trail-door"));
        Event event = seedEvent(orgA);
        String proof = "Ticked the door QR sign-up at event " + event.getId() + " (locale en) next to: \"x\"";
        consentService.capture(orgA, mid, "explicit", "door_qr", proof, "email", "door-v1", null,
                event.getId(), ConsentOrigin.DATA_SUBJECT, null);

        var entry = dsarService.consentHistory(orgA, mid).get(0);

        assertThat(entry.confirmationRequired()).isTrue();
        assertThat(entry.confirmedAt()).isNull();
        assertThat(entry.eventId()).isEqualTo(event.getId());
        assertThat(entry.eventName()).isEqualTo("DSAR Event");
        assertThat(entry.proofText()).isEqualTo(proof);
    }

    @Test
    void consent_history_carries_confirmed_at_once_confirmed() {
        UUID mid = seedMembership(orgA, fx.email("trail-confirmed"));
        consentService.capture(orgA, mid, "explicit", "survey", "Ticked the survey", "email", "s-v1", null,
                null, ConsentOrigin.DATA_SUBJECT, null);
        Instant confirmed = Instant.parse("2026-09-01T10:00:00Z");
        ConsentRecord r = consentRepo.findByMembershipId(mid).get(0);
        r.setConfirmedAt(confirmed);
        consentRepo.save(r);

        var entry = dsarService.consentHistory(orgA, mid).get(0);

        assertThat(entry.confirmationRequired()).isTrue();
        assertThat(entry.confirmedAt()).isEqualTo(confirmed);
        assertThat(entry.eventId()).isNull();
        assertThat(entry.eventName()).isNull();
    }

    @Test
    void consent_history_checkout_record_needs_no_confirmation() {
        UUID mid = seedMembership(orgA, fx.email("trail-checkout"));
        consentService.capture(orgA, mid, "explicit", "checkout", "Ticked the box", principalA);

        var entry = dsarService.consentHistory(orgA, mid).get(0);

        assertThat(entry.confirmationRequired()).isFalse();
        assertThat(entry.confirmedAt()).isNull();
    }

    @Test
    void consent_history_does_not_name_another_orgs_event() {
        UUID mid = seedMembership(orgA, fx.email("trail-foreign"));
        Event foreign = seedEvent(orgB);
        consentService.capture(orgA, mid, "explicit", "door_qr", "proof", "email", "door-v1", null,
                foreign.getId(), ConsentOrigin.DATA_SUBJECT, null);

        var entry = dsarService.consentHistory(orgA, mid).get(0);

        assertThat(entry.eventId()).isEqualTo(foreign.getId());
        assertThat(entry.eventName()).isNull();
    }

    @Test
    void consent_history_is_empty_for_a_member_who_never_consented() {
        UUID mid = seedMembership(orgA, fx.email("notrail"));
        assertThat(dsarService.consentHistory(orgA, mid)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.16: rectify
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void rectify_updates_display_name_city_notes() {
        UUID mid = seedMembership(orgA, fx.email("rectify"));

        dsarService.rectify(orgA, mid, "New Name", "Berlin", "corrected notes", principalA);

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getDisplayName()).isEqualTo("New Name");
        assertThat(m.getCity()).isEqualTo("Berlin");
        assertThat(m.getNotes()).isEqualTo("corrected notes");
        audit.assertRecorded(orgA, AuditActions.DSAR_RECTIFY, "membership", mid);
    }

    @Test
    void rectify_partial_update_only_sets_non_null_fields() {
        UUID mid = seedMembership(orgA, fx.email("rectify2"));
        Membership before = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        String originalCity = before.getCity();

        // Only update displayName — city should remain unchanged
        dsarService.rectify(orgA, mid, "Only Name", null, null, principalA);

        Membership after = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(after.getDisplayName()).isEqualTo("Only Name");
        assertThat(after.getCity()).isEqualTo(originalCity);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.21: object (synchronous unsubscribe)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void object_unsubscribes_synchronously_and_blocks_gate() {
        UUID mid = seedSubscribed(orgA, fx.email("object"), "explicit");

        dsarService.object(orgA, mid, principalA);

        // Immediately reflected in DB
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("unsubscribed");

        // Gate immediately blocks
        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).extracting("reason").containsExactly("marketing_unsubscribed");
        audit.assertRecorded(orgA, AuditActions.DSAR_OBJECT, "membership", mid);
    }

    @Test
    void object_appends_consent_record() {
        UUID mid = seedSubscribed(orgA, fx.email("objrec"), "explicit");
        long before = consentRepo.findByMembershipId(mid).size();

        dsarService.object(orgA, mid, principalA);

        List<ConsentRecord> records = consentRepo.findByMembershipId(mid);
        assertThat(records.size()).isGreaterThan((int) before);
        // Latest record should be unsubscribed
        ConsentRecord last = records.get(records.size() - 1);
        assertThat(last.getStatus()).isEqualTo("unsubscribed");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.17: requestErase — schedules 30-day grace
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void request_erase_sets_status_erase_pending_and_erase_at() {
        UUID mid = seedMembership(orgA, fx.email("erasereq"));

        dsarService.requestErase(orgA, mid, principalA);

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getStatus()).isEqualTo("erase_pending");
        assertThat(m.getEraseAt()).isNotNull();
        // eraseAt should be approximately 30 days from now
        assertThat(m.getEraseAt()).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
        assertThat(m.getEraseAt()).isBefore(Instant.now().plus(31, ChronoUnit.DAYS));
        audit.assertRecorded(orgA, AuditActions.DSAR_ERASE_REQUESTED, "membership", mid);
    }

    @Test
    void request_erase_deletes_fan_features_immediately() {
        UUID mid = seedMembership(orgA, fx.email("eraseff"));
        seedFanFeature(orgA, mid);

        dsarService.requestErase(orgA, mid, principalA);

        assertThat(fanFeatureRepo.findById(mid)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // audience-4: the 30-day grace period is not 30 more days of marketing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void request_erase_unsubscribes_immediately() {
        UUID mid = seedSubscribed(orgA, fx.email("erasesub"), "explicit");
        assertThat(sendGateService.evaluate(orgA, List.of(mid)).sendable()).containsExactly(mid);

        dsarService.requestErase(orgA, mid, principalA);

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(m.getConsentBasis()).isNull();
    }

    /**
     * audience-12: a mangled keyset cursor is client input. It threw
     * IllegalArgumentException, which has no handler and fell through to the catch-all as
     * a 500 — the dashboard could not tell a bad link from a broken server.
     */
    @Test
    void a_malformed_cursor_is_a_400_not_a_500() {
        assertThatThrownBy(() -> audienceService.listMembers(orgA, new AudienceService.MemberListRequest("not-a-cursor", 50, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
    }

    @Test
    void erase_pending_member_is_not_sendable() {
        UUID mid = seedSubscribed(orgA, fx.email("erasegate"), "explicit");
        // Status alone, with consent left intact, must already close the gate.
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setStatus("erase_pending");
        m.setEraseAt(Instant.now().plus(30, ChronoUnit.DAYS));
        membershipRepo.save(m);

        assertThat(sendGateService.evaluate(orgA, List.of(mid)).sendable()).isEmpty();
    }

    @Test
    void erase_pending_member_is_not_listed_exported_or_segmented() {
        UUID mid = seedSubscribed(orgA, fx.email("eraselist"), "explicit");
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setEvents(3);
        m.setStatus("erase_pending");
        m.setEraseAt(Instant.now().plus(30, ChronoUnit.DAYS));
        membershipRepo.save(m);

        assertThat(audienceService.listMembers(orgA, new AudienceService.MemberListRequest(null, 50, null, null, null, null, null, null)).items())
                .extracting(com.imin.iminapi.audience.dto.MemberDto::membershipId)
                .doesNotContain(mid.toString());
        assertThat(audienceService.exportMembersCsv(orgA, null, null))
                .extracting(com.imin.iminapi.audience.dto.MemberDto::membershipId)
                .doesNotContain(mid.toString());
        assertThat(membershipRepo.findRepeats(orgA)).extracting(Membership::getMembershipId)
                .doesNotContain(mid);
        assertThat(membershipRepo.findAllByOrgId(orgA)).extracting(Membership::getMembershipId)
                .doesNotContain(mid);
        assertThat(membershipRepo.findAllMembershipIdsByOrgId(orgA)).doesNotContain(mid);
        // The operator can still open the record — DSAR itself has to keep working.
        assertThat(audienceService.getMember(orgA, mid).membershipId()).isEqualTo(mid.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.17: executeErase — destructive cascade
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void execute_erase_deletes_membership_and_writes_tombstone() {
        UUID mid = seedSubscribed(orgA, fx.email("execerase"), "explicit");

        // Force eraseAt to the past so job would pick it up
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(m);
        assertThat(consentRepo.findByMembershipId(mid)).isNotEmpty();

        dsarService.executeErase(orgA, mid, principalA);

        // Membership gone, and its consent proof rows with it
        assertThat(membershipRepo.findByIdAndOrgId(mid, orgA)).isEmpty();
        assertThat(consentRepo.findByMembershipId(mid)).isEmpty();

        // Tombstone written
        audit.assertRecorded(orgA, AuditActions.DSAR_ERASE_EXECUTED, "membership", mid);
    }

    @Test
    void execute_erase_deletes_fan_features_explicitly() {
        UUID mid = seedMembership(orgA, fx.email("fanerase"));
        seedFanFeature(orgA, mid);

        dsarService.executeErase(orgA, mid, principalA);

        assertThat(fanFeatureRepo.findById(mid)).isEmpty();
    }

    @Test
    void export_records_include_fan_features() {
        UUID mid = seedMembership(orgA, fx.email("fanexport"));
        seedFanFeature(orgA, mid);

        DsarRecords records = dsarService.exportRecords(orgA, mid, principalA);

        DsarRecords.FanFeatureRecord f = records.fanFeatures();
        assertThat(f).isNotNull();
        assertThat(f.paidOrders()).isEqualTo(2);
        assertThat(f.firstPaidPurchaseAt()).isEqualTo(Instant.parse("2026-02-01T20:00:00Z"));
        assertThat(f.lastPaidPurchaseAt()).isEqualTo(Instant.parse("2026-08-15T20:00:00Z"));
        assertThat(f.guestClass()).isEqualTo("repeat");
        assertThat(f.taste()).isEqualTo("{\"pop\":1.0}");
        assertThat(f.cities()).isEqualTo("[\"metz\"]");
        assertThat(f.formats()).isEqualTo("[\"club\"]");
        assertThat(f.noShowN()).isEqualTo(1);
        assertThat(f.avgGroupSize()).isEqualByComparingTo("1.5");
        assertThat(f.sends30d()).isEqualTo(3);
        assertThat(f.lastContactFromPersonAt()).isEqualTo(Instant.parse("2026-08-15T20:00:00Z"));
        assertThat(f.logicVersion()).isEqualTo(1);
        assertThat(f.updatedAt()).isNotNull();
    }

    @Test
    void execute_erase_deletes_import_provenance_explicitly() {
        UUID mid = seedMembership(orgA, fx.email("proverase"));
        seedProvenance(orgA, mid);

        dsarService.executeErase(orgA, mid, principalA);

        assertThat(provenanceRepo.findByMembershipIdOrderByCreatedAtAsc(mid)).isEmpty();
    }

    @Test
    void execute_erase_deletes_experiment_assignments_explicitly() {
        UUID mid = seedMembership(orgA, fx.email("assignerase"));
        AudienceExperiment e = new AudienceExperiment();
        e.setOrgId(orgA);
        e.setEventId(seedEvent(orgA).getId());
        e.setArm("holdout");
        e.setSeed(3L);
        e.setMembers(1);
        e = experimentRepo.save(e);
        AudienceAssignment a = new AudienceAssignment();
        a.setExperimentId(e.getId());
        a.setMembershipId(mid);
        a.setArm("holdout");
        a.setAssignedAt(Instant.now());
        assignmentRepo.save(a);

        dsarService.executeErase(orgA, mid, principalA);

        assertThat(assignmentRepo.findByMembershipId(mid)).isEmpty();
    }

    @Test
    void export_records_include_import_provenance() {
        UUID mid = seedMembership(orgA, fx.email("provexport"));
        UUID importId = seedProvenance(orgA, mid);

        List<DsarRecords.ImportProvenanceRecord> rows =
                dsarService.exportRecords(orgA, mid, principalA).importProvenance();

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.importId()).isEqualTo(importId);
            assertThat(r.rowNumber()).isEqualTo(7);
            assertThat(r.sourcePlatform()).isEqualTo("shotgun");
            assertThat(r.exportDate()).isEqualTo(java.time.LocalDate.parse("2026-09-01"));
            assertThat(r.events()).isEqualTo("Night A");
            assertThat(r.lastPurchaseDate()).isEqualTo(java.time.LocalDate.parse("2026-08-01"));
            assertThat(r.marketingStatus()).isEqualTo("opted_in");
            assertThat(r.proofRef()).isEqualTo("screenshot-7");
            assertThat(r.accepted()).isTrue();
            assertThat(r.rejectReason()).isNull();
            assertThat(r.createdAt()).isNotNull();
        });
    }

    @Test
    void export_records_have_no_import_provenance_when_never_imported() {
        UUID mid = seedMembership(orgA, fx.email("noprov"));

        assertThat(dsarService.exportRecords(orgA, mid, principalA).importProvenance()).isEmpty();
    }

    @Test
    void export_records_keep_historic_opens_and_clicks() {
        UUID mid = seedMembership(orgA, fx.email("engaged"));
        setEngagement(mid, Instant.parse("2026-05-01T10:00:00Z"), Instant.parse("2026-05-02T10:00:00Z"));

        DsarRecords.EmailEngagementRecord e = dsarService.exportRecords(orgA, mid, principalA).emailEngagement();

        assertThat(e).isNotNull();
        assertThat(e.lastOpenedAt()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
        assertThat(e.lastClickedAt()).isEqualTo(Instant.parse("2026-05-02T10:00:00Z"));
    }

    @Test
    void export_records_keep_a_click_without_an_open() {
        UUID mid = seedMembership(orgA, fx.email("clickonly"));
        setEngagement(mid, null, Instant.parse("2026-05-02T10:00:00Z"));

        DsarRecords.EmailEngagementRecord e = dsarService.exportRecords(orgA, mid, principalA).emailEngagement();

        assertThat(e).isNotNull();
        assertThat(e.lastOpenedAt()).isNull();
        assertThat(e.lastClickedAt()).isEqualTo(Instant.parse("2026-05-02T10:00:00Z"));
    }

    @Test
    void export_records_have_null_engagement_when_none_held() {
        UUID mid = seedMembership(orgA, fx.email("unengaged"));

        assertThat(dsarService.exportRecords(orgA, mid, principalA).emailEngagement()).isNull();
    }

    private void setEngagement(UUID mid, Instant open, Instant click) {
        jdbc.update(
                "update memberships set last_email_open = ?, last_email_click = ? where membership_id = ?",
                open == null ? null : java.sql.Timestamp.from(open),
                click == null ? null : java.sql.Timestamp.from(click), mid);
    }

    @Test
    void export_records_have_null_fan_features_when_none_computed() {
        UUID mid = seedMembership(orgA, fx.email("nofan"));

        assertThat(dsarService.exportRecords(orgA, mid, principalA).fanFeatures()).isNull();
    }

    @Test
    void execute_erase_cascades_marketing_suppression() {
        UUID mid = seedSubscribed(orgA, fx.email("cascsup"), "explicit");

        // Add a marketing suppression
        SuppressionEntry se = new SuppressionEntry();
        se.setScope(SuppressionEntry.SCOPE_MARKETING);
        se.setOrgId(orgA);
        se.setMembershipId(mid);
        se.setReason(SuppressionEntry.REASON_MANUAL);
        suppressionRepo.save(se);

        // Verify it's there
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(orgA, mid)).isPresent();

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(m);

        dsarService.executeErase(orgA, mid, principalA);

        // Suppression gone
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(orgA, mid)).isEmpty();
    }

    @Test
    void execute_erase_shared_consumer_survives_when_other_org_references_it() {
        String email = fx.email("sharedconsumer");
        orderProjector.upsertMembership(orgA, email, email);
        orderProjector.upsertMembership(orgB, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership mA = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        mA.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(mA);

        dsarService.executeErase(orgA, mA.getMembershipId(), principalA);

        // orgA's membership gone
        assertThat(membershipRepo.findByIdAndOrgId(mA.getMembershipId(), orgA)).isEmpty();

        // Consumer still exists (orgB still references it)
        assertThat(consumerRepo.findByNormalizedEmail(email)).isPresent();

        // orgB's membership untouched
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgB, c.getConsumerId())).isPresent();
    }

    @Test
    void execute_erase_consumer_deleted_when_last_membership_erased() {
        String email = fx.email("lastconsumer");
        orderProjector.upsertMembership(orgA, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(m);

        dsarService.executeErase(orgA, m.getMembershipId(), principalA);

        // Consumer should be gone (no memberships remain)
        assertThat(consumerRepo.findByNormalizedEmail(email)).isEmpty();
    }

    @Test
    void execute_erase_idempotent_already_erased_is_noop() {
        UUID mid = seedMembership(orgA, fx.email("idemerase"));

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(m);

        dsarService.executeErase(orgA, mid, principalA);
        assertThat(membershipRepo.findByIdAndOrgId(mid, orgA)).isEmpty();
        // Second call should be a no-op, not throw
        assertThatCode(() -> dsarService.executeErase(orgA, mid, principalA))
                .doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.17: executeErase — notify_subscriptions cascade
    //
    // notify_subscriptions sits outside the consumer/membership graph (keyed by
    // (event, raw email), no org column), so the cascade above never reached it and an
    // erased buyer's address used to survive there — still queued for a release email.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void execute_erase_deletes_the_orgs_notify_subscriptions_for_that_email() {
        String email = fx.email("notifyerase");
        UUID mid = seedMembership(orgA, email);
        UUID subId = seedNotify(seedEvent(orgA).getId(), email);

        dsarService.executeErase(orgA, mid, principalA);

        assertThat(notifyRepo.findById(subId)).isEmpty();
    }

    @Test
    void execute_erase_keeps_another_orgs_notify_subscription_for_the_same_email() {
        String email = fx.email("sharednotify");
        UUID midA = seedMembership(orgA, email);
        UUID subA = seedNotify(seedEvent(orgA).getId(), email);
        UUID subB = seedNotify(seedEvent(orgB).getId(), email);

        dsarService.executeErase(orgA, midA, principalA);

        // DSAR is org-scoped: orgB's subscription for the same address is orgB's data.
        assertThat(notifyRepo.findById(subA)).isEmpty();
        assertThat(notifyRepo.findById(subB)).isPresent();
    }

    @Test
    void execute_erase_keeps_other_addresses_notify_subscriptions_in_the_same_org() {
        String erased = fx.email("targetednotify");
        UUID mid = seedMembership(orgA, erased);
        UUID eventId = seedEvent(orgA).getId();
        UUID subErased = seedNotify(eventId, erased);
        UUID subBystander = seedNotify(eventId, fx.email("bystandernotify"));

        dsarService.executeErase(orgA, mid, principalA);

        assertThat(notifyRepo.findById(subErased)).isEmpty();
        assertThat(notifyRepo.findById(subBystander)).isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Erasure job candidate selection
    // ─────────────────────────────────────────────────────────────────────────

    /** Status blank = an active member; offset is milliseconds from now. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "past due,                 A, erase_pending, -1000,      true",
            "past due by 100 ms,       A, erase_pending, -100,       true",
            "past due in another org,  B, erase_pending, -1000,      true",
            "erase_pending in 29 days, A, erase_pending, 2505600000, false",
            "active member,            A, ,              0,          false"})
    void erasure_due_selects_only_past_due_erase_pending(String name, String org, String status,
                                                          long offsetMillis, boolean due) {
        UUID orgId = "A".equals(org) ? orgA : orgB;
        UUID mid = seedMembership(orgId, fx.email("due"));
        if (status != null) {
            Membership m = membershipRepo.findByIdAndOrgId(mid, orgId).orElseThrow();
            m.setStatus(status);
            m.setEraseAt(Instant.now().plusMillis(offsetMillis));
            membershipRepo.save(m);
        }

        List<UUID> candidates = membershipRepo.findErasureDue(Instant.now()).stream()
                .map(Membership::getMembershipId).toList();

        if (due) assertThat(candidates).contains(mid);
        else assertThat(candidates).doesNotContain(mid);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cross-org: every DSAR action answers 404, never 403
    // ─────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface DsarCall {
        void call(DsarService s, UUID orgId, UUID membershipId, AuthPrincipal p);
    }

    static Stream<Arguments> dsarActions() {
        return Stream.of(
                Arguments.of("access", (DsarCall) (s, o, m, p) -> s.access(o, m, p)),
                Arguments.of("export", (DsarCall) (s, o, m, p) -> s.export(o, m, p)),
                Arguments.of("consentHistory", (DsarCall) (s, o, m, p) -> s.consentHistory(o, m)),
                Arguments.of("rectify", (DsarCall) (s, o, m, p) -> s.rectify(o, m, "X", null, null, p)),
                Arguments.of("object", (DsarCall) (s, o, m, p) -> s.object(o, m, p)),
                Arguments.of("requestErase", (DsarCall) (s, o, m, p) -> s.requestErase(o, m, p)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dsarActions")
    void another_orgs_member_returns_404(String name, DsarCall action) {
        UUID mid = seedMembership(orgB, fx.email("cross"));
        assertThatThrownBy(() -> action.call(dsarService, orgA, mid, principalA))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UUID seedMembership(UUID orgId, String email) {
        String normalized = EmailNormalizer.normalize(email);
        Consumer consumer = consumerRepo.findByNormalizedEmail(normalized).orElse(null);
        if (consumer == null) {
            consumer = new Consumer();
            consumer.setNormalizedEmail(normalized);
            consumer.setDisplayName(email);
            consumer = consumerRepo.save(consumer);
        }
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        return membershipRepo.save(m).getMembershipId();
    }

    private UUID seedProvenance(UUID orgId, UUID mid) {
        AudienceImport imp = new AudienceImport();
        imp.setOrgId(orgId);
        imp = importRepo.save(imp);
        ImportRowProvenance p = new ImportRowProvenance();
        p.setImportId(imp.getId());
        p.setMembershipId(mid);
        p.setRowNumber(7);
        p.setSourcePlatform("shotgun");
        p.setExportDate(java.time.LocalDate.parse("2026-09-01"));
        p.setEvents("Night A");
        p.setLastPurchaseDate(java.time.LocalDate.parse("2026-08-01"));
        p.setMarketingStatus("opted_in");
        p.setProofRef("screenshot-7");
        p.setAccepted(true);
        provenanceRepo.save(p);
        return imp.getId();
    }

    private void seedFanFeature(UUID orgId, UUID mid) {
        FanFeature f = new FanFeature();
        f.setMembershipId(mid);
        f.setOrgId(orgId);
        f.setPaidOrders(2);
        f.setFirstPaidPurchaseAt(Instant.parse("2026-02-01T20:00:00Z"));
        f.setLastPaidPurchaseAt(Instant.parse("2026-08-15T20:00:00Z"));
        f.setFanClass("repeat");
        f.setTaste("{\"pop\":1.0}");
        f.setCities("[\"metz\"]");
        f.setFormats("[\"club\"]");
        f.setNoShowN(1);
        f.setAvgGroupSize(new java.math.BigDecimal("1.5"));
        f.setSends30d(3);
        f.setLastContactFromPersonAt(Instant.parse("2026-08-15T20:00:00Z"));
        f.setLogicVersion(1);
        fanFeatureRepo.save(f);
    }

    private UUID seedSubscribed(UUID orgId, String email, String basis) {
        UUID mid = seedMembership(orgId, email);
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
        consentService.capture(orgId, mid, basis, "seed", "proof", p);
        return mid;
    }

    /** A minimal public event owned by {@code orgId} — the org anchor for notify rows. */
    private Event seedEvent(UUID orgId) {
        User u = new User();
        u.setOrgId(orgId);
        String userEmail = "dsar-evt-" + UUID.randomUUID() + "@d.com";
        u.setEmail(userEmail);
        u.setEmailLower(userEmail);
        u.setRole(UserRole.OWNER);
        u = userRepo.save(u);

        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("DSAR Event");
        e.setSlug("dsar-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        e.setCreatedBy(u.getId());
        e.setCurrency("EUR");
        return eventRepo.save(e);
    }

    private UUID seedNotify(UUID eventId, String email) {
        NotifySubscription s = new NotifySubscription();
        s.setEventId(eventId);
        s.setEmail(email);
        return notifyRepo.save(s).getId();
    }
}
