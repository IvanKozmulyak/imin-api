package com.imin.iminapi.audience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end (real service + real H2) web tests for the segment-create bug fix.
 *
 * <p>Covers the reported "segment creation intermittently blocked" root causes:
 * <ul>
 *   <li>blank name → clean 400 field error (was a NOT NULL → 500)</li>
 *   <li>object-shaped {@code rulesJson} → 201 (was a Map-binding → 400 malformed body)</li>
 *   <li>string-shaped {@code rulesJson} → 201 (both caller shapes now bind)</li>
 *   <li>unknown rule field → clean 400 VALIDATION_FAILED listing {@code rulesJson}</li>
 *   <li>GET /segments lazily seeds the 7 prebuilt segments, idempotently</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class AudienceSegmentCreateWebTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgRepo;
    @Autowired SegmentRepository segmentRepo;
    @Autowired DataSource dataSource;

    // Audit writes are out of scope here; mock so create/list don't touch the audit path.
    @MockitoBean AuditLogger auditLogger;

    final ObjectMapper om = new ObjectMapper();

    private UUID orgId;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        wipe();
        Organization org = new Organization();
        org.setName("SegCreateOrg");
        org.setSlug("segcreate-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("segcreate@test.com");
        org.setCountry("DE");
        orgId = orgRepo.save(org).getId();
        AuthPrincipal principal = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
        auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ── Create: blank name ────────────────────────────────────────────────────

    @Test
    void create_blank_name_returns_400_with_field_error() throws Exception {
        String body = "{\"name\":\"   \",\"kind\":\"dynamic\"}";
        mvc.perform(post("/api/v1/audience/segments").with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.name").exists());
    }

    // ── Create: rulesJson as a structured array ───────────────────────────────

    @Test
    void create_object_shaped_rules_returns_201() throws Exception {
        String body = "{\"name\":\"Big Spenders\",\"rulesJson\":["
                + "{\"field\":\"events\",\"operator\":\">=\",\"value\":\"3\"},"
                + "{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"10000\"}]}";
        mvc.perform(post("/api/v1/audience/segments").with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Big Spenders"))
                .andExpect(jsonPath("$.kind").value("dynamic"))
                .andExpect(jsonPath("$.rules.length()").value(2));
    }

    // ── Create: rulesJson as a pre-serialized JSON string ─────────────────────

    @Test
    void create_string_shaped_rules_returns_201() throws Exception {
        String body = "{\"name\":\"Repeaters\",\"rulesJson\":"
                + "\"[{\\\"field\\\":\\\"events\\\",\\\"operator\\\":\\\">=\\\",\\\"value\\\":\\\"2\\\"}]\"}";
        mvc.perform(post("/api/v1/audience/segments").with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Repeaters"))
                .andExpect(jsonPath("$.rules.length()").value(1));
    }

    // ── Create: unknown rule field ────────────────────────────────────────────

    @Test
    void create_unknown_rule_field_returns_400() throws Exception {
        String body = "{\"name\":\"Bad rule\",\"rulesJson\":["
                + "{\"field\":\"totally_unknown\",\"operator\":\">=\",\"value\":\"3\"}]}";
        mvc.perform(post("/api/v1/audience/segments").with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.rulesJson").exists());
    }

    // ── List: lazy prebuilt seeding, idempotent ───────────────────────────────

    @Test
    void list_seeds_seven_prebuilt_segments_and_is_idempotent() throws Exception {
        // First list: org has never been seeded → the endpoint provisions the 7 prebuilts.
        mvc.perform(get("/api/v1/audience/segments").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));

        // Second list: no duplicate seeding.
        mvc.perform(get("/api/v1/audience/segments").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));

        long prebuilt = segmentRepo.findByOrgId(orgId).stream()
                .filter(com.imin.iminapi.audience.model.Segment::isPrebuilt).count();
        org.assertj.core.api.Assertions.assertThat(prebuilt).isEqualTo(7);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

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
