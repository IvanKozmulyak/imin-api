package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 23:30Z on 2026-10-12 is already 2026-10-13 in Paris (CEST, +02:00). 2026-10-13 − 1095 days = 2023-10-14,
 * because 2024 is a leap year; its Paris midnight is 2023-10-13T22:00Z. In UTC the day is still 2026-10-12.
 */
class RetentionCutoffTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T23:30:00Z"), ZoneOffset.UTC);
    static final AudiencePlanLogic LOGIC = LogicLoader.load(new DefaultResourceLoader(), new AudiencePlanProperties());

    static Stream<Arguments> zones() {
        return Stream.of(
                arguments("Europe/Paris", true, "2023-10-14", "2023-10-13T22:00:00Z"),
                arguments("   ", true, "2023-10-13", "2023-10-13T00:00:00Z"),
                arguments("Mars/Olympus", true, "2023-10-13", "2023-10-13T00:00:00Z"),
                arguments(null, true, "2023-10-13", "2023-10-13T00:00:00Z"),
                arguments("Europe/Paris", false, "2023-10-13", "2023-10-13T00:00:00Z"));
    }

    @ParameterizedTest
    @MethodSource("zones")
    void cutoffIsTodayInTheOrgZoneMinusTheRetentionWindow(String timezone, boolean orgExists, String date, String at) {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setTimezone(timezone);
        when(orgs.findById(orgId)).thenReturn(orgExists ? Optional.of(org) : Optional.empty());

        RetentionCutoff.Cutoff c = new RetentionCutoff(orgs, LOGIC, CLOCK).forOrg(orgId);

        assertThat(LOGIC.logic().legal().retentionDays()).isEqualTo(1095);
        assertThat(c.date()).isEqualTo(LocalDate.parse(date));
        assertThat(c.at()).isEqualTo(Instant.parse(at));
    }
}
