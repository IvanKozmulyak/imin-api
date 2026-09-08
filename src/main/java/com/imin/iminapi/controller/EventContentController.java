package com.imin.iminapi.controller;

import com.imin.iminapi.dto.EventContentRequest;
import com.imin.iminapi.dto.EventContentResponse;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.EventContentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventContentController {

    private final EventContentService eventContentService;
    private final RateLimiter rateLimiter;

    /**
     * Unauthenticated ({@code SecurityConfig} permits it explicitly) and every
     * call spends an LLM token budget imin pays for. Keyed per client IP —
     * there is no principal to key on, which is the whole problem.
     */
    @PostMapping("/ai-content")
    public EventContentResponse generate(@Valid @RequestBody EventContentRequest request,
                                         HttpServletRequest http) {
        rateLimiter.consume("ai-content", "ip:" + http.getRemoteAddr());
        return eventContentService.generate(request);
    }
}
