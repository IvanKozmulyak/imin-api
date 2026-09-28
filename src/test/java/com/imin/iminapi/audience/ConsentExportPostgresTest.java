package com.imin.iminapi.audience;

import com.imin.iminapi.audience.service.ConsentExportService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.StringWriter;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** V150 backfills the provenance key from the proof-text prefix, and the export joins on it, on real Postgres. */
@Testcontainers(disabledWithoutDocker = true)
class ConsentExportPostgresTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void v150_backfillsTheRealImportRecordOnly_andTheExportJoinsOnIt() {
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        flyway(ds, "145").migrate();

        UUID orgId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        UUID realRecord = UUID.randomUUID();
        UUID forgedRecord = UUID.randomUUID();
        UUID acceptedRow = UUID.randomUUID();
        UUID rejectedRow = UUID.randomUUID();
        jdbc.update("INSERT INTO consumers (consumer_id, normalized_email) VALUES (?, ?)", consumerId, "pg@example.com");
        jdbc.update("INSERT INTO memberships (membership_id, org_id, consumer_id) VALUES (?, ?, ?)",
                membershipId, orgId, consumerId);
        jdbc.update("INSERT INTO audience_imports (id, org_id) VALUES (?, ?)", importId, orgId);
        jdbc.update("""
                INSERT INTO consent_records (id, membership_id, status, lawful_basis, source, proof_text, occurred_at)
                VALUES (?, ?, 'subscribed', 'explicit', 'organizer_import_row', ?, TIMESTAMPTZ '2026-09-01 10:00:00+00')
                """, realRecord, membershipId,
                "Per-row consent proof from CSV import " + importId + " row 7: marketing_status=opted_in");
        // Same source on the same membership, but its proof names no import.
        jdbc.update("""
                INSERT INTO consent_records (id, membership_id, status, lawful_basis, source, proof_text, occurred_at)
                VALUES (?, ?, 'subscribed', 'explicit', 'organizer_import_row', 'typed by hand', TIMESTAMPTZ '2026-09-02 10:00:00+00')
                """, forgedRecord, membershipId);
        jdbc.update("""
                INSERT INTO import_row_provenance (id, import_id, membership_id, row_number, source_platform,
                                                   export_date, marketing_status, proof_ref, accepted)
                VALUES (?, ?, ?, 7, 'shotgun', DATE '2026-09-01', 'opted_in', 'ref-7', TRUE)
                """, acceptedRow, importId, membershipId);
        // A rejected row for the same import is never keyed, even if a prefix happened to match.
        jdbc.update("""
                INSERT INTO import_row_provenance (id, import_id, membership_id, row_number,
                                                   marketing_status, accepted, reject_reason)
                VALUES (?, ?, ?, 7, 'none', FALSE, 'not_opted_in')
                """, rejectedRow, importId, membershipId);

        flyway(ds, "latest").migrate();

        assertThat(jdbc.queryForObject("SELECT consent_record_id FROM import_row_provenance WHERE id = ?",
                UUID.class, acceptedRow)).isEqualTo(realRecord);
        assertThat(jdbc.queryForObject("SELECT consent_record_id FROM import_row_provenance WHERE id = ?",
                UUID.class, rejectedRow)).isNull();

        StringWriter out = new StringWriter();
        new ConsentExportService(jdbc, new DataSourceTransactionManager(ds), Clock.systemUTC())
                .write(orgId, out, new AtomicLong());
        String[] lines = out.toString().split("\r\n");

        assertThat(lines).hasSize(3);
        assertThat(lines[1]).startsWith(realRecord + ",")
                .endsWith("," + importId + ",7,shotgun,2026-09-01,ref-7,false,");
        assertThat(lines[2]).startsWith(forgedRecord + ",")
                .endsWith(",typed by hand,,,,,,false,");
    }

    private static Flyway flyway(DriverManagerDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .target(target).load();
    }
}
