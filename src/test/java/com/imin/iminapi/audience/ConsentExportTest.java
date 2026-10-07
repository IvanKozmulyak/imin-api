package com.imin.iminapi.audience;

import com.imin.iminapi.audience.controller.ConsentExportController;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceImportService;
import com.imin.iminapi.audience.service.ConsentExportService;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.CsvContactParser;
import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/audience/consent/export}: rule 9, every consent record exportable per organizer. */
@IminIntegrationTest
class ConsentExportTest {

    private static final String URL = "/api/v1/audience/consent/export";
    private static final String HEADER = "record_id,captured_at,email,channel,status,basis,source,"
            + "text_version,order_id,proof_text,import_id,import_row,source_platform,export_date,proof_ref,"
            + "confirmation_required,confirmed_at";

    @Autowired MockMvc mvc;
    @Autowired ConsentService consentService;
    @Autowired AudienceImportService importService;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ImportRowProvenanceRepository provenanceRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired AuditRows audit;

    // ── header and role ─────────────────────────────────────────────────────

    @Test
    void emptyOrg_getsExactlyTheHeaderRow_andAZeroRowAuditEntry() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);

        String body = export(owner);

        assertThat(body).isEqualTo(HEADER + "\r\n");
        assertExportAudited(owner, "Consent records exported (0 row(s))");
    }

    @Test
    void responseIsAnUncachedCsvAttachment() throws Exception {
        AuthPrincipal owner = principal(UUID.randomUUID(), UserRole.OWNER);
        MvcResult started = mvc.perform(get(URL).with(auth(owner)))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"consent-records.csv\""))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void admin_mayExport() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("admin"));
        consentService.capture(orgId, mid, "explicit", "checkout", "ticked", principal(orgId, UserRole.OWNER));

        assertThat(rows(export(principal(orgId, UserRole.ADMIN)))).hasSize(1);
    }

    @Test
    void member_gets403_andNoAuditRow() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMembership(orgId, email("member"));

        mvc.perform(get(URL).with(auth(principal(orgId, UserRole.MEMBER))))
                .andExpect(status().isForbidden());

        assertThat(audit.forOrg(orgId)).noneMatch(r -> AuditActions.CONSENT_EXPORTED.equals(r.getAction()));
    }

    @Test
    void gateDevice_gets403() throws Exception {
        AuthPrincipal gate = AuthPrincipal.forGate(UUID.randomUUID(), UUID.randomUUID());
        mvc.perform(get(URL).with(auth(gate)))
                .andExpect(status().isForbidden());
    }

    // ── contents ────────────────────────────────────────────────────────────

    @Test
    void ownRecord_carriesEveryStoredColumn() throws Exception {
        UUID orgId = UUID.randomUUID();
        String address = email("full");
        UUID mid = seedMembership(orgId, address);
        UUID orderId = UUID.randomUUID();
        consentService.capture(orgId, mid, "explicit", "checkout", "I agree", "email",
                "checkout-v2", orderId, principal(orgId, UserRole.OWNER));

        List<String[]> rows = rows(export(principal(orgId, UserRole.OWNER)));

        assertThat(rows).hasSize(1);
        String[] r = rows.get(0);
        assertThat(r).hasSize(17);
        assertThat(UUID.fromString(r[0])).isNotNull();
        assertThat(java.time.Instant.parse(r[1])).isNotNull();
        assertThat(Arrays.copyOfRange(r, 2, 10)).containsExactly(
                address, "email", "subscribed", "explicit", "checkout", "checkout-v2",
                orderId.toString(), "I agree");
        // Not an import row: no provenance.
        assertThat(Arrays.copyOfRange(r, 10, 15)).containsOnly("");
        // A checkout grant needs no confirmation.
        assertThat(Arrays.copyOfRange(r, 15, 17)).containsExactly("false", "");
    }

    @Test
    void doorSignUp_isMarkedPendingUntilConfirmed_thenCarriesItsConfirmationTime() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("door"));
        UUID recordId = consentService.capture(orgId, mid, "explicit", "door_qr", "Ticked at the door", "email",
                "door-org-named-2026-09", null, null, ConsentOrigin.DATA_SUBJECT, null);

        String[] pending = rows(export(principal(orgId, UserRole.OWNER))).get(0);
        assertThat(Arrays.copyOfRange(pending, 15, 17)).containsExactly("true", "");

        java.time.Instant at = java.time.Instant.parse("2026-09-28T10:15:30Z");
        jdbc.update("update consent_records set confirmed_at = ? where id = ?", java.sql.Timestamp.from(at), recordId);

        String[] confirmed = rows(export(principal(orgId, UserRole.OWNER))).get(0);
        assertThat(Arrays.copyOfRange(confirmed, 15, 17)).containsExactly("true", at.toString());
    }

    @Test
    void unsubscribeRecord_isExportedWithItsStatusAndNullBasisEmpty() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("unsub"));
        consentService.unsubscribe(orgId, mid, "footer_link", "email", ConsentOrigin.OPERATOR,
                principal(orgId, UserRole.OWNER));

        String[] r = rows(export(principal(orgId, UserRole.OWNER))).get(0);

        assertThat(r[4]).isEqualTo("unsubscribed");
        assertThat(r[5]).isEmpty();
        assertThat(r[6]).isEqualTo("footer_link");
    }

    @Test
    void otherOrgsRecords_areAbsent() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        String mine = email("mine");
        String theirs = email("theirs");
        consentService.capture(orgA, seedMembership(orgA, mine), "explicit", "checkout", "a",
                principal(orgA, UserRole.OWNER));
        consentService.capture(orgB, seedMembership(orgB, theirs), "explicit", "checkout", "b",
                principal(orgB, UserRole.OWNER));

        String body = export(principal(orgA, UserRole.OWNER));

        assertThat(body).contains(mine).doesNotContain(theirs);
    }

    @Test
    void erasePendingMembership_isLeftOut() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);
        String kept = email("kept");
        String erasing = email("erasing");
        consentService.capture(orgId, seedMembership(orgId, kept), "explicit", "checkout", "k", owner);
        UUID gone = seedMembership(orgId, erasing);
        consentService.capture(orgId, gone, "explicit", "checkout", "e", owner);
        Membership m = memberships.findByIdAndOrgId(gone, orgId).orElseThrow();
        m.setStatus("erase_pending");
        memberships.save(m);

        String body = export(owner);

        assertThat(body).contains(kept).doesNotContain(erasing);
        assertExportAudited(owner, "Consent records exported (1 row(s))");
    }

    @Test
    void importedRow_carriesItsOwnProvenance() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);
        String address = email("imported");
        importService.importContacts(List.of(new CsvContactParser.RawContact(7, address, null, null,
                        "shotgun", "2026-09-01", null, null, "opted_in", "optin-shot-7")),
                false, owner);
        UUID mid = membershipOf(orgId, address);
        ImportRowProvenance p = provenanceRepo.findByMembershipIdOrderByCreatedAtAsc(mid).get(0);

        String[] r = rows(export(owner)).get(0);

        assertThat(r[6]).isEqualTo("organizer_import_row");
        assertThat(Arrays.copyOfRange(r, 10, 15)).containsExactly(
                p.getImportId().toString(), "7", "shotgun", "2026-09-01", "optin-shot-7");
        // The writer keyed the provenance row to the record in the same transaction.
        assertThat(p.getConsentRecordId()).isEqualTo(UUID.fromString(r[0]));
    }

    @Test
    void handTypedRecordCopyingTheImportProofPrefix_getsNoProvenance() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);
        String address = email("copied");
        importService.importContacts(List.of(new CsvContactParser.RawContact(4, address, null, null,
                        "shotgun", "2026-09-01", null, null, "opted_in", "proof-4")),
                false, owner);
        UUID mid = membershipOf(orgId, address);
        ImportRowProvenance p = provenanceRepo.findByMembershipIdOrderByCreatedAtAsc(mid).get(0);
        String copied = "Per-row consent proof from CSV import " + p.getImportId() + " row 4: copied";
        consentService.capture(orgId, mid, "explicit", "organizer_import_row", copied, owner);

        List<String[]> rows = rows(export(owner));

        assertThat(rows).hasSize(2);
        String[] typed = rows.stream().filter(r -> r[9].equals(copied)).findFirst().orElseThrow();
        assertThat(Arrays.copyOfRange(typed, 10, 15)).containsOnly("");
        String[] imported = rows.stream().filter(r -> !r[9].equals(copied)).findFirst().orElseThrow();
        assertThat(imported[0]).isEqualTo(p.getConsentRecordId().toString());
        assertThat(imported[11]).isEqualTo("4");
    }

    @Test
    void handTypedRecordClaimingTheImportSource_getsNoProvenance() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);
        String address = email("forged");
        importService.importContacts(List.of(new CsvContactParser.RawContact(2, address, null, null,
                        "shotgun", "2026-09-01", null, null, "opted_in", "proof-2")),
                false, owner);
        UUID mid = membershipOf(orgId, address);
        consentService.capture(orgId, mid, "explicit", "organizer_import_row", "typed by hand", owner);

        List<String[]> rows = rows(export(owner));

        assertThat(rows).hasSize(2);
        String[] typed = rows.stream().filter(r -> r[9].equals("typed by hand")).findFirst().orElseThrow();
        assertThat(Arrays.copyOfRange(typed, 10, 15)).containsOnly("");
        String[] imported = rows.stream().filter(r -> !r[9].equals("typed by hand")).findFirst().orElseThrow();
        assertThat(imported[11]).isEqualTo("2");
    }

    @Test
    void proofText_isQuotedAndFormulaGuarded() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId, email("quote"));
        consentService.capture(orgId, mid, "explicit", "checkout", "=HYPERLINK(\"x\"),\nline2",
                principal(orgId, UserRole.OWNER));

        String body = export(principal(orgId, UserRole.OWNER));

        assertThat(body).contains(",\"'=HYPERLINK(\"\"x\"\"),\nline2\",");
    }

    @Test
    void auditSummary_countsRows_andNeverNamesAnAddress() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);
        String address = email("audit");
        UUID mid = seedMembership(orgId, address);
        consentService.capture(orgId, mid, "explicit", "checkout", "x", owner);
        consentService.unsubscribe(orgId, mid, "footer_link", "email", ConsentOrigin.OPERATOR, owner);

        export(owner);

        String summary = assertExportAudited(owner, "Consent records exported (2 row(s))");
        assertThat(summary).doesNotContain(address);
    }

    // ── time bounds ─────────────────────────────────────────────────────────

    @Test
    void exportRunningPastTheDeadline_stopsAtTheNextRow() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);
        consentService.capture(orgId, seedMembership(orgId, email("t1")), "explicit", "checkout", "1", owner);
        consentService.capture(orgId, seedMembership(orgId, email("t2")), "explicit", "checkout", "2", owner);
        Instant t0 = Instant.parse("2026-09-27T10:00:00Z");
        // start, row 1 one second in, row 2 exactly at the deadline
        ConsentExportService sut = new ConsentExportService(jdbc, txManager,
                steppingClock(t0, t0.plusSeconds(1), t0.plus(Duration.ofMinutes(10))));
        StringWriter out = new StringWriter();
        AtomicLong rowsWritten = new AtomicLong();

        assertThatThrownBy(() -> sut.write(orgId, out, rowsWritten))
                .isInstanceOf(ConsentExportService.ExportTimedOutException.class);

        assertThat(rowsWritten.get()).isEqualTo(1);
        assertThat(rows(out.toString())).hasSize(1);
    }

    @Test
    void timedOutExport_isAuditedAsInterrupted_andRethrown() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal owner = principal(orgId, UserRole.OWNER);
        consentService.capture(orgId, seedMembership(orgId, email("t3")), "explicit", "checkout", "3", owner);
        consentService.capture(orgId, seedMembership(orgId, email("t4")), "explicit", "checkout", "4", owner);
        Instant t0 = Instant.parse("2026-09-27T10:00:00Z");
        ConsentExportService service = new ConsentExportService(jdbc, txManager,
                steppingClock(t0, t0, t0.plus(Duration.ofMinutes(11))));
        AuditLogger audit = mock(AuditLogger.class);
        ConsentExportController sut = new ConsentExportController(service, audit);

        assertThatThrownBy(() -> sut.export(owner).getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(ConsentExportService.ExportTimedOutException.class);

        verify(audit).record(owner, AuditActions.CONSENT_EXPORTED, "organization", orgId,
                "Consent export interrupted after 1 row(s)");
        verify(audit, never()).record(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.startsWith("Consent records exported"));
    }

    @Test
    void theQuery_carriesAStatementTimeout_andAFetchSize() throws Exception {
        JdbcTemplate jdbcMock = mock(JdbcTemplate.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        org.mockito.Mockito.when(tx.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        ConsentExportService sut = new ConsentExportService(jdbcMock, tx, Clock.systemUTC());
        ArgumentCaptor<PreparedStatementCreator> creator = ArgumentCaptor.forClass(PreparedStatementCreator.class);

        sut.write(UUID.randomUUID(), new StringWriter(), new AtomicLong());

        verify(jdbcMock).query(creator.capture(), any(RowCallbackHandler.class));
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        org.mockito.Mockito.when(con.prepareStatement(any())).thenReturn(ps);
        creator.getValue().createPreparedStatement(con);
        verify(ps).setQueryTimeout(60);
        verify(ps).setFetchSize(500);
    }

    @Test
    void productionAsyncRequestTimeout_outlastsTheExportBound() {
        // Tests load src/test/resources/application.yaml, so read the production file itself.
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yaml"));
        String raw = yaml.getObject().getProperty("spring.mvc.async.request-timeout");

        assertThat(raw).isNotNull();
        Duration timeout = org.springframework.boot.convert.DurationStyle.detectAndParse(raw);
        assertThat(timeout).isGreaterThan(Duration.ofMinutes(10));
    }

    // ── interrupted stream (controller unit) ────────────────────────────────

    @Test
    void failedStream_recordsAnInterruptedExport_andRethrows() {
        ConsentExportService service = mock(ConsentExportService.class);
        AuditLogger audit = mock(AuditLogger.class);
        ConsentExportController sut = new ConsentExportController(service, audit);
        AuthPrincipal owner = principal(UUID.randomUUID(), UserRole.OWNER);
        IllegalStateException boom = new IllegalStateException("connection reset");
        doAnswer(inv -> {
            inv.<AtomicLong>getArgument(2).set(3);
            throw boom;
        }).when(service).write(eq(owner.orgId()), any(), any());

        assertThatThrownBy(() -> sut.export(owner).getBody().writeTo(new ByteArrayOutputStream()))
                .isSameAs(boom);

        verify(audit).record(owner, AuditActions.CONSENT_EXPORTED, "organization", owner.orgId(),
                "Consent export interrupted after 3 row(s)");
    }

    @Test
    void refusedRole_neverStartsTheStream() {
        ConsentExportService service = mock(ConsentExportService.class);
        AuditLogger audit = mock(AuditLogger.class);
        ConsentExportController sut = new ConsentExportController(service, audit);
        AuthPrincipal member = principal(UUID.randomUUID(), UserRole.MEMBER);
        doThrow(com.imin.iminapi.security.ApiException.forbidden("no")).when(service).requirePrivileged(member);

        assertThatThrownBy(() -> sut.export(member))
                .isInstanceOf(com.imin.iminapi.security.ApiException.class);

        verify(service, never()).write(any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** The one persisted export row for the org, by this actor, with this summary. */
    private String assertExportAudited(AuthPrincipal actor, String summary) {
        var row = audit.assertRecorded(actor.orgId(), AuditActions.CONSENT_EXPORTED, "organization", actor.orgId());
        assertThat(row.getActorId()).isEqualTo(actor.userId());
        assertThat(row.getSummary()).isEqualTo(summary);
        return row.getSummary();
    }

    private String export(AuthPrincipal p) throws Exception {
        MvcResult started = mvc.perform(get(URL).with(auth(p)))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** RFC 4180 parse of the data rows (quoted cells may hold commas, quotes and line breaks). */
    private static List<String[]> rows(String body) {
        assertThat(body).startsWith(HEADER + "\r\n");
        String data = body.substring(HEADER.length() + 2);
        List<String[]> out = new java.util.ArrayList<>();
        List<String> cells = new java.util.ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < data.length() && data.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cell.toString()); cell.setLength(0);
            } else if (ch == '\r' && i + 1 < data.length() && data.charAt(i + 1) == '\n') {
                cells.add(cell.toString()); cell.setLength(0);
                out.add(cells.toArray(new String[0])); cells.clear(); i++;
            } else {
                cell.append(ch);
            }
        }
        return out;
    }

    private UUID seedMembership(UUID orgId, String normalizedEmail) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(normalizedEmail);
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setStatus("active");
        return memberships.save(m).getMembershipId();
    }

    private UUID membershipOf(UUID orgId, String normalizedEmail) {
        UUID consumerId = consumers.findByNormalizedEmail(normalizedEmail).orElseThrow().getConsumerId();
        return memberships.findByOrgIdAndConsumerId(orgId, consumerId).orElseThrow().getMembershipId();
    }

    /** Returns the given instants in order, then repeats the last one. */
    private static Clock steppingClock(Instant... instants) {
        java.util.concurrent.atomic.AtomicInteger i = new java.util.concurrent.atomic.AtomicInteger();
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                return instants[Math.min(i.getAndIncrement(), instants.length - 1)];
            }
        };
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private static AuthPrincipal principal(UUID orgId, UserRole role) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, role, UUID.randomUUID());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
