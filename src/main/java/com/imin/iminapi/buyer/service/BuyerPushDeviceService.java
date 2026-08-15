package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.model.BuyerPushDevice;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.util.Times;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Registers and revokes push delivery addresses. */
@Service
public class BuyerPushDeviceService {

    private final BuyerPushDeviceRepository devices;

    public BuyerPushDeviceService(BuyerPushDeviceRepository devices) {
        this.devices = devices;
    }

    /**
     * Idempotent upsert keyed on the token. A token already known to another
     * account is <b>re-pointed</b>, not rejected and not duplicated: the device
     * changed hands, and the previous owner must stop receiving alerts on it.
     * Re-pointing also clears {@code revoked_at}, so signing back in on a device
     * that was signed out works without a second row.
     */
    @Transactional
    public void register(UUID accountId, String expoToken, String platform,
                         String locale, String appVersion) {
        BuyerPushDevice device = devices.findByExpoToken(expoToken)
                .orElseGet(BuyerPushDevice::new);
        device.setBuyerAccountId(accountId);
        device.setExpoToken(expoToken);
        device.setPlatform(platform);
        device.setLocale(locale);
        device.setAppVersion(appVersion);
        device.setLastSeenAt(Times.nowMicros());
        device.setRevokedAt(null);
        devices.save(device);
    }

    /**
     * Revokes a device the caller owns. Silent when the token is unknown, already
     * revoked, or belongs to someone else — the caller learns nothing either way,
     * and sign-out must never fail.
     */
    @Transactional
    public void revoke(UUID accountId, String expoToken) {
        devices.findByExpoToken(expoToken)
                .filter(d -> accountId.equals(d.getBuyerAccountId()))
                .filter(d -> d.getRevokedAt() == null)
                .ifPresent(d -> {
                    d.setRevokedAt(Times.nowMicros());
                    devices.save(d);
                });
    }
}
