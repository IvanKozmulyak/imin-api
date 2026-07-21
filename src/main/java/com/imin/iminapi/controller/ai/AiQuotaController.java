package com.imin.iminapi.controller.ai;

import com.imin.iminapi.dto.ai.AiQuotaResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.ai.AiQuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiQuotaController {

    private final AiQuotaService quota;

    public AiQuotaController(AiQuotaService quota) {
        this.quota = quota;
    }

    @GetMapping("/quota")
    public AiQuotaResponse quota(@CurrentUser AuthPrincipal p) {
        return quota.status(p);
    }
}
