package com.imin.iminapi.audience;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link com.imin.iminapi.audience.controller.AudienceImportController} over the real services: attestation
 * gate, caps, missing email column, roles and the happy path; every address is unique (consumers are global).
 */
@IminIntegrationTest
class AudienceImportControllerWebTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired UserRepository users;

    private Organization organization;
    private AuthPrincipal owner;

    @BeforeEach
    void setUp() {
        organization = fx.org();
        owner = fx.principal(fx.owner(organization));
    }

    /** Own imports and memberships (provenance and consent rows cascade), then the org. */
    @AfterEach
    void tearDown() {
        try {
            jdbc.update("delete from audience_imports where org_id = ?", organization.getId());
            jdbc.update("delete from memberships where org_id = ?", organization.getId());
        } finally {
            OrgRows.delete(jdbc, List.of(organization.getId()));
        }
    }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "contacts.csv", "text/csv",
                body.getBytes(StandardCharsets.UTF_8));
    }

    // ── attestation gate ───────────────────────────────────────────────────────

    @Test
    void missing_attestation_returns_400() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(csv("email\n" + fx.email("alice") + "\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ATTESTATION_REQUIRED"));
    }

    @Test
    void attestation_not_true_returns_400() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(csv("email\n" + fx.email("alice") + "\n"))
                        .param("attestation", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ATTESTATION_REQUIRED"));
    }

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    void happy_path_imports_and_returns_counts() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(csv("email,name\n" + fx.email("alice") + ",Alice\n" + fx.email("bob") + ",Bob\n"))
                        .param("attestation", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.suppressed").value(0))
                .andExpect(jsonPath("$.invalidEmails").value(0));
    }

    @Test
    void dry_run_returns_counts() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(csv("email\n" + fx.email("alice") + "\n"))
                        .param("attestation", "true")
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    void provenance_import_returns_the_consent_split_and_records_hash_and_proof_ref() throws Exception {
        String body = "email,source_platform,export_date,marketing_status,proof_ref\n"
                + "" + fx.email("alice") + ",shotgun,2026-09-01,opted_in,screenshot-1\n"
                + "" + fx.email("bob") + ",,,,\n"
                + "" + fx.email("carol") + ",shotgun,2026-09-01,unsubscribed,\n";
        String expectedSha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));

        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(csv(body))
                        .param("attestation", "true")
                        .param("attestationVersion", "v2-2026-09-27")
                        .param("proofRef", "  https://platform.example/export  ")
                        .param("originalFileSha256", " " + "AB".repeat(32) + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.rowsExplicit").value(1))
                .andExpect(jsonPath("$.rowsNoBasis").value(1))
                .andExpect(jsonPath("$.rowsUnsubscribed").value(1))
                .andExpect(jsonPath("$.rowsCapped").value(0))
                .andExpect(jsonPath("$.importId").isNotEmpty());

        Map<String, Object> row = jdbc.queryForMap("select uploaded_file_sha256, proof_ref, attestation_version, "
                + "original_file_sha256 from audience_imports where org_id = ?", organization.getId());
        assertThat(row.get("uploaded_file_sha256")).isEqualTo(expectedSha);
        assertThat(row.get("proof_ref")).isEqualTo("https://platform.example/export");
        assertThat(row.get("attestation_version")).isEqualTo("v2-2026-09-27");
        assertThat(row.get("original_file_sha256")).isEqualTo("ab".repeat(32));
    }

    @Test
    void a_malformed_original_file_hash_is_not_stored() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(csv("email\n" + fx.email("alice") + "\n"))
                        .param("attestation", "true")
                        .param("originalFileSha256", "not-a-hash"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "select original_file_sha256, uploaded_file_sha256 from audience_imports where org_id = ?", organization.getId());
        assertThat(row.get("original_file_sha256")).isNull();
        assertThat((String) row.get("uploaded_file_sha256")).hasSize(64);
    }

    @Test
    void without_an_original_file_hash_only_the_uploaded_hash_is_stored() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(csv("email\n" + fx.email("alice") + "\n"))
                        .param("attestation", "true"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "select original_file_sha256, uploaded_file_sha256 from audience_imports where org_id = ?", organization.getId());
        assertThat(row.get("original_file_sha256")).isNull();
        assertThat((String) row.get("uploaded_file_sha256")).hasSize(64);
    }

    @Test
    void forbidden_column_returns_400_naming_the_column() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(csv("email,IBAN\n" + fx.email("alice") + ",FR76\n"))
                        .param("attestation", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_FORBIDDEN_COLUMN"))
                .andExpect(jsonPath("$.error.fields.column").value("IBAN"));
    }

    // ── caps + column detection ──────────────────────────────────────────────────

    @Test
    void missing_email_column_returns_400() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(csv("name,phone\nAlice,+15551234567\n"))
                        .param("attestation", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_EMAIL_COLUMN_MISSING"));
    }

    @Test
    void oversize_file_returns_400() throws Exception {
        byte[] big = new byte[6 * 1024 * 1024]; // 6MB > 5MB cap
        java.util.Arrays.fill(big, (byte) 'a');
        MockMultipartFile file = new MockMultipartFile("file", "big.csv", "text/csv", big);

        mvc.perform(multipart("/api/v1/audience/import").with(auth(owner))
                        .file(file)
                        .param("attestation", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_FILE_TOO_LARGE"));
    }

    // ── role ─────────────────────────────────────────────────────────────────────

    @Test
    void owner_can_import() throws Exception {
        importAs(principal(UserRole.OWNER), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
        assertThat(count("audience_imports")).isEqualTo(1);
    }

    @Test
    void admin_can_import() throws Exception {
        importAs(principal(UserRole.ADMIN), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
        assertThat(count("audience_imports")).isEqualTo(1);
    }

    @Test
    void member_gets403_and_nothing_is_written() throws Exception {
        importAs(principal(UserRole.MEMBER), false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(count("audience_imports")).isZero();
        assertThat(count("memberships")).isZero();
    }

    @Test
    void member_dryRun_gets403() throws Exception {
        importAs(principal(UserRole.MEMBER), true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void gate_device_gets403() throws Exception {
        importAs(AuthPrincipal.forGate(UUID.randomUUID(), organization.getId()), false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void member_is_refused_before_the_attestation_check() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email\n" + fx.email("alice") + "\n"))
                        .with(auth(principal(UserRole.MEMBER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private org.springframework.test.web.servlet.ResultActions importAs(AuthPrincipal p, boolean dryRun) throws Exception {
        return mvc.perform(multipart("/api/v1/audience/import")
                .file(csv("email\n" + fx.email("alice") + "\n"))
                .param("attestation", "true")
                .param("dryRun", String.valueOf(dryRun))
                .with(auth(p)));
    }

    /** A real user of the fixture org with this role. */
    private AuthPrincipal principal(UserRole role) {
        User u = fx.owner(organization);
        u.setRole(role);
        return fx.principal(users.save(u));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(AuthPrincipal p) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(p, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }

    /** Rows of this test's org only; the database is shared. */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where org_id = ?", Integer.class, organization.getId());
    }

    // ── auth ─────────────────────────────────────────────────────────────────────

    @Test
    void unauthenticated_returns_4xx() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email\n" + fx.email("alice") + "\n"))
                        .param("attestation", "true"))
                .andExpect(status().is4xxClientError());
    }
}
