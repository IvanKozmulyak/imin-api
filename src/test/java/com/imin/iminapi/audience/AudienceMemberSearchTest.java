package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.MemberDto;
import com.imin.iminapi.audience.dto.MemberPage;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceService;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Member search matches name, membership id, and an exact normalized email of a member of the org. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceMemberSearchTest {

    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AudienceService audienceService;
    @Autowired DataSource dataSource;

    @MockitoBean AuditLogger auditLogger;

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUp() {
        wipe();
        orgA = org("SearchOrgA").getId();
        orgB = org("SearchOrgB").getId();
    }

    @AfterEach
    void tearDown() { wipe(); }

    @Test
    void list_matches_exact_email_ignoring_case_and_whitespace() {
        UUID mid = seed(orgA, "fan@example.com", "Jo Doe");
        seed(orgA, "other@example.com", "Someone Else");

        List<String> ids = ids(list(orgA, "  FAN@Example.com "));

        assertThat(ids).containsExactly(mid.toString());
    }

    @Test
    void list_does_not_match_a_partial_email() {
        seed(orgA, "fan@example.com", "Jo Doe");

        assertThat(list(orgA, "example.com")).isEmpty();
    }

    @Test
    void list_does_not_return_another_orgs_member_with_that_email() {
        seed(orgB, "shared@example.com", "B Side");

        assertThat(list(orgA, "shared@example.com")).isEmpty();
    }

    @Test
    void list_still_matches_name() {
        UUID mid = seed(orgA, "fan@example.com", "Jo Doe");

        assertThat(ids(list(orgA, "jo d"))).containsExactly(mid.toString());
    }

    @Test
    void csv_export_matches_exact_email() {
        UUID mid = seed(orgA, "fan@example.com", "Jo Doe");
        seed(orgA, "other@example.com", "Someone Else");
        seed(orgB, "fan@example.com", "B Side");

        List<MemberDto> rows = audienceService.exportMembersCsv(orgA, null, " Fan@Example.COM");

        assertThat(ids(rows)).containsExactly(mid.toString());
    }

    @Test
    void csv_export_does_not_match_a_partial_email() {
        seed(orgA, "fan@example.com", "Jo Doe");

        assertThat(audienceService.exportMembersCsv(orgA, null, "example.com")).isEmpty();
    }

    private List<MemberDto> list(UUID orgId, String search) {
        MemberPage page = audienceService.listMembers(orgId,
                new AudienceService.MemberListRequest(null, 50, null, search, null, null, null, null));
        return page.items();
    }

    private static List<String> ids(List<MemberDto> rows) {
        return rows.stream().map(MemberDto::membershipId).toList();
    }

    private UUID seed(UUID orgId, String email, String name) {
        String normalized = EmailNormalizer.normalize(email);
        Consumer consumer = consumerRepo.findByNormalizedEmail(normalized).orElse(null);
        if (consumer == null) {
            consumer = new Consumer();
            consumer.setNormalizedEmail(normalized);
            consumer.setDisplayName(name);
            consumer = consumerRepo.save(consumer);
        }
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        m.setDisplayName(name);
        return membershipRepo.save(m).getMembershipId();
    }

    private Organization org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail(name + "@test.com");
        o.setCountry("DE");
        return orgRepo.save(o);
    }

    private void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from suppression_entries");
            s.execute("delete from consent_records");
            s.execute("delete from segments");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
            s.execute("delete from tickets");
            s.execute("delete from orders");
            s.execute("delete from events");
            s.execute("delete from users");
            s.execute("delete from organizations");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}
