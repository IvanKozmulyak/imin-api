package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureTarget;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The nightly recompute's queries and a full pass on Postgres. The database is shared,
 * so every assertion is on this test's own orgs and memberships, or a delta.
 */
@IminIntegrationTest
class FanFeatureRecomputeQueriesTest {

    @Autowired FanFeatureRepository features;
    @Autowired FanFeatureProjector projector;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired ConsentService consentService;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired AudiencePlanProperties planProps;
    @Autowired PropertyFlips flips;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor liveExecutor;

    FanFeatureFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new FanFeatureFixtures(orgs, users, events, orders, tickets, consumers, memberships);
    }

    /** Built by hand so the pass is not skipped while the context's startup recompute holds the lock. */
    @SuppressWarnings("unchecked")
    private FanFeatureRecomputeJob unlockedJob() {
        return new FanFeatureRecomputeJob(features, projector, clock, mock(ObjectProvider.class));
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void targets_keysetPagesCoverEveryLiveMembershipOnce() {
        FanFeatureFixtures.Org a = fx.org("UTC");
        FanFeatureFixtures.Org b = fx.org("UTC");
        List<Membership> made = new ArrayList<>();
        for (int i = 0; i < 3; i++) made.add(fx.membership(a.id(), FanFeatureFixtures.email("a" + i)));
        for (int i = 0; i < 2; i++) made.add(fx.membership(b.id(), FanFeatureFixtures.email("b" + i)));
        Membership erasing = fx.membership(a.id(), FanFeatureFixtures.email("erasing"));
        erasing.setStatus("erase_pending");
        memberships.save(erasing);
        made.add(erasing);
        Membership objector = fx.membership(b.id(), FanFeatureFixtures.email("objector"));
        objector.setObjectedProfiling(true);
        memberships.save(objector);
        made.add(objector);
        // The database is shared: pin this test's ids into one narrow key range and page through only that range.
        String prefix = UUID.randomUUID().toString().substring(0, 24);
        UUID start = UUID.fromString(prefix + "000000000000");
        UUID end = UUID.fromString(prefix + "0000000000ff");
        Set<UUID> live = new HashSet<>();
        UUID objectorId = null;
        for (int i = 0; i < made.size(); i++) {
            UUID pinned = UUID.fromString(prefix + String.format("%012x", i + 1));
            jdbc.update("update memberships set membership_id = ? where membership_id = ?", pinned,
                    made.get(i).getMembershipId());
            if (made.get(i) != erasing) live.add(pinned);
            if (made.get(i) == objector) objectorId = pinned;
        }

        List<FanFeatureTarget> seen = new ArrayList<>();
        List<FanFeatureTarget> page = features.findTargetsAfter(start, PageRequest.of(0, 2));
        int pages = 0;
        // Hex strings sort like Postgres uuids (unsigned bytes); UUID.compareTo is signed.
        while (!page.isEmpty() && page.get(0).membershipId().toString().compareTo(end.toString()) <= 0) {
            assertThat(page).hasSizeLessThanOrEqualTo(2);
            seen.addAll(page);
            pages++;
            page = features.findTargetsAfter(page.get(page.size() - 1).membershipId(), PageRequest.of(0, 2));
        }
        List<FanFeatureTarget> mine = seen.stream()
                .filter(t -> t.membershipId().toString().startsWith(prefix)).toList();

        assertThat(pages).isGreaterThan(1);
        assertThat(mine).extracting(FanFeatureTarget::membershipId).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(live);
        UUID objectorPinned = objectorId;
        assertThat(mine).filteredOn(t -> t.membershipId().equals(objectorPinned))
                .singleElement().satisfies(t -> {
                    assertThat(t.orgId()).isEqualTo(b.id());
                    assertThat(t.objectedProfiling()).isTrue();
                    assertThat(t.normalizedEmail()).startsWith("objector-");
                });
    }

    @Test
    void findTarget_scopedToOrgAndLiveStatus() {
        FanFeatureFixtures.Org a = fx.org("UTC");
        Membership m = fx.membership(a.id(), FanFeatureFixtures.email("one"));
        assertThat(features.findTarget(a.id(), m.getMembershipId())).isPresent();
        assertThat(features.findTarget(UUID.randomUUID(), m.getMembershipId())).isEmpty();
        m.setStatus("erase_pending");
        memberships.save(m);
        assertThat(features.findTarget(a.id(), m.getMembershipId())).isEmpty();
    }

    @Test
    void ordersByEmailBatch_matchesCaseInsensitivelyWithinTheOrg() {
        FanFeatureFixtures.Org a = fx.org("UTC");
        FanFeatureFixtures.Org b = fx.org("UTC");
        String email = FanFeatureFixtures.email("batch");
        Order mine = fx.paidOrder(fx.event(a, "Pop", daysAgo(1)), email.toUpperCase(), daysAgo(1), Ticket.STATE_ISSUED);
        fx.paidOrder(fx.event(b, "Pop", daysAgo(1)), email, daysAgo(1), Ticket.STATE_ISSUED);

        assertThat(orders.findByOrgIdAndNormalizedEmailIn(a.id(), List.of(email, "other@example.com")))
                .extracting(Order::getId).containsExactly(mine.getId());
    }

    @Test
    void recomputeAll_writesEveryLiveMembershipAcrossOrgs() {
        FanFeatureFixtures.Org paris = fx.org("Europe/Paris");
        FanFeatureFixtures.Org other = fx.org("UTC");
        String loyal = FanFeatureFixtures.email("loyal");
        for (int d : new int[]{60, 30, 5}) {
            Event e = fx.event(paris, "House & Techno", daysAgo(d));
            fx.paidOrder(e, loyal, daysAgo(d), Ticket.STATE_REDEEMED);
        }
        Membership loyalM = fx.membership(paris.id(), loyal);
        consentService.capture(paris.id(), loyalM.getMembershipId(), "explicit", "checkout", "Ticked the box",
                "email", null, null, ConsentOrigin.DATA_SUBJECT, null);
        Membership prospect = fx.membership(other.id(), FanFeatureFixtures.email("prospect"));
        Membership erasing = fx.membership(other.id(), FanFeatureFixtures.email("erasing"));
        erasing.setStatus("erase_pending");
        memberships.save(erasing);
        flips.set(planProps, "betaOrgIds", Set.of(paris.id(), other.id()));

        unlockedJob().recomputeAll();

        FanFeature l = features.findById(loyalM.getMembershipId()).orElseThrow();
        assertThat(l.getOrgId()).isEqualTo(paris.id());
        assertThat(l.getFanClass()).isEqualTo("loyal");
        assertThat(l.getPaidOrders()).isEqualTo(3);
        assertThat(l.getTaste()).isEqualTo("{\"house & techno\":1.0}");
        assertThat(l.getLastContactFromPersonAt()).isAfter(daysAgo(1));
        FanFeature p = features.findById(prospect.getMembershipId()).orElseThrow();
        assertThat(p.getOrgId()).isEqualTo(other.id());
        assertThat(p.getFanClass()).isEqualTo("none");
        assertThat(p.getTaste()).isEqualTo("{}");
        assertThat(features.findById(erasing.getMembershipId())).isEmpty();
    }

    @Test
    void countStale_countsMissingAndOldRows_notErasePending() {
        FanFeatureFixtures.Org a = fx.org("UTC");
        // Only this org is recomputed and other classes' live writes are drained: the count moves by own rows.
        flips.set(planProps, "betaOrgIds", Set.of(a.id()));
        AsyncDrain.drain(liveExecutor);
        Instant cutoff = Instant.now().minus(FanFeatureRecomputeJob.FRESHNESS);
        long base = features.countStale(cutoff);
        Membership fresh = fx.membership(a.id(), FanFeatureFixtures.email("fresh"));
        Membership old = fx.membership(a.id(), FanFeatureFixtures.email("old"));
        Membership erasing = fx.membership(a.id(), FanFeatureFixtures.email("erasing"));
        erasing.setStatus("erase_pending");
        memberships.save(erasing);
        assertThat(features.countStale(cutoff)).isEqualTo(base + 2);

        unlockedJob().recomputeAll();
        AsyncDrain.drain(liveExecutor);
        long c = features.countStale(cutoff);
        assertThat(c).isEqualTo(base);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fan_features WHERE membership_id IN (?, ?) "
                + "AND updated_at >= ?", Integer.class, fresh.getMembershipId(), old.getMembershipId(),
                Timestamp.from(cutoff))).isEqualTo(2);

        jdbc.update("UPDATE fan_features SET updated_at = ? WHERE membership_id = ?",
                Timestamp.from(daysAgo(2)), old.getMembershipId());
        assertThat(features.countStale(cutoff)).isEqualTo(c + 1);
        assertThat(features.findById(fresh.getMembershipId())).isPresent();
    }

    @Test
    void concurrentFirstWrite_secondWriterWaitsAndUpdates_oneRow() throws Exception {
        FanFeatureFixtures.Org a = fx.org("UTC");
        String email = FanFeatureFixtures.email("race");
        fx.paidOrder(fx.event(a, "Pop", daysAgo(2)), email, daysAgo(2), Ticket.STATE_ISSUED);
        Membership m = fx.membership(a.id(), email);
        FanFeatureTarget target = features.findTarget(a.id(), m.getMembershipId()).orElseThrow();
        CountDownLatch locked = new CountDownLatch(1);

        // The other writer holds the membership lock and inserts the first row while this one starts.
        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> new TransactionTemplate(txManager)
                .executeWithoutResult(tx -> {
                    memberships.lockByIdAndOrgId(m.getMembershipId(), a.id()).orElseThrow();
                    jdbc.update("""
                            INSERT INTO fan_features (membership_id, org_id, paid_orders, class, taste, cities,
                                                      formats, no_show_n, sends_30d, logic_version, updated_at)
                            VALUES (?, ?, 0, 'none', '{}', '[]', '[]', 0, 7, 1, ?)
                            """, m.getMembershipId(), a.id(), Timestamp.from(Instant.now()));
                    locked.countDown();
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        int written = projector.recomputeBatch(List.of(target));
        other.get(10, TimeUnit.SECONDS);

        assertThat(written).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fan_features WHERE membership_id = ?",
                Integer.class, m.getMembershipId())).isEqualTo(1);
        FanFeature f = features.findById(m.getMembershipId()).orElseThrow();
        assertThat(f.getPaidOrders()).isEqualTo(1);
        assertThat(f.getSends30d()).isEqualTo(7);
    }
}
