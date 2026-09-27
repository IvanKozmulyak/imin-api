package com.imin.iminapi.audience.controller;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceImportService;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.CsvContactParser;
import com.imin.iminapi.audience.service.MembershipProjector;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.publicapi.SmsConsentRequest;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audience.SmsConsentService;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.service.event.FreeCheckoutService;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives every consent-writing path and asserts none leaves a soft_opt_in record or basis; a source scan
 * pins that no production class writes one. New consent paths (door QR, survey) add themselves here.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class NeverSoftOptInGuardTest {

    private static final String PROOF = "Email me about this organiser's events. Unsubscribe anytime.";

    @Autowired FreeCheckoutService freeCheckout;
    @Autowired ConsentService consentService;
    @Autowired MembershipProjector membershipProjector;
    @Autowired AudienceImportService importService;
    @Autowired SmsConsentService smsConsentService;
    @Autowired AudienceController audienceController;
    @Autowired Validator validator;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanLogic planLogic;
    @Autowired UserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("taskExecutor") Executor asyncExecutor;
    @MockitoBean AuditLogger auditLogger;

    private UUID orgId;
    private Event event;
    private TicketTier freeTier;
    private AuthPrincipal organizer;
    private AudienceOrderProjector projector;

    @BeforeEach
    void setUp() {
        Organization org = new Organization();
        org.setName("Guard Org");
        org.setSlug("guard-" + UUID.randomUUID().toString().substring(0, 12));
        org.setContactEmail("guard@example.com");
        org.setCountry("FR");
        orgId = orgs.save(org).getId();

        User owner = new User();
        owner.setEmail("guard-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
        organizer = new AuthPrincipal(owner.getId(), orgId, UserRole.OWNER, UUID.randomUUID());

        event = new Event();
        event.setOrgId(orgId);
        event.setName("Guard Night");
        event.setSlug("guard-event-" + UUID.randomUUID().toString().substring(0, 12));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setPublishedAt(Instant.now().minusSeconds(3600));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        freeTier = new TicketTier();
        freeTier.setEventId(event.getId());
        freeTier.setName("Free GA");
        freeTier.setPriceMinor(0);
        freeTier.setQuantity(100);
        freeTier.setReserved(0);
        freeTier.setSold(0);
        freeTier.setEnabled(true);
        freeTier = tiers.save(freeTier);

        // Plain instance so the projection runs synchronously on this thread.
        projector = new AudienceOrderProjector(orders, consumers, memberships, membershipProjector, consentService, events -> { }, orgs, planLogic);
    }

    @AfterEach
    void tearDown() {
        drainAsync();
        List<UUID> mids = jdbc.queryForList("select membership_id from memberships where org_id = ?", UUID.class, orgId);
        List<UUID> cids = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, orgId);
        for (UUID mid : mids) {
            jdbc.update("delete from consent_records where membership_id = ?", mid);
            jdbc.update("delete from import_row_provenance where membership_id = ?", mid);
        }
        jdbc.update("delete from audience_imports where org_id = ?", orgId);
        jdbc.update("delete from suppression_entries where org_id = ?", orgId);
        jdbc.update("delete from memberships where org_id = ?", orgId);
        for (UUID cid : cids) jdbc.update("delete from consumers where consumer_id = ?", cid);
        jdbc.update("delete from tickets where order_id in (select id from orders where org_id = ?)", orgId);
        jdbc.update("delete from orders where org_id = ?", orgId);
        jdbc.update("delete from ticket_tiers where event_id = ?", event.getId());
        jdbc.update("delete from events where org_id = ?", orgId);
        jdbc.update("delete from users where org_id = ?", orgId);
        jdbc.update("delete from organizations where id = ?", orgId);
    }

    @Test
    void paidCheckout_withTickedBox_recordsExplicit() {
        Order order = paidOrder("paid-guard@example.com", true, PROOF);
        projector.onTicketsIssued(new TicketsIssuedEvent(order.getId()));

        assertThat(basesOf("paid-guard@example.com")).containsExactly("explicit");
        assertNoSoftOptInAnywhere();
    }

    /** Hosted Checkout and the fan-app PaymentIntent both persist the order and reach this projector. */
    @Test
    void paidCheckout_withoutProofText_recordsNothing() {
        Order order = paidOrder("hosted-guard@example.com", true, null);
        projector.onTicketsIssued(new TicketsIssuedEvent(order.getId()));

        assertThat(basesOf("hosted-guard@example.com")).isEmpty();
        assertNoSoftOptInAnywhere();
    }

    @Test
    void freeCheckout_withTickedBox_recordsExplicit() {
        Order order = freeCheckout.issueFreeOrder(event, freeTier, 1, "free-guard@example.com", null, false, true,
                CheckoutAttribution.NONE, null, null, new CheckoutConsent(true, PROOF));
        projector.onTicketsIssued(new TicketsIssuedEvent(order.getId()));
        drainAsync();

        // The committed free order also fires the async projector; both runs record explicit.
        assertThat(basesOf("free-guard@example.com")).isNotEmpty().containsOnly("explicit");
        assertNoSoftOptInAnywhere();
    }

    @Test
    void csvImport_provenRowAndAttestedRow_neverSoftOptIn() {
        importService.importContacts(List.of(
                new CsvContactParser.RawContact(2, "import-proven@example.com", null, null, "shotgun", "2026-09-01",
                        null, "2026-08-01", "opted_in", "optin-screenshot-2"),
                new CsvContactParser.RawContact(3, "import-attested@example.com", null, null)),
                false, organizer, "2026-09-27");

        assertThat(basesOf("import-proven@example.com")).containsExactly("explicit");
        assertThat(basesOf("import-attested@example.com")).isEmpty();
        assertNoSoftOptInAnywhere();
    }

    @Test
    void organizerConsentCapture_refusesSoftOptIn_andRecordsOnlyExplicit() {
        Order order = paidOrder("capture-guard@example.com", false, null);
        projector.onTicketsIssued(new TicketsIssuedEvent(order.getId()));
        UUID mid = membershipId("capture-guard@example.com");

        assertThat(validator.validate(new AudienceController.ConsentRequest(mid, "soft_opt_in", "desk", "Said yes")))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("basis"));
        AudienceController.ConsentRequest explicit = new AudienceController.ConsentRequest(mid, "explicit", "desk", "Said yes");
        assertThat(validator.validate(explicit)).isEmpty();
        audienceController.captureConsent(organizer, explicit);

        assertThat(basesOf("capture-guard@example.com")).containsExactly("explicit");
        assertNoSoftOptInAnywhere();
    }

    @Test
    void smsOrderConfirmationOptIn_recordsExplicit() {
        Order order = paidOrder("sms-guard@example.com", false, null);
        smsConsentService.submit(order.getToken(), new SmsConsentRequest("+33612345678", true, "Text me updates."));

        assertThat(jdbc.queryForList("select lawful_basis from consent_records cr join memberships m "
                + "on m.membership_id = cr.membership_id where m.org_id = ? and cr.channel = 'sms'", String.class, orgId))
                .containsExactly("explicit");
        assertNoSoftOptInAnywhere();
    }

    /** Covers paths not driven above (buyer preference centre, anything added later): every capture passes explicit. */
    @Test
    void sourceScan_everyConsentCaptureCallPassesExplicit() throws IOException {
        Pattern call = Pattern.compile("(?:consentService|consent)\\s*\\.\\s*capture\\s*\\(([^;]*?)\\)\\s*;", Pattern.DOTALL);
        List<String> offenders = new ArrayList<>();
        int calls = 0;
        for (Path file : mainSources()) {
            Matcher m = call.matcher(Files.readString(file));
            while (m.find()) {
                calls++;
                List<String> args = topLevelArgs(m.group(1));
                String basis = args.size() > 2 ? args.get(2) : "";
                // The organizer endpoint passes its body, whose @Pattern admits only "explicit" (driven above).
                boolean validatedBody = file.endsWith("AudienceController.java") && basis.equals("body.basis()");
                if (!basis.equals("\"explicit\"") && !validatedBody) offenders.add(file.getFileName() + ": " + basis);
            }
        }
        assertThat(calls).as("capture call sites found").isGreaterThanOrEqualTo(5);
        assertThat(offenders).isEmpty();
    }

    @Test
    void sourceScan_noProductionClassWritesTheSoftOptInLiteral() throws IOException {
        // Read-side vocabularies only: segment filters and the ConsentGate reader.
        Set<String> readers = Set.of("SegmentRuleSchema.java", "ConsentGate.java");
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            if (Files.readString(file).contains("\"soft_opt_in\"") && !readers.contains(file.getFileName().toString())) {
                offenders.add(file.toString());
            }
        }
        assertThat(offenders).isEmpty();
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private void drainAsync() {
        ThreadPoolExecutor tpe = ((ThreadPoolTaskExecutor) asyncExecutor).getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 10_000;
        while (tpe.getCompletedTaskCount() < tpe.getTaskCount() || !tpe.getQueue().isEmpty() || tpe.getActiveCount() > 0) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("async executor still busy after 10s");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Splits call arguments on top-level commas only, so nested calls and string literals stay whole. */
    static List<String> topLevelArgs(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        char quote = 0;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (quote != 0) {
                if (ch == '\\') i++;
                else if (ch == quote) quote = 0;
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                out.add(args.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(args.substring(start).trim());
        return out;
    }

    @Test
    void topLevelArgs_keepsNestedCommasAndLiteralsWhole() {
        assertThat(topLevelArgs("orgId, idOf(a, b), \"explicit\", \"a, b\", f(x, g(y, z))"))
                .containsExactly("orgId", "idOf(a, b)", "\"explicit\"", "\"a, b\"", "f(x, g(y, z))");
        assertThat(topLevelArgs("m.orgId(), resolve(m, List.of(1, 2)), body.basis()"))
                .containsExactly("m.orgId()", "resolve(m, List.of(1, 2))", "body.basis()");
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private void assertNoSoftOptInAnywhere() {
        assertThat(jdbc.queryForObject("select count(*) from consent_records cr join memberships m "
                + "on m.membership_id = cr.membership_id where m.org_id = ? and cr.lawful_basis = 'soft_opt_in'",
                Integer.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from memberships where org_id = ? and "
                + "(consent_basis = 'soft_opt_in' or sms_consent_basis = 'soft_opt_in')", Integer.class, orgId)).isZero();
    }

    private UUID membershipId(String email) {
        return jdbc.queryForObject("select m.membership_id from memberships m join consumers c "
                + "on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?",
                UUID.class, orgId, email);
    }

    private List<String> basesOf(String email) {
        return jdbc.queryForList("select cr.lawful_basis from consent_records cr join memberships m "
                + "on m.membership_id = cr.membership_id join consumers c on c.consumer_id = m.consumer_id "
                + "where m.org_id = ? and c.normalized_email = ? and cr.channel = 'email'", String.class, orgId, email);
    }

    private Order paidOrder(String email, boolean optIn, String proof) {
        Order o = new Order();
        o.setToken("guard-" + UUID.randomUUID());
        o.setEventId(event.getId());
        o.setOrgId(orgId);
        o.setEmail(email);
        o.setTotalMinor(2500);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setMarketingOptIn(optIn);
        o.setMarketingOptInProof(proof);
        o = orders.save(o);
        Ticket t = new Ticket();
        t.setToken("guard-t-" + UUID.randomUUID());
        t.setOrderId(o.getId());
        t.setEventId(event.getId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor(2500);
        tickets.save(t);
        return o;
    }
}
