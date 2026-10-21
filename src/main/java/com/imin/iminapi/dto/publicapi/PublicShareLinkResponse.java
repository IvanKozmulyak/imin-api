package com.imin.iminapi.dto.publicapi;

import java.util.UUID;

/** What a short code stands for: the event it opens and the tags the landing carries. */
public record PublicShareLinkResponse(UUID eventId, String utmSource, String utmMedium, String utmCampaign) {}
