package com.imin.iminapi.marketing.email;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.batch.Batch;
import com.resend.services.batch.model.BatchEmail;
import com.resend.services.batch.model.CreateBatchEmailsResponse;
import com.resend.services.emails.model.CreateEmailOptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CampaignEmailProviderTest {

    @Test
    void mapsBatchResponseIdsBackByOrder() throws Exception {
        Resend resend = mock(Resend.class);
        Batch batch = mock(Batch.class);
        when(resend.batch()).thenReturn(batch);
        when(batch.send(anyList())).thenReturn(new CreateBatchEmailsResponse(
                List.of(new BatchEmail("msg-1"), new BatchEmail("msg-2"))));

        CampaignEmailProvider provider = new CampaignEmailProvider(resend);
        // The shape MarketingEmailProperties.unsubscribeUrl actually emits. These
        // fixtures carried https://app.imin.wtf/optout?token=… until 2026-08-16 —
        // a form production can no longer produce, and which 404'd for the whole
        // time it could. They passed anyway: this class only threads the string
        // through to the List-Unsubscribe header.
        List<CampaignEmailProvider.OutgoingEmail> batchInput = List.of(
                new CampaignEmailProvider.OutgoingEmail("from <a@x>", "a@x", "s", "<p>h</p>", "t",
                        "https://api.imin.wtf/api/v1/public/unsubscribe/A"),
                new CampaignEmailProvider.OutgoingEmail("from <a@x>", "b@x", "s", "<p>h</p>", "t",
                        "https://api.imin.wtf/api/v1/public/unsubscribe/B"));

        List<String> ids = provider.sendBatch(batchInput);
        assertThat(ids).containsExactly("msg-1", "msg-2");
    }

    @Test
    void rejectsBatchOver100() {
        CampaignEmailProvider provider = new CampaignEmailProvider(mock(Resend.class));
        List<CampaignEmailProvider.OutgoingEmail> tooBig = java.util.Collections.nCopies(101,
                new CampaignEmailProvider.OutgoingEmail("f", "t@x", "s", "h", "t", "u"));
        assertThatThrownBy(() -> provider.sendBatch(tooBig))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * mkt-edge-4 (P1): resend-java 4.1.0 never throws ResendException from Batch.send — a
     * non-2xx is a PLAIN RuntimeException ("Failed to send batch emails: <code> <body>") and
     * an IOException is wrapped in one. So `catch (ResendException)` was dead code and every
     * real 429/5xx/timeout escaped as an unchecked exception that EmailChannelSender's
     * `catch (ApiException)` backoff could not see: the REQUIRES_NEW batch transaction rolled
     * back (no attempt increment, no next_attempt_at) and CampaignDispatcher failed the whole
     * campaign. Every provider failure must now arrive as a classified ApiException.
     */
    @Test
    void aRateLimitIsRetryable() throws Exception {
        assertThat(thrownBy("Failed to send batch emails: 429 {\"message\":\"Too many requests\"}"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(CampaignEmailProvider.TerminalBatchFailure.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aProviderServerErrorIsRetryable() throws Exception {
        assertThat(thrownBy("Failed to send batch emails: 503 upstream down"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(CampaignEmailProvider.TerminalBatchFailure.class);
    }

    @Test
    void aWrappedIoExceptionIsRetryable() throws Exception {
        Resend resend = mock(Resend.class);
        Batch batch = mock(Batch.class);
        when(resend.batch()).thenReturn(batch);
        // HttpClient.perform wraps every IOException in a bare RuntimeException.
        when(batch.send(anyList())).thenThrow(new RuntimeException(new IOException("connection reset")));

        assertThatThrownBy(() -> new CampaignEmailProvider(resend).sendBatch(oneEmail()))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(CampaignEmailProvider.TerminalBatchFailure.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void aClientErrorIsTerminalForThatBatch() throws Exception {
        // 422/401 will fail identically on every retry — backing off three times just delays
        // the truth. The sender marks these rows failed so /retry is the recovery path.
        assertThat(thrownBy("Failed to send batch emails: 422 {\"message\":\"Invalid `to` field\"}"))
                .isInstanceOf(CampaignEmailProvider.TerminalBatchFailure.class);
        assertThat(thrownBy("Failed to send batch emails: 401 unauthorized"))
                .isInstanceOf(CampaignEmailProvider.TerminalBatchFailure.class);
    }

    /** An unrecognised failure is retryable: the attempt budget bounds it, silence does not. */
    @Test
    void anUnclassifiableFailureIsRetryable() throws Exception {
        assertThat(thrownBy("something nobody has seen before"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(CampaignEmailProvider.TerminalBatchFailure.class);
    }

    private static List<CampaignEmailProvider.OutgoingEmail> oneEmail() {
        return List.of(new CampaignEmailProvider.OutgoingEmail("f", "t@x", "s", "h", "t", "u"));
    }

    private static Throwable thrownBy(String providerMessage) throws Exception {
        Resend resend = mock(Resend.class);
        Batch batch = mock(Batch.class);
        when(resend.batch()).thenReturn(batch);
        when(batch.send(anyList())).thenThrow(new RuntimeException(providerMessage));
        CampaignEmailProvider provider = new CampaignEmailProvider(resend);
        try {
            provider.sendBatch(oneEmail());
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected sendBatch to throw");
    }

    @Test
    void mapsResendExceptionToApiException() throws Exception {
        Resend resend = mock(Resend.class);
        Batch batch = mock(Batch.class);
        when(resend.batch()).thenReturn(batch);
        when(batch.send(anyList())).thenThrow(new ResendException("down"));

        CampaignEmailProvider provider = new CampaignEmailProvider(resend);
        assertThatThrownBy(() -> provider.sendBatch(List.of(
                new CampaignEmailProvider.OutgoingEmail("f", "t@x", "s", "h", "t", "u"))))
                .isInstanceOf(ApiException.class);
    }
}
