package com.imin.iminapi.controller.reference;

import com.imin.iminapi.dto.reference.CountryDto;
import com.imin.iminapi.util.StripeSupportedCountries;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {

    private static final List<CountryDto> COUNTRIES = buildCountries();

    @GetMapping("/countries")
    public ResponseEntity<List<CountryDto>> countries() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(COUNTRIES);
    }

    private static List<CountryDto> buildCountries() {
        return java.util.Arrays.stream(Locale.getISOCountries())
                .map(code -> new CountryDto(code, new Locale("", code).getDisplayCountry(Locale.ENGLISH)))
                .filter(c -> !c.name().isBlank() && !c.name().equals(c.code()))
                // Restrict to the Stripe-supported bloc (US / Canada / UK / EEA / Switzerland)
                // so only payable countries appear in signup / settings dropdowns. This also
                // keeps sanctioned jurisdictions out, since those aren't in the supported set.
                // The server-side checks in AuthService/OrgService still enforce the rule
                // defensively in case a client hand-rolls the code.
                .filter(c -> StripeSupportedCountries.isSupported(c.code()))
                .sorted(Comparator.comparing(CountryDto::name))
                .toList();
    }
}
