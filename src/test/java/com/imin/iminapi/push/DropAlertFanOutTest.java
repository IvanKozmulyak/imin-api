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
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.event.NotifyReleaseSender;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.util.Times;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Push rides the drop-alert release alongside the email, and never instead of
 * it. The failing case this exists to catch: a buyer with a device gets the push
 * but silently loses the email, or gets it twice.
 *
 * <p><b>The sender is constructed by hand, not autowired.</b> The autowired
 * bean's {@code sweep()} is proxied by {@code @SchedulerLock(lockAtLeastFor =
 * "PT10S")}, so the second sweep inside ten seconds — in this method or in the
 * next test — is silently skipped, and {@code verify(push).send(...)} then
 * fails with zero interactions.
 *
 * <p>The sweep reads every org's pending rows, so {@code @Transactional} rolls back whatever it marks,
 * and push and mail assertions name this test's own token and addresses.
 */
@IminIntegrationTest
@Transactional
class DropAlertFanOutTest {

    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired IminFixtures fx;
    @Autowired MutableClock clock;
    @Autowired RecordingEmailService mail;
    @Autowired ExpoPushSender push;
    @Autowired SuppressionRepository suppressions;
    @Autowired EmailTemplateRenderer renderer;
    @Autowired EmailProperties emailProps;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository buyerEmails;
    @Autowired BuyerPushDeviceRepository pushDevices;
    @Autowired BuyerNotificationPreferenceRepository pushPrefs;

    PushProperties pushProps;
    NotifyReleaseSender sender;

    Organization org;
    User owner;
    Instant now;
    String token;

    @org.springframework.beans.factory.annotation.Autowired
    com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService unsubscribeTokens;

    @BeforeEach
    void setUp() {
        now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        clock.setInstant(now);
        token = "ExponentPushToken[" + UUID.randomUUID() + "]";
        pushProps = new PushProperties();
        pushProps.setEnabled(true);
        sender = new NotifyReleaseSender(subscriptions, events, tiers, suppressions,
                mail, renderer, emailProps, clock,
                pushProps, push, pushDevices, buyerEmails, pushPrefs, unsubscribeTokens);

        org = fx.org();
        owner = fx.owner(org);
    }

    // ── The whole point: push rides along, email is untouched ──────────────

    @Test
    void anAccountHolderWithADeviceGetsBothAPushAndTheEmail() {
        when(push.send(anyList())).thenReturn(new ExpoPushSender.Result(1, Set.of()));

        UUID account = buyerAccount();
        String address = verifiedAddress(account);
        device(account, token);
        Event event = releasableEventWatchedBy(address);

        sender.sweep();

        List<PushMessage> sent = pushedTo(token);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).to()).isEqualTo(token);
        assertThat(sent.get(0).channelId()).isEqualTo(PushMessage.CHANNEL_DROP_ALERTS);
        assertThat(sent.get(0).data()).containsEntry("eventId", event.getId().toString());
        assertThat(sent.get(0).data()).containsEntry("type", "drop-alert");

        // The email is the promise; push must not have replaced it.
        assertThat(mailsTo(address)).isEqualTo(1);
        assertThat(notifiedAtOf(address)).isEqualTo(now);
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
        device(account, token);
        releasableEventWatchedBy(address);

        sender.sweep();

        // Sent despite the push blowing up …
        assertThat(mailsTo(address)).isEqualTo(1);
        // … and marked, so the next tick does not send it a second time.
        assertThat(notifiedAtOf(address)).isEqualTo(now);

        sender.sweep();
        assertThat(mailsTo(address)).isEqualTo(1);
    }

    // ── Who is reachable ───────────────────────────────────────────────────

    @Test
    void aGuestWatcherWithNoAccountGetsTheEmail() {
        String guest = "guest-" + UUID.randomUUID() + "@example.test";
        releasableEventWatchedBy(guest);

        sender.sweep();

        assertThat(mailsTo(guest)).isEqualTo(1);
    }

    /** An unverified claim on an address is not an account — anybody can make one. */
    @Test
    void anUnverifiedAddressIsNotAnAccountAndGetsNoPush() {
        UUID account = buyerAccount();
        String raw = "unverified-" + UUID.randomUUID() + "@example.test";
        buyerEmails.save(BuyerAccountEmail.of(account, raw, BuyerAccountEmail.ADDED_VIA_MANUAL));
        device(account, token);
        releasableEventWatchedBy(raw);

        sender.sweep();

        assertThat(mailsTo(raw)).isEqualTo(1);
        assertThat(pushedTo(token)).isEmpty();
    }

    @Test
    void aBuyerWhoTurnedDropAlertPushesOffGetsOnlyTheEmail() {
        UUID account = buyerAccount();
        String address = verifiedAddress(account);
        device(account, token);
        BuyerNotificationPreference pref = new BuyerNotificationPreference(account);
        pref.setPushDropAlerts(false);
        pushPrefs.save(pref);
        releasableEventWatchedBy(address);

        sender.sweep();

        assertThat(mailsTo(address)).isEqualTo(1);
        assertThat(pushedTo(token)).isEmpty();
    }

    // ── Registry hygiene ───────────────────────────────────────────────────

    @Test
    void aDeadTokenIsRevokedSoItIsNeverSentToAgain() {
        when(push.send(anyList())).thenReturn(new ExpoPushSender.Result(0, Set.of(token)));

        UUID account = buyerAccount();
        device(account, token);
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
        // The mock is shared across tests; drop what earlier ones recorded.
        clearInvocations(push);

        UUID account = buyerAccount();
        String address = verifiedAddress(account);
        device(account, token);
        releasableEventWatchedBy(address);

        sender.sweep();

        verifyNoInteractions(push);
        assertThat(mailsTo(address)).isEqualTo(1);
        assertThat(notifiedAtOf(address)).isEqualTo(now);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Messages for this test's token across every send; the sweep also pushes other tests' events. */
    @SuppressWarnings("unchecked")
    private List<PushMessage> pushedTo(String to) {
        return mockingDetails(push).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .flatMap(i -> ((List<PushMessage>) i.getArgument(0)).stream())
                .filter(m -> m.to().equals(to))
                .toList();
    }

    private long mailsTo(String address) {
        return mail.sent().stream().filter(m -> m.to().equals(address)).count();
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
        e.setPublishedAt(now.minusSeconds(3600));
        e.setStartsAt(now.plusSeconds(86400));
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
