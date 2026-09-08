package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.DsarRecords;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DSAR scope beyond the audience projection.
 *
 * <p>{@code DsarService} used to inject only the membership/consumer/consent
 * graph, so an Art.15 export answered with the projection alone and an Art.17
 * erasure left orders, tickets, /track beacons, Meta CAPI rows and audit actor
 * addresses standing. These cover both directions plus the role guard: an
 * export is a complete dossier on one person and any MEMBER could produce one.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceDsarScopeTest {

    @Autowired DsarService dsarService;
    @Autowired AudienceOrderProjector projector;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;
    @Autowired FunnelEventRepository funnelRepo;
    @Autowired MetaCapiEventRepository metaRepo;
    @Autowired NotifySubscriptionRepository notifyRepo;
    @Autowired AuditLogRepository auditLogRepo;
    @Autowired DataSource dataSource;

    @MockitoBean AuditLogger auditLogger;

    private static final String EMAIL = "subject@dsar.test";

    private UUID orgId;
    private UUID eventId;
    private UUID userId;
    private AuthPrincipal owner;
    private AuthPrincipal member;

    @BeforeEach
    void setUp() {
        wipe();
        Organization o = new Organization();
        o.setName("Scope Org");
        o.setSlug("scope-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("hello@scope.test");
        o.setCountry("DE");
        orgId = orgRepo.save(o).getId();

        User u = new User();
        u.setOrgId(orgId);
        String ue = "owner-" + UUID.randomUUID() + "@scope.test";
        u.setEmail(ue);
        u.setEmailLower(ue);
        u.setRole(UserRole.OWNER);
        userId = userRepo.save(u).getId();

        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Scope Night");
        e.setSlug("scope-night-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        e.setStartsAt(Instant.now().plusSeconds(86_400L));
        e.setCreatedBy(userId);
        e.setCurrency("EUR");
        eventId = eventRepo.save(e).getId();

        owner = new AuthPrincipal(userId, orgId, UserRole.OWNER, UUID.randomUUID());
        member = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.MEMBER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ── (a) Art.15: the export carries the records ───────────────────────────

    @Test
    void export_records_include_orders_tickets_funnel_meta_and_notify() {
        UUID membershipId = seedSubjectWithEverything();

        DsarRecords records = dsarService.exportRecords(orgId, membershipId, owner);

        assertThat(records.orders()).hasSize(1);
        assertThat(records.orders().get(0).email()).isEqualTo(EMAIL);
        assertThat(records.orders().get(0).totalMinor()).isEqualTo(2500L);
        assertThat(records.orders().get(0).eventName()).isEqualTo("Scope Night");

        assertThat(records.tickets()).hasSize(1);
        assertThat(records.tickets().get(0).tierName()).isEqualTo("GA");

        assertThat(records.funnelEvents()).hasSize(2);
        assertThat(records.funnelEvents()).extracting(DsarRecords.FunnelRecord::stage)
                .containsExactlyInAnyOrder(FunnelEvent.STAGE_PAGE_VIEW,
                        FunnelEvent.STAGE_CHECKOUT_START);

        assertThat(records.metaCapiEvents()).hasSize(1);
        assertThat(records.metaCapiEvents().get(0).emailSha256()).isEqualTo("abc123hash");

        assertThat(records.notifySubscriptions()).hasSize(1);
    }

    /** A ticket token is a bearer credential — the export must never print it raw. */
    @Test
    void export_hashes_the_ticket_token() {
        UUID membershipId = seedSubjectWithEverything();

        DsarRecords records = dsarService.exportRecords(orgId, membershipId, owner);

        String hash = records.tickets().get(0).tokenSha256();
        assertThat(hash).hasSize(64).doesNotContain("TKT_SCOPE");
    }

    /** Another org's rows for the same address are that org's data. */
    @Test
    void export_records_are_org_scoped() {
        UUID membershipId = seedSubjectWithEverything();
        Organization other = new Organization();
        other.setName("Other Org");
        other.setSlug("other-" + UUID.randomUUID().toString().substring(0, 8));
        other.setContactEmail("hello@other.test");
        other.setCountry("DE");
        UUID otherOrgId = orgRepo.save(other).getId();
        saveOrder(otherOrgId, eventId, 9999L, "anon-other");

        DsarRecords records = dsarService.exportRecords(orgId, membershipId, owner);

        assertThat(records.orders()).hasSize(1);
        assertThat(records.orders().get(0).totalMinor()).isEqualTo(2500L);
    }

    // ── (b) Art.17: the erasure reaches them ─────────────────────────────────

    @Test
    void erasure_deletes_funnel_rows_and_redacts_meta_and_audit_actor() {
        UUID membershipId = seedSubjectWithEverything();
        auditLogRepo.save(auditRow(EMAIL));
        auditLogRepo.save(auditRow("bystander@scope.test"));

        dsarService.executeErase(orgId, membershipId, owner);

        assertThat(funnelRepo.findAll())
                .as("the subject's beacons go; another session's stay")
                .extracting(FunnelEvent::getAnonId)
                .containsExactly("anon-bystander");

        MetaCapiEvent meta = metaRepo.findAll().get(0);
        assertThat(meta.getEmailSha256()).as("hashed address is still personal data").isNull();
        assertThat(meta.getFbp()).isNull();
        assertThat(meta.getFbc()).isNull();
        assertThat(meta.getOrderId()).as("the send record itself survives").isNotNull();

        assertThat(auditLogRepo.findAll())
                .extracting(AuditLog::getActorEmail)
                .containsExactlyInAnyOrder(null, "bystander@scope.test");
    }

    /** The accounting exemption: the invoice and the ticket stay. */
    @Test
    void erasure_retains_orders_and_tickets() {
        UUID membershipId = seedSubjectWithEverything();

        dsarService.executeErase(orgId, membershipId, owner);

        assertThat(orderRepo.findByOrgIdAndNormalizedEmail(orgId, EMAIL)).hasSize(1);
        assertThat(ticketRepo.findAll()).hasSize(1);
    }

    // ── (c) role guard ───────────────────────────────────────────────────────

    @Test
    void a_member_cannot_export() {
        UUID membershipId = seedSubjectWithEverything();
        assertThatThrownBy(() -> dsarService.export(orgId, membershipId, member))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
    }

    @Test
    void a_member_cannot_request_erasure() {
        UUID membershipId = seedSubjectWithEverything();
        assertThatThrownBy(() -> dsarService.requestErase(orgId, membershipId, member))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
        assertThat(membershipRepo.findByIdAndOrgId(membershipId, orgId).orElseThrow().getStatus())
                .isNotEqualTo("erase_pending");
    }

    /** A gate scanner carries UserRole.MEMBER; it must not be able to run a DSAR. */
    @Test
    void a_gate_device_cannot_export() {
        UUID membershipId = seedSubjectWithEverything();
        AuthPrincipal gate = AuthPrincipal.forGate(orgId, UUID.randomUUID());
        assertThatThrownBy(() -> dsarService.export(orgId, membershipId, gate))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void an_admin_can_export() {
        UUID membershipId = seedSubjectWithEverything();
        AuthPrincipal admin = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.ADMIN, UUID.randomUUID());
        assertThat(dsarService.export(orgId, membershipId, admin).getMembershipId())
                .isEqualTo(membershipId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID seedSubjectWithEverything() {
        com.imin.iminapi.model.Order order = saveOrder(orgId, eventId, 2500L, "anon-subject");

        Ticket t = new Ticket();
        t.setOrderId(order.getId());
        t.setEventId(eventId);
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor(2500);
        t.setState(Ticket.STATE_ISSUED);
        t.setToken("TKT_SCOPE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        ticketRepo.save(t);

        saveFunnel("anon-subject", FunnelEvent.STAGE_PAGE_VIEW);
        saveFunnel("anon-subject", FunnelEvent.STAGE_CHECKOUT_START);
        saveFunnel("anon-bystander", FunnelEvent.STAGE_PAGE_VIEW);

        MetaCapiEvent meta = new MetaCapiEvent();
        meta.setId(UUID.randomUUID());
        meta.setOrgId(orgId);
        meta.setOrderId(order.getId());
        meta.setOrderToken(order.getToken());
        meta.setPixelId("PIXEL");
        meta.setEmailSha256("abc123hash");
        meta.setFbp("fb.1.2.3");
        meta.setFbc("fbclk");
        meta.setValueMinor(2500L);
        meta.setCurrency("EUR");
        meta.setEventTime(Instant.now().getEpochSecond());
        metaRepo.save(meta);

        NotifySubscription sub = new NotifySubscription();
        sub.setEventId(eventId);
        sub.setEmail(EMAIL);
        notifyRepo.save(sub);

        projector.upsertMembership(orgId, EMAIL, EMAIL);
        Consumer c = consumerRepo.findByNormalizedEmail(EMAIL).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        return m.getMembershipId();
    }

    private com.imin.iminapi.model.Order saveOrder(UUID org, UUID event, long totalMinor, String anonId) {
        com.imin.iminapi.model.Order o = new com.imin.iminapi.model.Order();
        o.setEventId(event);
        o.setOrgId(org);
        o.setEmail(EMAIL);
        o.setTotalMinor(totalMinor);
        o.setApplicationFeeMinor(224L);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setAnonId(anonId);
        o.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        return orderRepo.save(o);
    }

    private void saveFunnel(String anonId, String stage) {
        FunnelEvent fe = new FunnelEvent();
        fe.setEventId(eventId);
        fe.setStage(stage);
        fe.setAnonId(anonId);
        funnelRepo.save(fe);
    }

    private AuditLog auditRow(String actorEmail) {
        AuditLog a = new AuditLog();
        a.setOrgId(orgId);
        a.setActorId(UUID.randomUUID());
        a.setActorEmail(actorEmail);
        a.setAction("test.action");
        a.setSummary("seed");
        a.setOccurredAt(Instant.now());
        return a;
    }

    private void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            for (String sql : List.of(
                    "delete from audit_logs", "delete from meta_capi_events",
                    "delete from event_funnel_events", "delete from suppression_entries",
                    "delete from consent_records", "delete from segments",
                    "delete from memberships", "delete from consumers",
                    "delete from erased_addresses", "delete from tickets",
                    "delete from orders", "delete from notify_subscriptions",
                    "delete from events", "delete from users", "delete from organizations")) {
                s.execute(sql);
            }
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}
