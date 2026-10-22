package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.model.RecipientStatuses;
import com.imin.iminapi.marketing.model.StockAlert;
import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertReason;
import com.imin.iminapi.marketing.model.StockAlertSettings;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.StockAlertRepository;
import com.imin.iminapi.marketing.send.CampaignDispatcher;
import com.imin.iminapi.marketing.send.CampaignSendUnit;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The send-time re-check of a stock alert's facts: before the drive and before every batch, through the real
 * dispatcher, send unit and sender, with the provider faked.
 */
@IminIntegrationTest
class StockAlertSendGuardIntegrationTest {

    @Autowired StockAlertTestSupport support;
    @Autowired StockAlertService stockAlerts;
    @Autowired StockAlertProperties props;
    @Autowired StockAlertRepository alerts;
    @Autowired CampaignService campaignService;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignSendUnit sendUnit;
    @Autowired CampaignEmailProvider provider;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private StockAlertTestSupport.Night night;
    private AuthPrincipal owner;
    private TicketTier early;
    private TicketTier regular;

    @BeforeEach
    void seed() {
        flips.set(props, "enabled", true);
        night = support.awakeNight();
        orgIds.add(night.org().getId());
        owner = fx.principal(night.owner());
        // Early bird stops selling in 20 h; Regular costs more and has 15 of 100 left (auto threshold 20).
        early = support.tier(night.event(), "Early bird", 1000, 100, 40,
                Instant.now().plus(20, ChronoUnit.HOURS), 0);
        regular = support.tier(night.event(), "Regular", 2000, 100, 85, null, 1);
        support.settings(night.event(), true, true, StockAlertSettings.MODE_ASK, null);
        AtomicInteger ids = new AtomicInteger();
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> CampaignEmailProvider.BatchResult.accepted(
                IntStream.range(0, ((List<?>) inv.getArgument(1)).size())
                        .mapToObj(i -> "msg-" + ids.incrementAndGet()).toList()));
    }

    @AfterEach
    void deleteOwnRows() {
        CampaignRows.delete(jdbc, orgIds);
        OrgRows.delete(jdbc, orgIds);
    }

    /** A reviewed alert whose draft the owner sent, due now. */
    private Campaign sentAlert(TicketTier t, StockAlertKind kind) {
        StockAlert a = support.readyAlert(night.event(), t, kind, 20);
        CampaignDto draft = stockAlerts.review(owner, night.event().getId(), a.getId());
        campaignService.send(draft.id(), owner, UUID.randomUUID().toString(), null);
        support.makeDue(draft.id());
        return campaigns.findByIdAndOrgId(draft.id(), night.org().getId()).orElseThrow();
    }

    private void breakFact(String reason) {
        switch (reason) {
            case StockAlertReason.SOLD_OUT -> support.setSold(regular, 100);
            case StockAlertReason.TIER_OFF -> jdbc.update("UPDATE ticket_tiers SET enabled = false WHERE id = ?", regular.getId());
            case StockAlertReason.MORE_LEFT -> support.setSold(regular, 50);
            case StockAlertReason.EVENT_NOT_ON_SALE ->
                    jdbc.update("UPDATE events SET visibility = 'PRIVATE' WHERE id = ?", night.event().getId());
            case StockAlertReason.DEADLINE_MOVED -> jdbc.update("UPDATE ticket_tiers SET sale_closes_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().plus(30, ChronoUnit.HOURS)), early.getId());
            case StockAlertReason.NEXT_NOT_HIGHER ->
                    jdbc.update("UPDATE ticket_tiers SET price_minor = 900 WHERE id = ?", regular.getId());
            default -> throw new IllegalArgumentException(reason);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {StockAlertReason.SOLD_OUT, StockAlertReason.TIER_OFF, StockAlertReason.MORE_LEFT,
            StockAlertReason.EVENT_NOT_ON_SALE, StockAlertReason.DEADLINE_MOVED, StockAlertReason.NEXT_NOT_HIGHER})
    void withdrawsWhenFactStopsBeingTrue(String reason) {
        support.mailable(night.org().getId(), 3);
        boolean priceUp = reason.equals(StockAlertReason.DEADLINE_MOVED) || reason.equals(StockAlertReason.NEXT_NOT_HIGHER);
        Campaign c = sentAlert(priceUp ? early : regular, priceUp ? StockAlertKind.PRICE_UP : StockAlertKind.LAST_TICKETS);
        breakFact(reason);

        dispatcher.runOnce();

        Campaign after = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("canceled");
        // Stopped before the drive: nobody was queued, so the campaign page claims no audience for it.
        assertThat(after.getRecipientCount()).isNull();
        assertThat(recipients.countByCampaignId(c.getId())).isZero();
        StockAlert alert = alerts.findByCampaignId(c.getId()).orElseThrow();
        assertThat(alert.getStatus()).isEqualTo(StockAlert.WITHDRAWN);
        assertThat(alert.getWithdrawnReason()).isEqualTo(reason);
        assertThat(support.notifications(night.owner().getId(), StockAlertNotifier.KIND_WITHDRAWN)).isEqualTo(1);
    }

    /** A 40-ticket tier whose alert carries a fixed threshold of 50 (the old, unbounded rule) is never sent full. */
    @ParameterizedTest(name = "{0} of 40 left")
    @ValueSource(ints = {40, 39})
    void fixedThresholdAboveTheTiersRule_isWithdrawnAtSend(int left) {
        support.mailable(night.org().getId(), 3);
        TicketTier small = support.tier(night.event(), "Small room", 3000, 40, 35, null, 2);
        Campaign c = sentAlert(small, StockAlertKind.LAST_TICKETS);
        jdbc.update("UPDATE stock_alerts SET facts_json = replace(facts_json, '\"threshold\":8', '\"threshold\":50') "
                + "WHERE campaign_id = ?", c.getId());
        assertThat(alerts.findByCampaignId(c.getId()).orElseThrow().getFactsJson()).contains("\"threshold\":50");
        support.setSold(small, 40 - left);

        dispatcher.runOnce();

        assertThat(campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow().getStatus()).isEqualTo("canceled");
        assertThat(recipients.countByCampaignId(c.getId())).isZero();
        StockAlert alert = alerts.findByCampaignId(c.getId()).orElseThrow();
        assertThat(alert.getStatus()).isEqualTo(StockAlert.WITHDRAWN);
        assertThat(alert.getWithdrawnReason()).isEqualTo(StockAlertReason.MORE_LEFT);
    }

    @Test
    void stopsBetweenBatches() {
        support.mailable(night.org().getId(), 150);
        Campaign c = sentAlert(regular, StockAlertKind.LAST_TICKETS);
        AtomicInteger calls = new AtomicInteger();
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            // The tier sells out while the first batch is at the provider.
            if (calls.incrementAndGet() == 1) support.setSold(regular, 100);
            return CampaignEmailProvider.BatchResult.accepted(IntStream.range(0, ((List<?>) inv.getArgument(1)).size())
                    .mapToObj(i -> "msg-" + calls.get() + "-" + i).toList());
        });

        dispatcher.runOnce();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(recipients.countByCampaignIdAndStatusIn(c.getId(), RecipientStatuses.LEFT_THE_QUEUE)).isEqualTo(100);
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "skipped").stream()
                .filter(r -> CampaignRecipient.SKIP_FACT_CHANGED.equals(r.getSkipReason())).count()).isEqualTo(50);
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isZero();
        assertThat(campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow().getStatus()).isEqualTo("canceled");
        StockAlert alert = alerts.findByCampaignId(c.getId()).orElseThrow();
        assertThat(alert.getWithdrawnReason()).isEqualTo(StockAlertReason.SOLD_OUT);
    }

    @SuppressWarnings("unchecked")
    @Test
    void factsFrozenOncePerSend() {
        support.mailable(night.org().getId(), 150);
        Campaign c = sentAlert(regular, StockAlertKind.LAST_TICKETS);
        jdbc.update("UPDATE campaigns SET status = 'sending', updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now()), c.getId());
        Campaign sending = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();

        // A run budget that is already spent: one batch, then the drive stops 'sending', as before a reclaim.
        sendUnit.processOne(sending, Instant.now());
        String frozen = alerts.findByCampaignId(c.getId()).orElseThrow().getFactsAtSendJson();
        assertThat(frozen).contains("\"remaining\":15");
        assertThat(recipients.countByCampaignIdAndStatusIn(c.getId(), RecipientStatuses.LEFT_THE_QUEUE)).isEqualTo(100);

        // Three more sold: still last tickets, but the resumed drive must keep quoting what the first batch said.
        support.setSold(regular, 88);
        sendUnit.processOne(campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow());

        assertThat(alerts.findByCampaignId(c.getId()).orElseThrow().getFactsAtSendJson()).isEqualTo(frozen);
        assertThat(recipients.countByCampaignIdAndStatusIn(c.getId(), RecipientStatuses.LEFT_THE_QUEUE)).isEqualTo(150);
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> sent = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeastOnce()).sendBatch(any(), sent.capture());
        List<CampaignEmailProvider.OutgoingEmail> emails = sent.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(emails).hasSize(150);
        assertThat(emails).allSatisfy(m -> {
            assertThat(m.subject()).isEqualTo("Last Regular tickets for " + night.event().getName());
            assertThat(m.text()).contains("15 tickets left").doesNotContain("12 tickets left");
        });
    }
}
