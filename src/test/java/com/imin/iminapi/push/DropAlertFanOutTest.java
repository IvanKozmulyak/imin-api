package com.imin.iminapi.push;

import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerNotificationPreference;
import com.imin.iminapi.buyer.model.BuyerPushDevice;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.event.NotifyReleaseSender;
import com.imin.iminapi.util.Times;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Push rides the drop-alert release alongside the email, and never instead of
 * it. The failing case this exists to catch: a buyer with a device gets the push
 * but silently loses the email, or gets it twice.
 *
 * <p><b>The sender is constructed by hand, not autowired.</b> Two hazards make
 * the autowired bean useless here, and both produce a green-looking test that
 * proves nothing:
 *
 * <ol>
 *   <li>{@code sweep()} is proxied by {@code @SchedulerLock(lockAtLeastFor =
 *       "PT10S")}, so the second sweep inside ten seconds — in this method or in
 *       the next test — is silently skipped, and
 *       {@code verify(push).send(...)} then fails with zero interactions.</li>
 *   <li>{@code @EnableScheduling} is active in tests with no profile guard, so
 *       the background dispatcher can fire the real bean's {@code sweep()} on
 *       these rows and steal the {@code notifiedAt} marks. {@code @Transactional}
 *       keeps the fixtures invisible to it (and out of sibling test classes
 *       sharing the context).</li>
 * </ol>
 *
 * <p>Same construction pattern, and the same reasons, as
 * {@code NotifyReleaseSenderTest}.
 */
@SpringBootTest
@Transactional
@Import(TestRateLimitConfig.class)
class DropAlertFanOutTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TOKEN = "ExponentPushToken[fanout0000000000000000]";

    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired SuppressionRepository suppressions;
    @Autowired EmailTemplateRenderer renderer;
    @Autowired EmailProperties emailProps;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository buyerEmails;
    @Autowired BuyerPushDeviceRepository pushDevices;
    @Autowired BuyerNotificationPreferenceRepository pushPrefs;

    EmailService emailService;
    ExpoPushSender push;
    PushProperties pushProps;
    NotifyReleaseSender sender;

    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        push = mock(ExpoPushSender.class);
        pushProps = new PushProperties();
        pushProps.setEnabled(true);
        sender = new NotifyReleaseSender(subscriptions, events, tiers, suppressions,
                emailService, renderer, emailProps, CLOCK,
                pushProps, push, pushDevices, buyerEmails, pushPrefs);

        org = new Organization();
        org.setName("Fanout Org");
        org.setSlug("fanout-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("fanout-org@example.com");
        org.setCountry("DE");
        org = organizations.save(org);

        owner = new User();
        owner.setEmail("fanout-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
    }

    // ── The whole point: push rides along, email is untouched ──────────────

    @Test
    void anAccountHolderWithADeviceGetsBothAPushAndTheEmail() {
        when(push.send(anyList())).thenReturn(new ExpoPushSender.Result(1, Set.of()));

        UUID account = buyerAccount();
        String address = verifiedAddress(account);
        device(account, TOKEN);
        Event event = releasableEventWatchedBy(address);

        sender.sweep();

        List<PushMessage> sent = capturedPush();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).to()).isEqualTo(TOKEN);
        assertThat(sent.get(0).channelId()).isEqualTo(PushMessage.CHANNEL_DROP_ALERTS);
        assertThat(sent.get(0).data()).containsEntry("eventId", event.getId().toString());
        assertThat(sent.get(0).data()).containsEntry("type", "drop-alert");

        // The email is the promise; push must not have replaced it.
        verify(emailService, times(1)).send(eq(address), anyString(), anyString(), anyString());
        assertThat(notifiedAtOf(address)).isEqualTo(NOW);
    }

    /**
     * THE property this task's design exists to protect. A push transport that
     * blows up must not stop the mail a buyer is owed, and must not cause a
     * second one on the next tick — which is exactly what would happen if the
     * fan-out sat inside the per-subscription try/catch that decides
     * {@code mark(sub)}.
     */
    @Test
    void aPushFailureNeitherSuppressesNorDuplicatesTheEmail() {
        when(push.send(anyList())).thenThrow(new IllegalStateException("expo down"));

        UUID account = buyerAccount();
        String address = verifiedAddress(account);
        device(account, TOKEN);
        releasableEventWatchedBy(address);

        sender.sweep();

        // Sent despite the push blowing up …
        verify(emailService, times(1)).send(eq(address), anyString(), anyString(), anyString());
        // … and marked, so the next tick does not send it a second time.
        assertThat(notifiedAtOf(address)).isEqualTo(NOW);

        sender.sweep();
        verify(emailService, times(1)).send(eq(address), anyString(), anyString(), anyString());
    }

    // ── Who is reachable ───────────────────────────────────────────────────

    @Test
    void aGuestWatcherGetsTheEmailAndNoPushIsAttempted() {
        String guest = "guest-" + UUID.randomUUID() + "@example.test";
        releasableEventWatchedBy(guest);

        sender.sweep();

        verify(emailService, times(1)).send(eq(guest), anyString(), anyString(), anyString());
        verify(push, never()).send(anyList());
    }

    /** An unverified claim on an address is not an account — anybody can make one. */
    @Test
    void anUnverifiedAddressIsNotAnAccountAndGetsNoPush() {
        UUID account = buyerAccount();
        String raw = "unverified-" + UUID.randomUUID() + "@example.test";
        buyerEmails.save(BuyerAccountEmail.of(account, raw, BuyerAccountEmail.ADDED_VIA_MANUAL));
        device(account, TOKEN);
        releasableEventWatchedBy(raw);

        sender.sweep();

        verify(emailService, times(1)).send(eq(raw), anyString(), anyString(), anyString());
        verify(push, never()).send(anyList());
    }

    @Test
    void aBuyerWhoTurnedDropAlertPushesOffGetsOnlyTheEmail() {
        UUID account = buyerAccount();
        String address = verifiedAddress(account);
        device(account, TOKEN);
        BuyerNotificationPreference pref = new BuyerNotificationPreference(account);
        pref.setPushDropAlerts(false);
        pushPrefs.save(pref);
        releasableEventWatchedBy(address);

        sender.sweep();

        verify(emailService, times(1)).send(eq(address), anyString(), anyString(), anyString());
        verify(push, never()).send(anyList());
    }

    // ── Registry hygiene ───────────────────────────────────────────────────

    @Test
    void aDeadTokenIsRevokedSoItIsNeverSentToAgain() {
        when(push.send(anyList())).thenReturn(new ExpoPushSender.Result(0, Set.of(TOKEN)));

        UUID account = buyerAccount();
        device(account, TOKEN);
        releasableEventWatchedBy(verifiedAddress(account));

        sender.sweep();

        assertThat(pushDevices.findLiveTokensForAccounts(List.of(account))).isEmpty();
    }

    // ── The dark switch ────────────────────────────────────────────────────

    /**
     * Disabled must mean the fan-out never starts — not that it runs and the
     * sender declines. A device is registered and eligible here, so the only
     * thing keeping {@code send} at zero interactions is the gate itself.
     */
    @Test
    void pushDisabledMeansNoFanOutAtAllEvenWithARegisteredDevice() {
        pushProps.setEnabled(false);

        UUID account = buyerAccount();
        String address = verifiedAddress(account);
        device(account, TOKEN);
        releasableEventWatchedBy(address);

        sender.sweep();

        verify(push, never()).send(anyList());
        verify(emailService, times(1)).send(eq(address), anyString(), anyString(), anyString());
        assertThat(notifiedAtOf(address)).isEqualTo(NOW);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<PushMessage> capturedPush() {
        ArgumentCaptor<List<PushMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(push).send(captor.capture());
        return captor.getValue();
    }

    private UUID buyerAccount() {
        BuyerAccount a = new BuyerAccount();
        a.setActivatedAt(Times.nowMicros());
        return accounts.save(a).getId();
    }

    private String verifiedAddress(UUID accountId) {
        String raw = "fan-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
        BuyerAccountEmail row = BuyerAccountEmail.of(accountId, raw, BuyerAccountEmail.ADDED_VIA_SIGNUP);
        row.markVerified(Times.nowMicros());
        row.makePrimary();
        buyerEmails.save(row);
        return raw;
    }

    private void device(UUID accountId, String token) {
        BuyerPushDevice d = new BuyerPushDevice();
        d.setBuyerAccountId(accountId);
        d.setExpoToken(token);
        d.setPlatform("ios");
        d.setLocale("en");
        d.setAppVersion("1.0.0");
        pushDevices.save(d);
    }

    /** A published, live event with stock, plus a pending notify-me row for {@code email}. */
    private Event releasableEventWatchedBy(String email) {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Fanout Night");
        e.setSlug("fanout-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setStartsAt(NOW.plusSeconds(86400));
        e.setTimezone("Europe/Berlin");
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        e.setVenueName("Funkhaus");
        e.setVenueCity("Berlin");
        e.setVenueCountry("DE");
        e = events.save(e);

        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("GA");
        t.setPriceMinor(2500);
        t.setQuantity(100);
        t.setSold(0);
        t.setEnabled(true);
        t.setSortOrder(0);
        tiers.save(t);

        NotifySubscription s = new NotifySubscription();
        s.setEventId(e.getId());
        s.setEmail(email);
        subscriptions.save(s);
        return e;
    }

    private Instant notifiedAtOf(String email) {
        return subscriptions.findAll().stream()
                .filter(s -> email.equalsIgnoreCase(s.getEmail()))
                .findFirst()
                .orElseThrow()
                .getNotifiedAt();
    }
}
