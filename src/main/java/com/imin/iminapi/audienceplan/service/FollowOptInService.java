package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.FollowProperties;
import com.imin.iminapi.audienceplan.dto.FollowOptInRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Keep me posted" on imin.wtf: a visitor ticks a switch naming the organizer and becomes an explicit member
 * (source {@code site_follow}, no event) once the emailed link confirms the address. Same grant as the door QR.
 */
@Service
public class FollowOptInService {

    public static final String SOURCE = "site_follow";

    private final OrganizationRepository orgs;
    private final AudiencePlanAccess access;
    private final AudiencePlanLogic logic;
    private final FollowProperties props;
    private final PublicOptInGrant grant;

    public FollowOptInService(OrganizationRepository orgs, AudiencePlanAccess access, AudiencePlanLogic logic,
                              FollowProperties props, PublicOptInGrant grant) {
        this.orgs = orgs;
        this.access = access;
        this.logic = logic;
        this.props = props;
        this.grant = grant;
    }

    @Transactional
    public void follow(String slug, FollowOptInRequest body) {
        Organization org = requireOpen(slug);
        String email = PublicOptInGrant.validEmail(body == null ? null : body.email());
        PublicOptInGrant.requireTicked(body.consentGiven());
        String version = PublicOptInGrant.validVersion(body.consentTextVersion(),
                logic.logic().legal().followTextVersions(), "follow");
        String text = PublicOptInGrant.validText(body.consentText(), org);
        PublicOptInGrant.requirePlainText(text);
        String locale = PublicOptInGrant.locale(body.locale());

        // While the emailed link still works, a repeat stores and mails nothing (same 200).
        grant.grant(org.getId(), email, SOURCE,
                "Ticked the sign-up switch on the imin.wtf page (locale " + locale + ") next to: \"" + text + "\"",
                version, null, locale, true);
    }

    /** One 404 for a slug off {@code imin.follow.org-slugs} (empty = all), an unknown org and audience-plan access off. */
    private Organization requireOpen(String slug) {
        if (slug == null || !props.getOrgSlugs().contains(slug)) throw ApiException.notFound("Organization");
        Organization org = orgs.findBySlug(slug).orElse(null);
        if (org == null || !access.isEnabled(org.getId())) throw ApiException.notFound("Organization");
        return org;
    }
}
