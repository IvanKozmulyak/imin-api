package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.audienceplan.service.PortraitService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The open-data portrait of a genre around a city; shared by every org, it holds no personal data. */
@RestController
@RequestMapping("/api/v1/audience")
public class PortraitController {

    private final PortraitService portraits;

    public PortraitController(PortraitService portraits) {
        this.portraits = portraits;
    }

    @GetMapping("/portrait")
    public AudiencePortraitResponse portrait(@CurrentUser AuthPrincipal p,
                                             @RequestParam(required = false) String genre,
                                             @RequestParam(required = false) String city) {
        return portraits.portrait(p.orgId(), genre, city);
    }
}
