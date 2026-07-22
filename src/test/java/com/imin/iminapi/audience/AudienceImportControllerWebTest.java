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
            s.execute("delete from suppression_entries");
            s.execute("delete from consent_records");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}
