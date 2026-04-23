package com.imin.iminapi.controller;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.EventCreatorResponse;
import com.imin.iminapi.service.EventCreatorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventCreatorController {

    private final EventCreatorService eventCreatorService;

    @PostMapping("/ai-create")
    @ResponseStatus(HttpStatus.CREATED)
    public EventCreatorResponse create(@Valid @RequestBody EventCreatorRequest request) {
        return eventCreatorService.create(request);
    }
}
