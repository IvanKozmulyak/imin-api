package com.imin.iminapi.dto.ai;

import java.util.List;
import java.util.UUID;

public record ConceptSetResponse(UUID generatedEventId, List<ConceptCardDto> concepts) {}
