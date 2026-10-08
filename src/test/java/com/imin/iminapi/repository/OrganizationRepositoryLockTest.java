package com.imin.iminapi.repository;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrganizationRepository#lockAndReadStripeAccountId(UUID)} is a NATIVE {@code SELECT … FOR UPDATE}
 * returning a scalar, and it serializes Connect account creation, so it runs here on Postgres; the empty
 * answer for an unset id is what tells the caller to call Stripe.
 * {@link OrganizationRepository#lockAndReadStripeLivemode(UUID)} reads the mode off that same locked row.
 * Each case runs in a rolled-back transaction, as the lock needs one.
 */
@IminIntegrationTest
class OrganizationRepositoryLockTest {

    @Autowired OrganizationRepository orgs;
    @Autowired IminFixtures fx;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void emptyWhenTheOrgHasNoConnectedAccountYet() {
        inRolledBackTx(() -> {
            UUID id = fx.org().getId();

            assertThat(orgs.lockAndReadStripeAccountId(id))
                    .as("a NULL column is the 'no account yet' answer, not a row that is missing")
                    .isEmpty();
        });
    }

    @Test
    void readsTheAccountIdOffTheLockedRow() {
        inRolledBackTx(() -> {
            String acct = acct();
            UUID id = org(o -> o.setStripeAccountId(acct));

            assertThat(orgs.lockAndReadStripeAccountId(id)).contains(acct);
        });
    }

    @Test
    void emptyForAnOrgThatDoesNotExist() {
        inRolledBackTx(() -> assertThat(orgs.lockAndReadStripeAccountId(UUID.randomUUID())).isEmpty());
    }

    @Test
    void readsTheLivemodeFlagOffTheLockedRow() {
        inRolledBackTx(() -> {
            UUID id = org(o -> {
                o.setStripeAccountId(acct());
                o.setStripeLivemode(true);
            });

            assertThat(orgs.lockAndReadStripeLivemode(id))
                    .as("the committed mode is what decides whether the locked account is usable")
                    .contains(true);
        });
    }

    @Test
    void livemodeIsEmptyWhenTheColumnWasNeverStamped() {
        inRolledBackTx(() -> {
            UUID id = org(o -> o.setStripeAccountId(acct()));

            assertThat(orgs.lockAndReadStripeLivemode(id))
                    .as("NULL is 'mode unknown', which is never a mismatch")
                    .isEmpty();
        });
    }

    private UUID org(Consumer<Organization> stripe) {
        Organization o = fx.org();
        stripe.accept(o);
        return orgs.saveAndFlush(o).getId();
    }

    private static String acct() {
        return "acct_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void inRolledBackTx(Runnable body) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            body.run();
        });
    }
}
