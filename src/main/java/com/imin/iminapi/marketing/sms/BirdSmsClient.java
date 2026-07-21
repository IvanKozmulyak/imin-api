package com.imin.iminapi.marketing.sms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Bird (MessageBird) outbound-SMS transport — the SOLE provider client.
 *
 * <p>ponytail: there is intentionally NO {@code SmsClient} interface. imin sends
 * through one aggregator (Bird — see {@code docs/research/sms-provider-decision.md}),
 * and "not sending" is a MODE ({@link SmsSender} dry-run), not a second
 * implementation. If/when a second provider (Vonage/Twilio) is added, THAT is the
 * moment to extract an interface here — {@code send(...)} is the seam. Adding the
 * abstraction now would be speculative generality.
 *
 * <p>Wire contract models the classic MessageBird REST {@code POST /messages}
 * ({@code Authorization: AccessKey <key>}). Bird's post-pivot API may instead be
 * {@code api.bird.com/workspaces/.../messages}; the base URL is configurable and
 * this shape must be reconciled against the live account at setup. This client is
 * ONLY invoked when {@link SmsProperties#isEnabled()} — in dry-run it is never called.
 *
 * <p>Error contract (matches the email sender): transport/5xx failure ⇒ throw
 * {@link ApiException} {@code UPSTREAM_UNAVAILABLE} (retryable); a per-message
 * provider rejection (4xx) ⇒ non-throwing {@link Result#rejected}.
 */
@Component
public class BirdSmsClient {

    private static final Logger log = LoggerFactory.getLogger(BirdSmsClient.class);

    private final SmsProperties props;
    private final RestClient http;

    public BirdSmsClient(SmsProperties props) {
        this.props = props;
        this.http = RestClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    /** Outcome of a single provider call: accepted (with a message id) or rejected (with a reason). */
    public record Result(boolean accepted, String providerMessageId, String rejectReason) {
        public static Result accepted(String id) { return new Result(true, id, null); }
        public static Result rejected(String reason) { return new Result(false, null, reason); }
    }

    /** MessageBird {@code /messages} response — only the id is needed downstream. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessagesResponse(String id) {}

    /**
     * POST one SMS. {@code from} is the alphanumeric sender ID; {@code toE164} a
     * single recipient; {@code body} the (already compliance-composed) text.
     *
     * @throws ApiException UPSTREAM_UNAVAILABLE on transport / 5xx (retryable)
     */
    public Result send(String from, String toE164, String body) {
        Map<String, Object> payload = Map.of(
                "originator", from,
                "recipients", List.of(toE164),
                "body", body);
        try {
            MessagesResponse resp = http.post()
                    .uri("/messages")
                    .header("Authorization", "AccessKey " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .body(MessagesResponse.class);
            String id = resp == null ? null : resp.id();
            log.info("[sms-bird] accepted to={} id={}", mask(toE164), id);
            return Result.accepted(id);
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                // Per-message rejection (bad number, unregistered sender, etc.) — do NOT retry.
                log.warn("[sms-bird] rejected to={} status={} body={}",
                        mask(toE164), e.getStatusCode(), e.getResponseBodyAsString());
                return Result.rejected("provider_" + e.getStatusCode().value());
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "SMS provider error", e);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "SMS provider unreachable", e);
        }
    }

    /** Log phones partially — never emit a full attendee number to logs. */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 5) return "***";
        return phone.substring(0, 3) + "***" + phone.substring(phone.length() - 2);
    }
}
