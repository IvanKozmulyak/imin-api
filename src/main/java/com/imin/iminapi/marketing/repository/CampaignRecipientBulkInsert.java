package com.imin.iminapi.marketing.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Writes a campaign's recipient snapshot one statement per {@link #BATCH} rows. A JPA save per row costs a
 * SELECT and an INSERT, because {@code CampaignRecipient} assigns its own id.
 */
@Repository
public class CampaignRecipientBulkInsert {

    static final int BATCH = 1000;

    private static final String SQL = """
            INSERT INTO campaign_recipients (id, campaign_id, membership_id, email, status, skip_reason, last_event_at)
            SELECT r.id, ?, r.membership_id, r.email, r.status, r.skip_reason, ?
              FROM unnest(?::uuid[], ?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[])
                   AS r(id, membership_id, email, status, skip_reason)
            ON CONFLICT (campaign_id, membership_id) DO NOTHING
            """;

    /** One snapshot row; {@code skipReason} is null for a pending row. */
    public record Row(UUID membershipId, String email, String status, String skipReason) {}

    private final JdbcTemplate jdbc;

    public CampaignRecipientBulkInsert(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Joins the caller's transaction; every row gets a new id and {@code lastEventAt}. */
    public void insert(UUID campaignId, Instant lastEventAt, List<Row> rows) {
        for (int from = 0; from < rows.size(); from += BATCH) {
            List<Row> chunk = rows.subList(from, Math.min(from + BATCH, rows.size()));
            int n = chunk.size();
            UUID[] ids = new UUID[n];
            UUID[] memberships = new UUID[n];
            String[] emails = new String[n];
            String[] statuses = new String[n];
            String[] reasons = new String[n];
            for (int i = 0; i < n; i++) {
                Row r = chunk.get(i);
                ids[i] = UUID.randomUUID();
                memberships[i] = r.membershipId();
                emails[i] = r.email();
                statuses[i] = r.status();
                reasons[i] = r.skipReason();
            }
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(SQL);
                ps.setObject(1, campaignId);
                ps.setObject(2, OffsetDateTime.ofInstant(lastEventAt, ZoneOffset.UTC));
                Array[] arrays = {
                        con.createArrayOf("uuid", ids),
                        con.createArrayOf("uuid", memberships),
                        con.createArrayOf("varchar", emails),
                        con.createArrayOf("varchar", statuses),
                        con.createArrayOf("varchar", reasons)};
                for (int i = 0; i < arrays.length; i++) ps.setArray(3 + i, arrays[i]);
                return ps;
            });
        }
    }
}
