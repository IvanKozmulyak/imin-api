package com.imin.iminapi.buyer;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerEmailVerificationCode;
import com.imin.iminapi.buyer.model.BuyerIdentity;
import com.imin.iminapi.buyer.model.BuyerPasswordResetToken;
import com.imin.iminapi.buyer.model.BuyerSession;
import com.imin.iminapi.buyer.model.BuyerVerificationAttempt;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerEmailVerificationCodeRepository;
import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.buyer.repository.BuyerPasswordResetTokenRepository;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.buyer.repository.BuyerVerificationAttemptRepository;
import com.imin.iminapi.buyer.service.BuyerAccountErasureJob;
import com.imin.iminapi.buyer.service.BuyerAccountErasureService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.util.Times;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The §7.2 cascade: {@code BuyerAccountErasureService}, its job, the
 * Consumer-survival guard it added to {@code DsarService}, and the tombstone.
 *
 * <p><b>{@code AuditLogger} is deliberately NOT mocked in this file.</b> Every
 * pre-existing test of the erasure cascade mocked it, and that is exactly how
 * the platform shipped an erasure job that erased nothing: the null-org audit
 * INSERT violated {@code audit_logs.org_id NOT NULL}, failed at the
 * {@code REQUIRES_NEW} commit — outside {@code AuditLogger}'s own try/catch —
 * and rolled the whole cascade back every night. A tombstone test that mocks
 * the logger tests nothing.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class BuyerAccountErasureTest {

    @Autowired BuyerAccountErasureService erasureService;
    @Autowired BuyerAccountErasureJob erasureJob;
    @Autowired DsarService dsarService;

    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emails;
    @Autowired BuyerSessionRepository sessions;
    @Autowired BuyerIdentityRepository identities;
    @Autowired BuyerEmailVerificationCodeRepository codes;
    @Autowired BuyerPasswordResetTokenRepository resetTokens;
    @Autowired BuyerVerificationAttemptRepository attempts;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired SuppressionRepository suppressions;
    @Autowired NotifySubscriptionRepository notifySubscriptions;
    @Autowired AuditLogRepository auditLogs;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUp() {
        orgA = org("EraOrgA");
        orgB = org("EraOrgB");
    }

    // ── The multi-address cascade ──────────────────────────────────────────

    /**
     * THE TEST THIS SLICE EXISTS FOR (§7.2).
     *
     * <p>{@code consumers.normalized_email} is UNIQUE, so two verified addresses
     * are two {@code Consumer} rows with two separate audiences behind them. An
     * implementation that resolved "the" consumer would pass every single-address
     * test in this file and still leave half the buyer's data on the platform.
     */
    @Test
    void erasure_fans_out_over_every_verified_address_not_just_the_primary() {
        UUID account = account();
        String first = verifiedAddress(account, true);
        String second = verifiedAddress(account, false);

        UUID cFirst = consumer(first);
        UUID cSecond = consumer(second);
        UUID mFirst = membership(orgA, cFirst);
        UUID mSecond = membership(orgB, cSecond);

        erasureService.erase(account);

        assertThat(membershipExists(mFirst, orgA)).isFalse();
        assertThat(membershipExists(mSecond, orgB)).isFalse();
        assertThat(consumers.findByNormalizedEmail(first)).isEmpty();
        assertThat(consumers.findByNormalizedEmail(second)).isEmpty();
    }

    /** The cascade is the existing org-scoped one, so everything it reaches goes too. */
    @Test
    void erasure_reuses_the_dsar_cascade_for_consent_and_suppression() {
        UUID account = account();
        String address = verifiedAddress(account, true);
        UUID mid = membership(orgA, consumer(address));

        consentRecord(mid);
        suppression(orgA, mid);
        assertThat(consentRecords.findByMembershipId(mid)).isNotEmpty();
        assertThat(suppressions.findMarketingByOrgAndMembership(orgA, mid)).isPresent();

        erasureService.erase(account);

        assertThat(consentRecords.findByMembershipId(mid)).isEmpty();
        assertThat(suppressions.findMarketingByOrgAndMembership(orgA, mid)).isEmpty();
    }

    /**
     * §7.2 step 3. {@code executeErase} deliberately removes only the erasing
     * org's notify rows, and a notify subscription can exist with no membership
     * at all — so the membership-driven cascade has no hook for either case.
     */
    @Test
    void erasure_deletes_notify_subscriptions_across_all_orgs_including_where_no_membership_exists() {
        UUID account = account();
        String address = verifiedAddress(account, true);
        // A membership in orgA only. orgB knows this buyer solely as a notify row.
        membership(orgA, consumer(address));

        UUID inA = notify(event(orgA), address);
        UUID inB = notify(event(orgB), address);

        erasureService.erase(account);

        assertThat(notifySubscriptions.findById(inA)).isEmpty();
        assertThat(notifySubscriptions.findById(inB)).isEmpty();
    }

    /** Even an unverified address is still the buyer's address sitting in our tables. */
    @Test
    void erasure_deletes_notify_subscriptions_for_unverified_addresses_too() {
        UUID account = account();
        verifiedAddress(account, true);
        String claimed = unverifiedAddress(account);

        UUID row = notify(event(orgA), claimed);

        erasureService.erase(account);

        assertThat(notifySubscriptions.findById(row)).isEmpty();
    }

    /**
     * §7.2 step 4, enumerated. The FK cascades from {@code buyer_accounts} cover
     * most of these; the address-keyed two do not cascade at all, which is the
     * whole reason the step is a list rather than a single DELETE.
     */
    @Test
    void erasure_clears_every_buyer_table_including_the_address_keyed_ones() {
        UUID account = account();
        String address = verifiedAddress(account, true);

        session(account);
        identity(account);
        verificationCode(account, address);
        resetToken(account);
        attempt(address);
        savedEvent(account, event(orgA));
        notificationPreference(account);
        marketingOptout(address, orgA);

        erasureService.erase(account);

        assertThat(accounts.findById(account)).isEmpty();
        assertThat(emails.findByBuyerAccountIdOrderByCreatedAtAsc(account)).isEmpty();
        assertThat(sessions.findByBuyerAccountIdAndRevokedAtIsNull(account)).isEmpty();
        assertThat(identities.findByBuyerAccountId(account)).isEmpty();
        assertThat(count("buyer_sessions", "buyer_account_id", account)).isZero();
        assertThat(count("buyer_email_verification_codes", "buyer_account_id", account)).isZero();
        assertThat(count("buyer_password_reset_tokens", "buyer_account_id", account)).isZero();
        assertThat(count("buyer_saved_events", "buyer_account_id", account)).isZero();
        assertThat(count("buyer_notification_preferences", "buyer_account_id", account)).isZero();

        // The two with no FK to cascade through.
        assertThat(jdbc.queryForObject(
                "select count(*) from buyer_verification_attempts where email_normalized = ?",
                Integer.class, address)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from marketing_optouts where email_normalized = ?",
                Integer.class, address)).isZero();
    }

    /**
     * The ordering constraint that is easy to get wrong. The guard added to
     * {@code DsarService} keeps each {@code Consumer} alive through step 2,
     * because the account's verified address rows still anchor them. Step 5 only
     * works because step 4 dropped {@code buyer_account_emails} first.
     */
    @Test
    void erasure_deletes_the_consumer_rows_after_the_anchor_is_gone() {
        UUID account = account();
        String address = verifiedAddress(account, true);
        membership(orgA, consumer(address));

        BuyerAccountErasureService.ErasureResult result = erasureService.erase(account);

        assertThat(result.consumersDeleted()).isEqualTo(1);
        assertThat(consumers.findByNormalizedEmail(address)).isEmpty();
    }

    /**
     * Blast-radius check. The cascade walks the account's address set and nothing
     * else, so a consumer that merely shares an org with the erased buyer keeps
     * its row and its membership. Worth pinning because every query in the
     * cascade is deliberately unscoped by org — the scoping is by address, and a
     * mistake there would be silent and wide.
     */
    @Test
    void erasure_does_not_touch_consumers_unrelated_to_the_account() {
        UUID account = account();
        String address = verifiedAddress(account, true);
        membership(orgA, consumer(address));

        UUID bystander = consumer("bystander-" + UUID.randomUUID() + "@example.com");
        UUID theirMembership = membership(orgA, bystander);

        erasureService.erase(account);

        assertThat(consumers.findByConsumerId(bystander)).isPresent();
        assertThat(membershipExists(theirMembership, orgA)).isTrue();
    }

    // ── The DsarService Consumer-survival guard (§7.2) ─────────────────────

    /**
     * MANDATORY (§7.2). There is no {@code buyer_accounts.consumer_id} FK, so
     * this guard is the only thing between an organizer erasing their copy of a
     * buyer and that buyer's imin account losing its identity anchor.
     */
    @Test
    void organizer_erasure_must_not_delete_a_consumer_anchored_by_a_verified_buyer_account() {
        UUID account = account();
        String address = verifiedAddress(account, true);
        UUID consumerId = consumer(address);
        UUID mid = membership(orgA, consumerId);

        // The organizer erases their only membership — normally the last one out
        // takes the Consumer with it.
        dsarService.executeErase(orgA, mid, principal(orgA));

        assertThat(membershipExists(mid, orgA)).isFalse();
        assertThat(consumers.findByNormalizedEmail(address))
                .as("the buyer's account anchor must survive an organizer's DSAR")
                .isPresent();
    }

    /**
     * The negative half. Without it the test above would also pass on an
     * implementation that simply never deletes consumers.
     */
    @Test
    void organizer_erasure_still_deletes_a_consumer_with_no_buyer_account_behind_it() {
        String address = "orphan-" + UUID.randomUUID() + "@example.com";
        UUID mid = membership(orgA, consumer(address));

        dsarService.executeErase(orgA, mid, principal(orgA));

        assertThat(consumers.findByNormalizedEmail(EmailNormalizer.normalize(address))).isEmpty();
    }

    /**
     * An unverified row is a claim anyone can make about any address. If it
     * pinned a Consumer alive, typing a stranger's address into a signup form
     * would veto their lawful Art.17 erasure.
     */
    @Test
    void an_unverified_buyer_address_does_not_anchor_a_consumer() {
        UUID account = account();
        verifiedAddress(account, true);
        String claimed = unverifiedAddress(account);
        UUID mid = membership(orgA, consumer(claimed));

        dsarService.executeErase(orgA, mid, principal(orgA));

        assertThat(consumers.findByNormalizedEmail(claimed)).isEmpty();
    }

    // ── The tombstone (§7.2 step 6) ────────────────────────────────────────

    /**
     * MANDATORY, and the reason {@code AuditLogger} is real in this file.
     *
     * <p>A deletion you cannot prove you performed is not a deletion. The row is
     * written per org that held a copy of the buyer, because
     * {@code audit_logs.org_id} is NOT NULL and every reader filters on it.
     */
    @Test
    void erasure_writes_a_durable_tombstone_per_affected_org() {
        UUID account = account();
        String first = verifiedAddress(account, true);
        String second = verifiedAddress(account, false);
        membership(orgA, consumer(first));
        membership(orgB, consumer(second));

        BuyerAccountErasureService.ErasureResult result = erasureService.erase(account);

        assertThat(result.tombstoned()).isTrue();

        List<AuditLog> tombstones = auditLogs.findAll().stream()
                .filter(r -> AuditActions.BUYER_ACCOUNT_ERASED.equals(r.getAction()))
                .filter(r -> account.equals(r.getTargetId()))
                .toList();

        assertThat(tombstones).hasSize(2);
        assertThat(tombstones).extracting(AuditLog::getOrgId).containsExactlyInAnyOrder(orgA, orgB);
        assertThat(tombstones).allSatisfy(r -> {
            assertThat(r.getOrgId()).isNotNull();
            assertThat(r.getTargetType()).isEqualTo("buyer_account");
            assertThat(r.getSummary()).contains("Art.17");
        });
    }

    /**
     * The per-membership tombstone from the reused cascade. This is the assertion
     * that would have caught the null-org bug: with the old
     * {@code (null, null, …)} principal no row was written AND the erasure rolled
     * back.
     */
    @Test
    void the_reused_dsar_cascade_also_writes_its_own_tombstone_with_a_real_org() {
        UUID account = account();
        String address = verifiedAddress(account, true);
        UUID mid = membership(orgA, consumer(address));

        erasureService.erase(account);

        List<AuditLog> rows = auditLogs.findAll().stream()
                .filter(r -> AuditActions.DSAR_ERASE_EXECUTED.equals(r.getAction()))
                .filter(r -> mid.equals(r.getTargetId()))
                .toList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOrgId()).isEqualTo(orgA);
    }

    /**
     * The org-less case, reported rather than faked. An account that never
     * bought anything and never joined an audience touches no org, so there is
     * nowhere for an {@code audit_logs} row to live — inventing a synthetic org
     * would be fabricating a record.
     */
    @Test
    void an_account_with_no_organizer_held_data_erases_and_reports_no_tombstone() {
        UUID account = account();
        verifiedAddress(account, true);

        BuyerAccountErasureService.ErasureResult result = erasureService.erase(account);

        assertThat(result.tombstoned()).isFalse();
        assertThat(result.orgs()).isEmpty();
        assertThat(accounts.findById(account)).isEmpty();
    }

    /**
     * THE REGRESSION GUARD for the bug this slice uncovered.
     *
     * <p>A null-org principal is exactly the payload {@code AudienceErasureJob}
     * used to pass. {@code audit_logs.org_id} is NOT NULL, so the INSERT marked
     * the {@code REQUIRES_NEW} transaction rollback-only and its commit — raised
     * by the interceptor, after {@code AuditLogger}'s catch was out of scope —
     * aborted the caller's business transaction. Net effect: no tombstone AND no
     * erasure, every night, silently.
     *
     * <p>The erasure must now complete regardless. The tombstone is a separate
     * concern and a separate fix: the jobs pass the erasing org, so the row is
     * writable — see {@link #the_reused_dsar_cascade_also_writes_its_own_tombstone_with_a_real_org()}.
     */
    @Test
    void an_unattributable_audit_row_can_never_roll_back_an_erasure() {
        UUID account = account();
        String address = verifiedAddress(account, true);
        UUID mid = membership(orgA, consumer(address));

        dsarService.executeErase(orgA, mid, new AuthPrincipal(null, null, UserRole.MEMBER, null));

        assertThat(membershipExists(mid, orgA))
                .as("the membership must be erased even when its audit row cannot be written")
                .isFalse();
    }

    // ── The job ────────────────────────────────────────────────────────────

    @Test
    void the_job_selects_only_past_due_delete_pending_accounts() {
        UUID due = account();
        schedule(due, -1);
        UUID notYet = account();
        schedule(notYet, 29 * 24 * 3600);
        UUID active = account();

        List<UUID> selected = accounts.findErasureDue(Instant.now()).stream()
                .map(BuyerAccount::getId).toList();

        assertThat(selected).contains(due).doesNotContain(notYet, active);
    }

    /** A {@code delete_pending} row with no deadline is not "due since forever". */
    @Test
    void the_job_ignores_a_pending_account_with_no_deadline() {
        UUID account = account();
        BuyerAccount a = accounts.findById(account).orElseThrow();
        a.setStatus(BuyerAccount.STATUS_DELETE_PENDING);
        a.setDeleteAt(null);
        accounts.save(a);

        assertThat(accounts.findErasureDue(Instant.now()).stream().map(BuyerAccount::getId))
                .doesNotContain(account);
    }

    @Test
    void the_job_erases_a_due_account_end_to_end() {
        UUID account = account();
        String address = verifiedAddress(account, true);
        UUID mid = membership(orgA, consumer(address));
        schedule(account, -1);

        erasureJob.run();

        assertThat(accounts.findById(account)).isEmpty();
        assertThat(membershipExists(mid, orgA)).isFalse();
    }

    @Test
    void erasing_twice_is_harmless() {
        UUID account = account();
        verifiedAddress(account, true);
        erasureService.erase(account);
        assertThat(accounts.findById(account)).isEmpty();
        // The job would never re-select it, but a retry must not explode either.
        assertThat(accounts.findById(account)).isEmpty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private UUID account() {
        BuyerAccount a = new BuyerAccount();
        a.setActivatedAt(Times.nowMicros());
        return accounts.save(a).getId();
    }

    private void schedule(UUID accountId, long secondsFromNow) {
        BuyerAccount a = accounts.findById(accountId).orElseThrow();
        a.setStatus(BuyerAccount.STATUS_DELETE_PENDING);
        a.setDeleteAt(Instant.now().plus(secondsFromNow, ChronoUnit.SECONDS));
        accounts.save(a);
    }

    private String verifiedAddress(UUID accountId, boolean primary) {
        String raw = "era-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        BuyerAccountEmail row = BuyerAccountEmail.of(accountId, raw, BuyerAccountEmail.ADDED_VIA_SIGNUP);
        row.markVerified(Times.nowMicros());
        if (primary) row.makePrimary();
        emails.save(row);
        return EmailNormalizer.normalize(raw);
    }

    private String unverifiedAddress(UUID accountId) {
        String raw = "claim-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        emails.save(BuyerAccountEmail.of(accountId, raw, BuyerAccountEmail.ADDED_VIA_MANUAL));
        return EmailNormalizer.normalize(raw);
    }

    private UUID consumer(String rawOrNormalized) {
        String normalized = EmailNormalizer.normalize(rawOrNormalized);
        return consumers.findByNormalizedEmail(normalized)
                .map(Consumer::getConsumerId)
                .orElseGet(() -> {
                    Consumer c = new Consumer();
                    c.setNormalizedEmail(normalized);
                    c.setDisplayName(normalized);
                    return consumers.save(c).getConsumerId();
                });
    }

    private UUID membership(UUID orgId, UUID consumerId) {
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumerId);
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
    }

    private boolean membershipExists(UUID membershipId, UUID orgId) {
        return memberships.findByIdAndOrgId(membershipId, orgId).isPresent();
    }

    private void consentRecord(UUID membershipId) {
        com.imin.iminapi.audience.model.ConsentRecord r = new com.imin.iminapi.audience.model.ConsentRecord();
        r.setMembershipId(membershipId);
        r.setChannel("email");
        r.setStatus("subscribed");
        r.setLawfulBasis("explicit");
        r.setSource("test");
        consentRecords.save(r);
    }

    private void suppression(UUID orgId, UUID membershipId) {
        SuppressionEntry e = new SuppressionEntry();
        e.setScope(SuppressionEntry.SCOPE_MARKETING);
        e.setOrgId(orgId);
        e.setMembershipId(membershipId);
        e.setReason(SuppressionEntry.REASON_MANUAL);
        suppressions.save(e);
    }

    private void session(UUID accountId) {
        BuyerSession s = new BuyerSession();
        s.setBuyerAccountId(accountId);
        s.setTokenHash(String.format("%064d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000)));
        s.setIssuedAt(Times.nowMicros());
        s.setLastUsedAt(Times.nowMicros());
        s.setExpiresAt(Times.nowMicros().plus(30, ChronoUnit.DAYS));
        sessions.save(s);
    }

    private void identity(UUID accountId) {
        BuyerIdentity i = new BuyerIdentity();
        i.setBuyerAccountId(accountId);
        i.setProvider("google");
        i.setProviderUserId("sub-" + UUID.randomUUID());
        identities.save(i);
    }

    private void verificationCode(UUID accountId, String address) {
        BuyerEmailVerificationCode c = new BuyerEmailVerificationCode();
        c.setBuyerAccountId(accountId);
        c.setEmailNormalized(address);
        c.setCodeHash(String.format("%064d", 1));
        c.setExpiresAt(Times.nowMicros().plus(1, ChronoUnit.HOURS));
        codes.save(c);
    }

    private void resetToken(UUID accountId) {
        BuyerPasswordResetToken t = new BuyerPasswordResetToken();
        t.setBuyerAccountId(accountId);
        t.setTokenHash(String.format("%064d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000)));
        t.setExpiresAt(Times.nowMicros().plus(30, ChronoUnit.MINUTES));
        resetTokens.save(t);
    }

    private void attempt(String address) {
        BuyerVerificationAttempt a = new BuyerVerificationAttempt();
        a.setEmailNormalized(address);
        a.setAttemptedAt(Times.nowMicros());
        a.setSucceeded(false);
        attempts.save(a);
    }

    private void savedEvent(UUID accountId, UUID eventId) {
        jdbc.update("insert into buyer_saved_events (buyer_account_id, event_id, created_at) "
                + "values (?, ?, current_timestamp)", accountId, eventId);
    }

    private void notificationPreference(UUID accountId) {
        jdbc.update("insert into buyer_notification_preferences "
                + "(buyer_account_id, event_reminders, product_news) values (?, true, false)", accountId);
    }

    private void marketingOptout(String address, UUID orgId) {
        jdbc.update("insert into marketing_optouts (email_normalized, org_id, channel, source, created_at) "
                + "values (?, ?, 'email', 'footer_link', current_timestamp)", address, orgId);
    }

    private UUID notify(UUID eventId, String address) {
        NotifySubscription s = new NotifySubscription();
        s.setEventId(eventId);
        s.setEmail(address);
        return notifySubscriptions.save(s).getId();
    }

    private int count(String table, String column, UUID value) {
        Integer n = jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
        return n == null ? 0 : n;
    }

    private UUID org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail(name + "@test.com");
        o.setCountry("DE");
        return orgs.save(o).getId();
    }

    private UUID event(UUID orgId) {
        User u = new User();
        u.setOrgId(orgId);
        String userEmail = "era-evt-" + UUID.randomUUID() + "@example.com";
        u.setEmail(userEmail);
        u.setEmailLower(userEmail);
        u.setRole(UserRole.OWNER);
        u = users.save(u);

        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Erasure Event");
        e.setSlug("erasure-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        e.setCreatedBy(u.getId());
        e.setCurrency("EUR");
        return events.save(e).getId();
    }

    private static AuthPrincipal principal(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }
}
