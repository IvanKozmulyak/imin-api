package com.imin.iminapi.predictor;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.predictor.dto.ReforecastResult;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.ReforecastTrigger;
import com.imin.iminapi.predictor.service.PredictionLedgerService;
import com.imin.iminapi.predictor.service.ReforecastJob;
import com.imin.iminapi.predictor.service.ReforecastService;
import com.imin.iminapi.predictor.service.ReforecastTriggerService;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PredictorRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The live re-forecast reads live sold: recompute, the daily job's "with sales" gate, the milestone trigger. */
@IminIntegrationTest
class ReforecastLiveOnlyTest {

    @Autowired ReforecastService reforecast;
    @Autowired ReforecastJob job;
    @Autowired ReforecastTriggerService triggers;
    @Autowired PredictionLedgerService ledger;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private User owner;

    @BeforeEach
    void setUp() {
        org = fx.org();
        owner = fx.owner(org);
    }

    @AfterEach
    void tearDown() {
        PredictorRows.delete(jdbc, List.of(org.getId()));
    }

    /** A live, on-sale event 20 days out in a city of its own, so no pacing curve matches. */
    private Event event() {
        Event e = fx.event(org, owner, EventStatus.LIVE, Instant.now().plusSeconds(20L * 86_400));
        e.setVenueCity("Paris" + DateCheckControllerTest.letters());
        e.setVenueCountry("FR");
        return events.save(e);
    }

    private TicketTier tier(Event e, int price, int quantity, int sold) {
        TicketTier t = fx.tier(e, price, quantity);
        t.setSold(sold);
        return tiers.save(t);
    }

    /** An order holding {@code n} issued tickets on {@code tier}. */
    private Order order(Event e, TicketTier tier, int n, boolean testMode) {
        Order o = fx.order(e, fx.email(testMode ? "test" : "live"));
        o.setTestMode(testMode);
        orders.save(o);
        for (int i = 0; i < n; i++) {
            Ticket t = fx.ticket(o, Ticket.STATE_ISSUED);
            t.setTierId(tier.getId());
            t.setTierName(tier.getName());
            t.setPriceMinor(tier.getPriceMinor());
            tickets.save(t);
        }
        return o;
    }

    private long reforecastRows(Event e) {
        return jdbc.queryForObject("select count(*) from prediction_ledger where event_id = ? and surface = ?",
                Long.class, e.getId(), PredictionSurface.REFORECAST.name());
    }

    /**
     * Live: 2 tickets at €10.00. Test: 10 at €30.00. The interim range 40–60 is priced at the live average
     * €10.00, so 40000–60000; counting the test tickets would price it at 32000/12 and stamp soldNow 12.
     */
    @Test
    void recompute_sizes_sold_and_revenue_from_live_tickets() {
        Event e = event();
        TicketTier cheap = tier(e, 1000, 100, 2);
        TicketTier dear = tier(e, 3000, 100, 10);
        order(e, cheap, 2, false);
        order(e, dear, 10, true);
        ledger.record(new PredictionLedgerService.RecordCommand(e.getId(), org.getId(),
                PredictionSurface.PRE_PUBLISH, 0, "test/model", "v1", "h", null,
                "{\"attendanceRange\":{\"low\":40,\"high\":60}}"));

        ReforecastResult r = reforecast.recompute(e.getId(), ReforecastTrigger.EDIT);

        assertThat(r.revenueRangeMinor()).isEqualTo(new ReforecastResult.RevenueRange(40_000L, 60_000L));
        assertThat(reforecast.lastForecastSold(e.getId())).isEqualTo(2);
    }

    /** The pass over this test's own two events: the live one gets its ledger row, the test-only one none. */
    @Test
    void daily_job_skips_an_event_whose_only_sales_are_test_mode() {
        Event withLiveSale = event();
        order(withLiveSale, tier(withLiveSale, 1000, 100, 3), 3, false);
        Event testOnly = event();
        order(testOnly, tier(testOnly, 1000, 100, 4), 4, true);

        int done = job.recomputeWithLiveSales(List.of(withLiveSale, testOnly));

        assertThat(done).isEqualTo(1);
        assertThat(reforecastRows(withLiveSale)).isEqualTo(1);
        assertThat(reforecastRows(testOnly)).isZero();
    }

    /** From a 0 baseline on capacity 100: 5 live of 60 held crosses nothing; 30 live crosses 25%. */
    @ParameterizedTest
    @CsvSource({"5, 55, 0", "30, 0, 1"})
    void milestone_trigger_reads_live_sold(int live, int test, long expectedRows) {
        Event e = event();
        TicketTier t = tier(e, 1000, 100, live + test);
        Order paid = order(e, t, live, false);
        if (test > 0) order(e, t, test, true);

        ReforecastTriggerService target = AopTestUtils.getUltimateTargetObject(triggers);
        target.onTicketsIssued(new TicketsIssuedEvent(paid.getId()));

        assertThat(reforecastRows(e)).isEqualTo(expectedRows);
    }
}
