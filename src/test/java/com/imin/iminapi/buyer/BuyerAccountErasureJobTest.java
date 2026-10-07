package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.service.BuyerAccountErasureJob;
import com.imin.iminapi.buyer.service.BuyerAccountErasureService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The job erases off a list it read before the loop, so each entry must go through the re-checking
 * {@code eraseIfStillDue}: a buyer who pressed "Keep my account" in between is not erased off the snapshot.
 */
class BuyerAccountErasureJobTest {

    @Test
    void eachSnapshotEntryGoesThroughTheReCheckNeverTheRawCascade() {
        BuyerAccountRepository accounts = mock(BuyerAccountRepository.class);
        BuyerAccountErasureService erasure = mock(BuyerAccountErasureService.class);
        BuyerAccount due = new BuyerAccount();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(due, "id", id);
        when(accounts.findErasureDue(any())).thenReturn(List.of(due));

        new BuyerAccountErasureJob(accounts, erasure).run();

        verify(erasure).eraseIfStillDue(id);
        verify(erasure, never()).erase(any());
    }
}
