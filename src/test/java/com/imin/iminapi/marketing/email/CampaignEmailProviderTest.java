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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

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
     * resend-java throws plain RuntimeExceptions, so every failure must be classified for the sender's backoff:
     * a 4xx other than 429 is terminal for that batch, anything else (unrecognised included) is retryable.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("providerFailures")
    void everyProviderFailureIsAClassifiedApiException(String name, RuntimeException thrown, boolean terminal)
            throws Exception {
        Throwable t = thrownBy(thrown);

        assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        });
        assertThat(t instanceof CampaignEmailProvider.TerminalBatchFailure).isEqualTo(terminal);
    }

    static Stream<Arguments> providerFailures() {
        return Stream.of(
                Arguments.of("rate limit", new RuntimeException(
                        "Failed to send batch emails: 429 {\"message\":\"Too many requests\"}"), false),
                Arguments.of("server error", new RuntimeException("Failed to send batch emails: 503 upstream down"), false),
                // HttpClient.perform wraps every IOException in a bare RuntimeException.
                Arguments.of("wrapped I/O", new RuntimeException(new IOException("connection reset")), false),
                Arguments.of("422", new RuntimeException(
                        "Failed to send batch emails: 422 {\"message\":\"Invalid `to` field\"}"), true),
                Arguments.of("401", new RuntimeException("Failed to send batch emails: 401 unauthorized"), true),
                Arguments.of("unclassifiable", new RuntimeException("something nobody has seen before"), false));
    }

    private static List<CampaignEmailProvider.OutgoingEmail> oneEmail() {
        return List.of(new CampaignEmailProvider.OutgoingEmail("f", "t@x", "s", "h", "t", "u"));
    }

    private static Throwable thrownBy(RuntimeException providerFailure) throws Exception {
        Resend resend = mock(Resend.class);
        Batch batch = mock(Batch.class);
        when(resend.batch()).thenReturn(batch);
        when(batch.send(anyList())).thenThrow(providerFailure);
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

    // ---- AI Act Art.50(2) marker (ADR-0005) ----

    @SuppressWarnings("unchecked")
    private static List<CreateEmailOptions> sentOptions(List<CampaignEmailProvider.OutgoingEmail> in) throws Exception {
        Resend resend = mock(Resend.class);
        Batch batch = mock(Batch.class);
        when(resend.batch()).thenReturn(batch);
        org.mockito.ArgumentCaptor<List<CreateEmailOptions>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        when(batch.send(captor.capture())).thenReturn(new CreateBatchEmailsResponse(List.of(new BatchEmail("m"))));
        new CampaignEmailProvider(resend).sendBatch(in);
        return captor.getValue();
    }

    @Test
    void aiGeneratedEmail_carriesTheDisclosureHeaders_besideTheUnsubscribeHeaders() throws Exception {
        List<CreateEmailOptions> sent = sentOptions(List.of(new CampaignEmailProvider.OutgoingEmail(
                "f", "a@x", "s", "<p>h</p>", "t", "https://u",
                new com.imin.iminapi.service.ai.provenance.AiEmailDisclosure(true, true))));

        assertThat(sent.get(0).getHeaders())
                .containsEntry("List-Unsubscribe", "<https://u>")
                .containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
                .containsEntry("AI-Disclosure", "mode=ai-originated")
                .containsEntry("X-IMIN-AI-Generated", "subject, body");
    }

    @Test
    void humanWrittenEmail_hasOnlyTheUnsubscribeHeaders() throws Exception {
        List<CreateEmailOptions> sent = sentOptions(List.of(new CampaignEmailProvider.OutgoingEmail(
                "f", "a@x", "s", "<p>h</p>", "t", "https://u")));

        assertThat(sent.get(0).getHeaders()).containsOnlyKeys("List-Unsubscribe", "List-Unsubscribe-Post");
    }

    @Test
    void nullDisclosure_isTreatedAsNone() throws Exception {
        List<CreateEmailOptions> sent = sentOptions(List.of(new CampaignEmailProvider.OutgoingEmail(
                "f", "a@x", "s", "<p>h</p>", "t", "https://u", null)));

        assertThat(sent.get(0).getHeaders()).containsOnlyKeys("List-Unsubscribe", "List-Unsubscribe-Post");
    }
}
