package com.imin.iminapi.audience;

import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.service.AudienceBackfillJob;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.Mockito.*;

/**
 * audience-16: the ApplicationReadyEvent listener called run() as a plain in-bean call, so
 * the ShedLock proxy carrying @SchedulerLock("audience_backfill") was bypassed and the
 * startup pass ran unlocked on every replica.
 */
class AudienceBackfillStartupTest {

    @Test
    @SuppressWarnings("unchecked")
    void startup_run_goes_through_the_proxy_so_the_scheduler_lock_applies() {
        AudienceBackfillJob proxied = mock(AudienceBackfillJob.class);
        ObjectProvider<AudienceBackfillJob> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(proxied);

        AudienceBackfillJob job = new AudienceBackfillJob(
                mock(OrderRepository.class),
                mock(AudienceOrderProjector.class),
                mock(ErasedAddressRepository.class),
                self,
                e -> { });

        job.onStartup();

        // The locked run() on the Spring-exposed bean, not this instance's own method body.
        verify(proxied).run();
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_publishesCompletionWithItsCounts_soTheFanFeatureRecomputeChains() {
        OrderRepository orders = mock(OrderRepository.class);
        java.util.UUID orgId = java.util.UUID.randomUUID();
        when(orders.findDistinctOrgAndEmailPairs()).thenReturn(java.util.List.of(
                new Object[]{orgId, "kept@example.com"}, new Object[]{orgId, "gone@example.com"}));
        ErasedAddressRepository erased = mock(ErasedAddressRepository.class);
        com.imin.iminapi.audience.model.ErasedAddress gone = new com.imin.iminapi.audience.model.ErasedAddress();
        gone.setEmailNormalized("gone@example.com");
        when(erased.findAllEntries()).thenReturn(java.util.List.of(gone));
        java.util.List<Object> published = new java.util.ArrayList<>();

        new AudienceBackfillJob(orders, mock(AudienceOrderProjector.class), erased,
                mock(ObjectProvider.class), published::add).run();

        org.assertj.core.api.Assertions.assertThat(published)
                .containsExactly(new com.imin.iminapi.audience.service.AudienceBackfillCompleted(1, 1));
    }
}
