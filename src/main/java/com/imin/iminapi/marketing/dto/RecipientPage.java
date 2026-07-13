package com.imin.iminapi.marketing.dto;

import java.util.List;

public record RecipientPage(List<RecipientDto> items, int page, int size) {}
