package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.SourcedRate;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import com.imin.iminapi.audienceplan.service.TribeSize.Estimate;
import com.imin.iminapi.audienceplan.service.TribeSize.Input;
import com.imin.iminapi.audienceplan.service.TribeSize.Source;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tribe size for a genre in one or more cities (data-sources §1.7): people aged 18-35 × music listeners ×
 * bar / club concert goers × the genre's share = genre-first people; × frequent goers = regulars.
 * Low multiplies every low rate, high every high rate. A missing census figure or genre share gives null sizes.
 */
@Service
public class TribeSizeCalculator {

    static final String POPULATION = "pop_18_35";
    static final String LISTENERS = "music_listeners";
    static final String CONCERT_GOERS = "bar_club_concert_goers";
    static final String FREQUENT = "frequent_goers";
    static final String NO_SHARE_METHOD = "no_genre_share_rate";

    private final AudiencePlanLogic logic;
    private final PublicDataService publicData;

    public TribeSizeCalculator(AudiencePlanLogic logic, PublicDataService publicData) {
        Map<String, SourcedRate> rates = logic.priors().tribeSize();
        for (String key : List.of(LISTENERS, CONCERT_GOERS, FREQUENT)) {
            if (!rates.containsKey(key)) {
                throw new IllegalStateException("audience plan priors.tribe_size." + key + ": is missing");
            }
            if (!rates.get(key).genres().isEmpty()) {
                throw new IllegalStateException("audience plan priors.tribe_size." + key
                        + ".genres: a participation rate cannot be a genre share");
            }
        }
        this.logic = logic;
        this.publicData = publicData;
    }

    /**
     * @param genreKey one of the 8 genre bucket keys
     * @param cityKeys the cities whose 18-35 populations are summed; every one needs a census figure
     */
    public TribeSize estimate(String genreKey, List<String> cityKeys) {
        if (genreKey == null || !logic.genres().whitelist().contains(genreKey)) {
            throw new IllegalArgumentException("Not a genre bucket: " + genreKey);
        }
        if (cityKeys == null || cityKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one city is required");
        }
        List<String> cities = List.copyOf(new LinkedHashSet<>(cityKeys));
        Optional<Map.Entry<String, SourcedRate>> share = logic.priors().tribeSize().entrySet().stream()
                .filter(e -> e.getValue().genres().contains(genreKey))
                .findFirst();
        if (share.isEmpty()) {
            Estimate none = new Estimate(null, null, NO_SHARE_METHOD, List.of(), List.of());
            return new TribeSize(genreKey, cities, none, none);
        }

        List<Input> inputs = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        long population = 0;
        boolean known = true;
        for (String city : cities) {
            Optional<OpenDataValue> census = publicData.get(city, OpenDataset.INSEE_AGE);
            Long headline = census.map(OpenDataValue::headline).orElse(null);
            Double value = headline == null ? null : headline.doubleValue();
            inputs.add(new Input(POPULATION, city, value, value));
            census.ifPresent(c -> sources.add(new Source(POPULATION, city, c.attribution(), c.refPeriod(),
                    c.sourceUrl(), null, c.stale())));
            if (headline == null) {
                known = false;
            } else {
                population += headline;
            }
        }

        String shareKey = share.get().getKey();
        double low = population;
        double high = population;
        for (String key : List.of(LISTENERS, CONCERT_GOERS, shareKey)) {
            SourcedRate rate = logic.priors().tribeSize().get(key);
            addRate(key, rate, inputs, sources);
            low *= rate.low();
            high *= rate.high();
        }
        String method = String.join(" × ", POPULATION, LISTENERS, CONCERT_GOERS, shareKey);
        Estimate genreFirst = sized(known, low, high, method, inputs, sources);

        SourcedRate frequent = logic.priors().tribeSize().get(FREQUENT);
        addRate(FREQUENT, frequent, inputs, sources);
        Estimate regulars = sized(known, low * frequent.low(), high * frequent.high(),
                method + " × " + FREQUENT, inputs, sources);
        return new TribeSize(genreKey, cities, genreFirst, regulars);
    }

    private static Estimate sized(boolean known, double low, double high, String method,
                                     List<Input> inputs, List<Source> sources) {
        return known
                ? new Estimate(Math.round(low), Math.round(high), method, List.copyOf(inputs), List.copyOf(sources))
                : new Estimate(null, null, method, List.copyOf(inputs), List.copyOf(sources));
    }

    private static void addRate(String key, SourcedRate rate, List<Input> inputs, List<Source> sources) {
        inputs.add(new Input(key, null, rate.low(), rate.high()));
        sources.add(new Source(key, null, rate.source(), rate.year() == null ? null : String.valueOf(rate.year()),
                null, rate.note(), false));
    }
}
