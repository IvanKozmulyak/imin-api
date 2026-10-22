package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * A batch whose outcome is unknown is replayed with the key it was stamped with, so Resend answers with the first
 * result instead of mailing again. Each call is recorded only when it carries this test's addresses.
 */
@IminIntegrationTest
class CampaignSendIdempotencyTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired CampaignService campaignService;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired javax.sql.DataSource dataSource;

    private final List<UUID> orgIds = new ArrayList<>();

    private record Call(String key, List<String> to) {}

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /** An org at local noon, so quiet hours never hold the dispatcher. */
    private Organization awakeOrg() {
        Organization o = fx.org();
        o.setTimezone(ZoneOffset.ofHours(12 - Instant.now().atZone(ZoneOffset.UTC).getHour()).getId());
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private Campaign campaign(String status, int pending, String tag) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(awakeOrg().getId());
        c.setChannel("email");
        c.setName("Idempotent blast");
        c.setStatus(status);
        // Sorts ahead of other classes' campaigns in the global claim.
        c.setScheduledAt(Instant.now().minus(3650, ChronoUnit.DAYS));
        c.setSubject("Subject");
        c.setBodyMd("Body");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        for (int i = 0; i < pending; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setEmail(tag + "-" + i + "-" + fx.email("fan"));
            r.setStatus("pending");
            recipients.save(r);
        }
        return c;
    }

    private static List<String> to(List<CampaignEmailProvider.OutgoingEmail> batch) {
        return batch.stream().map(CampaignEmailProvider.OutgoingEmail::to).toList();
    }

    private static CampaignEmailProvider.BatchResult idsFor(List<CampaignEmailProvider.OutgoingEmail> batch) {
        return CampaignEmailProvider.BatchResult.accepted(batch.stream().map(e -> msgId(e.to())).toList());
    }

    private static boolean ours(List<CampaignEmailProvider.OutgoingEmail> batch, String tag) {
        return batch.stream().anyMatch(e -> e.to().startsWith(tag + "-"));
    }

    @Test
    void aBatchWhoseCommitIsLostIsReplayedWithTheSameKey() {
        String tag = "lost-" + UUID.randomUUID();
        Campaign c = campaign("scheduled", 3, tag);
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        List<List<String>> bodies = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<PgFaults.Fault> fault = new AtomicReference<>();
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            if (!ours(batch, tag)) return idsFor(batch);
            calls.add(new Call(inv.getArgument(0), to(batch)));
            bodies.add(batch.stream().map(e -> e.from() + "|" + e.subject() + "|" + e.html() + "|" + e.text() + "|"
                    + e.unsubscribeUrl() + "|" + e.replyTo()).toList());
            // Resend accepted the batch; the write recording it is lost, as when the pod restarts before commit.
            if (calls.size() == 1) fault.set(PgFaults.failWrites(jdbc, "campaign_recipients", "campaign_id", c.getId()));
            return idsFor(batch);
        });

        try {
            dispatcher.runOnce();
        } finally {
            if (fault.get() != null) fault.get().close();
        }
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "pending"))
                .hasSize(3).allSatisfy(r -> assertThat(r.getSendKey()).isEqualTo(calls.get(0).key()));

        // The drive that threw failed the campaign; the organizer's Retry puts it back in the next pass.
        campaignService.retry(fx.principal(fx.owner(orgs.findById(c.getOrgId()).orElseThrow())), c.getId());
        dispatcher.runOnce();

        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).key()).isEqualTo(calls.get(0).key()).startsWith("campaign:" + c.getId() + ":");
        assertThat(calls.get(1).to()).containsExactlyElementsOf(calls.get(0).to());
        // Byte-identical, or Resend would answer the replay 409 instead of returning the first result.
        assertThat(bodies.get(1)).containsExactlyElementsOf(bodies.get(0));
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "sent")).hasSize(3).allSatisfy(r -> {
            assertThat(r.getAttemptCount()).isEqualTo((short) 1);
            assertThat(r.getProviderMessageId()).isEqualTo(msgId(r.getEmail()));
            assertThat(r.getSendKey()).isNull();
        });
    }

    @Test
    void aTimeoutIsReplayedInlineWithTheSameKey() {
        String tag = "timeout-" + UUID.randomUUID();
        Campaign c = campaign("sending", 2, tag);
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            if (!ours(batch, tag)) return idsFor(batch);
            calls.add(new Call(inv.getArgument(0), to(batch)));
            if (calls.size() == 1) {
                throw new CampaignEmailProvider.RetryableBatchFailure("Email service unavailable", 0, true, null,
                        new java.net.SocketTimeoutException("Read timed out"));
            }
            return idsFor(batch);
        });

        sender.sendNextBatch(c);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).key()).isNotNull().isEqualTo(calls.get(0).key());
        assertThat(calls.get(1).to()).containsExactlyElementsOf(calls.get(0).to());
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "sent")).hasSize(2).allSatisfy(r -> {
            assertThat(r.getProviderMessageId()).isEqualTo(msgId(r.getEmail()));
            assertThat(r.getAttemptCount()).isEqualTo((short) 1);
            assertThat(r.getSendKey()).isNull();
        });
        assertThat(sender.sendNextBatch(c)).isFalse();
        assertThat(calls).hasSize(2);
    }

    @Test
    void aReplayRejectedAsADifferentPayloadIsNeverSentAgain() {
        String tag = "mismatch-" + UUID.randomUUID();
        Campaign c = campaign("scheduled", 2, tag);
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            if (!ours(batch, tag)) return idsFor(batch);
            calls.add(new Call(inv.getArgument(0), to(batch)));
            if (calls.size() == 1) {
                throw new CampaignEmailProvider.RetryableBatchFailure("Email service unavailable", 500, false, null, null);
            }
            // The template changed while the batch backed off; Resend saw this key with the old body.
            throw new CampaignEmailProvider.ReplayMismatch();
        });

        dispatcher.runOnce();
        assertThat(campaigns.findStatusById(c.getId())).contains("sending");
        // The backoff elapses and the heartbeat goes stale, so the next pass resumes the campaign.
        jdbc.update("UPDATE campaign_recipients SET next_attempt_at = NULL WHERE campaign_id = ?", c.getId());
        jdbc.update("UPDATE campaigns SET updated_at = now() - interval '10 minutes' WHERE id = ?", c.getId());
        dispatcher.runOnce();

        assertThat(calls).hasSize(2);
        assertThat(calls.stream().map(Call::key).collect(Collectors.toSet())).hasSize(1);
        // Which rows the first request mailed is unknown: none counts as sent, none is mailed under a new key.
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "sent")).isEmpty();
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "skipped")).hasSize(2).allSatisfy(r -> {
            assertThat(r.getSkipReason()).isEqualTo("delivery_unconfirmed");
            assertThat(r.getSentAt()).isNull();
            assertThat(r.getSendKey()).isNull();
        });
        assertThat(campaigns.findStatusById(c.getId())).contains("sent");
        assertThat(Set.copyOf(calls.get(1).to())).isEqualTo(Set.copyOf(calls.get(0).to()));
    }

    /** A provider id derived from the address (the column holds 64 characters), so a shifted id shows. */
    private static String msgId(String to) {
        return "msg-" + UUID.nameUUIDFromBytes(to.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Puts every pending row of the campaign under one key, stamped {@code age} ago, as a batch whose outcome is unknown. */
    private String keyPending(Campaign c, java.time.Duration age, int attempts) {
        String key = "campaign:" + c.getId() + ":" + UUID.randomUUID();
        jdbc.update("UPDATE campaign_recipients SET send_key = ?, send_key_at = ?, attempt_count = ?"
                + " WHERE campaign_id = ? AND status = 'pending'",
                key, java.sql.Timestamp.from(Instant.now().minus(age)), attempts, c.getId());
        return key;
    }

    private CampaignRecipient row(Campaign c, UUID membershipId, String email) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(membershipId);
        r.setEmail(email);
        r.setStatus("pending");
        return recipients.save(r);
    }

    private UUID member(UUID orgId, String email) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(email);
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
    }

    @Test
    void aKeyPastTheReplayWindowIsNeverSentAgain() {
        String tag = "expired-" + UUID.randomUUID();
        Campaign c = campaign("sending", 2, tag);
        String oldKey = keyPending(c, java.time.Duration.ofHours(24), 1);
        String fresh = tag + "-fresh-" + fx.email("fan");
        row(c, null, fresh);
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            if (ours(batch, tag)) calls.add(new Call(inv.getArgument(0), to(batch)));
            return idsFor(batch);
        });

        sender.sendNextBatch(c);

        // Resend replays a key for 24 h only: a replay now would be a fresh send, so those rows are never sent.
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "skipped")).hasSize(2).allSatisfy(r -> {
            assertThat(r.getSkipReason()).isEqualTo("delivery_unconfirmed");
            assertThat(r.getSendKey()).isNull();
            assertThat(r.getSentAt()).isNull();
        });
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.to()).containsExactly(fresh);
            assertThat(call.key()).isNotEqualTo(oldKey);
        });
    }

    /** A replayed batch drops rows the gate no longer passes from the request and records them once the outcome is known. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"oneUnsubscribed", "everyoneUnsubscribed", "addressErased"})
    void aReplayedBatchReRunsTheGateInMemory(String scenario) {
        String tag = "rg-" + UUID.randomUUID();
        Campaign c = campaign("sending", 0, tag);
        // The local part stays under 64 characters, or the gate would rightly refuse it as invalid_address.
        String leaving = tag + "-a@" + com.imin.iminapi.support.IminFixtures.MAIL_DOMAIN;
        String staying = tag + "-b@" + com.imin.iminapi.support.IminFixtures.MAIL_DOMAIN;
        UUID a = member(c.getOrgId(), leaving);
        UUID b = member(c.getOrgId(), staying);
        CampaignRecipient rowA = "addressErased".equals(scenario) ? row(c, null, null) : row(c, a, leaving);
        CampaignRecipient rowB = row(c, b, staying);
        String key = keyPending(c, java.time.Duration.ofMinutes(5), 1);
        if (!"addressErased".equals(scenario)) {
            jdbc.update("UPDATE memberships SET consent_status = 'unsubscribed' WHERE membership_id = ?", a);
        }
        if ("everyoneUnsubscribed".equals(scenario)) {
            jdbc.update("UPDATE memberships SET consent_status = 'unsubscribed' WHERE membership_id = ?", b);
        }
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            if (batch.stream().anyMatch(e -> e.to() == null || e.to().startsWith(tag + "-"))) {
                calls.add(new Call(inv.getArgument(0), to(batch)));
            }
            return idsFor(batch);
        });

        sender.sendNextBatch(c);

        CampaignRecipient afterA = recipients.findById(rowA.getId()).orElseThrow();
        CampaignRecipient afterB = recipients.findById(rowB.getId()).orElseThrow();
        assertThat(afterA.getStatus()).isEqualTo("skipped");
        assertThat(afterA.getSkipReason()).isEqualTo("addressErased".equals(scenario) ? "no_email" : "marketing_unsubscribed");
        assertThat(afterA.getSendKey()).isNull();
        if ("everyoneUnsubscribed".equals(scenario)) {
            assertThat(calls).isEmpty();
            assertThat(afterB.getStatus()).isEqualTo("skipped");
            assertThat(afterB.getSkipReason()).isEqualTo("marketing_unsubscribed");
        } else {
            assertThat(calls).singleElement().satisfies(call -> {
                assertThat(call.key()).isEqualTo(key);
                assertThat(call.to()).containsExactly(staying);
            });
            assertThat(afterB.getStatus()).isEqualTo("sent");
        }
        assertThat(afterB.getSendKey()).isNull();
    }

    /** A batch another drive holds is left to it: no provider call and no attempt bump from this drive. */
    @Test
    void aBatchAnotherDriveHoldsIsLeftAlone() throws Exception {
        String tag = "held-" + UUID.randomUUID();
        Campaign c = campaign("sending", 2, tag);
        keyPending(c, java.time.Duration.ofMinutes(5), 1);
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            if (ours(batch, tag)) calls.add(new Call(inv.getArgument(0), to(batch)));
            return idsFor(batch);
        });
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (java.sql.PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM campaign_recipients WHERE campaign_id = ? FOR UPDATE")) {
                lock.setObject(1, c.getId());
                lock.executeQuery().close();
            }
            try {
                java.util.concurrent.Future<Boolean> drive = worker.submit(() -> sender.sendNextBatch(c));
                // A drive that went for the held rows would block on them; either way it must not have called Resend.
                try {
                    drive.get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException blocked) {
                    // asserted below
                }
                assertThat(calls).isEmpty();
                assertThat(jdbc.queryForList("SELECT attempt_count FROM campaign_recipients WHERE campaign_id = ?",
                        Integer.class, c.getId())).containsOnly(1);
            } finally {
                holder.rollback();
            }
        } finally {
            worker.shutdown();
            assertThat(worker.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }
}
