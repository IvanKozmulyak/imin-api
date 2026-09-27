package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.AudiencePlanListItem;
import com.imin.iminapi.audienceplan.service.PlanListService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Upcoming events of the caller's org with their stored audience plan status; {@code from} is an ISO instant. */
@RestController
@RequestMapping("/api/v1/audience-plans")
public class AudiencePlanListController {

    private final PlanListService plans;

    public AudiencePlanListController(PlanListService plans) {
        this.plans = plans;
    }

    @GetMapping
    public List<AudiencePlanListItem> list(@CurrentUser AuthPrincipal p, @RequestParam(required = false) String from) {
        return plans.list(p.orgId(), from);
    }
}
