package com.imin.iminapi.controller.reference;

import com.imin.iminapi.dto.reference.CountryDto;
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
                .sorted(Comparator.comparing(CountryDto::name))
                .toList();
    }
}
