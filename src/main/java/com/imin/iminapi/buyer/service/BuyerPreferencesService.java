package com.imin.iminapi.buyer.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.buyer.dto.BuyerPreferencesResponse;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerNotificationPreference;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The buyer preference centre (spec §4.4).
 *
 * <p><b>The organizer-updates toggle is DERIVED and never stored</b> (epic
 * §6.3). Storing it would create a second source of truth for a legal record:
 * {@code consent_records} is the trail and {@code memberships.consent_status} is
 * the state {@code SendGateService} actually reads. So it reads ON when at least
 * one membership on the account's verified addresses is subscribed, and OFF when
 * none is.
 *
 * <p>OFF fans out {@code unsubscribe(..., DATA_SUBJECT_GLOBAL, null)} — the data
 * subject acting on a GLOBAL preference, therefore globally reversible,
 * therefore no sticky row. That enum constant exists for this call site and no
 * other (epic §17.3).
 *
 * <p>ON fans out {@code capture(...)} for every membership WITHOUT a
 * {@code marketing_optouts} row, with the on-screen sentence verbatim as proof
 * text. <b>An explicit per-organizer unsubscribe is never resurrected by a
 * global switch</b> — without that rule the toggle manufactures consent on an
 * organizer's behalf, which is the single defect this class exists to prevent.
 */
@Service
public class BuyerPreferencesService {

    /** V85 constrains {@code marketing_optouts.source} to this vocabulary. */
    static final String SOURCE = "preference_centre_row";

    static final String CHANNEL_EMAIL = "email";

    /**
     * Stored verbatim on the consent record as proof. Must match the sentence
     * rendered next to the toggle in {@code imin-public} — if the screen and
     * this string ever diverge, the proof text stops describing what the buyer
     * actually agreed to.
     */
    static final String PROOF_TEXT =
            "Turned organizer updates on from my imin account preferences.";

    private final BuyerNotificationPreferenceRepository preferences;
    private final BuyerAccountEmailRepository emails;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final MarketingOptOutRepository optOuts;
    private final OrganizationRepository organizations;
    private final ConsentService consent;

    public BuyerPreferencesService(BuyerNotificationPreferenceRepository preferences,
                                   BuyerAccountEmailRepository emails,
                                   ConsumerRepository consumers,
                                   MembershipRepository memberships,
                                   MarketingOptOutRepository optOuts,
                                   OrganizationRepository organizations,
                                   ConsentService consent) {
        this.preferences = preferences;
        this.emails = emails;
        this.consumers = consumers;
        this.memberships = memberships;
        this.optOuts = optOuts;
        this.organizations = organizations;
        this.consent = consent;
    }

    // ── reads ──────────────────────────────────────────────────────────────

    @Transactional
    public BuyerPreferencesResponse read(UUID accountId) {
        BuyerNotificationPreference row = rowFor(accountId);
        Reach reach = reachOf(accountId);
        return new BuyerPreferencesResponse(
                row.isEventReminders(),
                reach.anySubscribed(),
                reach.locked(),
                row.isProductNews());
    }

    @Transactional(readOnly = true)
    public List<BuyerPreferencesResponse.Organizer> organizers(UUID accountId) {
        Reach reach = reachOf(accountId);
        if (reach.memberships().isEmpty()) return List.of();

        Map<UUID, String> names = organizations
                .findAllById(reach.memberships().stream().map(Membership::getOrgId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(o -> o.getId(), o -> o.getName(), (a, b) -> a));

        List<BuyerPreferencesResponse.Organizer> out = new ArrayList<>();
        for (Membership m : reach.memberships()) {
            out.add(new BuyerPreferencesResponse.Organizer(
                    m.getOrgId(),
                    names.getOrDefault(m.getOrgId(), null),
                    "subscribed".equals(m.getConsentStatus()),
                    reach.stickyOrgIds().contains(m.getOrgId())));
        }
        return out;
    }

    // ── writes ─────────────────────────────────────────────────────────────

    @Transactional
    public BuyerPreferencesResponse update(UUID accountId, Map<String, Object> patch) {
        if (patch.containsKey("eventReminders")) {
            rowFor(accountId).setEventReminders(bool(patch.get("eventReminders"), "eventReminders"));
        }
        if (patch.containsKey("productNews")) {
            rowFor(accountId).setProductNews(bool(patch.get("productNews"), "productNews"));
        }
        if (patch.containsKey("organizerUpdates")) {
            fanOut(accountId, bool(patch.get("organizerUpdates"), "organizerUpdates"));
        }
        return read(accountId);
    }

    private void fanOut(UUID accountId, boolean on) {
        Reach reach = reachOf(accountId);
        for (Membership m : reach.memberships()) {
            if (on) {
                // Never resurrect an explicit per-organizer objection.
                if (reach.stickyOrgIds().contains(m.getOrgId())) continue;
                if ("subscribed".equals(m.getConsentStatus())) continue;
                consent.capture(m.getOrgId(), m.getMembershipId(), "explicit", SOURCE,
                        PROOF_TEXT, CHANNEL_EMAIL, null);
            } else {
                if (!"subscribed".equals(m.getConsentStatus())) continue;
                consent.unsubscribe(m.getOrgId(), m.getMembershipId(), SOURCE, CHANNEL_EMAIL,
                        ConsentOrigin.DATA_SUBJECT_GLOBAL, null);
            }
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    /** Creates the row lazily with its column defaults rather than 404ing. */
    private BuyerNotificationPreference rowFor(UUID accountId) {
        return preferences.findById(accountId)
                .orElseGet(() -> preferences.save(new BuyerNotificationPreference(accountId)));
    }

    /**
     * Everything the toggle depends on, resolved once: verified addresses →
     * consumers → memberships, plus the sticky rows that pin some of them shut.
     */
    private record Reach(List<Membership> memberships, Set<UUID> stickyOrgIds) {

        boolean anySubscribed() {
            return memberships.stream().anyMatch(m -> "subscribed".equals(m.getConsentStatus()));
        }

        /**
         * Locked when there is something to toggle and none of it can move.
         * An account with no memberships is not locked — it is simply empty,
         * and will pick up consent the first time it buys.
         */
        boolean locked() {
            if (memberships.isEmpty()) return false;
            return memberships.stream().allMatch(m -> stickyOrgIds.contains(m.getOrgId()));
        }
    }

    private Reach reachOf(UUID accountId) {
        List<String> verified = emails.findByBuyerAccountIdOrderByCreatedAtAsc(accountId).stream()
                .filter(e -> e.getVerifiedAt() != null)
                .map(BuyerAccountEmail::getEmailNormalized)
                .toList();
        if (verified.isEmpty()) return new Reach(List.of(), Set.of());

        List<UUID> consumerIds = verified.stream()
                .map(consumers::findByNormalizedEmail)
                .flatMap(java.util.Optional::stream)
                .map(c -> c.getConsumerId())
                .toList();
        if (consumerIds.isEmpty()) return new Reach(List.of(), Set.of());

        List<Membership> rows = memberships.findAllOrgsByConsumerIdIn(consumerIds);

        Set<UUID> sticky = new HashSet<>();
        for (String address : verified) {
            optOuts.findByEmailNormalized(address).stream()
                    .filter(o -> CHANNEL_EMAIL.equals(o.getChannel()))
                    .forEach(o -> sticky.add(o.getOrgId()));
        }
        return new Reach(rows, sticky);
    }

    private static boolean bool(Object raw, String field) {
        if (raw instanceof Boolean b) return b;
        throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                field + " must be true or false");
    }
}
