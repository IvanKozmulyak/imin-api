package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.SuppressionService;
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
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ConsentGate clauses, one test per clause. Run on H2 ({@link ConsentGateTest}) and on
 * Postgres 17 ({@link ConsentGatePostgresTest}) because the gate is one native query.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
abstract class ConsentGateScenarios {

    /** Paris date 2026-09-27; cutoff date = 2026-09-27 − 1095 days = 2023-09-28. */
    static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    static final LocalDate CUTOFF_DATE = LocalDate.parse("2023-09-28");
    static final String NAMED_VERSION = "checkout-named-v1";
    static final Instant RECENT = NOW.minus(10, ChronoUnit.DAYS);
    static final UUID LOW_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID LOW_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID HIGH_ID_1 = UUID.fromString("7fffffff-ffff-ffff-ffff-fffffffffff1");
    static final UUID HIGH_ID_2 = UUID.fromString("7fffffff-ffff-ffff-ffff-fffffffffff2");

    @Autowired FanFeatureRepository fanRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AudiencePlanLogic shippedLogic;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired ConsentService consentService;
    @Autowired SuppressionService suppressionService;
    @Autowired MarketingOptOutRepository optOutRepo;
    @Autowired AudienceImportRepository importRepo;
    @Autowired ImportRowProvenanceRepository provenanceRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AuditLogger auditLogger;

    UUID orgA;
    UUID orgB;
    AuthPrincipal organizerA;
    private final List<UUID> orgs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orgA = org("Europe/Paris");
        orgB = org("Europe/Paris");
        organizerA = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
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
            jdbc.update("delete from suppression_entries where org_id = ?", org);
            jdbc.update("delete from marketing_optouts where org_id = ?", org);
            jdbc.update("delete from memberships where org_id = ?", org);
            for (UUID cid : cids) {
                String email = jdbc.queryForObject("select normalized_email from consumers where consumer_id = ?",
                        String.class, cid);
                jdbc.update("delete from suppression_entries where normalized_email = ?", email);
                jdbc.update("delete from consumers where consumer_id = ?", cid);
            }
            jdbc.update("delete from tickets where order_id in (select id from orders where org_id = ?)", org);
            jdbc.update("delete from orders where org_id = ?", org);
            jdbc.update("delete from events where org_id = ?", org);
            jdbc.update("delete from users where org_id = ?", org);
            jdbc.update("delete from organizations where id = ?", org);
        }
        orgs.clear();
    }

    // ── explicit: organizer_import_row ─────────────────────────────────────

    @Test
    void importRow_withAcceptedProvenance_isMailable() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "organizer_import_row", "2026-09-27", null, RECENT);
        provenance(orgA, mid, true, LocalDate.parse("2026-08-01"));

        assertMailable(gate(), orgA, mid);
    }

    @Test
    void importRow_withoutProvenance_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "organizer_import_row", "2026-09-27", null, RECENT);
        contact(mid, RECENT);

        assertReason(gate(), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void importRow_withOnlyRejectedProvenance_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "organizer_import_row", "2026-09-27", null, RECENT);
        provenance(orgA, mid, false, LocalDate.parse("2026-08-01"));

        assertReason(gate(), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void importRow_typedByOrganizerWithoutTextVersion_isLegacyEvenWithProvenance() {
        UUID mid = member(orgA);
        provenance(orgA, mid, true, LocalDate.parse("2026-08-01"));
        consentService.capture(orgA, mid, "explicit", "organizer_import_row", "typed", organizerA);

        assertReason(gate(), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void legacyBulkOrganizerImport_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "organizer_import", null, null, RECENT);
        contact(mid, RECENT);

        assertReason(gate(), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    // ── explicit: checkout ─────────────────────────────────────────────────

    @Test
    void checkout_withAllowlistedVersion_isMailable() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, UUID.randomUUID(), RECENT);

        assertMailable(gate(), orgA, mid);
    }

    @Test
    void checkout_withNullVersion_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", null, UUID.randomUUID(), RECENT);
        contact(mid, RECENT);

        assertReason(gate(), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void checkout_withNonAllowlistedVersion_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", "checkout-unnamed-v0", UUID.randomUUID(), RECENT);
        contact(mid, RECENT);

        assertReason(gate(), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void checkout_withShippedEmptyAllowlist_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, UUID.randomUUID(), RECENT);
        contact(mid, RECENT);

        ConsentGate shipped = new ConsentGate(fanRepo, orgRepo, shippedLogic, props(false), clock());
        assertReason(shipped, orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    // ── explicit: organizer-typed and other sources ────────────────────────

    @Test
    void organizerTypedCapture_isLegacyUnproven() {
        UUID mid = member(orgA);
        consentService.capture(orgA, mid, "explicit", "met at the bar", "Said yes in person", organizerA);
        contact(mid, RECENT);

        assertReason(gate(), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void organizerTypedCapture_usingTheCheckoutSource_isLegacyUnproven() {
        UUID mid = member(orgA);
        consentService.capture(orgA, mid, "explicit", "checkout", "Claimed checkout", organizerA);
        contact(mid, RECENT);

        assertReason(gate(), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void doorQr_withTextVersion_isMailable() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", null, RECENT);

        assertMailable(gate(), orgA, mid);
    }

    @Test
    void survey_withoutTextVersion_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "survey", null, null, RECENT);
        contact(mid, RECENT);

        assertReason(gate(), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void latestSubscribingRecordDecides() {
        UUID proven = member(orgA);
        consent(proven, "explicit", "checkout", NAMED_VERSION, null, RECENT.minus(5, ChronoUnit.DAYS));
        consent(proven, "explicit", "manual", null, null, RECENT);
        contact(proven, RECENT);
        UUID reproven = member(orgA);
        consent(reproven, "explicit", "organizer_import", null, null, RECENT.minus(5, ChronoUnit.DAYS));
        consent(reproven, "explicit", "checkout", NAMED_VERSION, null, RECENT);

        ConsentGate gate = gate();
        assertReason(gate, orgA, proven, ConsentGate.LEGACY_UNPROVEN);
        assertMailable(gate, orgA, reproven);
    }

    @Test
    void equalOccurredAt_provenRecordWinsOverLegacy_whateverTheIds() {
        UUID legacyHighId = member(orgA);
        UUID a1 = consentId(legacyHighId, "explicit", "organizer_import", null, RECENT);
        UUID a2 = consentId(legacyHighId, "explicit", "checkout", NAMED_VERSION, RECENT);
        setId(a1, HIGH_ID_1);
        setId(a2, LOW_ID_1);
        UUID legacyLowId = member(orgA);
        UUID b1 = consentId(legacyLowId, "explicit", "checkout", NAMED_VERSION, RECENT);
        UUID b2 = consentId(legacyLowId, "explicit", "organizer_import", null, RECENT);
        setId(b1, HIGH_ID_2);
        setId(b2, LOW_ID_2);

        ConsentGate gate = gate();
        assertMailable(gate, orgA, legacyHighId);
        assertMailable(gate, orgA, legacyLowId);
    }

    @Test
    void equalOccurredAt_bothProven_higherIdDecides() {
        // Import row (not a contact) outranks door_qr by id, so the verdict follows the import's retention.
        UUID mid = member(orgA);
        provenance(orgA, mid, true, null);
        UUID door = consentId(mid, "explicit", "door_qr", "door-v1", RECENT);
        UUID imp = consentId(mid, "explicit", "organizer_import_row", "2026-09-27", RECENT);
        setId(door, LOW_ID_1);
        setId(imp, HIGH_ID_1);

        assertReason(gate(), orgA, mid, ConsentGate.RETENTION_3Y);
    }

    @Test
    void smsOnlyConsent_isNoBasis() {
        UUID mid = member(orgA);
        ConsentRecord r = record(mid, "explicit", "order_confirmation", "sms-v1", null, RECENT);
        r.setChannel("sms");
        consentRepo.save(r);
        contact(mid, RECENT);

        assertReason(gate(), orgA, mid, ConsentGate.NO_BASIS);
    }

    @Test
    void neverConsented_isNoBasis() {
        UUID mid = member(orgA);
        contact(mid, RECENT);

        assertReason(gate(), orgA, mid, ConsentGate.NO_BASIS);
    }

    // ── soft_opt_in ────────────────────────────────────────────────────────

    @Test
    void softOptIn_flagOff_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "soft_opt_in", "checkout", null, paidOrder(orgA, "stripe", 1500, false, Ticket.STATE_ISSUED), RECENT);
        contact(mid, RECENT);

        assertReason(gate(false), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void softOptIn_flagOn_paidOrderOfThisOrg_isMailable() {
        UUID mid = member(orgA);
        consent(mid, "soft_opt_in", "checkout", null, paidOrder(orgA, "stripe", 1500, false, Ticket.STATE_ISSUED), RECENT);
        contact(mid, RECENT);

        assertMailable(gate(true), orgA, mid);
    }

    @Test
    void softOptIn_flagOn_freeOrder_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "soft_opt_in", "checkout", null, paidOrder(orgA, "free", 0, false, Ticket.STATE_ISSUED), RECENT);
        contact(mid, RECENT);

        assertReason(gate(true), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void softOptIn_flagOn_testModeOrder_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "soft_opt_in", "checkout", null, paidOrder(orgA, "stripe", 1500, true, Ticket.STATE_ISSUED), RECENT);
        contact(mid, RECENT);

        assertReason(gate(true), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void softOptIn_flagOn_fullyRefundedOrder_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "soft_opt_in", "checkout", null, paidOrder(orgA, "stripe", 1500, false, Ticket.STATE_REFUNDED), RECENT);
        contact(mid, RECENT);

        assertReason(gate(true), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void softOptIn_flagOn_legacyRowWithoutOrderId_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "soft_opt_in", "checkout", null, null, RECENT);
        contact(mid, RECENT);

        assertReason(gate(true), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @Test
    void softOptIn_flagOn_orderOfAnotherOrg_isLegacyUnproven() {
        UUID mid = member(orgA);
        consent(mid, "soft_opt_in", "checkout", null, paidOrder(orgB, "stripe", 1500, false, Ticket.STATE_ISSUED), RECENT);
        contact(mid, RECENT);

        assertReason(gate(true), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"door_qr", "organizer_import_row", "survey"})
    void softOptIn_flagOn_paidOrder_nonCheckoutSource_isLegacyUnproven(String source) {
        UUID mid = member(orgA);
        provenance(orgA, mid, true, LocalDate.parse("2026-08-01"));
        consent(mid, "soft_opt_in", source, "v1", paidOrder(orgA, "stripe", 1500, false, Ticket.STATE_ISSUED), RECENT);
        contact(mid, RECENT);

        assertReason(gate(true), orgA, mid, ConsentGate.LEGACY_UNPROVEN);
    }

    // ── status, suppression, objection ─────────────────────────────────────

    @Test
    void unsubscribed_isExcluded() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        setConsentStatus(mid, "unsubscribed");

        assertReason(gate(), orgA, mid, ConsentGate.UNSUBSCRIBED);
    }

    @Test
    void stickyOptOut_isUnsubscribed_evenWhileSubscribed() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        optOutRepo.save(MarketingOptOut.of(emailOf(mid), orgA, "email", "one_click"));

        assertReason(gate(), orgA, mid, ConsentGate.UNSUBSCRIBED);
    }

    @Test
    void marketingSuppressed_isSuppressed() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        suppressionService.addMarketing(orgA, mid, SuppressionEntry.REASON_MANUAL, organizerA);

        assertReason(gate(), orgA, mid, ConsentGate.SUPPRESSED);
    }

    @Test
    void deliverabilitySuppressed_isSuppressed() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        suppressionService.addDeliverability(emailOf(mid), SuppressionEntry.REASON_HARD_BOUNCE);

        assertReason(gate(), orgA, mid, ConsentGate.SUPPRESSED);
    }

    @Test
    void erasePending_isExcluded() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        jdbc.update("update memberships set status = 'erase_pending' where membership_id = ?", mid);

        assertReason(gate(), orgA, mid, ConsentGate.ERASE_PENDING);
    }

    @Test
    void blankEmail_isNoEmail() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        jdbc.update("update consumers set normalized_email = '' where consumer_id = "
                + "(select consumer_id from memberships where membership_id = ?)", mid);

        assertReason(gate(), orgA, mid, ConsentGate.NO_EMAIL);
    }

    @Test
    void objectedProfiling_isObjected() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        jdbc.update("update memberships set objected_profiling = TRUE where membership_id = ?", mid);

        assertReason(gate(), orgA, mid, ConsentGate.OBJECTED);
    }

    // ── 3-year rule (org timezone) ─────────────────────────────────────────

    @Test
    void lastContact1095DaysAgo_isMailable() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", null, Instant.parse("2020-01-01T12:00:00Z"));
        contact(mid, CUTOFF_DATE.atTime(12, 0).toInstant(ZoneOffset.UTC));

        assertMailable(gate(), orgA, mid);
    }

    @Test
    void lastContact1096DaysAgo_isRetention3y() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "door_qr", "door-v1", null, Instant.parse("2020-01-01T12:00:00Z"));
        contact(mid, CUTOFF_DATE.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC));

        assertReason(gate(), orgA, mid, ConsentGate.RETENTION_3Y);
    }

    @Test
    void nullLastContact_isRetention3y() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "organizer_import_row", "2026-09-27", null, RECENT);
        provenance(orgA, mid, true, null);

        assertReason(gate(), orgA, mid, ConsentGate.RETENTION_3Y);
    }

    @Test
    void provenanceLastPurchaseDate_onTheCutoffDay_counts_dayBefore_doesNot() {
        UUID onCutoff = member(orgA);
        consent(onCutoff, "explicit", "organizer_import_row", "2026-09-27", null, RECENT);
        provenance(orgA, onCutoff, true, CUTOFF_DATE);
        UUID dayBefore = member(orgA);
        consent(dayBefore, "explicit", "organizer_import_row", "2026-09-27", null, RECENT);
        provenance(orgA, dayBefore, true, CUTOFF_DATE.minusDays(1));

        ConsentGate gate = gate();
        assertMailable(gate, orgA, onCutoff);
        assertReason(gate, orgA, dayBefore, ConsentGate.RETENTION_3Y);
    }

    @Test
    void importConsentTime_isNotContact() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "organizer_import_row", "2026-09-27", null, RECENT);
        provenance(orgA, mid, true, CUTOFF_DATE.minusDays(1));

        assertReason(gate(), orgA, mid, ConsentGate.RETENTION_3Y);
    }

    @Test
    void provingPersonConsentTime_countsAsContact_withoutFanFeatures() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);

        assertMailable(gate(), orgA, mid);
    }

    @Test
    void retentionCutoff_usesTheOrgTimezone() {
        // 22:30 UTC on 2023-09-27 is already 2023-09-28 (the cutoff day) in Paris.
        Instant lateEvening = Instant.parse("2023-09-27T22:30:00Z");
        UUID paris = member(orgA);
        consent(paris, "explicit", "door_qr", "door-v1", null, Instant.parse("2020-01-01T12:00:00Z"));
        contact(paris, lateEvening);
        UUID utcOrg = org("UTC");
        UUID utc = member(utcOrg);
        consent(utc, "explicit", "door_qr", "door-v1", null, Instant.parse("2020-01-01T12:00:00Z"));
        contact(utc, lateEvening);

        ConsentGate gate = gate();
        assertMailable(gate, orgA, paris);
        assertReason(gate, utcOrg, utc, ConsentGate.RETENTION_3Y);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Mars/Olympus_Mons"})
    void blankOrInvalidOrgTimezone_fallsBackToUtc(String timezone) {
        // Mailable in Paris (see above); UTC puts this contact one day before the cutoff.
        UUID badOrg = org(timezone);
        UUID mid = member(badOrg);
        consent(mid, "explicit", "door_qr", "door-v1", null, Instant.parse("2020-01-01T12:00:00Z"));
        contact(mid, Instant.parse("2023-09-27T22:30:00Z"));

        assertReason(gate(), badOrg, mid, ConsentGate.RETENTION_3Y);
    }

    // ── tracking, isolation, breakdown ─────────────────────────────────────

    @Test
    void canTrack_isAlwaysFalse() {
        assertThat(gate().canTrack()).isFalse();
        assertThat(gate(true).canTrack()).isFalse();
    }

    @Test
    void membershipOfAnotherOrg_isNeverMailableNorLeaked() {
        UUID midB = member(orgB);
        consent(midB, "explicit", "checkout", NAMED_VERSION, null, RECENT);

        ConsentGate gate = gate();
        assertThat(gate.canMarket(orgB, midB)).isTrue();
        assertThat(gate.canMarket(orgA, midB)).isFalse();
        assertThat(gate.reasons(orgA, List.of(midB))).isEmpty();
        assertThat(gate.mailableMembershipIds(orgA)).doesNotContain(midB);
    }

    @Test
    void breakdown_countsEveryReason_andSumsToMembersMinusMailable() {
        UUID ok1 = member(orgA);
        consent(ok1, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        UUID ok2 = member(orgA);
        consent(ok2, "explicit", "door_qr", "door-v1", null, RECENT);
        UUID legacy1 = member(orgA);
        consent(legacy1, "explicit", "checkout", null, null, RECENT);
        UUID legacy2 = member(orgA);
        consent(legacy2, "explicit", "organizer_import", null, null, RECENT);
        UUID none = member(orgA);
        UUID unsub = member(orgA);
        consent(unsub, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        setConsentStatus(unsub, "unsubscribed");
        UUID old = member(orgA);
        consent(old, "explicit", "door_qr", "door-v1", null, Instant.parse("2020-01-01T12:00:00Z"));
        UUID otherOrg = member(orgB);
        consent(otherOrg, "explicit", "checkout", NAMED_VERSION, null, RECENT);

        ConsentGate gate = gate();
        ConsentGate.Breakdown b = gate.breakdown(orgA);

        assertThat(b.members()).isEqualTo(7);
        assertThat(b.mailable()).isEqualTo(2);
        assertThat(b.exclusions()).containsOnlyKeys(ConsentGate.REASONS);
        assertThat(b.exclusions()).containsEntry(ConsentGate.LEGACY_UNPROVEN, 2)
                .containsEntry(ConsentGate.NO_BASIS, 1)
                .containsEntry(ConsentGate.UNSUBSCRIBED, 1)
                .containsEntry(ConsentGate.RETENTION_3Y, 1)
                .containsEntry(ConsentGate.SUPPRESSED, 0);
        assertThat(b.legacyNotMailable()).isEqualTo(2);
        assertThat(b.exclusions().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(b.members() - b.mailable());
        assertThat(gate.mailableMembershipIds(orgA)).containsExactlyInAnyOrder(ok1, ok2);
        Map<UUID, Optional<String>> reasons = gate.reasons(orgA, List.of(ok1, legacy1, none, old));
        assertThat(reasons).containsEntry(ok1, Optional.empty())
                .containsEntry(legacy1, Optional.of(ConsentGate.LEGACY_UNPROVEN))
                .containsEntry(none, Optional.of(ConsentGate.NO_BASIS))
                .containsEntry(old, Optional.of(ConsentGate.RETENTION_3Y));
    }

    @Test
    void breakdown_ofAnEmptyOrg_isAllZeros() {
        ConsentGate.Breakdown b = gate().breakdown(orgA);
        assertThat(b.members()).isZero();
        assertThat(b.mailable()).isZero();
        assertThat(b.exclusions()).containsOnlyKeys(ConsentGate.REASONS).allSatisfy((k, v) -> assertThat(v).isZero());
    }

    @Test
    void nullOrEmptyArguments_answerEmpty_neverThrow() {
        UUID mid = member(orgA);
        consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        List<UUID> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(mid);

        ConsentGate gate = gate();
        assertThat(gate.reasons(null, List.of(mid))).isEmpty();
        assertThat(gate.reasons(orgA, null)).isEmpty();
        assertThat(gate.reasons(orgA, List.of())).isEmpty();
        assertThat(gate.reasons(orgA, withNull)).containsOnlyKeys(mid);
        assertThat(gate.canMarket(null, mid)).isFalse();
        assertThat(gate.canMarket(orgA, null)).isFalse();
        assertThat(gate.mailableMembershipIds(null)).isEmpty();
        ConsentGate.Breakdown b = gate.breakdown(null);
        assertThat(b.members()).isZero();
        assertThat(b.mailable()).isZero();
        assertThat(b.exclusions()).containsOnlyKeys(ConsentGate.REASONS).allSatisfy((k, v) -> assertThat(v).isZero());
    }

    @Test
    void reasonsForASubset_matchTheWholeOrgVerdicts() {
        // The id filter sits inside the consent ranking; a subset must see exactly the verdicts the org scan gives.
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            UUID mid = member(orgA);
            switch (i % 4) {
                case 0 -> consent(mid, "explicit", "checkout", NAMED_VERSION, null, RECENT);
                case 1 -> consent(mid, "explicit", "organizer_import", null, null, RECENT);
                case 2 -> consent(mid, "explicit", "door_qr", "door-v1", null, Instant.parse("2020-01-01T12:00:00Z"));
                default -> { }
            }
            consent(mid, "explicit", "manual", null, null, Instant.parse("2019-01-01T12:00:00Z"));
            all.add(mid);
        }
        UUID midB = member(orgB);
        consent(midB, "explicit", "checkout", NAMED_VERSION, null, RECENT);

        ConsentGate gate = gate();
        Map<UUID, Optional<String>> whole = gate.reasons(orgA, all);
        assertThat(whole).hasSize(24);
        List<UUID> subset = List.of(all.get(0), all.get(5), all.get(10), all.get(15), midB);
        Map<UUID, Optional<String>> part = gate.reasons(orgA, subset);

        assertThat(part).containsOnlyKeys(all.get(0), all.get(5), all.get(10), all.get(15));
        part.forEach((id, reason) -> assertThat(reason).isEqualTo(whole.get(id)));
        assertThat(part.get(all.get(0))).isEmpty();
        assertThat(part.get(all.get(5))).contains(ConsentGate.LEGACY_UNPROVEN);
        assertThat(part.get(all.get(10))).contains(ConsentGate.RETENTION_3Y);
        assertThat(part.get(all.get(15))).contains(ConsentGate.LEGACY_UNPROVEN);
        assertThat(gate.mailableMembershipIds(orgA)).containsExactlyInAnyOrderElementsOf(
                whole.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reasons_chunksLargeIdListsIntoQueriesOfAtMost1000() {
        UUID first = member(orgA);
        consent(first, "explicit", "checkout", NAMED_VERSION, null, RECENT);
        UUID middle = member(orgA);
        consent(middle, "explicit", "organizer_import", null, null, RECENT);
        UUID last = member(orgA);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 2500; i++) ids.add(UUID.randomUUID());
        ids.set(0, first);
        ids.set(1500, middle);
        ids.set(2499, last);
        FanFeatureRepository counting = mock(FanFeatureRepository.class, AdditionalAnswers.delegatesTo(fanRepo));
        ConsentGate gate = new ConsentGate(counting, orgRepo, withNamedVersions(shippedLogic, Set.of(NAMED_VERSION)),
                props(false), clock());

        Map<UUID, Optional<String>> reasons = gate.reasons(orgA, ids);

        assertThat(reasons).containsOnlyKeys(first, middle, last)
                .containsEntry(first, Optional.empty())
                .containsEntry(middle, Optional.of(ConsentGate.LEGACY_UNPROVEN))
                .containsEntry(last, Optional.of(ConsentGate.NO_BASIS));
        ArgumentCaptor<Collection<UUID>> chunks = ArgumentCaptor.forClass(Collection.class);
        verify(counting, times(3)).findExclusionReasons(eq(orgA), any(), any(), any(), any(), any(), any(),
                any(), any(), chunks.capture());
        assertThat(chunks.getAllValues()).extracting(Collection::size).containsExactly(1000, 1000, 500);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    ConsentGate gate() {
        return gate(false);
    }

    ConsentGate gate(boolean softOptIn) {
        return new ConsentGate(fanRepo, orgRepo, withNamedVersions(shippedLogic, Set.of(NAMED_VERSION)),
                props(softOptIn), clock());
    }

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    static AudiencePlanProperties props(boolean softOptIn) {
        AudiencePlanProperties p = new AudiencePlanProperties();
        p.setSoftOptInEnabled(softOptIn);
        return p;
    }

    static AudiencePlanLogic withNamedVersions(AudiencePlanLogic l, Set<String> versions) {
        AudiencePlanLogic.Logic o = l.logic();
        AudiencePlanLogic.Legal g = o.legal();
        AudiencePlanLogic.Legal legal = new AudiencePlanLogic.Legal(g.retentionDays(),
                g.softOptInRequiresPaidOrderAndOrgSeller(), g.esRobinsonCheck(), g.explicitSources(), versions);
        return new AudiencePlanLogic(new AudiencePlanLogic.Logic(o.version(), o.modes(), o.targetDefaultPct(),
                o.minSegmentToShow(), o.tasteHalfLifeDays(), o.classes(), o.inviteOtherGenreOnlyIfCoverageBelow(),
                o.exclusions(), o.coverageVerdict(), o.experiments(), legal), l.priors(), l.genres());
    }

    static void assertMailable(ConsentGate gate, UUID orgId, UUID mid) {
        assertThat(gate.reasons(orgId, List.of(mid))).containsEntry(mid, Optional.empty());
        assertThat(gate.canMarket(orgId, mid)).isTrue();
        assertThat(gate.mailableMembershipIds(orgId)).contains(mid);
    }

    static void assertReason(ConsentGate gate, UUID orgId, UUID mid, String reason) {
        assertThat(gate.reasons(orgId, List.of(mid))).containsEntry(mid, Optional.of(reason));
        assertThat(gate.canMarket(orgId, mid)).isFalse();
        assertThat(gate.mailableMembershipIds(orgId)).doesNotContain(mid);
    }

    UUID org(String timezone) {
        Organization o = new Organization();
        o.setName("Gate Org");
        o.setSlug("gate-" + UUID.randomUUID().toString().substring(0, 12));
        o.setContactEmail("gate@example.com");
        o.setCountry("FR");
        o.setTimezone(timezone);
        UUID id = orgRepo.save(o).getId();
        orgs.add(id);
        return id;
    }

    UUID member(UUID orgId) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("gate-" + UUID.randomUUID() + "@example.com");
        c = consumerRepo.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        return membershipRepo.save(m).getMembershipId();
    }

    String emailOf(UUID mid) {
        return jdbc.queryForObject("select c.normalized_email from consumers c join memberships m "
                + "on m.consumer_id = c.consumer_id where m.membership_id = ?", String.class, mid);
    }

    ConsentRecord record(UUID mid, String basis, String source, String textVersion, UUID orderId, Instant at) {
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(mid);
        r.setStatus("subscribed");
        r.setLawfulBasis(basis);
        r.setSource(source);
        r.setProofText("proof");
        r.setTextVersion(textVersion);
        r.setOrderId(orderId);
        r.setOccurredAt(at);
        return r;
    }

    void consent(UUID mid, String basis, String source, String textVersion, UUID orderId, Instant at) {
        consentRepo.save(record(mid, basis, source, textVersion, orderId, at));
        jdbc.update("update memberships set consent_status = 'subscribed', consent_basis = ? where membership_id = ?",
                basis, mid);
    }

    UUID consentId(UUID mid, String basis, String source, String textVersion, Instant at) {
        UUID id = consentRepo.save(record(mid, basis, source, textVersion, null, at)).getId();
        jdbc.update("update memberships set consent_status = 'subscribed', consent_basis = ? where membership_id = ?",
                basis, mid);
        return id;
    }

    /** Pins a consent id so an id tie-break is observable; high ids sort last under signed and unsigned order. */
    void setId(UUID from, UUID to) {
        jdbc.update("update consent_records set id = ? where id = ?", to, from);
    }

    void setConsentStatus(UUID mid, String status) {
        jdbc.update("update memberships set consent_status = ? where membership_id = ?", status, mid);
    }

    void contact(UUID mid, Instant at) {
        UUID orgId = jdbc.queryForObject("select org_id from memberships where membership_id = ?", UUID.class, mid);
        FanFeature f = new FanFeature();
        f.setMembershipId(mid);
        f.setOrgId(orgId);
        f.setLastContactFromPersonAt(at);
        f.setLogicVersion(1);
        fanRepo.save(f);
    }

    void provenance(UUID orgId, UUID mid, boolean accepted, LocalDate lastPurchaseDate) {
        AudienceImport imp = new AudienceImport();
        imp.setOrgId(orgId);
        imp = importRepo.save(imp);
        ImportRowProvenance p = new ImportRowProvenance();
        p.setImportId(imp.getId());
        p.setMembershipId(mid);
        p.setRowNumber(1);
        p.setSourcePlatform("shotgun");
        p.setExportDate(LocalDate.parse("2026-09-01"));
        p.setLastPurchaseDate(lastPurchaseDate);
        p.setMarketingStatus("opted_in");
        p.setProofRef(accepted ? "export-2026-09-01.csv" : null);
        p.setAccepted(accepted);
        p.setRejectReason(accepted ? null : "missing_proof");
        provenanceRepo.save(p);
    }

    UUID paidOrder(UUID orgId, String paymentMethod, long totalMinor, boolean testMode, String ticketState) {
        User owner = new User();
        owner.setEmail("gate-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        owner = userRepo.save(owner);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Gate Night");
        e.setSlug("gate-event-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        e = eventRepo.save(e);
        Order o = new Order();
        o.setToken("gate-" + UUID.randomUUID());
        o.setEventId(e.getId());
        o.setOrgId(orgId);
        o.setEmail("buyer@example.com");
        o.setTotalMinor(totalMinor);
        o.setCurrency("EUR");
        o.setPaymentMethod(paymentMethod);
        o.setTestMode(testMode);
        o = orderRepo.save(o);
        Ticket t = new Ticket();
        t.setToken("gate-t-" + UUID.randomUUID());
        t.setOrderId(o.getId());
        t.setEventId(e.getId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor((int) totalMinor);
        t.setState(ticketState);
        ticketRepo.save(t);
        return o.getId();
    }
}
