package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.SubjectVariantsLlm;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.SubjectVariantsService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The sell-through fact the subject-line model is grounded on, through the real service. */
@IminIntegrationTest
class SubjectVariantsUrgencyTest {

    @Autowired SubjectVariantsService service;
    @Autowired ChatClient chatClient;
    @Autowired CampaignRepository campaigns;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    /**
     * tier.sold 60 of 100, 55 of them held by a test-mode order: the line states seats only (40 left,
     * test tickets still hold theirs) and no sold share, which would have to pick one of two bases.
     */
    @Test
    void urgency_states_seats_left_only() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        Event event = fx.event(org, owner, EventStatus.LIVE, Instant.now().plusSeconds(10 * 86_400));
        TicketTier tier = fx.tier(event, 1500, 100);
        tier.setSold(60);
        tiers.save(tier);
        Order live = fx.order(event, fx.email("live"));
        for (int i = 0; i < 5; i++) fx.ticket(live, Ticket.STATE_ISSUED);
        Order test = fx.order(event, fx.email("test"));
        test.setTestMode(true);
        orders.save(test);
        for (int i = 0; i < 55; i++) fx.ticket(test, Ticket.STATE_ISSUED);
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org.getId());
        c.setChannel("email");
        c.setName("Push");
        c.setStatus("draft");
        c.setEventId(event.getId());
        c.setSubject("Original");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);

        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(spec.user(prompt.capture())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.entity(SubjectVariantsLlm.class)).thenReturn(new SubjectVariantsLlm(List.of("a", "b", "c")));

        service.generate(fx.principal(owner), c.getId(), null);

        assertThat(prompt.getValue()).contains("Ticket sales: 40 of 100 tickets left\n");
    }
}
