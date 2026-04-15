package com.imin.iminapi.dto;

import java.util.List;

public record LlmGenerationResult(
        List<LlmEventConcept> concepts,
        List<LlmSocialCopy> socialCopy,
        List<String> accentColors
) {}
