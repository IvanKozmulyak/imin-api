package com.imin.iminapi.audience.dto;

public record SegmentResolveDto(int matched, int mailable, int excluded, long avgLtvMinor) {}
