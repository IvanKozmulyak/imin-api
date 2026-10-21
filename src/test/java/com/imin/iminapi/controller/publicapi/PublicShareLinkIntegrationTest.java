package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.marketing.kit.ShareLink;
import com.imin.iminapi.marketing.kit.ShareLinkService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A short link opens only an event the public site would show. */
@IminIntegrationTest
class PublicShareLinkIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired EventRepository events;
    @Autowired ShareLinkService links;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private Organization org;
    private User owner;

    @BeforeEach
    void setUp() {
        org = fx.org();
        orgIds.add(org.getId());
        owner = fx.owner(org);
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"live", "draft", "private", "deleted", "unknown"})
    void resolvesOnlyAPublicEvent(String kind) throws Exception {
        Event e = fx.event(org, owner, kind.equals("draft") ? EventStatus.DRAFT : EventStatus.LIVE,
                Instant.now().plusSeconds(86_400));
        e.setPublishedAt(Instant.now());
        if (kind.equals("private")) e.setVisibility(EventVisibility.PRIVATE);
        events.save(e);
        ShareLink link = links.getOrCreateForStoryKit(org.getId(), e.getId(), owner.getId());
        if (kind.equals("deleted")) {
            jdbc.update("update events set deleted_at = now() where id = ?", e.getId());
        }
        String code = kind.equals("unknown") ? "Zz9Zz9Z" : link.getCode();

        if (kind.equals("live")) {
            mvc.perform(get("/api/v1/public/links/" + code))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "public, s-maxage=300"))
                    .andExpect(jsonPath("$.eventId").value(e.getId().toString()))
                    .andExpect(jsonPath("$.utmSource").value("instagram"))
                    .andExpect(jsonPath("$.utmMedium").value("story"))
                    .andExpect(jsonPath("$.utmCampaign").value("kit_" + code));
        } else {
            mvc.perform(get("/api/v1/public/links/" + code)).andExpect(status().isNotFound());
        }
    }
}
