package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.dto.org.BrandUpdateRequest;
import com.imin.iminapi.dto.org.OrgPatchRequest;
import com.imin.iminapi.dto.org.OrgSetupRequest;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.oauth.OAuthAccountService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.auth.OrganizerTerms;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.util.Slugger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Setup and the other organization and user writers do not undo each other's columns (no @Version on either). */
@IminIntegrationTest
class OrgSetupStaleWriteTest {

    @Autowired OAuthAccountService oauth;
    @Autowired OrgSetupService setup;
    @Autowired OrgService orgService;
    @Autowired OrgBrandService brand;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager txm;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;

    @ParameterizedTest
    @ValueSource(strings = {"settings", "brand"})
    void aSaveThatLoadedTheOrgBeforeSetup_leavesSetupInPlace(String writer) {
        AuthResponse signedIn = googleSignUp();
        AuthPrincipal owner = owner(signedIn);
        UUID org = signedIn.org().id();
        String orgName = "Night Owls " + UUID.randomUUID().toString().substring(0, 8);

        tx(TransactionDefinition.PROPAGATION_REQUIRED).executeWithoutResult(outer -> {
            orgs.findById(org).orElseThrow(); // the save below holds the org as it was before setup
            tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                    .executeWithoutResult(inner -> setup.complete(owner, request(orgName)));
            switch (writer) {
                case "settings" -> orgService.patch(owner, null,
                        new OrgPatchRequest(null, null, null, null, "Night Owls SAS", "legal@example.test"));
                case "brand" -> brand.put(owner, new BrandUpdateRequest("Owls", List.of("#112233"), true));
                default -> throw new IllegalArgumentException(writer);
            }
        });

        Map<String, Object> row = row(org);
        assertThat(row.get("setup_completed_at")).as("setup stays complete").isNotNull();
        assertThat(row.get("name")).isEqualTo(orgName);
        assertThat(row.get("slug")).isEqualTo(Slugger.slugify(orgName));
        assertThat(row.get("country")).isEqualTo("FR");
        assertThat(row.get("timezone")).isEqualTo("Europe/Paris");
        if (writer.equals("settings")) assertThat(row.get("legal_name")).isEqualTo("Night Owls SAS");
        else assertThat(row.get("brand_name")).isEqualTo("Owls");
    }

    @Test
    void setupHoldingAStaleOrg_leavesAnotherSaveInPlace() {
        AuthResponse signedIn = googleSignUp();
        AuthPrincipal owner = owner(signedIn);
        UUID org = signedIn.org().id();
        String orgName = "Night Owls " + UUID.randomUUID().toString().substring(0, 8);

        tx(TransactionDefinition.PROPAGATION_REQUIRED).executeWithoutResult(outer -> {
            orgs.findById(org).orElseThrow(); // setup below holds the org as it was before this save
            tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(inner -> orgService.patch(owner,
                    null, new OrgPatchRequest(null, null, null, null, "Night Owls SAS", "legal@example.test")));
            setup.complete(owner, request(orgName));
        });

        Map<String, Object> row = row(org);
        assertThat(row.get("legal_name")).as("the save that committed first survives setup").isEqualTo("Night Owls SAS");
        assertThat(row.get("setup_completed_at")).isNotNull();
        assertThat(row.get("name")).isEqualTo(orgName);
        assertThat(row.get("country")).isEqualTo("FR");
    }

    @Test
    void aSignInThatLoadedTheOwnerBeforeSetup_leavesSetupsNamesAndTermsInPlace() {
        OAuthUserInfo google = new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                fx.email("ada"), true, "Ada", "Lovelace", "Ada Lovelace");
        AuthResponse signedIn = oauth.resolve(google);
        AuthPrincipal owner = owner(signedIn);
        String orgName = "Night Owls " + UUID.randomUUID().toString().substring(0, 8);

        tx(TransactionDefinition.PROPAGATION_REQUIRED).executeWithoutResult(outer -> {
            users.findById(owner.userId()).orElseThrow(); // the sign-in below holds the owner as before setup
            tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(inner -> setup.complete(owner,
                    new OrgSetupRequest("Grace", "Hopper", orgName, "FR", OrganizerTerms.CURRENT_VERSION, null)));
            oauth.resolve(google); // known identity: saves the owner it holds, with a new last_active_at
        });

        Map<String, Object> user = jdbc.queryForMap("select first_name, last_name, avatar_initials,"
                + " terms_accepted_at, terms_version from users where id = ?", owner.userId());
        assertThat(user.get("first_name")).isEqualTo("Grace");
        assertThat(user.get("last_name")).isEqualTo("Hopper");
        assertThat(user.get("avatar_initials")).isEqualTo("GH");
        assertThat(user.get("terms_accepted_at")).isNotNull();
        assertThat(user.get("terms_version")).isEqualTo(OrganizerTerms.CURRENT_VERSION);
    }

    // ---------- helpers ----------

    private AuthResponse googleSignUp() {
        return oauth.resolve(new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                fx.email("ada"), true, "Ada", "Lovelace", "Ada Lovelace"));
    }

    private static AuthPrincipal owner(AuthResponse signedIn) {
        return new AuthPrincipal(signedIn.user().id(), signedIn.org().id(), UserRole.OWNER, UUID.randomUUID());
    }

    private static OrgSetupRequest request(String orgName) {
        return new OrgSetupRequest("Ada", "Lovelace", orgName, "FR", OrganizerTerms.CURRENT_VERSION, null);
    }

    private TransactionTemplate tx(int propagation) {
        TransactionTemplate t = new TransactionTemplate(txm);
        t.setPropagationBehavior(propagation);
        return t;
    }

    private Map<String, Object> row(UUID org) {
        return jdbc.queryForMap("select name, slug, country, timezone, setup_completed_at, legal_name, brand_name"
                + " from organizations where id = ?", org);
    }
}
