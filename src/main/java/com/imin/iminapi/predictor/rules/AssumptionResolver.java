package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.Assumption.Field;
import com.imin.iminapi.predictor.rules.Assumption.Source;
import com.imin.iminapi.predictor.rules.QuestionBank.GenreProfile;
import com.imin.iminapi.predictor.rules.QuestionBank.ProfileField;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** One assumption per {@link Field} in enum order: organizer value first, else the genre profile, else omitted. */
public final class AssumptionResolver {

    /** ponytail: priceMinor carries no currency, so price is assumed only where it is EUR like the profile. */
    static final Set<String> EUR_COUNTRIES = Set.of("FR", "NL", "DE", "ES", "BE", "LU", "PT");

    private AssumptionResolver() {}

    public static List<Assumption> resolve(DateCheckInput in, GenreProfile profile) {
        List<Assumption> out = new ArrayList<>();
        // An empty age range is no value; an explicit empty communities list is the organizer's own answer.
        add(out, Field.AUDIENCE_AGE, in.audienceAge() == null || in.audienceAge().isEmpty() ? null : in.audienceAge(),
                profileField(profile, GenreProfile::audienceAge));
        add(out, Field.COMMUNITIES, in.communities(), profileField(profile, GenreProfile::communities));
        if (EUR_COUNTRIES.contains(in.country())) {
            // One shape for both sources: [min, max] in minor units; a single organizer price is [p, p].
            Long p = in.priceMinor();
            add(out, Field.PRICE_MINOR, p == null ? null : List.of(p, p),
                    toMinor(profileField(profile, GenreProfile::typicalPriceEur)));
        }
        add(out, Field.START_HOUR, in.startHour(), profileField(profile, GenreProfile::typicalStartHour));
        add(out, Field.BUYING_LEAD_DAYS, in.buyingLeadDays(), profileField(profile, GenreProfile::buyingLeadDays));
        return out;
    }

    private static void add(List<Assumption> out, Field field, Object organizer, ProfileField<?> profile) {
        if (organizer != null) {
            out.add(new Assumption(field, organizer, Source.ORGANIZER, false, null));
        } else if (profile != null && profile.value() != null) {
            out.add(new Assumption(field, profile.value(), Source.PROFILE, profile.estimate(), profile.sourcedUrl()));
        }
    }

    private static <T> ProfileField<T> profileField(GenreProfile profile, Function<GenreProfile, ProfileField<T>> get) {
        return profile == null ? null : get.apply(profile);
    }

    private static ProfileField<List<Long>> toMinor(ProfileField<List<Integer>> eur) {
        if (eur == null || eur.value() == null) return null;
        List<Long> minor = eur.value().stream().map(v -> v * 100L).toList();
        return new ProfileField<>(minor, eur.sourcedUrl(), eur.estimate());
    }
}
