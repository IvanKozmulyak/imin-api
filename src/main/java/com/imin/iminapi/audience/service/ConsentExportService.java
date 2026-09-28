package com.imin.iminapi.audience.service;

import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.RoleGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every consent record an organizer holds, as CSV (rule 9: consent is exportable per organizer).
 *
 * <p>Rows are streamed from one cursor straight to the writer, so memory does not grow with the
 * org. Records of {@code erase_pending} memberships are left out; an erased membership's records
 * are already gone (FK cascade).
 */
@Service
public class ConsentExportService {

    public static final String HEADER = "record_id,captured_at,email,channel,status,basis,source,"
            + "text_version,order_id,proof_text,import_id,import_row,source_platform,export_date,proof_ref,"
            + "confirmation_required,confirmed_at";

    private static final int FETCH_SIZE = 500;

    /** Per-statement bound, in seconds, on the database side of the export. */
    static final int QUERY_TIMEOUT_SECONDS = 60;

    // Whole-download bound, checked between rows; a write stuck on a stalled client
    // is ended by spring.mvc.async.request-timeout instead.
    static final Duration MAX_DURATION = Duration.ofMinutes(10);

    private static final String SQL = """
            SELECT c.id, c.occurred_at, cs.normalized_email, c.channel, c.status, c.lawful_basis,
                   c.source, c.text_version, c.order_id, c.proof_text, c.confirmation_required, c.confirmed_at,
                   p.import_id, p.row_number AS import_row, p.source_platform, p.export_date, p.proof_ref
              FROM consent_records c
              JOIN memberships m ON m.membership_id = c.membership_id
              JOIN consumers cs ON cs.consumer_id = m.consumer_id
              LEFT JOIN import_row_provenance p ON p.consent_record_id = c.id
             WHERE m.org_id = ?
               AND m.status = 'active'
             ORDER BY c.occurred_at, c.id
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public ConsentExportService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** OWNER or ADMIN; MEMBER and gate devices get 403. */
    public void requirePrivileged(AuthPrincipal principal) {
        RoleGuard.requireAtLeast(principal, UserRole.ADMIN, "export consent records");
    }

    /**
     * Writes the header and one line per record of {@code orgId}, counting data rows into
     * {@code rows} as they are written so a caller can report a partial export. Throws
     * {@link ExportTimedOutException} once {@link #MAX_DURATION} has passed.
     */
    public void write(UUID orgId, Writer out, AtomicLong rows) {
        Instant deadline = clock.instant().plus(MAX_DURATION);
        // PostgreSQL only honours the fetch size (a server-side cursor) with autocommit off.
        readOnly.executeWithoutResult(status -> {
            try {
                out.write(HEADER);
                out.write("\r\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            jdbc.query(con -> {
                var ps = con.prepareStatement(SQL);
                ps.setFetchSize(FETCH_SIZE);
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                ps.setObject(1, orgId);
                return ps;
            }, (ResultSet rs) -> {
                if (!clock.instant().isBefore(deadline)) {
                    throw new ExportTimedOutException(MAX_DURATION);
                }
                try {
                    writeRow(rs, out);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                rows.incrementAndGet();
            });
        });
    }

    /** The download ran past {@link #MAX_DURATION}; rows already written did leave. */
    public static class ExportTimedOutException extends RuntimeException {
        ExportTimedOutException(Duration limit) {
            super("Consent export exceeded " + limit);
        }
    }

    private static void writeRow(ResultSet rs, Writer out) throws SQLException, IOException {
        Timestamp at = rs.getTimestamp("occurred_at");
        LocalDate exportDate = rs.getObject("export_date", LocalDate.class);
        Object importRow = rs.getObject("import_row");
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        String[] cells = {
                str(rs.getObject("id")),
                at == null ? null : at.toInstant().toString(),
                rs.getString("normalized_email"),
                rs.getString("channel"),
                rs.getString("status"),
                rs.getString("lawful_basis"),
                rs.getString("source"),
                rs.getString("text_version"),
                str(rs.getObject("order_id")),
                rs.getString("proof_text"),
                str(rs.getObject("import_id")),
                str(importRow),
                rs.getString("source_platform"),
                exportDate == null ? null : exportDate.toString(),
                rs.getString("proof_ref"),
                // A door QR / survey grant counts only once confirmed; an empty confirmed_at with true is pending.
                String.valueOf(rs.getBoolean("confirmation_required")),
                confirmedAt == null ? null : confirmedAt.toInstant().toString(),
        };
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.write(',');
            out.write(csv(cells[i]));
        }
        out.write("\r\n");
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** RFC 4180 quoting plus the spreadsheet-formula guard used by the attendee export. */
    static String csv(String v) {
        if (v == null) return "";
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
