package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.model.BuyerPushDevice;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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
 * <p>{@code mvc} and the mocked {@code EmailService} come from
 * {@link NativeBuyerTestBase} and must not be re-declared here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerPushDeviceTest extends NativeBuyerTestBase {

    private static final String TOKEN = "ExponentPushToken[aaaaaaaaaaaaaaaaaaaaaa]";

    @Autowired BuyerPushDeviceRepository devices;

    /**
     * Every assertion below counts rows, and the H2 schema is shared by the
     * whole suite, so the table starts empty on each test rather than carrying
     * the previous method's device.
     */
    @BeforeEach
    void emptyRegistry() {
        devices.deleteAll();
    }

    @Test
    void registrationIsIdempotent() throws Exception {
        String bearer = signUpAndSignInNative();

        register(bearer, TOKEN).andExpect(status().isNoContent());
        register(bearer, TOKEN).andExpect(status().isNoContent());

        assertThat(devices.findByExpoToken(TOKEN)).isPresent();
        assertThat(devices.count()).isEqualTo(1);
    }

    @Test
    void aDeviceThatChangesHandsChangesOwners() throws Exception {
        String first = signUpAndSignInNative();
        String second = signUpAndSignInNative();

        register(first, TOKEN).andExpect(status().isNoContent());
        register(second, TOKEN).andExpect(status().isNoContent());

        assertThat(devices.count()).isEqualTo(1);
        BuyerPushDevice row = devices.findByExpoToken(TOKEN).orElseThrow();
        assertThat(row.getBuyerAccountId()).isEqualTo(accountIdOf(second));
        assertThat(row.getRevokedAt()).isNull();

        // The half that actually matters to the first buyer: their account has
        // no live delivery address on this phone any more.
        assertThat(devices.findLiveTokensForAccounts(List.of(accountIdOf(first)))).isEmpty();
        assertThat(devices.findLiveTokensForAccounts(List.of(accountIdOf(second))))
                .containsExactly(TOKEN);
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
        register(first, TOKEN).andExpect(status().isNoContent());

        BuyerPushDevice duplicate = new BuyerPushDevice();
        duplicate.setBuyerAccountId(accountIdOf(second));
        duplicate.setExpoToken(TOKEN);
        duplicate.setPlatform("android");

        assertThatThrownBy(() -> devices.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revokeStopsTheDeviceCountingAsLive() throws Exception {
        String bearer = signUpAndSignInNative();
        register(bearer, TOKEN).andExpect(status().isNoContent());

        revoke(bearer, TOKEN).andExpect(status().isNoContent());

        assertThat(devices.findByExpoToken(TOKEN).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(devices.findLiveTokensForAccounts(List.of(accountIdOf(bearer)))).isEmpty();
    }

    /** Signing back in on a device that was signed out reuses the row, unrevoked. */
    @Test
    void signingBackInReRegistersTheSameRow() throws Exception {
        String bearer = signUpAndSignInNative();
        register(bearer, TOKEN).andExpect(status().isNoContent());
        revoke(bearer, TOKEN).andExpect(status().isNoContent());

        register(bearer, TOKEN).andExpect(status().isNoContent());

        assertThat(devices.count()).isEqualTo(1);
        assertThat(devices.findByExpoToken(TOKEN).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void oneBuyerCannotRevokeAnothersDevice() throws Exception {
        String owner = signUpAndSignInNative();
        String stranger = signUpAndSignInNative();
        register(owner, TOKEN).andExpect(status().isNoContent());

        revoke(stranger, TOKEN).andExpect(status().isNoContent());   // idempotent, leaks nothing

        assertThat(devices.findByExpoToken(TOKEN).orElseThrow().getRevokedAt()).isNull();
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
        register(owner, TOKEN).andExpect(status().isNoContent());

        revoke(stranger, TOKEN)
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(""));
        revoke(stranger, "ExponentPushToken[zzzzzzzzzzzzzzzzzzzzzz]")
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(""));
    }

    @Test
    void thePushDeviceEndpointsNeedABuyerSession() throws Exception {
        mvc.perform(post("/api/v1/buyer/push-devices")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expoToken\":\"" + TOKEN + "\",\"platform\":\"ios\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/buyer/push-devices/revoke")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expoToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownPlatformIsRejected() throws Exception {
        String bearer = signUpAndSignInNative();

        mvc.perform(post("/api/v1/buyer/push-devices")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expoToken\":\"" + TOKEN + "\",\"platform\":\"web\"}"))
                .andExpect(status().isBadRequest());

        assertThat(devices.count()).isZero();
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
