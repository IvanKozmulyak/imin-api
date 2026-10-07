package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.model.BuyerPushDevice;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The push device registry. The property that matters most: a device that
 * changes hands must change owners, not accumulate them — otherwise the
 * previous buyer keeps getting alerts on a phone that is no longer theirs.
 *
 * <p>That property has two independent halves and a test each:
 * {@link #aDeviceThatChangesHandsChangesOwners} pins the re-point in the
 * service, and {@link #theTokenIsUniqueAcrossAccountsInTheDatabase} pins the
 * {@code UNIQUE (expo_token)} backstop that turns a future re-point bug into a
 * failed write instead of a silent second subscriber.
 *
 * <p>{@code mvc} and the recording mailer come from {@link NativeBuyerTestBase}.
 */
@IminIntegrationTest
class BuyerPushDeviceTest extends NativeBuyerTestBase {

    @Autowired BuyerPushDeviceRepository devices;
    @Autowired JdbcTemplate jdbc;

    /** Unique per test: expo_token is UNIQUE and the database is shared. */
    private String token;

    @BeforeEach
    void ownToken() {
        token = "ExponentPushToken[" + UUID.randomUUID().toString().replace("-", "").substring(0, 22) + "]";
    }

    /** Device rows held by these accounts. */
    private int devicesOf(UUID... accountIds) {
        int n = 0;
        for (UUID id : accountIds) {
            n += jdbc.queryForObject("select count(*) from buyer_push_devices where buyer_account_id = ?",
                    Integer.class, id);
        }
        return n;
    }

    @Test
    void registrationIsIdempotent() throws Exception {
        String bearer = signUpAndSignInNative();

        register(bearer, token).andExpect(status().isNoContent());
        register(bearer, token).andExpect(status().isNoContent());

        assertThat(devices.findByExpoToken(token)).isPresent();
        assertThat(devicesOf(accountIdOf(bearer))).isEqualTo(1);
    }

    @Test
    void aDeviceThatChangesHandsChangesOwners() throws Exception {
        String first = signUpAndSignInNative();
        String second = signUpAndSignInNative();

        register(first, token).andExpect(status().isNoContent());
        register(second, token).andExpect(status().isNoContent());

        assertThat(devicesOf(accountIdOf(first), accountIdOf(second))).isEqualTo(1);
        BuyerPushDevice row = devices.findByExpoToken(token).orElseThrow();
        assertThat(row.getBuyerAccountId()).isEqualTo(accountIdOf(second));
        assertThat(row.getRevokedAt()).isNull();

        // The half that actually matters to the first buyer: their account has
        // no live delivery address on this phone any more.
        assertThat(devices.findLiveTokensForAccounts(List.of(accountIdOf(first)))).isEmpty();
        assertThat(devices.findLiveTokensForAccounts(List.of(accountIdOf(second))))
                .containsExactly(token);
    }

    /**
     * The constraint, proven without going through the service. If
     * {@code UNIQUE (expo_token)} were ever dropped from V92, a re-point bug
     * would leave two live rows and two subscribers for one phone; with it, the
     * write fails loudly instead.
     */
    @Test
    void theTokenIsUniqueAcrossAccountsInTheDatabase() throws Exception {
        String first = signUpAndSignInNative();
        String second = signUpAndSignInNative();
        register(first, token).andExpect(status().isNoContent());

        BuyerPushDevice duplicate = new BuyerPushDevice();
        duplicate.setBuyerAccountId(accountIdOf(second));
        duplicate.setExpoToken(token);
        duplicate.setPlatform("android");

        assertThatThrownBy(() -> devices.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revokeStopsTheDeviceCountingAsLive() throws Exception {
        String bearer = signUpAndSignInNative();
        register(bearer, token).andExpect(status().isNoContent());

        revoke(bearer, token).andExpect(status().isNoContent());

        assertThat(devices.findByExpoToken(token).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(devices.findLiveTokensForAccounts(List.of(accountIdOf(bearer)))).isEmpty();
    }

    /** Signing back in on a device that was signed out reuses the row, unrevoked. */
    @Test
    void signingBackInReRegistersTheSameRow() throws Exception {
        String bearer = signUpAndSignInNative();
        register(bearer, token).andExpect(status().isNoContent());
        revoke(bearer, token).andExpect(status().isNoContent());

        register(bearer, token).andExpect(status().isNoContent());

        assertThat(devicesOf(accountIdOf(bearer))).isEqualTo(1);
        assertThat(devices.findByExpoToken(token).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void oneBuyerCannotRevokeAnothersDevice() throws Exception {
        String owner = signUpAndSignInNative();
        String stranger = signUpAndSignInNative();
        register(owner, token).andExpect(status().isNoContent());

        revoke(stranger, token).andExpect(status().isNoContent());   // idempotent, leaks nothing

        assertThat(devices.findByExpoToken(token).orElseThrow().getRevokedAt()).isNull();
    }

    /**
     * A stranger's token, an unknown token and an already-revoked token are all
     * answered identically, so the response cannot be used to probe whether a
     * given device is registered to somebody.
     */
    @Test
    void revokeAnswersTheSameWhateverTheTokenIs() throws Exception {
        String owner = signUpAndSignInNative();
        String stranger = signUpAndSignInNative();
        register(owner, token).andExpect(status().isNoContent());

        revoke(stranger, token)
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(""));
        revoke(stranger, "ExponentPushToken[" + UUID.randomUUID().toString().substring(0, 22) + "]")
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(""));
    }

    @Test
    void thePushDeviceEndpointsNeedABuyerSession() throws Exception {
        mvc.perform(post("/api/v1/buyer/push-devices")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expoToken\":\"" + token + "\",\"platform\":\"ios\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/buyer/push-devices/revoke")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expoToken\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownPlatformIsRejected() throws Exception {
        String bearer = signUpAndSignInNative();

        mvc.perform(post("/api/v1/buyer/push-devices")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expoToken\":\"" + token + "\",\"platform\":\"web\"}"))
                .andExpect(status().isBadRequest());

        assertThat(devicesOf(accountIdOf(bearer))).isZero();
    }

    @Test
    void preferencesExposeThePushSwitch() throws Exception {
        String bearer = signUpAndSignInNative();
        mvc.perform(get("/api/v1/buyer/preferences")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushDropAlerts").value(true));
    }

    @Test
    void theirPushSwitchCanBeTurnedOff() throws Exception {
        String bearer = signUpAndSignInNative();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/buyer/preferences")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pushDropAlerts\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushDropAlerts").value(false));

        mvc.perform(get("/api/v1/buyer/preferences")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushDropAlerts").value(false))
                // the other switches are untouched by a partial patch
                .andExpect(jsonPath("$.eventReminders").value(true));
    }

    private ResultActions register(String bearer, String token) throws Exception {
        return mvc.perform(post("/api/v1/buyer/push-devices")
                .header("Authorization", "Bearer " + bearer)
                .header("X-Imin-Client", "native")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expoToken\":\"" + token + "\",\"platform\":\"ios\",\"locale\":\"en\"}"));
    }

    private ResultActions revoke(String bearer, String token) throws Exception {
        return mvc.perform(post("/api/v1/buyer/push-devices/revoke")
                .header("Authorization", "Bearer " + bearer)
                .header("X-Imin-Client", "native")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expoToken\":\"" + token + "\"}"));
    }
}
