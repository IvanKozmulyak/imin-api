package com.imin.iminapi.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code orders.email_normalized} is derived in {@code Order.onWrite()}, so it cannot drift from
 * {@code orders.email} through any entity write path. The one gap a JPA lifecycle callback cannot
 * cover is a bulk JPQL/native UPDATE, which bypasses callbacks entirely — and that column is the
 * join key {@code GET /buyer/orders} matches against {@code buyer_account_emails.email_normalized},
 * so a drifted row is an order the buyer who paid for it cannot see.
 *
 * <p>The javadoc on {@code Order.emailNormalized} used to guard this by asserting that
 * OrderRepository "has no @Modifying query at all". That stopped being true at V87
 * (stampReminder24h / stampReminder3h), and a reader checking the guarantee would have been
 * checking a fact that had already failed — while the property that actually matters (no bulk
 * update writes orders.email without setting email_normalized in the same statement) still held.
 * This test asserts the property instead of the fact, so the next bulk UPDATE added to that
 * repository is measured against something true.
 */
class OrderEmailNormalizedInvariantTest {

    /** {@code o.email = …}, but not {@code o.emailNormalized = …}. */
    private static final Pattern WRITES_EMAIL = Pattern.compile("\\.email\\s*=");
    private static final Pattern UPDATE_STATEMENT =
            Pattern.compile("update\\s+Order\\b.*?(?=\"\"\"|\")", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    void noBulkUpdateWritesOrdersEmailWithoutAlsoSettingEmailNormalized() throws IOException {
        String src = Files.readString(
                Path.of("src/main/java/com/imin/iminapi/repository/OrderRepository.java"),
                StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<>();
        Matcher m = UPDATE_STATEMENT.matcher(src);
        while (m.find()) {
            String stmt = m.group();
            if (WRITES_EMAIL.matcher(stmt).find() && !stmt.contains("emailNormalized")) {
                offenders.add(stmt.strip());
            }
        }

        assertThat(offenders)
                .as("""
                    These bulk UPDATE statements write orders.email without setting
                    email_normalized in the same statement. A JPQL/native UPDATE bypasses
                    Order.onWrite(), so the derived column drifts — and it is the join key
                    GET /buyer/orders uses, so the buyer who paid stops seeing the order.""")
                .isEmpty();
    }
}
