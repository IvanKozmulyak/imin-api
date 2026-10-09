package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SuppressionService;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.webhook.ResendWebhookProperties;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PgLocks;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resend delivers at least once and in no order: a late event never walks back a terminal status, and concurrent
 * events for one message neither deadlock nor fail. Single events and replays belong to the projector/controller tests.
 */
@IminIntegrationTest
class ResendWebhookOutOfOrderTest {

    private static final String SECRET_B64 = "c3VwZXJzZWNyZXRrZXkw"; // base64("supersecretkey0")

    private static final Instant SENT = Instant.parse("2026-07-11T10:00:00Z");
    private static final Instant DELIVERED = Instant.parse("2026-07-11T10:00:05Z");
    private static final Instant BOUNCED = Instant.parse("2026-07-11T10:00:30Z");
    private static final Instant OPENED = Instant.parse("2026-07-11T10:05:00Z");
    private static final Instant COMPLAINED = Instant.parse("2026-07-11T10:10:00Z");
    private static final Instant UNSUBSCRIBED = Instant.parse("2026-07-11T10:20:00Z");
    /** Not a Resend event: the owned one-click opt-out, written through the repository the endpoint uses. */
    private static final String UNSUBSCRIBE = "unsubscribe";

    @Autowired MockMvc mvc;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired CampaignRepository campaignRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ResendWebhookProperties webhookProps;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AuditRows auditRows;
    @Autowired SuppressionService suppressionService;

    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void signingSecret() {
        flips.set(webhookProps, "secret", "whsec_" + SECRET_B64);
    }

    /** The campaigns are left `sending`, which the dispatcher reclaims for every org once stale. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /** One webhook as Resend sends it; {@code bounceType} is non-null only for email.bounced. */
    record Event(String type, Instant createdAt, String bounceType) {
        static Event of(String type, Instant at) { return new Event(type, at, null); }
        static Event permanentBounce() { return new Event("email.bounced", BOUNCED, "Permanent"); }
        static Event transientBounce() { return new Event("email.bounced", BOUNCED, "Transient"); }
        @Override public String toString() {
            String name = type.startsWith("email.") ? type.substring("email.".length()) : type;
            return bounceType == null ? name : name + "(" + bounceType + ")";
        }
    }

    record Expected(String status, String errorCode, Instant deliveredAt, Instant openedAt) {}

    private record Seeded(UUID orgId, UUID membershipId, CampaignRecipient recipient, String email) {}

    static Stream<Arguments> eventPairsInBothOrders() {
        Event sent = Event.of("email.sent", SENT);
        Event delivered = Event.of("email.delivered", DELIVERED);
        Event opened = Event.of("email.opened", OPENED);
        Event complained = Event.of("email.complained", COMPLAINED);
        Event bounced = Event.permanentBounce();
        Event softBounced = Event.transientBounce();
        Event unsubscribed = Event.of(UNSUBSCRIBE, UNSUBSCRIBED);

        Expected deliveredAndOpened = new Expected("delivered", null, DELIVERED, OPENED);
        Expected deliveredOnly = new Expected("delivered", null, DELIVERED, null);
        Expected hardBounced = new Expected("bounced", "hard_bounce", DELIVERED, null);
        Expected complaint = new Expected("complained", null, DELIVERED, null);
        Expected openedComplaint = new Expected("complained", null, null, OPENED);
        Expected optedOut = new Expected("unsubscribed", null, DELIVERED, null);
        // A transient bounce is not terminal: a later delivery wins, a later bounce wins too.
        Expected softThenDelivered = new Expected("delivered", "soft_bounce", DELIVERED, null);
        Expected deliveredThenSoft = new Expected("bounced", "soft_bounce", DELIVERED, null);
        // A complaint outranks a bounce; a transient bounce never downgrades a permanent one.
        Expected complaintOverBounce = new Expected("complained", "hard_bounce", null, null);
        Expected hardOverSoft = new Expected("bounced", "hard_bounce", null, null);

        // The third argument is last_event_at: the last arrival's instant, even when its CASE keeps the status.
        return Stream.of(
                arguments(List.of(delivered, opened), deliveredAndOpened, OPENED),
                arguments(List.of(opened, delivered), deliveredAndOpened, DELIVERED),
                arguments(List.of(sent, delivered), deliveredOnly, DELIVERED),
                arguments(List.of(delivered, sent), deliveredOnly, DELIVERED),
                arguments(List.of(delivered, bounced), hardBounced, BOUNCED),
                arguments(List.of(bounced, delivered), hardBounced, DELIVERED),
                arguments(List.of(delivered, complained), complaint, COMPLAINED),
                arguments(List.of(complained, delivered), complaint, DELIVERED),
                arguments(List.of(opened, complained), openedComplaint, COMPLAINED),
                arguments(List.of(complained, opened), openedComplaint, OPENED),
                arguments(List.of(delivered, unsubscribed), optedOut, UNSUBSCRIBED),
                arguments(List.of(unsubscribed, delivered), optedOut, DELIVERED),
                arguments(List.of(softBounced, delivered), softThenDelivered, DELIVERED),
                arguments(List.of(delivered, softBounced), deliveredThenSoft, BOUNCED),
                arguments(List.of(complained, bounced), complaintOverBounce, BOUNCED),
                arguments(List.of(bounced, complained), complaintOverBounce, COMPLAINED),
                arguments(List.of(bounced, softBounced), hardOverSoft, BOUNCED),
                arguments(List.of(softBounced, bounced), hardOverSoft, BOUNCED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventPairsInBothOrders")
    void finalRecipientStateAfterTwoEventsInEitherOrder(List<Event> arrivals, Expected expected, Instant lastEventAt)
            throws Exception {
        Seeded s = seed(fx.email("ooo"));
        for (Event e : arrivals) deliver(s, e);

        CampaignRecipient after = recipientRepo.findById(s.recipient().getId()).orElseThrow();
        assertThat(after.getStatus()).as("status").isEqualTo(expected.status());
        assertThat(after.getErrorCode()).as("error_code").isEqualTo(expected.errorCode());
        assertThat(after.getDeliveredAt()).as("delivered_at").isEqualTo(expected.deliveredAt());
        assertThat(after.getOpenedAt()).as("opened_at").isEqualTo(expected.openedAt());
        assertThat(after.getLastEventAt()).as("last_event_at").isEqualTo(lastEventAt);
    }

    private static final Instant OPENED_AGAIN = Instant.parse("2026-07-11T10:15:00Z");

    static Stream<Arguments> repeatedEngagementInBothOrders() {
        // opened_at / clicked_at keep the earliest instant; last_event_at is the last arrival's instant.
        return Stream.of(
                arguments("email.opened", List.of(OPENED, OPENED_AGAIN), OPENED, OPENED_AGAIN),
                arguments("email.opened", List.of(OPENED_AGAIN, OPENED), OPENED, OPENED),
                arguments("email.clicked", List.of(OPENED, OPENED_AGAIN), OPENED, OPENED_AGAIN),
                arguments("email.clicked", List.of(OPENED_AGAIN, OPENED), OPENED, OPENED));
    }

    @ParameterizedTest(name = "{0} arriving {1}")
    @MethodSource("repeatedEngagementInBothOrders")
    void aRepeatedOpenOrClickKeepsTheFirstInstant(String type, List<Instant> arrivals, Instant first, Instant lastEventAt)
            throws Exception {
        Seeded s = seed(fx.email("ooo-repeat"));
        for (Instant at : arrivals) deliver(s, Event.of(type, at));

        CampaignRecipient after = recipientRepo.findById(s.recipient().getId()).orElseThrow();
        Instant stamped = type.equals("email.opened") ? after.getOpenedAt() : after.getClickedAt();
        Instant other = type.equals("email.opened") ? after.getClickedAt() : after.getOpenedAt();
        assertThat(stamped).as(type + " stamp").isEqualTo(first);
        assertThat(other).as("the other engagement stamp").isNull();
        assertThat(after.getLastEventAt()).as("last_event_at").isEqualTo(lastEventAt);
    }

    static Stream<Arguments> eventsCommittedWhileAComplaintIsInFlight() {
        return Stream.of(
                arguments(Event.of("email.delivered", DELIVERED), new Expected("complained", null, DELIVERED, null)),
                arguments(Event.of("email.opened", OPENED), new Expected("complained", null, null, OPENED)));
    }

    /**
     * The complaint reads the row, then is held on its suppression INSERT while another event for the
     * same message commits; its own recipient write must not put the stale columns back.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("eventsCommittedWhileAComplaintIsInFlight")
    void anEventCommittedDuringAComplaintSurvivesIt(Event concurrent, Expected expected) throws Exception {
        Seeded s = seed(fx.email("ooo-race"));
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "suppression_entries", "membership_id",
                s.membershipId())) {
            CompletableFuture<Void> complaint = CompletableFuture.runAsync(() -> {
                try {
                    deliver(s, Event.of("email.complained", COMPLAINED));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            CompletableFuture<Void> other = null;
            try {
                pause.awaitBlocked(Duration.ofSeconds(10));
                other = CompletableFuture.runAsync(() -> {
                    try {
                        deliver(s, concurrent);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                try {
                    other.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException waitingOnTheComplaintsRowLock) {
                    // Also a valid order: it then applies after the complaint commits.
                }
            } finally {
                pause.release();
                complaint.get(10, TimeUnit.SECONDS);
                if (other != null) other.get(10, TimeUnit.SECONDS);
            }
        }

        CampaignRecipient after = recipientRepo.findById(s.recipient().getId()).orElseThrow();
        assertThat(after.getStatus()).as("status").isEqualTo(expected.status());
        assertThat(after.getErrorCode()).as("error_code").isEqualTo(expected.errorCode());
        assertThat(after.getDeliveredAt()).as("delivered_at").isEqualTo(expected.deliveredAt());
        assertThat(after.getOpenedAt()).as("opened_at").isEqualTo(expected.openedAt());
        // The held complaint writes last, so its own instant is the one left on the row.
        assertThat(after.getLastEventAt()).as("last_event_at").isEqualTo(COMPLAINED);
    }

    /**
     * A complaint and a third transient bounce for one message take the suppression slot before the recipient row,
     * so the bounce queues behind the complaint instead of deadlocking, and finds the row written rather than failing.
     */
    @Test
    void aComplaintAndAThirdSoftBounceQueueInsteadOfDeadlocking() throws Exception {
        Seeded s = seed(fx.email("lock-order"));
        priorSoftBounce(s);
        priorSoftBounce(s);
        Webhook complaint = webhook(s, Event.of("email.complained", COMPLAINED));
        Webhook bounce = webhook(s, Event.transientBounce());

        MvcResult complained;
        MvcResult bounced;
        // The complaint's SUPPRESSION_ADDED audit write runs after its suppression INSERT, in its own transaction.
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "audit_logs", "target_id", s.membershipId())) {
            CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> send(complaint));
            CompletableFuture<MvcResult> second = null;
            try {
                pause.awaitBlocked(Duration.ofSeconds(10));
                second = CompletableFuture.supplyAsync(() -> send(bounce));
                PgLocks.awaitLockWait(jdbc, "insert into suppression_entries",
                        "the bounce waits on the complaint's suppression row");
            } finally {
                pause.release();
                complained = first.get(30, TimeUnit.SECONDS);
                bounced = second == null ? null : second.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(sqlStates(complained)).as("complaint").doesNotContain("40P01");
        assertThat(sqlStates(bounced)).as("bounce").doesNotContain("40P01");
        assertThat(complained.getResponse().getStatus()).as("complaint").isEqualTo(200);
        assertThat(bounced.getResponse().getStatus()).as("bounce, first delivery " + sqlStates(bounced)).isEqualTo(200);

        CampaignRecipient after = recipientRepo.findById(s.recipient().getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("complained");
        assertThat(after.getErrorCode()).isEqualTo("soft_bounce");
        assertThat(jdbc.queryForList("select reason from suppression_entries where scope = 'marketing'"
                + " and org_id = ? and membership_id = ?", String.class, s.orgId(), s.membershipId()))
                .containsExactly("spam");
        auditRows.assertRecorded(s.orgId(), AuditActions.SUPPRESSION_ADDED, "membership", s.membershipId());
        assertThat(membershipRepo.findByIdAndOrgId(s.membershipId(), s.orgId()).orElseThrow().isObjectedProfiling()).isTrue();
    }

    /**
     * The third soft bounce commits its suppression while a complaint waits on the same slot: the buyer's own
     * spam report is the reason organizers see, and only the bounce's insert is audited.
     */
    @Test
    void aComplaintQueuedBehindASoftBounceSuppressionRecordsSpam() throws Exception {
        Seeded s = seed(fx.email("reason-race"));
        priorSoftBounce(s);
        priorSoftBounce(s);
        Webhook bounce = webhook(s, Event.transientBounce());
        Webhook complaint = webhook(s, Event.of("email.complained", COMPLAINED));

        MvcResult bounced;
        MvcResult complained;
        // The bounce's SUPPRESSION_ADDED audit write runs after its suppression INSERT, in its own transaction.
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "audit_logs", "target_id", s.membershipId())) {
            CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> send(bounce));
            CompletableFuture<MvcResult> second = null;
            try {
                pause.awaitBlocked(Duration.ofSeconds(10));
                second = CompletableFuture.supplyAsync(() -> send(complaint));
                PgLocks.awaitLockWait(jdbc, "insert into suppression_entries",
                        "the complaint waits on the bounce's suppression row");
            } finally {
                pause.release();
                bounced = first.get(30, TimeUnit.SECONDS);
                complained = second == null ? null : second.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(bounced.getResponse().getStatus()).as("bounce " + sqlStates(bounced)).isEqualTo(200);
        assertThat(complained.getResponse().getStatus()).as("complaint " + sqlStates(complained)).isEqualTo(200);
        assertThat(marketingReasons(s)).containsExactly("spam");
        assertThat(auditRows.assertRecorded(s.orgId(), AuditActions.SUPPRESSION_ADDED, "membership", s.membershipId())
                .getSummary()).contains("reason=soft-bounce");
        assertThat(membershipRepo.findByIdAndOrgId(s.membershipId(), s.orgId()).orElseThrow().isObjectedProfiling()).isTrue();
    }

    static Stream<Arguments> aSystemWriterMeetsAStoredReason() {
        return Stream.of(
                arguments("spam", Event.transientBounce(), "spam"),
                arguments("soft-bounce", Event.of("email.complained", COMPLAINED), "spam"),
                arguments("unsubscribe", Event.transientBounce(), "unsubscribe"),
                arguments("unsubscribe", Event.of("email.complained", COMPLAINED), "unsubscribe"),
                arguments("manual", Event.transientBounce(), "manual"),
                arguments("manual", Event.of("email.complained", COMPLAINED), "manual"));
    }

    /** Spam replaces only a system soft-bounce; a person's or organizer's reason is never rewritten. */
    @ParameterizedTest(name = "{0} then {1} -> {2}")
    @MethodSource("aSystemWriterMeetsAStoredReason")
    void theStoredSuppressionReasonAfterASecondWriter(String stored, Event event, String expected) throws Exception {
        Seeded s = seed(fx.email("reason-rank"));
        priorSoftBounce(s);
        priorSoftBounce(s);
        suppressionService.addMarketing(s.orgId(), s.membershipId(), stored,
                new AuthPrincipal(null, s.orgId(), UserRole.MEMBER, null));

        deliver(s, event);

        assertThat(marketingReasons(s)).containsExactly(expected);
        auditRows.assertRecorded(s.orgId(), AuditActions.SUPPRESSION_ADDED, "membership", s.membershipId());
    }

    private List<String> marketingReasons(Seeded s) {
        return jdbc.queryForList("select reason from suppression_entries where scope = 'marketing'"
                + " and org_id = ? and membership_id = ?", String.class, s.orgId(), s.membershipId());
    }

    /** A soft-bounced send of an earlier campaign: one strike against the membership. */
    private void priorSoftBounce(Seeded s) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(s.orgId());
        c.setChannel("email");
        c.setName("webhook-earlier");
        c.setStatus("sending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(s.membershipId());
        r.setEmail(s.email());
        r.setStatus("bounced");
        r.setErrorCode("soft_bounce");
        r.setProviderMessageId("msg_" + UUID.randomUUID());
        recipientRepo.save(r);
    }

    /** One signed webhook as Resend would send it. */
    private record Webhook(String svixId, String body) {}

    private Webhook webhook(Seeded s, Event e) {
        String bounce = e.bounceType() == null ? ""
                : ",\"bounce\":{\"type\":\"" + e.bounceType() + "\",\"subType\":\"General\"}";
        String body = "{\"type\":\"" + e.type() + "\",\"created_at\":\"" + e.createdAt() + "\","
                + "\"data\":{\"email_id\":\"" + s.recipient().getProviderMessageId() + "\","
                + "\"to\":[\"" + s.email() + "\"]" + bounce + "}}";
        return new Webhook("svix_" + UUID.randomUUID(), body);
    }

    private MvcResult send(Webhook w) {
        try {
            String ts = String.valueOf(System.currentTimeMillis() / 1000L);
            return mvc.perform(post("/api/v1/public/webhooks/resend")
                            .header("svix-id", w.svixId()).header("svix-timestamp", ts)
                            .header("svix-signature", sign(w.svixId(), ts, w.body()))
                            .contentType("application/json").content(w.body()))
                    .andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every SQLState in the cause chain of the exception the request ended with. */
    private static List<String> sqlStates(MvcResult result) {
        List<String> states = new ArrayList<>();
        for (Throwable t = result.getResolvedException(); t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) states.add(sql.getSQLState());
            if (t.getCause() == t) break;
        }
        return states;
    }

    /** The complaint branch needs a real membership; the campaign carries the org the projector derives. */
    private Seeded seed(String email) {
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);

        Consumer consumer = new Consumer();
        consumer.setNormalizedEmail(email.toLowerCase(Locale.ROOT));
        consumer = consumerRepo.save(consumer);

        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        m = membershipRepo.save(m);

        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("webhook-out-of-order");
        c.setStatus("sending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(email);
        r.setStatus("sent");
        r.setProviderMessageId("msg_" + UUID.randomUUID());
        return new Seeded(orgId, m.getMembershipId(), recipientRepo.save(r), email);
    }

    /** Each delivery is a distinct Svix message, so the dedup claim never swallows it. */
    private void deliver(Seeded s, Event e) throws Exception {
        if (e.type().equals(UNSUBSCRIBE)) {
            recipientRepo.markUnsubscribed(s.recipient().getCampaignId(), s.membershipId(), e.createdAt());
            return;
        }
        String bounce = e.bounceType() == null ? ""
                : ",\"bounce\":{\"type\":\"" + e.bounceType() + "\",\"subType\":\"General\"}";
        String body = "{\"type\":\"" + e.type() + "\",\"created_at\":\"" + e.createdAt() + "\","
                + "\"data\":{\"email_id\":\"" + s.recipient().getProviderMessageId() + "\","
                + "\"to\":[\"" + s.email() + "\"]" + bounce + "}}";
        String svixId = "svix_" + UUID.randomUUID();
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        mvc.perform(post("/api/v1/public/webhooks/resend")
                        .header("svix-id", svixId).header("svix-timestamp", ts)
                        .header("svix-signature", sign(svixId, ts, body))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    private static String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET_B64), "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(
                mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
}
