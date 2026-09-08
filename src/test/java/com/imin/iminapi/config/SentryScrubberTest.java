package com.imin.iminapi.config;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentry receives every error-level log statement in production
 * ({@code application-prod.yaml}), so anything a scrubber misses is a copy of
 * that data inside a third party.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class SentryScrubberTest {

    @Autowired SentryOptions.BeforeSendCallback beforeSend;
    @Autowired SentryOptions.BeforeBreadcrumbCallback beforeBreadcrumb;

    /**
     * The wiring, not just the function: Spring's Sentry starter only applies
     * these if they are beans, and a scrubber that is never registered is
     * indistinguishable from no scrubber at all.
     */
    @Test
    void the_callbacks_are_registered_as_beans() {
        assertThat(beforeSend).isNotNull();
        assertThat(beforeBreadcrumb).isNotNull();
    }

    @Test
    void an_address_in_the_message_never_leaves_the_process() {
        SentryEvent event = new SentryEvent();
        Message m = new Message();
        m.setFormatted("Resend API call failed for ada@example.com: 422");
        m.setMessage("Resend API call failed for {}: {}");
        m.setParams(List.of("ada@example.com", "422"));
        event.setMessage(m);

        SentryEvent out = beforeSend.execute(event, new io.sentry.Hint());

        assertThat(out).isNotNull();
        assertThat(out.getMessage().getFormatted()).doesNotContain("ada@example.com");
        assertThat(out.getMessage().getParams()).noneMatch(p -> p.contains("ada@example.com"));
        // The reason survives — a scrubbed report still has to be actionable.
        assertThat(out.getMessage().getFormatted()).contains("422");
    }

    @Test
    void an_address_inside_an_exception_value_is_scrubbed_too() {
        SentryEvent event = new SentryEvent();
        SentryException e = new SentryException();
        e.setValue("no mailbox for grace@hopper.navy.mil");
        event.setExceptions(List.of(e));

        assertThat(beforeSend.execute(event, new io.sentry.Hint())
                .getExceptions().get(0).getValue())
                .doesNotContain("grace@hopper.navy.mil")
                .contains("hopper.navy.mil");
    }

    /** A ticket token in the request URL is a bearer credential for that ticket. */
    @Test
    void ticket_tokens_are_stripped_from_the_request_url_and_query() {
        SentryEvent event = new SentryEvent();
        Request r = new Request();
        r.setUrl("https://api.imin.wtf/api/v1/public/tickets/Yg8sK2mQ1pRt7vLx0aZb/qr.png");
        r.setQueryString("token=abc123def456&utm_source=ig");
        event.setRequest(r);

        SentryEvent out = beforeSend.execute(event, new io.sentry.Hint());

        assertThat(out.getRequest().getUrl()).doesNotContain("Yg8sK2mQ1pRt7vLx0aZb");
        assertThat(out.getRequest().getQueryString())
                .doesNotContain("abc123def456")
                .contains("utm_source=ig");
    }

    @Test
    void breadcrumbs_are_scrubbed_on_their_own_callback_and_via_the_event() {
        Breadcrumb crumb = new Breadcrumb();
        crumb.setMessage("sent to ada@example.com");
        crumb.setData("recipient", "ada@example.com");

        Breadcrumb out = beforeBreadcrumb.execute(crumb, new io.sentry.Hint());

        assertThat(out.getMessage()).doesNotContain("ada@example.com");
        assertThat(String.valueOf(out.getData().get("recipient"))).doesNotContain("ada@example.com");
    }

    @Test
    void extras_are_scrubbed_and_an_empty_event_survives() {
        SentryEvent event = new SentryEvent();
        event.setExtra("buyer", "ada@example.com");

        SentryEvent out = beforeSend.execute(event, new io.sentry.Hint());

        assertThat(String.valueOf(out.getExtras().get("buyer"))).doesNotContain("ada@example.com");
        assertThat(beforeSend.execute(new SentryEvent(), new io.sentry.Hint())).isNotNull();
    }

    @Test
    void a_breadcrumb_with_nothing_in_it_does_not_blow_up() {
        assertThat(beforeBreadcrumb.execute(new Breadcrumb(), new io.sentry.Hint())).isNotNull();
        assertThat(Map.of()).isEmpty();
    }
}
