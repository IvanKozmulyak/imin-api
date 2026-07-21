package com.imin.iminapi.marketing.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link SmsSender}: dry-run mode, the GDPR consent refusal,
 * FR STOP-mention composition, and the live-send mapping — all without a Spring context.
 */
class SmsSenderTest {

    private final SmsProperties props = mock(SmsProperties.class);
    private final SmsConsentGate gate = mock(SmsConsentGate.class);
    private final BirdSmsClient client = mock(BirdSmsClient.class);
    private final SmsSender sender = new SmsSender(props, gate, client);

    private static final String PHONE = "+33612345678";

    @Test
    void dryRunWhenDisabled_recordsSentDryRunAndNeverCallsProvider() {
        when(props.isEnabled()).thenReturn(false);          // blank api key => dry-run
        when(props.getSenderId()).thenReturn("IMIN");
        when(gate.canSendMarketing(PHONE)).thenReturn(true);

        SmsSender.Outcome out = sender.send(PHONE, "Doors open at 8pm");

        assertThat(out.status()).isEqualTo(SmsSender.Status.SENT_DRY_RUN);
        assertThat(out.phoneE164()).isEqualTo(PHONE);
        verifyNoInteractions(client);                        // provider must NOT be touched in dry-run
    }

    @Test
    void refusesNumberWithoutConsent_andNeverCallsProvider() {
        when(props.isEnabled()).thenReturn(false);
        when(gate.canSendMarketing(PHONE)).thenReturn(false); // no explicit SMS consent

        SmsSender.Outcome out = sender.send(PHONE, "Doors open at 8pm");

        assertThat(out.status()).isEqualTo(SmsSender.Status.REFUSED_NO_CONSENT);
        verifyNoInteractions(client);
    }

    @Test
    void invalidNumberIsRejectedBeforeConsentCheck() {
        SmsSender.Outcome out = sender.send("not-a-phone", "hi");

        assertThat(out.status()).isEqualTo(SmsSender.Status.INVALID_NUMBER);
        verifyNoInteractions(client, gate);
    }

    @Test
    void liveSendAppendsStopMentionAndMapsAcceptedResult() {
        when(props.isEnabled()).thenReturn(true);
        when(props.getSenderId()).thenReturn("IMIN");
        when(gate.canSendMarketing(PHONE)).thenReturn(true);
        when(client.send(eq("IMIN"), eq(PHONE), anyString()))
                .thenReturn(BirdSmsClient.Result.accepted("bird-msg-1"));

        SmsSender.Outcome out = sender.send(PHONE, "Doors open at 8pm");

        assertThat(out.status()).isEqualTo(SmsSender.Status.SENT);
        assertThat(out.providerMessageId()).isEqualTo("bird-msg-1");
        // FR compliance: the body actually sent must carry the mandatory opt-out.
        verify(client).send(eq("IMIN"), eq(PHONE),
                org.mockito.ArgumentMatchers.contains("STOP au 36180"));
    }

    @Test
    void stopMentionAppendedOnlyWhenAbsent() {
        assertThat(SmsSender.withStopMention("Doors open at 8pm"))
                .endsWith(SmsSender.FR_STOP_MENTION);
        // Already carries a STOP instruction — not doubled.
        String withStop = "Sale ends! STOP au 36180";
        assertThat(SmsSender.withStopMention(withStop)).isEqualTo(withStop);
    }
}
