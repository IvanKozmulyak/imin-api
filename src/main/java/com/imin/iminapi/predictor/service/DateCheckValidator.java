package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.dto.AssumptionsPatch;
import com.imin.iminapi.predictor.dto.DateCheckRequest;
import com.imin.iminapi.predictor.dto.DateCheckRequest.KnownEventInput;
import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBank.GenreProfile;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.util.CountryTimeZones;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a date-check request and collects every field error into one 422 FIELD_INVALID. The codes
 * (required, too_long, unknown, too_many, duplicate, past, beyond_horizon, must_be_positive, out_of_range)
 * are the contract the webapp translates.
 */
@Component
public class DateCheckValidator {

    static final int MAX_CITY = 100;
    static final int MAX_POSTAL_CODE = 16;
    static final int MAX_FORMAT = 32;
    static final int MAX_LINEUP = 20;
    static final int MAX_AGE = 120;
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    /** The checked country and "today" in its zone. */
    public record Resolved(String country, LocalDate today) {}

    private final QuestionBank bank;
    private final DateCheckProperties props;
    private final Clock clock;

    public DateCheckValidator(QuestionBank bank, DateCheckProperties props, Clock clock) {
        this.bank = bank;
        this.props = props;
        this.clock = clock;
    }

    public Resolved validate(DateCheckRequest req, String orgCountry) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (blank(req.city())) errors.put("city", "required");
        else if (req.city().trim().length() > MAX_CITY) errors.put("city", "too_long");

        String country = !blank(req.country()) ? req.country() : orgCountry;
        country = blank(country) ? null : country.trim().toUpperCase(Locale.ROOT);
        if (country == null) errors.put("country", "required");
        else if (!COUNTRY.matcher(country).matches()) errors.put("country", "unknown");

        if (req.postalCode() != null && req.postalCode().trim().length() > MAX_POSTAL_CODE) {
            errors.put("postalCode", "too_long");
        }

        if (blank(req.genreFamily())) {
            errors.put("genreFamily", "required");
        } else if (!QuestionBank.GENRE_BUCKETS.contains(req.genreFamily())) {
            errors.put("genreFamily", "unknown");
        } else if (!blank(req.subGenre())) {
            GenreProfile profile = bank.profiles().get(req.genreFamily());
            if (profile == null || !profile.subGenres().contains(req.subGenre())) errors.put("subGenre", "unknown");
        }

        LocalDate today = today(country != null && COUNTRY.matcher(country).matches() ? country : null);
        checkDates(req.dates(), today, errors);

        positive(errors, "capacity", req.capacity() == null ? null : req.capacity().longValue());
        positive(errors, "priceMinor", req.priceMinor());
        if (req.format() != null && req.format().trim().length() > MAX_FORMAT) errors.put("format", "too_long");
        hour(errors, "startHour", req.startHour());
        hour(errors, "endHour", req.endHour());
        if (req.lineup() != null && req.lineup().size() > MAX_LINEUP) errors.put("lineup", "too_many");

        List<KnownEventInput> known = req.knownEvents();
        if (known != null) {
            for (int i = 0; i < known.size(); i++) {
                KnownEventInput k = known.get(i);
                String at = "knownEvents[" + i + "]";
                if (k == null) {
                    errors.put(at, "required");
                    continue;
                }
                if (blank(k.name())) errors.put(at + ".name", "required");
                if (k.date() == null) errors.put(at + ".date", "required");
                if (k.strength() == null || k.strength() < 1 || k.strength() > 2) errors.put(at + ".strength", "out_of_range");
            }
        }
        assumptions(errors, req.audienceAge(), req.communities(), req.buyingLeadDays());

        throwIfAny(errors);
        return new Resolved(country, today);
    }

    /**
     * Checks a patch against a stored check. A stored date already behind today is the check's fault, not a
     * patch field, so it answers {@code check: past}. Returns that today.
     */
    public LocalDate validatePatch(AssumptionsPatch patch, String country, List<LocalDate> dates) {
        Map<String, String> errors = new LinkedHashMap<>();
        assumptions(errors, patch.audienceAge(), patch.communities(), patch.buyingLeadDays());
        positive(errors, "priceMinor", patch.priceMinor());
        hour(errors, "startHour", patch.startHour());
        LocalDate today = today(country);
        if (dates.stream().anyMatch(d -> d.isBefore(today))) errors.put("check", "past");
        throwIfAny(errors);
        return today;
    }

    /** Today in the country's zone; UTC when the country has no mapped zone. */
    public LocalDate today(String country) {
        ZoneId zone = country == null ? ZoneOffset.UTC
                : CountryTimeZones.zoneFor(country).<ZoneId>map(ZoneId::of).orElse(ZoneOffset.UTC);
        return LocalDate.now(clock.withZone(zone));
    }

    private void checkDates(List<LocalDate> dates, LocalDate today, Map<String, String> errors) {
        if (dates == null || dates.isEmpty()) {
            errors.put("dates", "required");
            return;
        }
        if (dates.size() > props.getMaxDates()) errors.put("dates", "too_many");
        Set<LocalDate> seen = new HashSet<>();
        LocalDate horizon = today.plusMonths(props.getMaxHorizonMonths());
        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            String at = "dates[" + i + "]";
            if (d == null) {
                errors.put(at, "required");
                continue;
            }
            if (!seen.add(d)) errors.putIfAbsent("dates", "duplicate");
            if (d.isBefore(today)) errors.put(at, "past");
            else if (d.isAfter(horizon)) errors.put(at, "beyond_horizon");
        }
    }

    private static void assumptions(Map<String, String> errors, List<Integer> age, List<String> communities,
                                    Integer buyingLeadDays) {
        // An empty range is rejected, not read as "no answer": omit the field to keep the profile value.
        if (age != null) {
            boolean ok = age.size() == 2 && age.get(0) != null && age.get(1) != null
                    && age.get(0) >= 0 && age.get(0) <= age.get(1) && age.get(1) <= MAX_AGE;
            if (!ok) errors.put("audienceAge", "out_of_range");
        }
        if (communities != null) {
            for (int i = 0; i < communities.size(); i++) {
                String c = communities.get(i);
                if (blank(c)) errors.put("communities[" + i + "]", "required");
                else if (!COUNTRY.matcher(c.trim().toUpperCase(Locale.ROOT)).matches()) {
                    errors.put("communities[" + i + "]", "unknown");
                }
            }
        }
        positive(errors, "buyingLeadDays", buyingLeadDays == null ? null : buyingLeadDays.longValue());
    }

    private static void positive(Map<String, String> errors, String field, Long v) {
        if (v != null && v <= 0) errors.put(field, "must_be_positive");
    }

    private static void hour(Map<String, String> errors, String field, Integer v) {
        if (v != null && (v < 0 || v > 23)) errors.put(field, "out_of_range");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void throwIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.FIELD_INVALID, "Validation failed", errors);
        }
    }
}
