package com.imin.iminapi.controller;

import com.imin.iminapi.dto.EventContentRequest;
import com.imin.iminapi.dto.EventContentResponse;
import com.imin.iminapi.service.EventContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventContentController {

    private final EventContentService eventContentService;

    @PostMapping("/ai-content")
    public EventContentResponse generate(@Valid @RequestBody EventContentRequest request) {
        return eventContentService.generate(request);
    }
}
