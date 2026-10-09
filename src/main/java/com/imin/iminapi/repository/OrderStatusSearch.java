package com.imin.iminapi.repository;

import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.dispute.DisputeWithholding;
import com.imin.iminapi.model.Ticket;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The Orders-tab row status, defined once in SQL so the list, the filter, the detail and the
 * export cannot disagree, and a status filter runs before the row cap.
 *
 * <p>Tickets in {@code refunded} or {@code revoked} are no longer held: none → {@code paid}
 * (also an order with no tickets), all → {@code refunded}, some → {@code partially_refunded}.
 * A chargeback revokes the tickets, so any OPEN or LOST dispute on the order overrides that with
 * {@code disputed}; WON and WITHDRAWN_REINSTATED gave the money back and keep the ticket status.
 */
@Repository
public class OrderStatusSearch {

    public static final String PAID = "paid";
    public static final String PARTIALLY_REFUNDED = "partially_refunded";
    public static final String REFUNDED = "refunded";
    public static final String DISPUTED = "disputed";
    public static final Set<String> STATUSES = Set.of(PAID, PARTIALLY_REFUNDED, REFUNDED, DISPUTED);

    public record Hit(UUID orderId, String status) {}

    private static final String STATUS_CTE = """
            with s as (
              select o.id, o.created_at,
                case
                  when exists (select 1 from disputes d
                               where d.order_id = o.id and d.status in (:withholding)) then 'disputed'
                  when not exists (select 1 from tickets t
                                   where t.order_id = o.id and t.state in (:inactive)) then 'paid'
                  when exists (select 1 from tickets t
                               where t.order_id = o.id and t.state not in (:inactive)) then 'partially_refunded'
                  else 'refunded'
                end as status
              from orders o
              where o.event_id = :eventId
            """;

    // Separate strings, not a nullable :q, so Postgres never sees an untyped null in lower()/like.
    private static final String SEARCH = """
                and (lower(o.email) like '%' || :q || '%' escape '!'
                     or left(cast(o.id as text), 8) like :q || '%' escape '!')
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderStatusSearch(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Newest first. {@code status} and {@code q} null = no filter; {@code q} must already be trimmed
     * and non-blank when given. {@code limit} null = every match.
     */
    public List<Hit> find(UUID eventId, String status, String q, Integer limit) {
        MapSqlParameterSource params = baseParams(eventId);
        StringBuilder sql = new StringBuilder(STATUS_CTE);
        if (q != null) {
            sql.append(SEARCH);
            params.addValue("q", escapeLike(q.toLowerCase(Locale.ROOT)));
        }
        sql.append(") select id, status from s");
        if (status != null) {
            sql.append(" where status = :status");
            params.addValue("status", status);
        }
        sql.append(" order by created_at desc, id");
        if (limit != null) {
            sql.append(" limit :limit");
            params.addValue("limit", limit);
        }
        return jdbc.query(sql.toString(), params,
                (rs, i) -> new Hit(rs.getObject("id", UUID.class), rs.getString("status")));
    }

    /** The one order, only when it belongs to {@code eventId}. */
    public List<Hit> findOne(UUID eventId, UUID orderId) {
        MapSqlParameterSource params = baseParams(eventId).addValue("orderId", orderId);
        String sql = STATUS_CTE + " and o.id = :orderId) select id, status from s";
        return jdbc.query(sql, params,
                (rs, i) -> new Hit(rs.getObject("id", UUID.class), rs.getString("status")));
    }

    private static MapSqlParameterSource baseParams(UUID eventId) {
        return new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("withholding", DisputeWithholding.STATUSES.stream().map(DisputeStatus::toWire).toList())
                .addValue("inactive", List.of(Ticket.STATE_REFUNDED, Ticket.STATE_REVOKED));
    }

    // '!' is the query's ESCAPE character, so '%' and '_' typed by the organizer match literally.
    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
