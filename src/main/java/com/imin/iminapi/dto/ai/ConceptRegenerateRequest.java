package com.imin.iminapi.dto.ai;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ConceptRegenerateRequest(@NotNull UUID conceptId, List<String> lock) {}
