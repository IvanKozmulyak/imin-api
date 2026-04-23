package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaUploadResponse(String url, long sizeBytes, String contentType, Integer durationSec) {}
