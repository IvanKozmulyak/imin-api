package com.imin.iminapi.buyer.service;

import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Runs one {@code (membership, channel)} unsubscribe on its <b>own</b>
 * transaction, for the deletion fan-out in {@link BuyerAccountDeletionService}.
 *
 * <p><b>This is a separate bean for one reason: the transaction boundary.</b>
 * Exactly the reasoning behind {@code MarketingOptOutRecorder}, applied to the
 * next call site out. {@code ConsentService.unsubscribe} is
 * {@code @Transactional} with the default {@code REQUIRED} propagation, so
 * called directly from {@code requestDeletion} it participates in the deletion's
 * transaction — and Spring's {@code globalRollbackOnParticipationFailure}
 * (default {@code true}) marks that shared transaction rollback-only the moment
 * it throws, <i>before</i> the exception reaches the caller's {@code catch}.
 * The deletion then dies at commit with an {@code UnexpectedRollbackException},
 * taking the status flip, the {@code delete_at}, the session revocation and
 * every successful unsubscribe with it.
 *
 * <p>{@code REQUIRES_NEW} is what makes that {@code catch} real rather than
 * decorative, and a Spring proxy only applies it across bean boundaries — a
 * private method on the deletion service would silently join the caller's
 * transaction and give no isolation at all.
 *
 * <p>The catch stays in the caller, outside this boundary, so it also covers
 * whatever the proxy throws at this method's own commit.
 */
@Component
public class BuyerUnsubscribeRunner {

    private final ConsentService consentService;

    public BuyerUnsubscribeRunner(ConsentService consentService) {
        this.consentService = consentService;
    }

    /**
     * @throws RuntimeException whatever the unsubscribe or its commit raises —
     *         the caller is outside this transaction and must decide what a
     *         failed org means for the rest of the fan-out.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unsubscribeOne(UUID orgId, UUID membershipId, String source, String channel,
                               ConsentOrigin origin, AuthPrincipal principal) {
        consentService.unsubscribe(orgId, membershipId, source, channel, origin, principal);
    }
}
