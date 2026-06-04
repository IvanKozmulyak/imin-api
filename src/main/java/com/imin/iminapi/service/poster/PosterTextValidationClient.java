package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.PosterTextSpec;

import java.util.List;

public interface PosterTextValidationClient {
    ValidationResult validate(byte[] imageBytes, PosterTextSpec spec);

    record ValidationResult(boolean accepted, List<String> missingRequired, List<String> extraText) {}
}
