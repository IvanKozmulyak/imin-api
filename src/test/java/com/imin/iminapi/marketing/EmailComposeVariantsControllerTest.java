package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.controller.EmailComposeVariantsController;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsRequest;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsResponse;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsResponse.EmailVariant;
import com.imin.iminapi.marketing.service.EmailComposeVariantsService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
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

class EmailComposeVariantsControllerTest {

    private final EmailComposeVariantsService service = mock(EmailComposeVariantsService.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final EmailComposeVariantsController controller =
            new EmailComposeVariantsController(service, rateLimiter);

    private final UUID userId = UUID.randomUUID();
    private final AuthPrincipal principal =
            new AuthPrincipal(userId, UUID.randomUUID(), UserRole.OWNER, UUID.randomUUID());

    @Test
    void consumesAiConceptBucketThenDelegates() {
        UUID campaignId = UUID.randomUUID();
        EmailComposeVariantsResponse expected = new EmailComposeVariantsResponse(
                List.of(new EmailVariant("S", "P", "B")));
        when(service.generate(eq(principal), eq(campaignId), eq(2), eq("early bird")))
                .thenReturn(expected);

        EmailComposeVariantsResponse res = controller.composeVariants(
                principal, campaignId, new EmailComposeVariantsRequest(2, "early bird"));

        assertThat(res).isSameAs(expected);
        verify(rateLimiter).consume("ai-concept", userId.toString());
    }

    @Test
    void rateLimitedRequestNeverReachesTheService() {
        UUID campaignId = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, "slow down"))
                .when(rateLimiter).consume(eq("ai-concept"), eq(userId.toString()));

        assertThatThrownBy(() -> controller.composeVariants(principal, campaignId, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verify(service, never()).generate(any(), any(), any(), any());
    }

    @Test
    void nullBodyPassesNullCountAndHint() {
        UUID campaignId = UUID.randomUUID();
        when(service.generate(eq(principal), eq(campaignId), eq(null), eq(null)))
                .thenReturn(new EmailComposeVariantsResponse(List.of()));

        controller.composeVariants(principal, campaignId, null);

        verify(service).generate(principal, campaignId, null, null);
    }
}
