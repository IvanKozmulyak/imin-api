package com.imin.iminapi.audience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.dto.ImportResultResponse;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceImportService;
import com.imin.iminapi.audience.service.CsvContactParser;
import com.imin.iminapi.audienceplan.model.AudienceImport;
import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.AudienceImportRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/audience/imports}: org-scoped import history without personal data. */
@IminIntegrationTest
class AudienceImportHistoryTest {

    private static final String URL = "/api/v1/audience/imports";
    private static final String SHA_A = "0123456789abcdef".repeat(4);
    private static final String SHA_B = "fedcba9876543210".repeat(4);

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired AudienceImportService importService;
    @Autowired AudienceImportRepository importRepo;
    @Autowired ImportRowProvenanceRepository provenanceRepo;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired Clock clock;

    /** Import times relative to the app clock; whole seconds survive the round trip unchanged. */
    private Instant t0;

    @BeforeEach
    void setUp() {
        t0 = clock.instant().truncatedTo(ChronoUnit.SECONDS).minus(30, ChronoUnit.DAYS);
    }

    // ── role ────────────────────────────────────────────────────────────────

    @Test
    void owner_listsTheOrgsImports() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedHeader(orgId, t0);

        assertThat(list(principal(orgId, UserRole.OWNER))).hasSize(1);
    }

    @Test
    void admin_listsTheOrgsImports() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedHeader(orgId, t0);

        assertThat(list(principal(orgId, UserRole.ADMIN))).hasSize(1);
    }

    /** A MEMBER of the org and a door-scanner device are both refused. */
    @ParameterizedTest
    @ValueSource(strings = {"member", "gate"})
    void nonManager_gets403(String who) throws Exception {
        UUID orgId = UUID.randomUUID();
        seedHeader(orgId, t0);
        AuthPrincipal p = who.equals("member") ? principal(orgId, UserRole.MEMBER)
                : AuthPrincipal.forGate(UUID.randomUUID(), orgId);

        mvc.perform(get(URL).with(auth(p))).andExpect(status().isForbidden());
    }

    // ── scoping, order, limit ───────────────────────────────────────────────

    @Test
    void anotherOrgsImport_isNeverListed() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID mine = seedHeader(orgId, t0);
        seedHeader(UUID.randomUUID(), t0.plus(1, ChronoUnit.DAYS));

        JsonNode body = list(principal(orgId, UserRole.OWNER));

        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("id").asText()).isEqualTo(mine.toString());
    }

    @Test
    void emptyOrg_getsAnEmptyList() throws Exception {
        assertThat(list(principal(UUID.randomUUID(), UserRole.OWNER))).isEmpty();
    }

    @Test
    void newestFirst() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID older = seedHeader(orgId, t0.minus(31, ChronoUnit.DAYS));
        UUID newer = seedHeader(orgId, t0);

        JsonNode body = list(principal(orgId, UserRole.OWNER));

        assertThat(ids(body)).containsExactly(newer.toString(), older.toString());
    }

    @ParameterizedTest
    @CsvSource({
            "3,   ?limit=2,   2",
            "2,   ?limit=0,   1",
            "201, ?limit=500, 200",
            "51,  '',         50"})
    void limit_isHonouredAndClamped(int seeded, String query, int expected) throws Exception {
        UUID orgId = UUID.randomUUID();
        seedHeaders(orgId, seeded);

        assertThat(list(principal(orgId, UserRole.OWNER), query)).hasSize(expected);
    }

    // ── fields ──────────────────────────────────────────────────────────────

    @Test
    void headerFields_areMapped_withHashesTruncatedTo12() throws Exception {
        UUID orgId = UUID.randomUUID();
        AudienceImport h = header(orgId, t0);
        h.setOriginalFileSha256(SHA_A);
        h.setUploadedFileSha256(SHA_B);
        h.setSourcePlatform("shotgun");
        h.setExportDate(LocalDate.parse("2026-08-30"));
        h.setAttestationVersion("2026-09-26");
        h.setRowsTotal(10);
        h.setRowsExplicit(4);
        h.setRowsNoBasis(3);
        h.setRowsRejected(2);
        importRepo.save(h);

        JsonNode s = list(principal(orgId, UserRole.OWNER)).get(0);

        assertThat(s.get("originalFileHashPrefix").asText()).isEqualTo("0123456789ab");
        assertThat(s.get("uploadedFileHashPrefix").asText()).isEqualTo("fedcba987654");
        assertThat(s.get("sourcePlatform").asText()).isEqualTo("shotgun");
        assertThat(s.get("exportDate").asText()).isEqualTo("2026-08-30");
        assertThat(s.get("attestationVersion").asText()).isEqualTo("2026-09-26");
        assertThat(s.get("rowsTotal").asInt()).isEqualTo(10);
        assertThat(s.get("rowsExplicit").asInt()).isEqualTo(4);
        assertThat(s.get("rowsNoBasis").asInt()).isEqualTo(3);
        assertThat(s.get("rowsRejected").asInt()).isEqualTo(2);
        assertThat(Instant.parse(s.get("createdAt").asText())).isEqualTo(t0);
    }

    @Test
    void missingHashes_areNull() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedHeader(orgId, t0);

        JsonNode s = list(principal(orgId, UserRole.OWNER)).get(0);

        assertThat(s.get("originalFileHashPrefix").isNull()).isTrue();
        assertThat(s.get("uploadedFileHashPrefix").isNull()).isTrue();
    }

    /** The proof link is evidence the organizer keeps: the list says whether it exists, never what it is. */
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "https://drive.example/consent-export-secret, true",
            "'  ',                                        false",
            "NULL,                                        false"})
    void proofRef_isReportedAsPresence_neverAsItsText(String proofRef, boolean present) throws Exception {
        UUID orgId = UUID.randomUUID();
        AudienceImport h = header(orgId, t0);
        h.setProofRef(proofRef);
        importRepo.save(h);

        String raw = rawList(principal(orgId, UserRole.OWNER), "");
        JsonNode s = json.readTree(raw).get(0);

        assertThat(s.get("proofRefPresent").asBoolean()).isEqualTo(present);
        assertThat(s.has("proofRef")).isFalse();
        if (proofRef != null && !proofRef.isBlank()) assertThat(raw).doesNotContain("consent-export-secret");
    }

    @Test
    void realImport_listsNoPersonalData() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);
        String address = email("pii");
        importService.importContacts(List.of(new CsvContactParser.RawContact(2, address, "Ada Lovelace", "+33612345678",
                "shotgun", "2026-09-01", "Night A", "2026-08-01", "opted_in", "optin-row-2")), false, owner);

        String raw = rawList(owner, "");
        JsonNode s = json.readTree(raw).get(0);

        assertThat(raw).doesNotContain(address, "Ada", "+33612345678", "optin-row-2", "Night A",
                owner.userId().toString());
        assertThat(s.has("createdBy")).isFalse();
    }

    // ── per-row provenance summary ──────────────────────────────────────────

    @Test
    void provenance_isCountedByOutcomeAndReason() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);
        ImportResultResponse r = importService.importContacts(List.of(
                row(2, email("ok1"), "opted_in", "p-2"),
                row(3, email("ok2"), "opted_in", "p-3"),
                row(4, email("noproof"), "opted_in", null),
                row(5, email("none1"), null, null),
                row(6, email("none2"), null, null)), false, owner);

        JsonNode s = list(owner).get(0);
        JsonNode p = s.get("provenance");

        assertThat(s.get("id").asText()).isEqualTo(r.importId().toString());
        assertThat(p.get("rowsRecorded").asInt()).isEqualTo(5);
        assertThat(p.get("rowsAccepted").asInt()).isEqualTo(2);
        assertThat(p.get("notAcceptedByReason").get("missing_proof").asInt()).isEqualTo(1);
        assertThat(p.get("notAcceptedByReason").get("not_opted_in").asInt()).isEqualTo(2);
        assertThat(p.get("notAcceptedByReason").size()).isEqualTo(2);
    }

    @Test
    void notAcceptedRowWithoutReason_countsAsUnspecified() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID importId = seedHeader(orgId, t0);
        seedProvenance(importId, seedMembership(orgId, email("noreason")), false, null);

        JsonNode p = list(principal(orgId, UserRole.OWNER)).get(0).get("provenance");

        assertThat(p.get("rowsRecorded").asInt()).isEqualTo(1);
        assertThat(p.get("rowsAccepted").asInt()).isZero();
        assertThat(p.get("notAcceptedByReason").get("unspecified").asInt()).isEqualTo(1);
    }

    @Test
    void erasedPersonsProvenance_isNoLongerCounted() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID importId = seedHeader(orgId, t0);
        UUID kept = seedMembership(orgId, email("kept"));
        UUID erased = seedMembership(orgId, email("erased"));
        seedProvenance(importId, kept, true, null);
        seedProvenance(importId, erased, true, null);
        provenanceRepo.deleteByMembershipId(erased);

        JsonNode p = list(principal(orgId, UserRole.OWNER)).get(0).get("provenance");

        assertThat(p.get("rowsRecorded").asInt()).isEqualTo(1);
        assertThat(p.get("rowsAccepted").asInt()).isEqualTo(1);
    }

    @Test
    void importWithoutProvenanceRows_hasZeroCountsAndNoReasons() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedHeader(orgId, t0);

        JsonNode p = list(principal(orgId, UserRole.OWNER)).get(0).get("provenance");

        assertThat(p.get("rowsRecorded").asInt()).isZero();
        assertThat(p.get("rowsAccepted").asInt()).isZero();
        assertThat(p.get("notAcceptedByReason").isEmpty()).isTrue();
    }

    @Test
    void provenanceOfOneImport_doesNotLeakIntoAnother() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID first = seedHeader(orgId, t0.minus(31, ChronoUnit.DAYS));
        UUID second = seedHeader(orgId, t0);
        seedProvenance(first, seedMembership(orgId, email("first")), true, null);

        JsonNode body = list(principal(orgId, UserRole.OWNER));

        assertThat(body.get(0).get("id").asText()).isEqualTo(second.toString());
        assertThat(body.get(0).get("provenance").get("rowsRecorded").asInt()).isZero();
        assertThat(body.get(1).get("provenance").get("rowsRecorded").asInt()).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private JsonNode list(AuthPrincipal p) throws Exception {
        return list(p, "");
    }

    private JsonNode list(AuthPrincipal p, String query) throws Exception {
        return json.readTree(rawList(p, query));
    }

    private String rawList(AuthPrincipal p, String query) throws Exception {
        return mvc.perform(get(URL + query).with(auth(p)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> ids(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    private static CsvContactParser.RawContact row(int n, String address, String status, String proof) {
        return new CsvContactParser.RawContact(n, address, null, null,
                "shotgun", "2026-09-01", null, null, status, proof);
    }

    private static AudienceImport header(UUID orgId, Instant createdAt) {
        AudienceImport h = new AudienceImport();
        h.setOrgId(orgId);
        h.setCreatedAt(createdAt);
        return h;
    }

    private UUID seedHeader(UUID orgId, Instant createdAt) {
        return importRepo.save(header(orgId, createdAt)).getId();
    }

    private void seedHeaders(UUID orgId, int n) {
        Instant base = t0.minus(1, ChronoUnit.DAYS);
        for (int i = 0; i < n; i++) seedHeader(orgId, base.plusSeconds(i * 60L));
    }

    private void seedProvenance(UUID importId, UUID membershipId, boolean accepted, String reason) {
        ImportRowProvenance p = new ImportRowProvenance();
        p.setImportId(importId);
        p.setMembershipId(membershipId);
        p.setRowNumber(2);
        p.setMarketingStatus(accepted ? "opted_in" : "none");
        p.setAccepted(accepted);
        p.setRejectReason(reason);
        provenanceRepo.save(p);
    }

    private UUID seedMembership(UUID orgId, String normalizedEmail) {
        com.imin.iminapi.audience.model.Consumer c = new com.imin.iminapi.audience.model.Consumer();
        c.setNormalizedEmail(normalizedEmail);
        c = consumers.save(c);
        com.imin.iminapi.audience.model.Membership m = new com.imin.iminapi.audience.model.Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setStatus("active");
        return memberships.save(m).getMembershipId();
    }

    private static String email(String tag) {
        return tag + "-" + UUID.randomUUID() + "@example.com";
    }

    private static AuthPrincipal principal(UUID orgId, UserRole role) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, role, UUID.randomUUID());
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
