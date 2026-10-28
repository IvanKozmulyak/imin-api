package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.config.MarketingChatClient;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A sold-out event gets an advisory that points at the people waiting for tickets, never a "buy tickets"
 * draft; capacity and sold-out read enabled tiers, and seats held by checkouts are named, not sold.
 */
@IminIntegrationTest
class MomentumSoldOutAdvisoryTest {

    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired MomentumEvaluator evaluator;
    @Autowired MomentumService momentum;
    @Autowired MomentumSuggestionRepository suggestions;
    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired NotificationRepository notifications;
    @Autowired @Qualifier(MarketingChatClient.NAME) ChatClient marketingChat;

    Organization org;
    User owner;
    Event event;

    @BeforeEach
    void setUp() {
        org = fx.org();
        owner = fx.owner(org);
        Instant now = Instant.now();
        Event e = fx.event(org, owner, EventStatus.LIVE, now.plus(Duration.ofDays(20)));
        jdbc.update("UPDATE events SET published_at = ?, on_sale_at = ? WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofDays(10))), Timestamp.from(now.minus(Duration.ofDays(10))),
                e.getId());
        event = events.findById(e.getId()).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM momentum_suggestions WHERE org_id = ?", org.getId());
        OrgRows.delete(jdbc, List.of(org.getId()));
    }

    /**
     * Enabled tier 50: 45 sold, 5 held. Disabled tier 50: 10 sold. Capacity 60 (50 enabled + 10 already sold
     * on the disabled tier), nothing left, 5 held, 55 sold. The audience floor is the default and the org has
     * no audience, which would have stopped a campaign suggestion.
     */
    @Test
    void soldOut_recordsAnAdvisoryWithTheWaitingCount_andApproveIsRefused() {
        tier(50, 45, 5, true);
        tier(50, 10, 0, false);
        subscribe(null);
        subscribe(null);
        subscribe(Instant.now().minus(Duration.ofDays(1)));

        evaluate();

        MomentumSuggestion s = suggestions.findByEventIdAndTriggerTypeAndStatus(event.getId(), "sold_out", "suggested")
                .orElseThrow();
        assertThat(s.getDraftPayload()).isEqualTo("{\"action\":\"waitlist\"}");
        assertThat(s.getMetricsSnapshot()).contains("\"capacity\":60,", "\"seatsLeft\":0,", "\"seatsHeld\":5");
        verifyNoInteractions(marketingChat);
        assertThat(notifications.findByUserIdAndReadAtIsNull(owner.getId())).isEmpty();

        MomentumSuggestionDto card = momentum.list(fx.principal(owner), "suggested").stream()
                .filter(d -> d.id().equals(s.getId())).findFirst().orElseThrow();
        assertThat(card.action()).isEqualTo("waitlist");
        assertThat(card.waitlist()).isEqualTo(new MomentumSuggestionDto.WaitlistInfo(2));
        assertThat(card.facts().capacity()).isEqualTo(60);
        assertThat(card.facts().seatsSold()).isEqualTo(55);
        assertThat(card.facts().seatsHeld()).isEqualTo(5);

        assertThatThrownBy(() -> momentum.approve(fx.principal(owner), s.getId()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaigns WHERE org_id = ?", Long.class, org.getId()))
                .isZero();
    }

    /** With every tier switched off nothing is for sale, so the event is not called sold out. */
    @Test
    void noSoldOut_whenNoTierIsEnabled() {
        tier(50, 50, 0, false);

        evaluate();

        assertThat(suggestions.findByEventIdAndTriggerTypeAndStatus(event.getId(), "sold_out", "suggested")).isEmpty();
    }

    private void evaluate() {
        MomentumEvaluator target = AopTestUtils.getUltimateTargetObject(evaluator);
        target.evaluateOne(event, Instant.now());
    }

    private void tier(int quantity, int sold, int reserved, boolean enabled) {
        TicketTier t = fx.tier(event, 2500, quantity);
        t.setSold(sold);
        t.setReserved(reserved);
        t.setEnabled(enabled);
        tiers.save(t);
    }

    private void subscribe(Instant notifiedAt) {
        NotifySubscription s = new NotifySubscription();
        s.setEventId(event.getId());
        s.setEmail(fx.email("fan"));
        s.setNotifiedAt(notifiedAt);
        subscriptions.save(s);
    }
}
