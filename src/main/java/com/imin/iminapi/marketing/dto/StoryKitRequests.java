package com.imin.iminapi.marketing.dto;

import jakarta.validation.constraints.NotBlank;

public record StoryKitRequests() {

    public record Create(@NotBlank String locale) {}

    public record CaptionRequest(int revision) {}
}
