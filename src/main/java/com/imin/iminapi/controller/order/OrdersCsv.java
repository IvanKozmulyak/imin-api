package com.imin.iminapi.controller.order;

import com.imin.iminapi.controller.order.dto.OrderRowResponse;
import com.imin.iminapi.util.CsvCell;
import com.imin.iminapi.util.MoneyFormat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** The Orders-tab CSV: the same rows the list renders, one line per order, never a buyer name. */
final class OrdersCsv {

    static final String HEADER =
            "order_ref,order_id,buyer_email,created_at,status,tickets,tickets_refunded,total,currency,promo_code";

    private OrdersCsv() {}

    static String write(List<OrderRowResponse> rows) {
        StringBuilder sb = new StringBuilder(HEADER).append("\r\n");
        for (OrderRowResponse r : rows) {
            sb.append(CsvCell.escape(r.shortCode())).append(',')
              .append(r.id()).append(',')
              .append(CsvCell.escape(r.email())).append(',')
              .append(r.createdAt() == null ? "" : r.createdAt().toString()).append(',')
              .append(r.status()).append(',')
              .append(r.ticketCount()).append(',')
              .append(r.refundedTicketCount()).append(',')
              .append(major(r.totalMinor(), r.currency())).append(',')
              .append(r.currency() == null ? "" : CsvCell.escape(r.currency().toUpperCase(Locale.ROOT))).append(',')
              .append(CsvCell.escape(r.promoCode()))
              .append("\r\n");
        }
        return sb.toString();
    }

    private static String major(long minor, String currency) {
        if (MoneyFormat.isZeroDecimal(currency)) return Long.toString(minor);
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
