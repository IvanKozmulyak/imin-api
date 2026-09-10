package com.imin.iminapi.config;

import com.imin.iminapi.util.LogSafe;
import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Last line of defence between imin's logs and Sentry.
 *
 * <h2>Why a callback and not just fixed call sites</h2>
 *
 * <p>{@code application-prod.yaml} ships every error-level log statement to
 * Sentry, so any address, phone number or ticket token that reaches a log line
 * reaches a third party. {@link LogSafe} fixes the call sites imin writes; this
 * fixes the ones it does not — a framework's exception message quoting a failed
 * SQL parameter, a Resend SDK error echoing the recipient, a request URL that
 * carries a ticket token as a path segment. Those arrive at Sentry through code
 * nobody here will remember to audit.
 *
 * <p>Spring's Sentry starter picks up {@link SentryOptions.BeforeSendCallback}
 * and {@link SentryOptions.BeforeBreadcrumbCallback} beans automatically, so
 * registering them is the whole wiring. The beans exist in every profile
 * deliberately: they are pure functions with no config, and having them only
 * where Sentry is enabled would mean the test suite could never see them.
 *
 * <p>Scrubbing is best-effort by construction — every branch is null-tolerant
 * and the event is always returned. Dropping a crash report because the scrubber
 * tripped over an unexpected shape would trade a privacy problem for a
 * blindness problem.
 */
@Configuration
public class SentryScrubberConfig {

    @Bean
    public SentryOptions.BeforeSendCallback iminPiiScrubber() {
        return (event, hint) -> scrub(event);
    }

    @Bean
    public SentryOptions.BeforeBreadcrumbCallback iminBreadcrumbScrubber() {
        return (breadcrumb, hint) -> scrub(breadcrumb);
    }

    /** Visible for testing — the callbacks are lambdas, this is the behaviour worth pinning. */
    static SentryEvent scrub(SentryEvent event) {
        if (event == null) return null;

        Message message = event.getMessage();
        if (message != null) {
            message.setFormatted(LogSafe.redact(message.getFormatted()));
            message.setMessage(LogSafe.redact(message.getMessage()));
            List<String> params = message.getParams();
            if (params != null) {
                List<String> clean = new ArrayList<>(params.size());
                for (String p : params) clean.add(LogSafe.redact(p));
                message.setParams(clean);
            }
        }

        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            for (SentryException e : exceptions) e.setValue(LogSafe.redact(e.getValue()));
        }

        Request request = event.getRequest();
        if (request != null) {
            request.setUrl(LogSafe.redact(request.getUrl()));
            request.setQueryString(LogSafe.redact(request.getQueryString()));
        }

        Map<String, Object> extras = event.getExtras();
        if (extras != null) {
            for (Map.Entry<String, Object> entry : extras.entrySet()) {
                if (entry.getValue() instanceof String s) entry.setValue(LogSafe.redact(s));
            }
        }

        List<Breadcrumb> crumbs = event.getBreadcrumbs();
        if (crumbs != null) {
            for (Breadcrumb c : crumbs) scrub(c);
        }
        return event;
    }

    /** Visible for testing. */
    static Breadcrumb scrub(Breadcrumb breadcrumb) {
        if (breadcrumb == null) return null;
        breadcrumb.setMessage(LogSafe.redact(breadcrumb.getMessage()));
        Map<String, Object> data = breadcrumb.getData();
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (entry.getValue() instanceof String s) entry.setValue(LogSafe.redact(s));
            }
        }
        return breadcrumb;
    }
}
