package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The send drive re-checks the audience-plan sends switch before every batch. */
class CampaignSendUnitSendsSwitchTest {

    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final CampaignRecipientRepository recipients = mock(CampaignRecipientRepository.class);
    private final RecipientMaterializer materializer = mock(RecipientMaterializer.class);
    private final EmailChannelSender sender = mock(EmailChannelSender.class);
    private final AudiencePlanProperties props = new AudiencePlanProperties();
    private final CampaignSendUnit unit;

    CampaignSendUnitSendsSwitchTest() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // The row is still 'sending' when the drive finishes.
        when(campaigns.markSentIfSending(any(), any())).thenReturn(1);
        unit = new CampaignSendUnit(campaigns, recipients, materializer, sender,
                mock(ApplicationEventPublisher.class), tm, new AudiencePlanAccess(props), Clock.systemUTC());
    }

    private static Campaign campaign(String origin) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setStatus("sending");
        c.setOrigin(origin);
        return c;
    }

    @Test
    void switchedOffMidDrive_stopsBeforeNextBatch_andStaysSending() {
        props.setSendsEnabled(true);
        Campaign c = campaign("audience_plan");
        when(sender.sendNextBatch(c)).thenAnswer(inv -> {
            props.setSendsEnabled(false);
            return true;
        });

        unit.processOne(c);

        verify(sender, times(1)).sendNextBatch(c);
        verify(recipients, never()).failExhaustedPending(any(), anyShort(), anyString(), any());
        verify(campaigns, never()).save(any());
        verify(campaigns, never()).markSentIfSending(any(), any());
        assertThat(c.getStatus()).isEqualTo("sending");
        assertThat(c.getSentAt()).isNull();
    }

    @Test
    void switchedOffMidDrive_manualCampaignKeepsSending() {
        props.setSendsEnabled(true);
        Campaign c = campaign("manual");
        when(sender.sendNextBatch(c)).thenAnswer(inv -> {
            props.setSendsEnabled(false);
            return true;
        }).thenReturn(false);
        when(recipients.countRetryablePending(any(), anyShort())).thenReturn(0L);
        when(recipients.countByCampaignIdAndStatusIn(any(), any())).thenReturn(1L);

        unit.processOne(c);

        verify(sender, times(2)).sendNextBatch(c);
        assertThat(c.getStatus()).isEqualTo("sent");
    }

    @Test
    void sendsOn_audiencePlanDrainsAndFinishes() {
        props.setSendsEnabled(true);
        Campaign c = campaign("audience_plan");
        when(sender.sendNextBatch(c)).thenReturn(true, false);
        when(recipients.countRetryablePending(any(), anyShort())).thenReturn(0L);
        when(recipients.countByCampaignIdAndStatusIn(any(), any())).thenReturn(1L);

        unit.processOne(c);

        verify(sender, times(2)).sendNextBatch(c);
        assertThat(c.getStatus()).isEqualTo("sent");
    }
}
