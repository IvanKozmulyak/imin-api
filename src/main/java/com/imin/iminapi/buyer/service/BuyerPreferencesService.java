package com.imin.iminapi.buyer.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.email.EmailLocale;
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
     * The most memberships one toggle will fan out over in a single request.
     *
     * <p>Each one costs roughly three statements inside the caller's
     * transaction, so an unbounded loop is an unbounded write transaction
     * holding locks on {@code memberships} and {@code consent_records}. A
     * frequent buyer with a role address like {@code tickets@venue.com} can
     * accumulate memberships without limit.
     *
     * <p>Over the cap the request is refused rather than half-applied: a
     * partial fan-out would leave the derived toggle reading a state the buyer
     * did not ask for, and the retry would be indistinguishable from a fresh
     * press. 200 is far above any real buyer and far below anything that holds
     * a transaction open long enough to matter.
     */
    static final int MAX_FANOUT = 200;

    /**
     * The sentence rendered next to the toggle in {@code imin-public}, stored
     * verbatim on the consent record as proof — <b>in the language the buyer
     * actually read it in</b>.
     *
     * <p>These four strings are
     * {@code profile.notifications.organizers.body} in
     * {@code imin-public/lib/i18n/{en,es,fr,uk}.ts}, character for character.
     * {@code BuyerPreferencesProofTextTest} pins them so a change here is
     * deliberate; if you edit the screen, edit these too.
     *
     * <p>Storing English for everybody — which this did until 2026-08-13 — puts
     * a sentence on file as Art. 7(1) proof that the data subject was never
     * shown, in a language they may not read. Art. 7(2) and 12(1) both ask that
     * the request be intelligible to them, and a proof text that describes a
     * different sentence than the one on screen is not proof of anything.
     */
    public static String proofText(String locale) {
        return EmailLocale.choose(locale,
                "Marketing from organizers you've bought from. Turning this on won't undo an unsubscribe you've already made.",
                "Marketing de organizadores a los que has comprado. Activarlo no deshará una baja que ya hayas hecho.",
                "Le marketing des organisateurs chez qui tu as acheté. L’activer n’annulera pas une désinscription déjà faite.",
                "Розсилки від організаторів, у яких ти вже маєш покупки. Увімкнення не скасує відписку, яку ти вже оформив(ла).");
    }

    private final BuyerNotificationPreferenceRepository preferences;
    private final BuyerAccountRepository accounts;
    private final BuyerAccountEmailRepository emails;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final MarketingOptOutRepository optOuts;
    private final OrganizationRepository organizations;
    private final ConsentService consent;

    public BuyerPreferencesService(BuyerNotificationPreferenceRepository preferences,
                                   BuyerAccountRepository accounts,
                                   BuyerAccountEmailRepository emails,
                                   ConsumerRepository consumers,
                                   MembershipRepository memberships,
                                   MarketingOptOutRepository optOuts,
                                   OrganizationRepository organizations,
                                   ConsentService consent) {
        this.preferences = preferences;
        this.accounts = accounts;
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
                row.isProductNews(),
                row.isPushDropAlerts());
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
        if (patch.containsKey("pushDropAlerts")) {
            rowFor(accountId).setPushDropAlerts(bool(patch.get("pushDropAlerts"), "pushDropAlerts"));
        }
        // Resolved once and threaded through. The address → consumer →
        // membership → optout walk is the expensive part of this endpoint, and
        // doing it again for the response doubled it on every write.
        Reach reach = reachOf(accountId);
        if (patch.containsKey("organizerUpdates")) {
            fanOut(accountId, reach, bool(patch.get("organizerUpdates"), "organizerUpdates"));
            reach = reachOf(accountId);   // the fan-out just changed it
        }
        BuyerNotificationPreference row = rowFor(accountId);
        return new BuyerPreferencesResponse(
                row.isEventReminders(), reach.anySubscribed(), reach.locked(), row.isProductNews(),
                row.isPushDropAlerts());
    }

    private void fanOut(UUID accountId, Reach reach, boolean on) {
        if (reach.memberships().size() > MAX_FANOUT) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_REQUEST,
                    "This account holds too many organizer subscriptions to change at once."
                            + " Use the unsubscribe link in an organizer's email instead.");
        }
        // The proof text is the sentence THIS buyer read, so it is resolved
        // from their own locale rather than defaulted to English. Resolved once
        // for the whole fan-out, not per membership.
        String proof = proofText(localeOf(accountId));
        for (Membership m : reach.memberships()) {
            if (on) {
                // Never resurrect an explicit per-organizer objection.
                if (reach.stickyOrgIds().contains(m.getOrgId())) continue;
                if ("subscribed".equals(m.getConsentStatus())) continue;
                consent.capture(m.getOrgId(), m.getMembershipId(), "explicit", SOURCE,
                        proof, CHANNEL_EMAIL, null);
            } else {
                if (!"subscribed".equals(m.getConsentStatus())) continue;
                consent.unsubscribe(m.getOrgId(), m.getMembershipId(), SOURCE, CHANNEL_EMAIL,
                        ConsentOrigin.DATA_SUBJECT_GLOBAL, null);
            }
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    /** The account's own UI language, or null for the English default. */
    private String localeOf(UUID accountId) {
        return accounts.findById(accountId).map(BuyerAccount::getLocale).orElse(null);
    }

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
