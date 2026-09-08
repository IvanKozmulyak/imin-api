package com.imin.iminapi.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one helper every log line that touches a recipient must go through.
 *
 * <p>{@code application-prod.yaml} ships error-level logs to Sentry, so a
 * plaintext address in a {@code log.error} is not "a line in a file we control"
 * — it is a copy of the buyer's address in a third-party system, written on
 * every sale ({@code TicketIssuanceEmailer}) and every nightly audience pass.
 */
class LogSafeTest {

    @Test
    void email_keeps_the_domain_and_a_stable_correlator_but_not_the_local_part() {
        String masked = LogSafe.email("Ada.Lovelace@Example.com");

        assertThat(masked).doesNotContain("Ada.Lovelace").doesNotContain("ada.lovelace");
        assertThat(masked).contains("example.com");
        // Same address ⇒ same correlator, so a support thread is still followable.
        assertThat(masked).isEqualTo(LogSafe.email("ada.lovelace@example.com"));
        // Different address ⇒ different correlator.
        assertThat(masked).isNotEqualTo(LogSafe.email("grace@example.com"));
    }

    @Test
    void email_handles_null_and_junk_without_throwing() {
        assertThat(LogSafe.email(null)).isEqualTo("<none>");
        assertThat(LogSafe.email("")).isEqualTo("<none>");
        assertThat(LogSafe.email("not-an-address")).doesNotContain("not-an-address");
    }

    @Test
    void phone_keeps_only_the_country_prefix_and_the_last_two_digits() {
        assertThat(LogSafe.phone("+34612345678")).isEqualTo("+34***78");
        assertThat(LogSafe.phone(null)).isEqualTo("***");
    }

    /**
     * The Bird client logs the provider's raw error body, which echoes back the
     * destination number — defeating the mask two lines above it.
     */
    @Test
    void redact_scrubs_addresses_and_phone_numbers_out_of_free_text() {
        String body = "{\"error\":\"unroutable\",\"to\":\"+34612345678\",\"contact\":\"ada@example.com\"}";
        String scrubbed = LogSafe.redact(body);

        assertThat(scrubbed).doesNotContain("+34612345678").doesNotContain("ada@example.com");
        assertThat(scrubbed).contains("unroutable");
    }

    /** A ticket token in a URL is a bearer credential — it opens the ticket. */
    @Test
    void redact_scrubs_bearer_tokens_out_of_urls() {
        String scrubbed = LogSafe.redact(
                "GET https://api.imin.wtf/api/v1/public/tickets/Yg8sK2mQ1pRt7vLx0aZb/qr.png?token=abc123def456");

        assertThat(scrubbed).doesNotContain("Yg8sK2mQ1pRt7vLx0aZb").doesNotContain("abc123def456");
        assertThat(scrubbed).contains("api.imin.wtf");
    }

    /**
     * Sentry hands the query string over without the leading {@code ?}, so the
     * first parameter has no separator in front of it.
     */
    @Test
    void redact_scrubs_the_first_query_parameter_too() {
        String scrubbed = LogSafe.redact("token=abc123def456&utm_source=ig");

        assertThat(scrubbed).doesNotContain("abc123def456").contains("utm_source=ig");
    }

    @Test
    void redact_is_null_safe() {
        assertThat(LogSafe.redact(null)).isNull();
    }
}
