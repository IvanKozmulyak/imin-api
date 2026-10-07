package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A sticky-write violation that is not a duplicate: the re-read finds no row. The database only raises
 * duplicates today, so this branch is driven with a mock; the real lost race is StickyMarketingOptOutIsolationTest.
 */
class ConsentServiceStickyRaceTest {

    @Test
    void aNonDuplicateViolation_isSettledByAReRead_andTheUnsubscribeStillCompletes() {
        MembershipRepository memberships = mock(MembershipRepository.class);
        ConsentRecordRepository consentRecords = mock(ConsentRecordRepository.class);
        ConsumerRepository consumers = mock(ConsumerRepository.class);
        MarketingOptOutRecorder recorder = mock(MarketingOptOutRecorder.class);
        ConsentService service = new ConsentService(memberships, consentRecords, consumers, recorder,
                mock(AuditLogger.class), mock(ApplicationEventPublisher.class), mock(FanFeatureRepository.class));

        UUID orgId = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        String email = "nodup-" + UUID.randomUUID() + "@example.com";
        Membership m = new Membership();
        m.setMembershipId(mid);
        m.setOrgId(orgId);
        m.setConsumerId(consumerId);
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        Consumer c = new Consumer();
        c.setConsumerId(consumerId);
        c.setNormalizedEmail(email);
        when(memberships.findByIdAndOrgId(mid, orgId)).thenReturn(Optional.of(m));
        when(consumers.findByConsumerId(consumerId)).thenReturn(Optional.of(c));
        doThrow(new DataIntegrityViolationException("check constraint")).when(recorder)
                .record(email, orgId, "email", "footer_link");
        when(recorder.exists(email, orgId, "email")).thenReturn(false);

        assertThatCode(() -> service.unsubscribe(orgId, mid, "footer_link", ConsentOrigin.DATA_SUBJECT, null))
                .doesNotThrowAnyException();

        ArgumentCaptor<Membership> saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).save(saved.capture());
        assertThat(saved.getValue().getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(saved.getValue().getConsentBasis()).isNull();
        ArgumentCaptor<ConsentRecord> proof = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecords, times(1)).save(proof.capture());
        assertThat(proof.getValue().getStatus()).isEqualTo("unsubscribed");
        assertThat(proof.getValue().getChannel()).isEqualTo("email");
        // The re-read, not the exception type, decides it was not the duplicate.
        verify(recorder).exists(email, orgId, "email");
    }
}
