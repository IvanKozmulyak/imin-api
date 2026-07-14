package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.service.event.PublicEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * metaPixelId surfaces on the public event payload when the org has a connection,
 * and is null otherwise (spec §5). Uses whatever published-event fixture the
 * existing PublicEventService tests use — adapt the setup helper accordingly.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional // roll back per-test inserts so seeded rows don't leak into the shared H2 DB used by @DataJpaTest slices
class PublicEventMetaPixelTest {

    @Autowired PublicEventService publicEventService;
    @Autowired MetaPixelConnectionRepository connRepo;
    @Autowired com.imin.iminapi.repository.EventRepository events;
    @Autowired com.imin.iminapi.repository.OrganizationRepository organizations;
    @Autowired com.imin.iminapi.repository.UserRepository users;

    // No PublicEventTestFixtures exists in the repo — inline the published-event insert
    // here, copied from the proven PublicEventServiceTest.setUp()+publishedLiveEvent()
    // (imin-api/src/test/java/.../service/event/PublicEventServiceTest.java:59-90). The
    // event MUST satisfy findPublic: visibility=PUBLIC, publishedAt set, status<>DRAFT,
    // deletedAt null. organizations needs NOT NULL name+UNIQUE slug+contact_email+country
    // (V5); events.created_by is a NOT NULL FK to users(id) (V6) so seed a User first.
    private UUID orgId;
    private UUID eventId;

    @org.junit.jupiter.api.BeforeEach
    void seedPublishedEvent() {
        com.imin.iminapi.model.Organization org = new com.imin.iminapi.model.Organization();
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("org@example.com");
        org.setCountry("DE");
        orgId = organizations.save(org).getId();

        com.imin.iminapi.model.User owner = new com.imin.iminapi.model.User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(com.imin.iminapi.model.UserRole.OWNER);
        UUID createdBy = users.save(owner).getId();

        com.imin.iminapi.model.Event e = new com.imin.iminapi.model.Event();
        e.setOrgId(orgId);
        e.setName("Great Event");
        e.setSlug("great-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(com.imin.iminapi.model.EventVisibility.PUBLIC); // findPublic requires PUBLIC
        e.setStatus(com.imin.iminapi.model.EventStatus.LIVE);           // findPublic requires status<>DRAFT
        e.setPublishedAt(java.time.Instant.now().minusSeconds(3600));   // findPublic requires publishedAt not null
        e.setCreatedBy(createdBy);                                       // FK → users(id)
        e.setCurrency("EUR");
        eventId = events.save(e).getId();
    }

    @Test
    void metaPixelIdNullWhenNoConnection() {
        PublicEventResponse resp = publicEventService.get(eventId, true);
        assertThat(resp.metaPixelId()).isNull();
    }

    @Test
    void metaPixelIdPresentWhenOrgConnected() {
        MetaPixelConnection c = new MetaPixelConnection();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setEventId(null); // org-wide default
        c.setPixelId("PIX-PUBLIC-1");
        c.setCapiAccessTokenEnc("enc");
        connRepo.save(c);

        PublicEventResponse resp = publicEventService.get(eventId, true);
        assertThat(resp.metaPixelId()).isEqualTo("PIX-PUBLIC-1");
    }
}
