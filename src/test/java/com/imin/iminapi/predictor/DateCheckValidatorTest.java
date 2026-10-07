package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.dto.AssumptionsPatch;
import com.imin.iminapi.predictor.dto.DateCheckRequest;
import com.imin.iminapi.predictor.dto.DateCheckRequest.KnownEventInput;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBankLoader;
import com.imin.iminapi.predictor.service.DateCheckValidator;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Field codes are the shared contract with the webapp; every rule answers 422 FIELD_INVALID. */
class DateCheckValidatorTest {

    private static final QuestionBank BANK = QuestionBankLoader.load(new DefaultResourceLoader());
    /** 23:30Z on 16 Nov 2026: already the 17th in Paris. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T23:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate PARIS_TODAY = LocalDate.of(2026, 11, 17);

    private final DateCheckValidator validator = new DateCheckValidator(BANK, new DateCheckProperties(), CLOCK);

    private static DateCheckRequest req(String city, String country, String genre, String subGenre, List<LocalDate> dates) {
        return new DateCheckRequest(city, country, null, null, genre, subGenre, dates, 300, 2000L, "club", 23, 5,
                List.of(), null, null, null, null, null);
    }

    private static DateCheckRequest paris(LocalDate... dates) {
        return req("Paris", "FR", "house & techno", null, Arrays.asList(dates));
    }

    private Map<String, String> fields(DateCheckRequest r, String orgCountry) {
        try {
            validator.validate(r, orgCountry);
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo(ErrorCode.FIELD_INVALID);
            return e.fields();
        }
        throw new AssertionError("expected a 422");
    }

    private Map<String, String> fields(DateCheckRequest r) {
        return fields(r, null);
    }

    @Test
    void validRequestResolvesCountryAndVenueToday() {
        DateCheckValidator.Resolved r = validator.validate(paris(PARIS_TODAY.plusDays(10)), null);

        assertThat(r.country()).isEqualTo("FR");
        assertThat(r.today()).isEqualTo(PARIS_TODAY);
    }

    private static DateCheckRequest full(Integer capacity, Long priceMinor, Integer startHour, Integer endHour,
                                         List<String> lineup, List<KnownEventInput> known, List<String> communities) {
        return new DateCheckRequest("Paris", "FR", null, null, "house & techno", null,
                List.of(PARIS_TODAY.plusDays(3)), capacity, priceMinor, null, startHour, endHour, lineup, known,
                null, communities, null, null);
    }

    static Stream<Arguments> oneRuleRejections() {
        List<LocalDate> three = List.of(PARIS_TODAY.plusDays(3));
        List<LocalDate> six = new ArrayList<>();
        for (int i = 1; i <= 6; i++) six.add(PARIS_TODAY.plusDays(i));
        List<String> lineup = new ArrayList<>();
        for (int i = 0; i < 21; i++) lineup.add("dj" + i);
        LocalDate d = PARIS_TODAY.plusDays(5);
        return Stream.of(
                Arguments.of("emptyDates", paris(), Map.of("dates", "required"), false),
                Arguments.of("nullDates", req("Paris", "FR", "house & techno", null, null), Map.of("dates", "required"), false),
                Arguments.of("nullDateInList", req("Paris", "FR", "house & techno", null, Collections.singletonList(null)),
                        Map.of("dates[0]", "required"), false),
                Arguments.of("sixDates", req("Paris", "FR", "house & techno", null, six), Map.of("dates", "too_many"), false),
                Arguments.of("duplicateDates", paris(d, d), Map.of("dates", "duplicate"), false),
                Arguments.of("capacityAndPriceZero", full(0, 0L, null, null, null, null, null),
                        Map.of("capacity", "must_be_positive", "priceMinor", "must_be_positive"), false),
                Arguments.of("blankCity", req("  ", "FR", "house & techno", null, three), Map.of("city", "required"), false),
                Arguments.of("cityTooLong", req("x".repeat(101), "FR", "house & techno", null, three),
                        Map.of("city", "too_long"), false),
                Arguments.of("unknownGenre", req("Paris", "FR", "polka", null, three), Map.of("genreFamily", "unknown"), false),
                Arguments.of("missingGenre", req("Paris", "FR", null, null, three), Map.of("genreFamily", "required"), false),
                Arguments.of("subGenreNotInBucketList", req("Paris", "FR", "house & techno", "dubstep", three),
                        Map.of("subGenre", "unknown"), false),
                Arguments.of("hourOutOfRange", full(null, null, 24, -1, null, null, null),
                        Map.of("startHour", "out_of_range", "endHour", "out_of_range"), false),
                Arguments.of("lineupOverTwenty", full(null, null, null, null, lineup, null, null),
                        Map.of("lineup", "too_many"), false),
                Arguments.of("communityNotACountryCode", full(null, null, null, null, null, null, List.of("ng", "Nigeria")),
                        Map.of("communities[1]", "unknown"), true),
                Arguments.of("nullKnownEvent", full(null, null, null, null, null, Collections.singletonList(null), null),
                        Map.of("knownEvents[0]", "required"), true),
                Arguments.of("noCountryAnywhere", req("Paris", null, "house & techno", null, three),
                        Map.of("country", "required"), false),
                Arguments.of("countryNotACode", req("Paris", "France", "house & techno", null, three),
                        Map.of("country", "unknown"), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneRuleRejections")
    void oneRuleRejectionIs422(String name, DateCheckRequest r, Map<String, String> expected, boolean only) {
        Map<String, String> fields = fields(r);

        assertThat(fields).containsAllEntriesOf(expected);
        if (only) assertThat(fields).containsOnlyKeys(expected.keySet());
    }

    @Test
    void pastDateIs422InVenueZone() {
        assertThat(fields(paris(LocalDate.of(2026, 11, 16)))).containsEntry("dates[0]", "past");
        validator.validate(paris(PARIS_TODAY), null);
    }

    @Test
    void horizonBoundaryInclusive() {
        LocalDate edge = PARIS_TODAY.plusMonths(18);

        validator.validate(paris(edge), null);
        assertThat(fields(paris(PARIS_TODAY.plusDays(1), edge.plusDays(1))))
                .containsEntry("dates[1]", "beyond_horizon");
    }

    @Test
    void subGenreFromYamlAccepted() {
        validator.validate(req("Paris", "FR", "house & techno", "melodic techno", List.of(PARIS_TODAY.plusDays(3))), null);
    }

    @Test
    void knownEventStrength3Rejected() {
        DateCheckRequest r = new DateCheckRequest("Paris", "FR", null, null, "house & techno", null,
                List.of(PARIS_TODAY.plusDays(3)), null, null, null, null, null, null,
                List.of(new KnownEventInput("Rival", PARIS_TODAY.plusDays(3), null, 3),
                        new KnownEventInput(" ", null, null, null)),
                null, null, null, null);

        assertThat(fields(r)).containsEntry("knownEvents[0].strength", "out_of_range")
                .containsEntry("knownEvents[1].name", "required")
                .containsEntry("knownEvents[1].date", "required")
                .containsEntry("knownEvents[1].strength", "out_of_range");
    }

    @Test
    void assumptionFieldsChecked() {
        DateCheckRequest r = new DateCheckRequest("Paris", "FR", "7".repeat(17), null, "house & techno", null,
                List.of(PARIS_TODAY.plusDays(3)), null, null, "f".repeat(33), null, null, null, null,
                List.of(40, 20), Arrays.asList("NG", null), 0, null);

        assertThat(fields(r)).containsEntry("postalCode", "too_long").containsEntry("format", "too_long")
                .containsEntry("audienceAge", "out_of_range").containsEntry("communities[1]", "required")
                .containsEntry("buyingLeadDays", "must_be_positive");
    }

    @Test
    void emptyAudienceAgeRejected() {
        DateCheckRequest r = new DateCheckRequest("Paris", "FR", null, null, "house & techno", null,
                List.of(PARIS_TODAY.plusDays(3)), null, null, null, null, null, null, null,
                List.of(), null, null, null);

        assertThat(fields(r)).containsOnlyKeys("audienceAge").containsEntry("audienceAge", "out_of_range");
        assertThatThrownBy(() -> validator.validatePatch(new AssumptionsPatch(List.of(), null, null, null, null),
                "FR", List.of(PARIS_TODAY)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields())
                        .containsOnlyKeys("audienceAge").containsEntry("audienceAge", "out_of_range"));
    }

    @Test
    void unmappedCountryUsesUtcToday() {
        assertThat(validator.today("ZZ")).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(validator.today("FR")).isEqualTo(PARIS_TODAY);
        assertThat(validator.validate(req("Nowhere", "ZZ", "house & techno", null,
                List.of(LocalDate.of(2026, 11, 16))), null).today()).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void countryFallsBackToOrg() {
        DateCheckValidator.Resolved r = validator.validate(
                req("Amsterdam", null, "house & techno", null, List.of(PARIS_TODAY.plusDays(3))), "nl");

        assertThat(r.country()).isEqualTo("NL");
        assertThat(validator.validate(
                req("Paris", " fr ", "house & techno", null, List.of(PARIS_TODAY.plusDays(3))), "NL").country())
                .isEqualTo("FR");
    }

    @Test
    void allErrorsCollectedInOneResponse() {
        LocalDate d = PARIS_TODAY.plusDays(2);
        DateCheckRequest r = new DateCheckRequest("", "FR", null, null, "polka", null, List.of(d, d), -5, null, null,
                30, null, null, null, null, null, null, null);

        assertThat(fields(r)).containsOnlyKeys("city", "genreFamily", "dates", "capacity", "startHour");
    }

    @Test
    void patchChecksTheSameCodesAndStoredDatesStillAhead() {
        AssumptionsPatch bad = new AssumptionsPatch(List.of(10), null, -1L, 25, 0);

        assertThatThrownBy(() -> validator.validatePatch(bad, "FR", List.of(PARIS_TODAY)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields())
                        .containsEntry("audienceAge", "out_of_range")
                        .containsEntry("priceMinor", "must_be_positive")
                        .containsEntry("startHour", "out_of_range")
                        .containsEntry("buyingLeadDays", "must_be_positive"));
        assertThatThrownBy(() -> validator.validatePatch(new AssumptionsPatch(null, List.of(), null, null, null),
                "FR", List.of(PARIS_TODAY.minusDays(1), PARIS_TODAY)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields())
                        .containsOnlyKeys("check").containsEntry("check", "past"));
        assertThat(validator.validatePatch(new AssumptionsPatch(null, List.of(), null, null, null), "FR",
                List.of(PARIS_TODAY))).isEqualTo(PARIS_TODAY);
    }
}
