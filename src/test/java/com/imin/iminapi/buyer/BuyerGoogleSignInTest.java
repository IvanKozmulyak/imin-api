package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.buyer.service.BuyerOAuthService;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.oauth.OAuthStateService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * {@link BuyerOAuthService}'s resolution matrix, exercised over an
 * already-verified {@link OAuthUserInfo} so no Google round-trip is involved.
 *
 * <p>The load-bearing assertion in this file is the one that looks like
 * bookkeeping: <b>a buyer Google sign-in creates no {@code organizations} or
 * {@code users} row for the address or subject</b>. Reusing
 * {@code OAuthAccountService} for buyers would silently turn every new buyer
 * into an organizer, and the sign-in would still appear to succeed — so nothing
 * except those rows catches it.
 */
@IminIntegrationTest
class BuyerGoogleSignInTest {

    @Autowired BuyerOAuthService google;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emails;
    @Autowired BuyerIdentityRepository identities;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired OAuthStateService states;
    @Autowired GoogleOAuthService googleTokens;
    @Autowired JdbcTemplate jdbc;

    private String address;
    private String subject;

    @BeforeEach
    void setUp() {
        address = "ada+" + UUID.randomUUID() + "@example.com";
        subject = "google-sub-" + UUID.randomUUID();
    }

    private OAuthUserInfo info(String email, boolean emailVerified) {
        return new OAuthUserInfo("google", subject, email, emailVerified, "Ada", "Lovelace", "Ada Lovelace");
    }

    /** OAuthAccountService provisions an org with this contact email, a user with it and an identity on the subject. */
    private void assertNoOrganizerRowsWereCreated(String... emails) {
        for (String email : emails) {
            assertThat(jdbc.queryForObject("select count(*) from organizations where lower(contact_email) = ?",
                    Integer.class, email.toLowerCase()))
                    .as("a buyer signing in with Google must never provision an Organization")
                    .isZero();
            assertThat(users.existsByEmailLower(email.toLowerCase()))
                    .as("a buyer signing in with Google must never become a User")
                    .isFalse();
        }
        assertThat(jdbc.queryForObject("select count(*) from user_identities where provider_user_id = ?",
                Integer.class, subject)).isZero();
    }

    /**
     * The Google token exchange must not hold a pooled database connection.
     *
     * <p>Hikari runs with {@code auto-commit=true} and nothing sets
     * {@code hibernate.connection.provider_disables_autocommit}, so Hibernate
     * takes its JDBC connection at transaction <i>begin</i>, not at first
     * statement. A transactional web callback therefore pins one of production's
     * twenty connections for as long as Google takes to answer — with no upper
     * bound, since the OAuth {@code RestClient} carried no timeouts either. This
     * is the failure mode {@code BuyerOrderActionsController.resend} documents
     * and deliberately avoids, and the shape the native lanes already have:
     * verify first, then call the transactional resolve.
     */
    @Test
    void the_google_token_exchange_never_runs_inside_a_transaction() {
        AtomicBoolean insideTransaction = new AtomicBoolean(true);
        doAnswer(invocation -> {
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return info(address, true);
        }).when(googleTokens).exchangeCode(anyString(), anyString());

        String nonce = states.newBrowserNonce();
        String state = states.sign("google", OAuthStateService.AUDIENCE_BUYER, nonce);

        var userInfo = google.exchange("an-authorization-code", state, nonce);
        var signedIn = google.resolve(userInfo, "JUnit/1.0");

        assertThat(insideTransaction)
                .as("an outbound HTTP call must not be made while a database connection is pinned")
                .isFalse();
        assertThat(signedIn.session().rawToken()).isNotBlank();
        assertNoOrganizerRowsWereCreated(address);
    }

    // ── (5) Create ─────────────────────────────────────────────────────────

    @Test
    void a_new_google_buyer_gets_an_account_a_verified_address_and_an_identity_and_no_organization() {
        var signedIn = google.resolve(info(address, true), "JUnit/1.0");

        BuyerAccount account = accounts.findById(signedIn.account().getId()).orElseThrow();
        assertThat(account.getPasswordHash()).as("a Google-only account has no password").isNull();
        assertThat(account.getDisplayName()).isEqualTo("Ada Lovelace");
        assertThat(account.getActivatedAt()).isNotNull();

        BuyerAccountEmail row = emails.findByVerifiedKey(address).orElseThrow();
        assertThat(row.getBuyerAccountId()).isEqualTo(account.getId());
        assertThat(row.getAddedVia()).isEqualTo(BuyerAccountEmail.ADDED_VIA_GOOGLE);
        assertThat(row.isPrimary()).as("the first verified address becomes primary").isTrue();

        assertThat(identities.findByProviderAndProviderUserId("google", subject))
                .get()
                .extracting(i -> i.getBuyerAccountId())
                .isEqualTo(account.getId());

        assertThat(signedIn.session().rawToken()).isNotBlank();
        assertNoOrganizerRowsWereCreated(address);
    }

    // ── (1) Known identity ─────────────────────────────────────────────────

    @Test
    void a_returning_google_buyer_is_matched_by_subject_and_creates_nothing_new() {
        var first = google.resolve(info(address, true), "JUnit/1.0");

        // Same subject, DIFFERENT email — the buyer changed the address on their
        // Google account. Matching by email would strand them with a new,
        // empty account and no tickets.
        String moved = "moved+" + UUID.randomUUID() + "@example.com";
        var second = google.resolve(info(moved, true), "JUnit/1.0");

        assertThat(second.account().getId()).isEqualTo(first.account().getId());
        assertThat(identities.findByProviderAndProviderUserId("google", subject)).get()
                .extracting(i -> i.getBuyerAccountId()).isEqualTo(first.account().getId());
        assertThat(emails.findByVerifiedKey(moved).map(BuyerAccountEmail::getBuyerAccountId)
                .filter(id -> !id.equals(first.account().getId())))
                .as("no new account was created for the new address")
                .isEmpty();
        assertNoOrganizerRowsWereCreated(address, moved);
    }

    @Test
    void a_returning_identity_is_not_re_gated_on_email_verified() {
        // The gate protects the link/create branches, which mint address claims.
        // An identity match mints none, and locking a returning buyer out
        // because Google flipped a flag would be a false lockout for no gain.
        var first = google.resolve(info(address, true), "JUnit/1.0");

        var second = google.resolve(info(address, false), "JUnit/1.0");

        assertThat(second.account().getId()).isEqualTo(first.account().getId());
    }

    // ── (2) / (3) The gates ────────────────────────────────────────────────

    @Test
    void a_token_with_no_email_is_rejected() {
        assertThatThrownBy(() -> google.resolve(info(null, true), "JUnit/1.0"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_EMAIL_REQUIRED);

        assertNoOrganizerRowsWereCreated(address);
    }

    @Test
    void an_unverified_google_address_cannot_create_a_pre_verified_claim() {
        // §15 D-3. Without this gate the create branch mints a *verified*
        // buyer_account_emails row straight from the token, handing whoever can
        // get Google to name an address a pre-verified claim on it.
        assertThatThrownBy(() -> google.resolve(info(address, false), "JUnit/1.0"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_EMAIL_UNVERIFIED);

        assertThat(identities.findByProviderAndProviderUserId("google", subject))
                .as("no account was created for this subject").isEmpty();
        assertThat(emails.findByVerifiedKey(address)).isEmpty();
        assertNoOrganizerRowsWereCreated(address);
    }

    @Test
    void an_unverified_google_address_cannot_join_an_existing_buyers_order_history() {
        // The other half of D-3: linking is as dangerous as creating, because a
        // link signs the caller in as an existing buyer whose orders are already
        // joined to that address.
        BuyerAccount victim = seedVerifiedAccount(address);

        assertThatThrownBy(() -> google.resolve(info(address, false), "JUnit/1.0"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_EMAIL_UNVERIFIED);

        assertThat(identities.findByBuyerAccountId(victim.getId())).isEmpty();
    }

    // ── (4) Link ───────────────────────────────────────────────────────────

    @Test
    void a_verified_google_address_links_to_the_existing_buyer_account() {
        BuyerAccount existing = seedVerifiedAccount(address);

        var signedIn = google.resolve(info(address, true), "JUnit/1.0");

        assertThat(signedIn.account().getId()).isEqualTo(existing.getId());
        assertThat(emails.findByVerifiedKey(address)).get()
                .extracting(BuyerAccountEmail::getBuyerAccountId)
                .as("linking must not fork a second account").isEqualTo(existing.getId());
        assertThat(identities.findByBuyerAccountId(existing.getId())).hasSize(1);
        assertNoOrganizerRowsWereCreated(address);
    }

    @Test
    void creating_via_google_re_claims_an_unverified_squat_on_the_address() {
        // §2.3 rule 3, through the same path a redeemed code takes.
        BuyerAccount squatter = accounts.save(new BuyerAccount());
        emails.save(BuyerAccountEmail.of(squatter.getId(), address, BuyerAccountEmail.ADDED_VIA_SIGNUP));

        var signedIn = google.resolve(info(address, true), "JUnit/1.0");

        assertThat(signedIn.account().getId()).isNotEqualTo(squatter.getId());
        assertThat(emails.findByBuyerAccountIdAndEmailNormalized(squatter.getId(), address))
                .as("the unverified squat is dropped when someone proves control")
                .isEmpty();
        assertThat(emails.findByVerifiedKey(address)).get()
                .extracting(BuyerAccountEmail::getBuyerAccountId)
                .isEqualTo(signedIn.account().getId());
    }

    private BuyerAccount seedVerifiedAccount(String email) {
        BuyerAccount account = accounts.save(new BuyerAccount());
        BuyerAccountEmail row = BuyerAccountEmail.of(
                account.getId(), email, BuyerAccountEmail.ADDED_VIA_SIGNUP);
        row.markVerified(Instant.now());
        row.makePrimary();
        emails.save(row);
        account.setActivatedAt(Instant.now());
        return accounts.save(account);
    }
}
