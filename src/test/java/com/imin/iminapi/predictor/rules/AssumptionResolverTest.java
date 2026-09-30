package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.Assumption.Field;
import com.imin.iminapi.predictor.rules.Assumption.Source;
import com.imin.iminapi.predictor.rules.QuestionBank.GenreProfile;
import com.imin.iminapi.predictor.rules.QuestionBank.ProfileField;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssumptionResolverTest {

    private static final String URL = "https://example.org/survey";

    private static final GenreProfile PROFILE = new GenreProfile(List.of("techno"),
            new ProfileField<>(List.of(21, 35), URL, false),
            new ProfileField<>(List.of("NG"), null, true),
            new ProfileField<>(List.of(12, 25), null, true),
            new ProfileField<>(23, null, true),
            new ProfileField<>(7, null, true));

    private static DateCheckInput input(String country, Long priceMinor, Integer startHour, List<Integer> age,
                                        List<String> communities, Integer lead) {
        return new DateCheckInput("Paris", country, null, null, null, "house & techno", null, 300, priceMinor, "club",
                startHour, 5, List.of(), null, UUID.fromString("00000000-0000-0000-0000-00000000000a"),
                LocalDate.of(2026, 9, 30), age, communities, lead, null);
    }

    @Test
    void organizerAssumptionOverridesProfile() {
        List<Assumption> out = AssumptionResolver.resolve(
                input("FR", 1500L, 22, List.of(25, 40), List.of("SN"), 14), PROFILE);

        assertThat(out).containsExactly(
                new Assumption(Field.AUDIENCE_AGE, List.of(25, 40), Source.ORGANIZER, false, null),
                new Assumption(Field.COMMUNITIES, List.of("SN"), Source.ORGANIZER, false, null),
                new Assumption(Field.PRICE_MINOR, List.of(1500L, 1500L), Source.ORGANIZER, false, null),
                new Assumption(Field.START_HOUR, 22, Source.ORGANIZER, false, null),
                new Assumption(Field.BUYING_LEAD_DAYS, 14, Source.ORGANIZER, false, null));
    }

    @Test
    void emptyInputFilledFromProfileWithProvenance() {
        List<Assumption> out = AssumptionResolver.resolve(input("FR", null, null, null, null, null), PROFILE);

        assertThat(out).containsExactly(
                new Assumption(Field.AUDIENCE_AGE, List.of(21, 35), Source.PROFILE, false, URL),
                new Assumption(Field.COMMUNITIES, List.of("NG"), Source.PROFILE, true, null),
                new Assumption(Field.PRICE_MINOR, List.of(1200L, 2500L), Source.PROFILE, true, null),
                new Assumption(Field.START_HOUR, 23, Source.PROFILE, true, null),
                new Assumption(Field.BUYING_LEAD_DAYS, 7, Source.PROFILE, true, null));
    }

    @Test
    void emptyCommunitiesListIsOrganizerValue() {
        List<Assumption> out = AssumptionResolver.resolve(input("FR", null, null, null, List.of(), null), PROFILE);

        assertThat(out).filteredOn(a -> a.field() == Field.COMMUNITIES)
                .containsExactly(new Assumption(Field.COMMUNITIES, List.of(), Source.ORGANIZER, false, null));
    }

    @Test
    void emptyAudienceAgeFallsBackToProfile() {
        List<Assumption> out = AssumptionResolver.resolve(input("FR", null, null, List.of(), null, null), PROFILE);

        assertThat(out).filteredOn(a -> a.field() == Field.AUDIENCE_AGE)
                .containsExactly(new Assumption(Field.AUDIENCE_AGE, List.of(21, 35), Source.PROFILE, false, URL));
    }

    @Test
    void profileFieldWithNullValueOmitted() {
        GenreProfile nullValues = new GenreProfile(List.of(),
                new ProfileField<>(null, URL, false),
                new ProfileField<>(null, null, true),
                new ProfileField<>(null, null, true),
                new ProfileField<>(null, null, true),
                new ProfileField<>(null, null, true));

        assertThat(AssumptionResolver.resolve(input("FR", null, null, null, null, null), nullValues)).isEmpty();
    }

    @Test
    void missingProfileFieldOmitted() {
        GenreProfile partial = new GenreProfile(List.of(), null, null, null, null,
                new ProfileField<>(7, null, true));

        List<Assumption> fromPartial = AssumptionResolver.resolve(input("FR", null, 22, null, null, null), partial);
        List<Assumption> noProfile = AssumptionResolver.resolve(input("FR", null, 22, null, null, null), null);

        assertThat(fromPartial).extracting(Assumption::field).containsExactly(Field.START_HOUR, Field.BUYING_LEAD_DAYS);
        assertThat(noProfile).containsExactly(new Assumption(Field.START_HOUR, 22, Source.ORGANIZER, false, null));
    }

    @Test
    void priceOmittedOutsideEurCountries() {
        List<Assumption> organizer = AssumptionResolver.resolve(input("UA", 50000L, null, null, null, null), PROFILE);
        List<Assumption> profile = AssumptionResolver.resolve(input("UA", null, null, null, null, null), PROFILE);
        List<Assumption> eur = AssumptionResolver.resolve(input("PT", null, null, null, null, null), PROFILE);

        assertThat(organizer).extracting(Assumption::field).doesNotContain(Field.PRICE_MINOR);
        assertThat(profile).extracting(Assumption::field).doesNotContain(Field.PRICE_MINOR);
        assertThat(eur).filteredOn(a -> a.field() == Field.PRICE_MINOR)
                .containsExactly(new Assumption(Field.PRICE_MINOR, List.of(1200L, 2500L), Source.PROFILE, true, null));
    }
}
