package com.imin.iminapi.predictor.dto;

import java.time.LocalDate;
import java.util.Map;

/** A dated to-do: {@code key} is an i18n template key, {@code params} its finding's facts. */
public record ActionDto(String key, LocalDate dueDate, String questionId, Map<String, Object> params) {}
