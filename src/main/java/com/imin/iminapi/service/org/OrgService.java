package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.org.OrgPatchRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.payout.PayoutRunRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RoleGuard;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.util.SanctionedCountries;
import com.imin.iminapi.util.StripeSupportedCountries;
import com.imin.iminapi.web.IfMatchSupport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class OrgService {

    private final OrganizationRepository orgs;
    private final IfMatchSupport ifMatch;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final SettlementRepository settlements;
    private final PayoutRunRepository payouts;
    private final AuditLogger audit;

    public OrgService(OrganizationRepository orgs, IfMatchSupport ifMatch,
                      OrderRepository orders, TicketRepository tickets,
                      SettlementRepository settlements, PayoutRunRepository payouts,
                      AuditLogger audit) {
        this.orgs = orgs;
        this.ifMatch = ifMatch;
        this.orders = orders;
        this.tickets = tickets;
        this.settlements = settlements;
        this.payouts = payouts;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public OrganizationDto get(AuthPrincipal p) {
        Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        return OrganizationDto.from(o);
    }

    /**
     * Edits the org profile. OWNER/ADMIN only: {@code contactEmail} is where org
     * notifications land and {@code country} is the Stripe/AML jurisdiction, so
     * this is a settings surface, not event content.
     */
    @Transactional
    public OrganizationDto patch(AuthPrincipal p, String ifMatchHeader, OrgPatchRequest body) {
        RoleGuard.requireAtLeast(p, UserRole.ADMIN, "edit organization settings");
        Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        ifMatch.requireMatch(ifMatchHeader, o.getUpdatedAt());
        if (body.name() != null) o.setName(body.name());
        if (body.contactEmail() != null) o.setContactEmail(body.contactEmail());
        // Only validate/replace when the country actually changes — this grandfathers existing
        // orgs whose (now-unsupported) country predates the supported-country gate, so they can
        // still edit other fields. A real change must pass both sanctions and Stripe-support checks.
        if (body.country() != null && !body.country().equalsIgnoreCase(o.getCountry())) {
            SanctionedCountries.requireAllowed(body.country());
            StripeSupportedCountries.requireSupported(body.country());
            o.setCountry(body.country().toUpperCase());
        }
        if (body.timezone() != null) o.setTimezone(body.timezone());
        o.setUpdatedAt(Instant.now());
        return OrganizationDto.from(orgs.save(o));
    }

    /**
     * Deletes an organization that has never traded.
     *
     * <h2>Why this refuses</h2>
     *
     * <p>{@code organizations → events → orders → tickets} are all
     * {@code ON DELETE CASCADE} (V6:3, V24:15). One call therefore used to
     * destroy every ticket the org's buyers hold — people who are not party to
     * this decision and whose ticket is their proof of entry — along with the
     * order and settlement records imin has to retain for tax. So the delete is
     * refused outright once any of those exist, with a 409 and
     * {@link ErrorCode#ORG_HAS_RECORDS}. There is no force flag: a caller who
     * could override this would be back to the original defect.
     *
     * <p>What a traded org actually needs is soft-delete plus a grace period,
     * keeping the invoice-bearing rows. That is deliberately <b>not</b> built
     * here — it is a product decision about retention windows and what an
     * organizer sees afterwards — and is tracked as the follow-up on this card.
     * Refusing is the safe half, and it is the half that stops data loss today.
     */
    @Transactional
    public void delete(AuthPrincipal p) {
        if (p.role() != UserRole.OWNER) throw ApiException.forbidden("Only the org owner can delete the organization");
        if (!orgs.existsById(p.orgId())) throw ApiException.notFound("Organization");
        requireNothingToRetain(p.orgId());
        orgs.deleteById(p.orgId()); // FK ON DELETE CASCADE wipes users, events, etc.
        // Written after the delete but in its own REQUIRES_NEW transaction, so the
        // row survives the cascade (audit_logs.org_id carries no FK, V21:3) and the
        // actor lookup still resolves — the suspended outer transaction has not
        // committed the user's removal yet.
        audit.record(p, AuditActions.ORG_DELETED, "org", p.orgId(),
                "Organization deleted by owner (no orders, tickets, settlements or payouts)");
    }

    private void requireNothingToRetain(java.util.UUID orgId) {
        String reason = null;
        if (orders.existsByOrgId(orgId)) reason = "orders";
        else if (tickets.existsByOrgId(orgId)) reason = "issued tickets";
        else if (settlements.existsByOrgId(orgId)) reason = "settlements";
        else if (payouts.existsByOrgId(orgId)) reason = "payouts";
        if (reason == null) return;
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORG_HAS_RECORDS,
                "This organization has " + reason + " and cannot be deleted. "
                + "Deleting it would destroy tickets its buyers hold and records imin is "
                + "required to retain. Contact support to close the account instead.");
    }
}
