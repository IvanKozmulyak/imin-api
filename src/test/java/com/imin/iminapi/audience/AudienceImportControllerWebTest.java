package com.imin.iminapi.audience;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer (MockMvc) tests for {@link com.imin.iminapi.audience.controller.AudienceImportController}
 * against real services + H2. Covers attestation gate, size/row caps, missing email column, and the
 * multipart happy path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class AudienceImportControllerWebTest {

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    // Best-effort audit; stub so consent-capture audit writes don't hit UserRepository.
    @MockitoBean AuditLogger auditLogger;

    static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a0001");
    static final UUID USER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a0010");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithOrgA {}

    public static class StubFactory implements WithSecurityContextFactory<WithOrgA> {
        @Override
        public org.springframework.security.core.context.SecurityContext createSecurityContext(WithOrgA ann) {
            AuthPrincipal p = new AuthPrincipal(USER_A, ORG_A, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @BeforeEach
    void setUp() { wipe(); }

    @AfterEach
    void tearDown() { wipe(); }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "contacts.csv", "text/csv",
                body.getBytes(StandardCharsets.UTF_8));
    }

    // ── attestation gate ───────────────────────────────────────────────────────

    @Test
    @WithOrgA
    void missing_attestation_returns_400() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email\nalice@example.com\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ATTESTATION_REQUIRED"));
    }

    @Test
    @WithOrgA
    void attestation_not_true_returns_400() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email\nalice@example.com\n"))
                        .param("attestation", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_ATTESTATION_REQUIRED"));
    }

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    @WithOrgA
    void happy_path_imports_and_returns_counts() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email,name\nalice@example.com,Alice\nbob@example.com,Bob\n"))
                        .param("attestation", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.suppressed").value(0))
                .andExpect(jsonPath("$.invalidEmails").value(0));
    }

    @Test
    @WithOrgA
    void dry_run_returns_counts() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email\nalice@example.com\n"))
                        .param("attestation", "true")
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    @WithOrgA
    void provenance_import_returns_the_consent_split_and_records_hash_and_proof_ref() throws Exception {
        String body = "email,source_platform,export_date,marketing_status,proof_ref\n"
                + "alice@example.com,shotgun,2026-09-01,opted_in,screenshot-1\n"
                + "bob@example.com,,,,\n"
                + "carol@example.com,shotgun,2026-09-01,unsubscribed,\n";
        String expectedSha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));

        mvc.perform(multipart("/api/v1/audience/import")
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

        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "select uploaded_file_sha256, proof_ref, attestation_version, original_file_sha256 from audience_imports")) {
            org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
            org.assertj.core.api.Assertions.assertThat(rs.getString(1)).isEqualTo(expectedSha);
            org.assertj.core.api.Assertions.assertThat(rs.getString(2)).isEqualTo("https://platform.example/export");
            org.assertj.core.api.Assertions.assertThat(rs.getString(3)).isEqualTo("v2-2026-09-27");
            org.assertj.core.api.Assertions.assertThat(rs.getString(4)).isEqualTo("ab".repeat(32));
        }
    }

    @Test
    @WithOrgA
    void a_malformed_original_file_hash_is_not_stored() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email\nalice@example.com\n"))
                        .param("attestation", "true")
                        .param("originalFileSha256", "not-a-hash"))
                .andExpect(status().isOk());

        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "select original_file_sha256, uploaded_file_sha256 from audience_imports")) {
            org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
            org.assertj.core.api.Assertions.assertThat(rs.getString(1)).isNull();
            org.assertj.core.api.Assertions.assertThat(rs.getString(2)).hasSize(64);
        }
    }

    @Test
    @WithOrgA
    void without_an_original_file_hash_only_the_uploaded_hash_is_stored() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email\nalice@example.com\n"))
                        .param("attestation", "true"))
                .andExpect(status().isOk());

        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "select original_file_sha256, uploaded_file_sha256 from audience_imports")) {
            org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
            org.assertj.core.api.Assertions.assertThat(rs.getString(1)).isNull();
            org.assertj.core.api.Assertions.assertThat(rs.getString(2)).hasSize(64);
        }
    }

    @Test
    @WithOrgA
    void forbidden_column_returns_400_naming_the_column() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email,IBAN\nalice@example.com,FR76\n"))
                        .param("attestation", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_FORBIDDEN_COLUMN"))
                .andExpect(jsonPath("$.error.fields.column").value("IBAN"));
    }

    // ── caps + column detection ──────────────────────────────────────────────────

    @Test
    @WithOrgA
    void missing_email_column_returns_400() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("name,phone\nAlice,+15551234567\n"))
                        .param("attestation", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_EMAIL_COLUMN_MISSING"));
    }

    @Test
    @WithOrgA
    void oversize_file_returns_400() throws Exception {
        byte[] big = new byte[6 * 1024 * 1024]; // 6MB > 5MB cap
        java.util.Arrays.fill(big, (byte) 'a');
        MockMultipartFile file = new MockMultipartFile("file", "big.csv", "text/csv", big);

        mvc.perform(multipart("/api/v1/audience/import")
                        .file(file)
                        .param("attestation", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_FILE_TOO_LARGE"));
    }

    // ── auth ─────────────────────────────────────────────────────────────────────

    @Test
    void unauthenticated_returns_4xx() throws Exception {
        mvc.perform(multipart("/api/v1/audience/import")
                        .file(csv("email\nalice@example.com\n"))
                        .param("attestation", "true"))
                .andExpect(status().is4xxClientError());
    }

    private void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from import_row_provenance");
            s.execute("delete from audience_imports");
            s.execute("delete from suppression_entries");
            s.execute("delete from consent_records");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}
