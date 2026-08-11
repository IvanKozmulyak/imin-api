package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.dto.publicapi.NotifySubscriptionRequest;
import com.imin.iminapi.dto.publicapi.NotifySubscriptionResponse;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.event.NotifySubscriptionService;
import com.imin.iminapi.service.event.PublicEventService;
import com.imin.iminapi.service.event.QuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-IP rate limit on {@code POST /api/v1/public/events/{id}/notify}.
 *
 * <p>The route is unauthenticated and every row it writes later earns a real outbound
 * email from {@code NotifyReleaseSender}, so the limiter must fire BEFORE the service
 * is reached — otherwise a loop fills {@code notify_subscriptions} with fake addresses.
 * Same bucket/keying shape as the public checkout limiter.
 */
class NotifyRateLimitTest {

    private final PublicEventService publicEventService = mock(PublicEventService.class);
    private final NotifySubscriptionService notifyService = mock(NotifySubscriptionService.class);
    private final QuoteService quoteService = mock(QuoteService.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final PublicEventController controller = new PublicEventController(
            publicEventService, notifyService, quoteService, rateLimiter);

    private final UUID eventId = UUID.randomUUID();

    private MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr(ip);
        return http;
    }

    @Test
    void consumesNotifySubscribeBucketKeyedByClientIpThenDelegates() {
        NotifySubscriptionRequest body = new NotifySubscriptionRequest("ada@example.com");
        when(notifyService.subscribe(eq(eventId), eq(body))).thenReturn(NotifySubscriptionResponse.ok());

        var res = controller.notify(eventId, body, requestFrom("203.0.113.7"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(rateLimiter).consume("notify-subscribe", "ip:203.0.113.7");
        verify(notifyService).subscribe(eventId, body);
    }

    @Test
    void rateLimitedRequestNeverReachesTheService() {
        doThrow(ApiException.rateLimited())
                .when(rateLimiter).consume(eq("notify-subscribe"), eq("ip:203.0.113.7"));

        assertThatThrownBy(() -> controller.notify(
                eventId, new NotifySubscriptionRequest("ada@example.com"), requestFrom("203.0.113.7")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.RATE_LIMITED);
                });

        verify(notifyService, never()).subscribe(any(), any());
    }

    @Test
    void limitIsPerIpSoOneSpammerDoesNotBlockOtherBuyers() {
        doThrow(ApiException.rateLimited())
                .when(rateLimiter).consume(eq("notify-subscribe"), eq("ip:203.0.113.7"));
        NotifySubscriptionRequest body = new NotifySubscriptionRequest("bob@example.com");
        when(notifyService.subscribe(eq(eventId), eq(body))).thenReturn(NotifySubscriptionResponse.ok());

        // A different client IP is a different bucket key and still gets through.
        var res = controller.notify(eventId, body, requestFrom("198.51.100.42"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(rateLimiter).consume("notify-subscribe", "ip:198.51.100.42");
    }

    @Test
    void malformedBodyIsStillCharged() {
        // The limiter runs before validation on purpose: a loop posting garbage bodies is
        // exactly the traffic we want to shed, and refunding those attempts would let it
        // probe for free.
        when(notifyService.subscribe(eq(eventId), eq(null)))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid request body"));

        assertThatThrownBy(() -> controller.notify(eventId, null, requestFrom("203.0.113.9")))
                .isInstanceOf(ApiException.class);

        verify(rateLimiter).consume("notify-subscribe", "ip:203.0.113.9");
    }
}
