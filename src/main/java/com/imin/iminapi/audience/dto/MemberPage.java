package com.imin.iminapi.audience.dto;

import java.util.List;

public record MemberPage(List<MemberDto> items, String nextCursor) {}
