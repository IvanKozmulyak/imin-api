package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.audience.model.ErasedAddress;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.MarketingOptOutRecorder;
import com.imin.iminapi.audience.service.SuppressionService;
import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminderStatus;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.checkoutreminder.service.CheckoutReminderSender;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The send-time checks, the live numbers in the email, and at most one email per reminder. */
@IminIntegrationTest
class CheckoutReminderSenderTest {

    /** 12:00 in Paris on a Thursday: outside quiet hours. */
    private static final Instant NOW = Instant.parse("2026-11-05T11:00:00Z");
    private static final String FROM_ADDRESS = "reminders@news.imin.test";

    @Autowired CheckoutReminderSender sender;
    @Autowired CheckoutReminderProperties props;
    @Autowired MarketingEmailProperties emailProps;
    @Autowired CampaignEmailProvider provider;
    @Autowired UnsubscribeTokenService tokens;
    @Autowired PropertyFlips flips;
    @Autowired MutableClock clock;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired CheckoutReminderRepository reminders;
    @Autowired OrgCheckoutReminderSettingRepository settings;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired MarketingOptOutRecorder optOuts;
    @Autowired SuppressionService suppressions;
    @Autowired ErasedAddressRepository erased;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private ReminderRows rows;
    private ReminderRows.Org org;
    private Event night;
    private TicketTier tier;
    private String email;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);
        flips.set(props, "enabled", true);
        flips.set(emailProps, "fromAddress", FROM_ADDRESS);
        rows = new ReminderRows(fx, orgs, events, reminders, settings, consumers, memberships, jdbc);
        org = rows.org(NOW);
        night = rows.night(org, NOW.plus(Duration.ofDays(3)));
        tier = rows.tier(night, 1600, 50);
        email = rows.email("sender");
        when(provider.sendBatch(any(), any())).thenAnswer(inv ->
                CampaignEmailProvider.BatchResult.accepted(List.of("msg_" + UUID.randomUUID())));
    }

    @AfterEach
    void cleanUp() {
        rows.deleteOwn();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"HAS_TICKETS", "SOLD_OUT", "SALES_CLOSED:tier_closed", "SALES_CLOSED:cancelled",
            "SALES_CLOSED:sales_hold", "TOO_CLOSE_TO_DOORS", "TOO_LATE", "UNSUBSCRIBED:opt_out",
            "UNSUBSCRIBED:membership", "UNSUBSCRIBED:suppressed", "UNDELIVERABLE", "ERASED:platform", "ERASED:org",
            "ERASED:erase_pending", "CONSENT_VERSION_RETIRED",
            "PAUSED", "DISABLED", "LEGAL_IDENTITY_MISSING"})
    void dropsWithTheReasonAndSendsNothing(String variant) {
        Instant startedAt = NOW.minus(Duration.ofMinutes(61));
        switch (variant) {
            case "HAS_TICKETS" -> fx.order(night, email);
            case "SOLD_OUT" -> saveTier(t -> t.setSold(50));
            case "SALES_CLOSED:tier_closed" -> saveTier(t -> t.setSaleClosesAt(NOW.minus(Duration.ofHours(1))));
            case "SALES_CLOSED:cancelled" -> saveNight(e -> e.setStatus(EventStatus.CANCELLED));
            case "SALES_CLOSED:sales_hold" -> jdbc.update("""
                    INSERT INTO moderation_holds (id, org_id, event_id, suspend_sales, hold_payouts, ground, facts,
                                                  placed_by, placed_at)
                    VALUES (?, ?, ?, TRUE, FALSE, 'fraud', 'test hold', ?, now())
                    """, UUID.randomUUID(), org.id(), night.getId(), org.owner().getId());
            // Doors 2 h 59 min after the send.
            case "TOO_CLOSE_TO_DOORS" -> saveNight(e -> e.setStartsAt(NOW.plus(Duration.ofMinutes(179))));
            // Checkout started 24 h 01 min before the send.
            case "TOO_LATE" -> startedAt = NOW.minus(Duration.ofMinutes(24 * 60 + 1));
            case "UNSUBSCRIBED:opt_out" -> optOuts.record(email, org.id(), "email", "one_click");
            case "UNSUBSCRIBED:membership" -> rows.member(org, email, "unsubscribed");
            case "UNSUBSCRIBED:suppressed" -> {
                Membership m = rows.member(org, email, "subscribed");
                suppressions.addMarketingIfAbsent(org.id(), m.getMembershipId(), "manual",
                        new AuthPrincipal(null, org.id(), UserRole.MEMBER, null));
            }
            case "UNDELIVERABLE" -> suppressions.addDeliverabilityIfAbsent(email, "hard-bounce");
            case "ERASED:platform" -> erase(null);
            case "ERASED:org" -> erase(org.id());
            // An accepted erasure request still in its 30-day grace period.
            case "ERASED:erase_pending" -> jdbc.update("UPDATE memberships SET status = 'erase_pending' WHERE membership_id = ?",
                    rows.member(org, email, "subscribed").getMembershipId());
            // Set on the row below: a version taken off the allowlist after the tick was kept.
            case "CONSENT_VERSION_RETIRED" -> { }
            case "PAUSED" -> orgs.pauseMarketingIfNotPaused(org.id(), "complaint_rate", NOW.minus(Duration.ofHours(2)));
            case "DISABLED" -> settings.disable(org.id(), NOW);
            case "LEGAL_IDENTITY_MISSING" -> saveOrg(o -> o.setLegalName(" "));
            default -> throw new IllegalArgumentException(variant);
        }
        UUID id = rows.waiting(org, night, tier, email, 2, "en", false, startedAt, NOW.minus(Duration.ofMinutes(1)));
        if (variant.equals("CONSENT_VERSION_RETIRED")) {
            jdbc.update("UPDATE checkout_reminders SET consent_text_version = 'checkout-generic-2026-09' WHERE id = ?", id);
        }

        tick();

        CheckoutReminder after = rows.load(id);
        assertThat(after.getStatus()).isEqualTo(CheckoutReminderStatus.NOT_SENT);
        assertThat(after.getReason().name()).isEqualTo(variant.split(":")[0]);
        assertThat(sentTo(email)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"remaining6", "remaining12", "remaining1qty2", "localeFr", "promo"})
    void sendsOneEmailWithLiveNumbers(String variant) {
        int sold = switch (variant) {
            case "remaining12" -> 38;
            case "remaining1qty2" -> 49;
            default -> 44;
        };
        saveTier(t -> t.setSold(sold));
        String locale = variant.equals("localeFr") ? "fr" : "en";
        UUID id = rows.waiting(org, night, tier, email, 2, locale, variant.equals("promo"),
                NOW.minus(Duration.ofMinutes(61)), NOW.minus(Duration.ofMinutes(1)));

        tick();

        List<CampaignEmailProvider.OutgoingEmail> sent = sentTo(email);
        assertThat(sent).hasSize(1);
        CampaignEmailProvider.OutgoingEmail m = sent.get(0);
        assertThat(m.from()).isEqualTo("\"Vechirka via IMIN\" <" + FROM_ADDRESS + ">");
        String token = m.unsubscribeUrl().substring(m.unsubscribeUrl().lastIndexOf('/') + 1);
        assertThat(m.unsubscribeUrl()).isEqualTo(emailProps.getApiPublicBaseUrl() + "/api/v1/public/unsubscribe/" + token);
        assertThat(tokens.verifyReminder(token)).contains(id);
        assertThat(m.html()).contains(m.unsubscribeUrl()).contains(org.org().getLegalName())
                .contains("hello@vechirka.example").contains(night.getName());
        assertThat(m.text()).contains(m.unsubscribeUrl()).contains(org.org().getLegalName());
        int linkQty = variant.equals("remaining1qty2") ? 1 : 2;
        String link = emailProps.getBuyerSiteBaseUrl() + "/e/" + night.getId() + "/buy?tier=" + tier.getId()
                + "&qty=" + linkQty + "&utm_source=imin&utm_medium=email&utm_campaign=checkout_reminder";
        assertThat(m.text()).contains(link);
        assertThat(m.html()).contains(link.replace("&", "&amp;"));
        // 2 × 16.00 + (5 % of 32.00 + 2 × 0.99) = 35.58; 1 × 16.00 + (0.80 + 0.99) = 17.79.
        switch (variant) {
            case "remaining6" -> {
                assertThat(m.subject()).isEqualTo("You didn’t finish your order");
                assertThat(m.text()).contains("2 × " + tier.getName()).contains("€35.58").contains("incl. service fee")
                        .contains("Only 6 left when we sent this email.");
            }
            case "remaining12" -> assertThat(m.text()).contains("€35.58").doesNotContain("left when");
            case "remaining1qty2" -> assertThat(m.text()).contains("1 × " + tier.getName()).contains("€17.79")
                    .contains("Only 1 left when we sent this email.").contains("so the link picks 1");
            case "localeFr" -> {
                assertThat(m.subject()).isEqualTo("Ta commande n’est pas terminée");
                assertThat(m.text()).contains("35,58").contains("€").contains("frais de service inclus")
                        .contains("Il n’en restait plus que 6 quand nous avons envoyé cet e-mail.").contains("Tu as coché");
                assertThat(m.html()).contains("lang=\"fr\"");
            }
            case "promo" -> assertThat(m.text()).contains("You used a promo code. Enter it again at checkout");
            default -> throw new IllegalArgumentException(variant);
        }
        if (!variant.equals("promo")) assertThat(m.text()).doesNotContain("promo code");

        CheckoutReminder after = rows.load(id);
        assertThat(after.getStatus()).isEqualTo(CheckoutReminderStatus.SENT);
        assertThat(after.getSentAt()).isEqualTo(NOW);
        assertThat(after.getProviderMessageId()).startsWith("msg_");

        tick();
        assertThat(sentTo(email)).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"staleClaim", "connectionRefused", "unknownHost", "rateLimited",
            "lastFailureThatNeverReachedResend", "readTimeout", "serverError", "unrecognisedFailure",
            "providerRejects"})
    void aCrashAfterSendingNeverSendsTwice(String variant) {
        UUID id = rows.waiting(org, night, tier, email, 2, "en", false,
                NOW.minus(Duration.ofMinutes(75)), NOW.minus(Duration.ofMinutes(15)));
        switch (variant) {
            case "staleClaim" -> jdbc.update("UPDATE checkout_reminders SET status = 'SENDING', claimed_at = ? WHERE id = ?",
                    Timestamp.from(NOW.minus(Duration.ofMinutes(11))), id);
            case "connectionRefused" -> failSend(io(new ConnectException("Failed to connect to api.resend.com/443")));
            case "unknownHost" -> failSend(io(new UnknownHostException("api.resend.com")));
            case "rateLimited" -> failSend(status(429));
            case "lastFailureThatNeverReachedResend" -> {
                jdbc.update("UPDATE checkout_reminders SET attempts = 2 WHERE id = ?", id);
                failSend(io(new ConnectException("Connection refused")));
            }
            // Resend may have accepted the batch before the answer was lost.
            case "readTimeout" -> failSend(io(new SocketTimeoutException("timeout")));
            case "serverError" -> failSend(status(500));
            case "unrecognisedFailure" -> when(provider.sendBatch(any(), any())).thenThrow(
                    new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE, "down"));
            case "providerRejects" -> when(provider.sendBatch(any(), any())).thenThrow(
                    new CampaignEmailProvider.CampaignRejected("rejected", 422));
            default -> throw new IllegalArgumentException(variant);
        }

        tick();

        CheckoutReminder after = rows.load(id);
        switch (variant) {
            case "staleClaim" -> {
                assertThat(after.getStatus()).isEqualTo(CheckoutReminderStatus.NOT_SENT);
                assertThat(after.getReason().name()).isEqualTo("SEND_UNKNOWN");
                assertThat(attemptsTo(email)).isZero();
            }
            case "connectionRefused", "unknownHost", "rateLimited" -> {
                assertThat(after.getStatus()).isEqualTo(CheckoutReminderStatus.PENDING);
                assertThat(after.getAttempts()).isEqualTo(1);
                assertThat(after.getClaimedAt()).isNull();
                assertThat(attemptsTo(email)).isEqualTo(1);
            }
            case "readTimeout", "serverError", "unrecognisedFailure" -> {
                assertThat(after.getStatus()).isEqualTo(CheckoutReminderStatus.NOT_SENT);
                assertThat(after.getReason().name()).isEqualTo("SEND_UNKNOWN");
                assertThat(attemptsTo(email)).isEqualTo(1);
                // Never claimed again, so never sent a second time.
                tick();
                assertThat(attemptsTo(email)).isEqualTo(1);
            }
            case "lastFailureThatNeverReachedResend", "providerRejects" -> {
                assertThat(after.getStatus()).isEqualTo(CheckoutReminderStatus.NOT_SENT);
                assertThat(after.getReason().name()).isEqualTo("SEND_FAILED");
                assertThat(attemptsTo(email)).isEqualTo(1);
            }
            default -> throw new IllegalArgumentException(variant);
        }
    }

    @Test
    void aTickWhileAnotherReplicaHoldsTheLockSendsNothing() {
        UUID id = rows.waiting(org, night, tier, email, 2, "en", false,
                NOW.minus(Duration.ofMinutes(61)), NOW.minus(Duration.ofMinutes(1)));
        jdbc.update("""
                INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
                VALUES ('CheckoutReminderSender.tick', now() + interval '5 minutes', now(), 'another-replica')
                ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_at = EXCLUDED.locked_at,
                                                 locked_by = EXCLUDED.locked_by
                """);

        sender.tick();

        assertThat(rows.load(id).getStatus()).isEqualTo(CheckoutReminderStatus.PENDING);
        assertThat(attemptsTo(email)).isZero();

        // Once the other replica's lock lapses, the next tick sends it.
        tick();
        assertThat(sentTo(email)).hasSize(1);
        assertThat(rows.load(id).getStatus()).isEqualTo(CheckoutReminderStatus.SENT);
    }

    @Test
    void aClaimSkipsARowAnotherReplicaIsHolding() throws Exception {
        // The held row is due first, so a claim that waited for it or took it would show.
        UUID held = rows.waiting(org, night, tier, email, 2, "en", false,
                NOW.minus(Duration.ofMinutes(90)), NOW.minus(Duration.ofMinutes(30)));
        UUID free = rows.waiting(org, night, tier, rows.email("sender-free"), 1, "en", false,
                NOW.minus(Duration.ofMinutes(61)), NOW.minus(Duration.ofMinutes(1)));

        try (Connection replica = dataSource.getConnection()) {
            replica.setAutoCommit(false);
            try (PreparedStatement lock = replica.prepareStatement(
                    "SELECT id FROM checkout_reminders WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, held);
                lock.executeQuery().close();
            }
            try {
                List<UUID> claimed = CompletableFuture.supplyAsync(() -> reminders.claimDue(NOW, 50))
                        .get(10, TimeUnit.SECONDS);
                assertThat(claimed).contains(free).doesNotContain(held);
            } finally {
                replica.rollback();
            }
        }

        assertThat(rows.load(held).getStatus()).isEqualTo(CheckoutReminderStatus.PENDING);
        assertThat(rows.load(free).getStatus()).isEqualTo(CheckoutReminderStatus.SENDING);
    }

    @Test
    void aClaimInsideQuietHoursWaitsForTheMorning() {
        // 22:30 in Paris; the row was due before 22:00 but is only claimed now.
        Instant late = Instant.parse("2026-11-05T21:30:00Z");
        clock.setInstant(late);
        UUID id = rows.waiting(org, night, tier, email, 2, "en", false,
                late.minus(Duration.ofMinutes(95)), late.minus(Duration.ofMinutes(35)));

        tick();

        CheckoutReminder after = rows.load(id);
        assertThat(after.getStatus()).isEqualTo(CheckoutReminderStatus.PENDING);
        assertThat(after.getDueAt()).isEqualTo(Instant.parse("2026-11-06T08:00:00Z"));
        assertThat(attemptsTo(email)).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The provider call fails the way CampaignEmailProvider reports it. */
    private void failSend(RuntimeException providerFailure) {
        when(provider.sendBatch(any(), any())).thenThrow(providerFailure);
    }

    /** A transport failure: RestClient wraps the IOException, the provider wraps that as retryable. */
    private static RuntimeException io(java.io.IOException e) {
        return new CampaignEmailProvider.RetryableBatchFailure("Email service unavailable", 0, true, null,
                new org.springframework.web.client.ResourceAccessException("I/O error", e));
    }

    /** Resend answered with this status and asked to come back. */
    private static RuntimeException status(int status) {
        return new CampaignEmailProvider.RetryableBatchFailure("Email service unavailable", status, false, null, null);
    }

    private void tick() {
        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = 'CheckoutReminderSender.tick'");
        sender.tick();
    }

    /** Emails the provider was asked to deliver to {@code to}, failed calls included. */
    private long attemptsTo(String to) {
        return mockingDetails(provider).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendBatch"))
                .flatMap(i -> ((List<?>) i.getArgument(1)).stream())
                .map(o -> (CampaignEmailProvider.OutgoingEmail) o)
                .filter(o -> to.equals(o.to()))
                .count();
    }

    @SuppressWarnings("unchecked")
    private List<CampaignEmailProvider.OutgoingEmail> sentTo(String to) {
        if (attemptsTo(to) == 0) return List.of();
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(1)).sendBatch(any(), captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).filter(o -> to.equals(o.to())).toList();
    }

    private void saveTier(java.util.function.Consumer<TicketTier> change) {
        TicketTier t = tiers.findById(tier.getId()).orElseThrow();
        change.accept(t);
        tier = tiers.save(t);
    }

    private void saveNight(java.util.function.Consumer<Event> change) {
        Event e = events.findById(night.getId()).orElseThrow();
        change.accept(e);
        night = events.save(e);
    }

    private void saveOrg(java.util.function.Consumer<Organization> change) {
        Organization o = orgs.findById(org.id()).orElseThrow();
        change.accept(o);
        orgs.save(o);
    }

    private void erase(UUID orgId) {
        ErasedAddress e = new ErasedAddress();
        e.setOrgId(orgId);
        e.setEmailNormalized(email);
        erased.save(e);
    }
}
