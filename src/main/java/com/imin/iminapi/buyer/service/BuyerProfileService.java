package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerIdentity;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.util.Times;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The buyer's own profile — name, city, locale, password, linked identities
 * (spec §4.3).
 *
 * <p>Deliberately narrow. It cannot touch addresses (that is
 * {@code BuyerAddressService}, which owns the verification rules) and it cannot
 * touch {@code status} or {@code deleteAt} (that is the erasure flow). The
 * three columns it writes are display data with no legal weight.
 */
@Service
public class BuyerProfileService {

    /** The four locales the site ships. Matches {@code lib/i18n}. */
    private static final Set<String> LOCALES = Set.of("en", "es", "fr", "uk");

    private static final int DISPLAY_NAME_MAX = 255;   // column widths, V83
    private static final int CITY_MAX = 128;

    private final BuyerAccountRepository accounts;
    private final BuyerIdentityRepository identities;
    private final BuyerSessionService sessions;
    private final PasswordHasher hasher;

    public BuyerProfileService(BuyerAccountRepository accounts,
                               BuyerIdentityRepository identities,
                               BuyerSessionService sessions,
                               PasswordHasher hasher) {
        this.accounts = accounts;
        this.identities = identities;
        this.sessions = sessions;
        this.hasher = hasher;
    }

    /**
     * Applies a partial patch. Only keys actually present in the body are
     * touched; an explicit null clears, and a blank string is treated as null
     * so "  " never becomes a display name.
     */
    @Transactional
    public BuyerAccount updateProfile(UUID accountId, Map<String, Object> patch) {
        BuyerAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Account"));

        if (patch.containsKey("displayName")) {
            account.setDisplayName(text(patch.get("displayName"), "displayName", DISPLAY_NAME_MAX));
        }
        if (patch.containsKey("city")) {
            account.setCity(text(patch.get("city"), "city", CITY_MAX));
        }
        if (patch.containsKey("locale")) {
            String locale = text(patch.get("locale"), "locale", 5);
            if (locale != null && !LOCALES.contains(locale)) {
                throw badRequest("locale must be one of en, es, fr, uk");
            }
            account.setLocale(locale);
        }

        account.setUpdatedAt(Times.nowMicros());
        return accounts.save(account);
    }

    /**
     * Changes the password and revokes every OTHER session, deliberately
     * sparing this one — the opposite of {@code POST /buyer/emails/primary},
     * which revokes all including the caller. Changing a password is a hygiene
     * action you perform mid-session; being signed out of the tab you did it in
     * reads as a failure.
     *
     * <p>{@code currentPassword} is required exactly when one exists. A
     * Google-only account has no {@code password_hash} and therefore nothing to
     * prove; demanding one would make the control unreachable for the accounts
     * that most need it.
     */
    @Transactional
    public void changePassword(UUID accountId, UUID actingSessionId,
                               String currentPassword, String newPassword) {
        BuyerAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Account"));

        String existing = account.getPasswordHash();
        if (existing != null) {
            if (currentPassword == null || !hasher.verify(currentPassword, existing)) {
                // 403, not 404: the caller is authenticated, they just failed to
                // prove the password. There is nothing to enumerate here.
                throw ApiException.forbidden("Current password is incorrect");
            }
        }

        account.setPasswordHash(hasher.hash(newPassword));
        account.setUpdatedAt(Times.nowMicros());
        accounts.save(account);

        sessions.revokeAllExcept(accountId, actingSessionId);
    }

    @Transactional(readOnly = true)
    public List<BuyerIdentity> listIdentities(UUID accountId) {
        return identities.findByBuyerAccountId(accountId);
    }

    /**
     * Unlinks a provider. Refuses with 409 {@code LAST_CREDENTIAL} when it
     * would leave the account with no way back in — no password hash and no
     * other identity. Locking someone out of their own tickets is not a valid
     * outcome of a settings toggle.
     */
    @Transactional
    public void unlinkIdentity(UUID accountId, String provider) {
        BuyerAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Account"));

        List<BuyerIdentity> all = identities.findByBuyerAccountId(accountId);
        BuyerIdentity target = all.stream()
                .filter(i -> i.getProvider().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Identity"));

        boolean noPassword = account.getPasswordHash() == null;
        boolean noOtherIdentity = all.size() == 1;
        if (noPassword && noOtherIdentity) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.LAST_CREDENTIAL,
                    "Set a password before unlinking your only sign-in method");
        }

        identities.delete(target);
    }

    /** Trims, maps blank to null, and enforces the column width. */
    private static String text(Object raw, String field, int max) {
        if (raw == null) return null;
        if (!(raw instanceof String s)) throw badRequest(field + " must be a string or null");
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > max) throw badRequest(field + " must be at most " + max + " characters");
        return trimmed;
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, message);
    }
}
