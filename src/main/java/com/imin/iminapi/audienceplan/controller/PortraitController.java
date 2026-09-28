package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.audienceplan.service.PortraitResearchService;
import com.imin.iminapi.audienceplan.service.PortraitService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The portrait of a genre around a city; shared by every org, it holds no personal data. The GET records the request
 * and, when the pair has no research yet, schedules it; the answer shows up on a later GET.
 */
@RestController
@RequestMapping("/api/v1/audience")
public class PortraitController {

    private final PortraitService portraits;
    private final PortraitResearchService research;

    public PortraitController(PortraitService portraits, PortraitResearchService research) {
        this.portraits = portraits;
        this.research = research;
    }

    @GetMapping("/portrait")
    public AudiencePortraitResponse portrait(@CurrentUser AuthPrincipal p,
                                             @RequestParam(required = false) String genre,
                                             @RequestParam(required = false) String city) {
        PortraitService.PortraitKey key = portraits.validate(p.orgId(), genre, city);
        research.requestIfMissing(p.orgId(), key.genreKey(), key.cityKey());
        return portraits.forCity(key.genreKey(), key.cityKey());
    }
}
