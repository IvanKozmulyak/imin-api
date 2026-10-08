package com.imin.iminapi.marketing;

import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.service.event.PublicEventService;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** metaPixelId surfaces on the public event payload when the org has a connection, and is null otherwise. */
@IminIntegrationTest
class PublicEventMetaPixelTest {

    @Autowired PublicEventService publicEventService;
    @Autowired MetaPixelConnectionRepository connRepo;
    @Autowired EventRepository events;
    @Autowired IminFixtures fx;

    @ParameterizedTest(name = "connected={0}")
    @CsvSource({"true, PIX-PUBLIC-1", "false, "}) // an empty column is null
    void metaPixelIdFollowsTheOrgConnection(boolean connected, String expectedPixel) {
        Organization org = fx.org();
        Event e = fx.event(org, fx.owner(org), EventStatus.LIVE, null);
        // findPublic needs a published event.
        e.setPublishedAt(Instant.now().minusSeconds(3600));
        events.save(e);
        if (connected) {
            MetaPixelConnection c = new MetaPixelConnection();
            c.setId(UUID.randomUUID());
            c.setOrgId(org.getId());
            c.setEventId(null); // org-wide default
            c.setPixelId("PIX-PUBLIC-1");
            c.setCapiAccessTokenEnc("enc");
            connRepo.save(c);
        }

        PublicEventResponse resp = publicEventService.get(e.getId(), true);

        assertThat(resp.metaPixelId()).isEqualTo(expectedPixel);
    }
}
