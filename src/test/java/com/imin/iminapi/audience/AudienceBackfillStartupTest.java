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
                self);

        job.onStartup();

        // The locked run() on the Spring-exposed bean, not this instance's own method body.
        verify(proxied).run();
    }
}
