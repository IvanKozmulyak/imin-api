package com.imin.iminapi.repository;

import com.imin.iminapi.model.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrganizationRepository#lockAndReadStripeAccountId(UUID)} is a NATIVE
 * {@code SELECT … FOR UPDATE} returning a scalar, and it serializes Connect account creation —
 * so it has to actually execute on the engine the suite runs. H2 in PG-compat mode accepts
 * plain {@code FOR UPDATE} (it rejects the {@code FOR NO KEY UPDATE} a JPA pessimistic lock
 * would render), and the empty answer for an unset id is what tells the caller to call Stripe.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrganizationRepositoryLockTest {

    @Autowired OrganizationRepository orgs;

    @Test
    void emptyWhenTheOrgHasNoConnectedAccountYet() {
        UUID id = orgs.save(org(null)).getId();

        assertThat(orgs.lockAndReadStripeAccountId(id))
                .as("a NULL column is the 'no account yet' answer, not a row that is missing")
                .isEmpty();
    }

    @Test
    void readsTheAccountIdOffTheLockedRow() {
        UUID id = orgs.save(org("acct_locked_read")).getId();

        assertThat(orgs.lockAndReadStripeAccountId(id)).contains("acct_locked_read");
    }

    @Test
    void emptyForAnOrgThatDoesNotExist() {
        assertThat(orgs.lockAndReadStripeAccountId(UUID.randomUUID())).isEmpty();
    }

    private Organization org(String stripeAccountId) {
        Organization o = new Organization();
        o.setName("Lock Org");
        o.setSlug("lock-org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("lock@test.example");
        o.setCountry("DE");
        o.setStripeAccountId(stripeAccountId);
        return o;
    }
}
