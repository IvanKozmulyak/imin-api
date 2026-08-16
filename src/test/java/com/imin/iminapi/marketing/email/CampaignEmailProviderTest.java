package com.imin.iminapi.marketing.email;

import com.imin.iminapi.security.ApiException;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.batch.Batch;
import com.resend.services.batch.model.BatchEmail;
import com.resend.services.batch.model.CreateBatchEmailsResponse;
import com.resend.services.emails.model.CreateEmailOptions;
import org.junit.jupiter.api.Test;

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
