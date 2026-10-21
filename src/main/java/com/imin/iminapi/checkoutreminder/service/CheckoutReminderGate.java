package com.imin.iminapi.checkoutreminder.service;

import com.imin.iminapi.audience.model.MarketingOptOutId;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminderReason;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.event.TierAvailability;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every check a reminder must pass at send time, re-read from the database: the organizer still
 * wants it, the address may still be emailed by this organizer, the ticked version still counts as
 * consent, the buyer has not bought, and the tier is still on sale with time left before the night.
 * The first failing check names the reason.
 */
@Component
public class CheckoutReminderGate {

    private static final String ERASE_PENDING = "erase_pending";

    /** What passed the gate, loaded once for the email. */
    public record Pass(Organization org, Event event, TicketTier tier) {}

    public record Result(CheckoutReminderReason reason, Pass pass) {
        public static Result fail(CheckoutReminderReason reason) { return new Result(reason, null); }
        public boolean passed() { return reason == null; }
    }

    private final CheckoutReminderProperties props;
    private final OrgCheckoutReminderSettingRepository settings;
    private final OrganizationRepository orgs;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final ModerationHoldRepository holds;
    private final OrderRepository orders;
    private final ErasedAddressRepository erased;
    private final MarketingOptOutRepository optOuts;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final SuppressionRepository suppressions;
    private final AudiencePlanLogic logic;

    public CheckoutReminderGate(CheckoutReminderProperties props, OrgCheckoutReminderSettingRepository settings,
                                OrganizationRepository orgs, EventRepository events, TicketTierRepository tiers,
                                ModerationHoldRepository holds, OrderRepository orders,
                                ErasedAddressRepository erased, MarketingOptOutRepository optOuts,
                                ConsumerRepository consumers, MembershipRepository memberships,
                                SuppressionRepository suppressions, AudiencePlanLogic logic) {
        this.props = props;
        this.settings = settings;
        this.orgs = orgs;
        this.events = events;
        this.tiers = tiers;
        this.holds = holds;
        this.orders = orders;
        this.erased = erased;
        this.optOuts = optOuts;
        this.consumers = consumers;
        this.memberships = memberships;
        this.suppressions = suppressions;
        this.logic = logic;
    }

    @Transactional(readOnly = true)
    public Result check(CheckoutReminder r, Instant now) {
        Organization org = orgs.findById(r.getOrgId()).orElse(null);
        if (org == null || !settings.isEnabled(org.getId())) return Result.fail(CheckoutReminderReason.DISABLED);
        if (org.getMarketingPausedAt() != null) return Result.fail(CheckoutReminderReason.PAUSED);
        if (!org.hasLegalIdentity()) return Result.fail(CheckoutReminderReason.LEGAL_IDENTITY_MISSING);

        String email = r.getEmailNormalized();
        if (email == null || email.isBlank()) return Result.fail(CheckoutReminderReason.ERASED);
        Optional<Membership> member = consumers.findByNormalizedEmail(email)
                .flatMap(c -> memberships.findByOrgIdAndConsumerId(org.getId(), c.getConsumerId()));
        if (erased.existsPlatformWide(email) || erased.existsForOrg(org.getId(), email)
                || member.map(m -> ERASE_PENDING.equals(m.getStatus())).orElse(false)) {
            return Result.fail(CheckoutReminderReason.ERASED);
        }
        if (optOuts.existsById(new MarketingOptOutId(email, org.getId(), "email"))
                || member.map(m -> "unsubscribed".equals(m.getConsentStatus())).orElse(false)
                || member.map(m -> suppressions.findMarketingByOrgAndMembership(org.getId(), m.getMembershipId())
                        .isPresent()).orElse(false)) {
            return Result.fail(CheckoutReminderReason.UNSUBSCRIBED);
        }
        if (!suppressions.findDeliverabilityEmailsIn(List.of(email)).isEmpty()) {
            return Result.fail(CheckoutReminderReason.UNDELIVERABLE);
        }
        // The allowlist read at capture is read again: a version taken off it no longer counts as consent.
        String version = r.getConsentTextVersion();
        if (version == null || !logic.logic().legal().organizerNamedTextVersions().contains(version)) {
            return Result.fail(CheckoutReminderReason.CONSENT_VERSION_RETIRED);
        }
        if (orders.existsForEventAndEmail(r.getEventId(), email)) {
            return Result.fail(CheckoutReminderReason.HAS_TICKETS);
        }

        Event event = events.findById(r.getEventId()).orElse(null);
        TicketTier tier = tiers.findById(r.getTierId()).orElse(null);
        if (!listed(event, tier) || !buyableApartFromStock(event, tier, now)) {
            return Result.fail(CheckoutReminderReason.SALES_CLOSED);
        }
        if (TierAvailability.remaining(tier) <= 0) return Result.fail(CheckoutReminderReason.SOLD_OUT);
        // The buy page's own rule has the last word, so the email never sends someone to a closed page.
        if (!TierAvailability.isPurchasable(event, tier, now)) return Result.fail(CheckoutReminderReason.SALES_CLOSED);
        if (CheckoutReminderSchedule.tooLate(r.getStartedAt(), now, props.maxAge())) {
            return Result.fail(CheckoutReminderReason.TOO_LATE);
        }
        if (CheckoutReminderSchedule.tooCloseToDoors(event.getStartsAt(), now, props.minLead())) {
            return Result.fail(CheckoutReminderReason.TOO_CLOSE_TO_DOORS);
        }
        return new Result(null, new Pass(org, event, tier));
    }

    /** Publicly listed and not under a sales hold. */
    private boolean listed(Event event, TicketTier tier) {
        if (event == null || tier == null || !event.getId().equals(tier.getEventId())) return false;
        if (event.getDeletedAt() != null || event.getVisibility() != EventVisibility.PUBLIC
                || event.getPublishedAt() == null || event.getStatus() == EventStatus.DRAFT) {
            return false;
        }
        return !holds.salesSuspended(event.getOrgId(), event.getId());
    }

    /** {@link TierAvailability#isPurchasable} without its stock clause, so a sold-out tier gets its own reason. */
    private static boolean buyableApartFromStock(Event event, TicketTier tier, Instant now) {
        return tier.isEnabled()
                && event.getStatus() != EventStatus.PAST && event.getStatus() != EventStatus.CANCELLED
                && (event.getOnSaleAt() == null || !now.isBefore(event.getOnSaleAt()))
                && (tier.getSaleStartsAt() == null || !now.isBefore(tier.getSaleStartsAt()))
                && !TierAvailability.isClosed(event, tier, now);
    }
}
