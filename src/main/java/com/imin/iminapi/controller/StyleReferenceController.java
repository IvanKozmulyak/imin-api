package com.imin.iminapi.controller;

import com.imin.iminapi.dto.StyleReferenceSummary;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.poster.ReferenceImageLibrary;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/v1/posters/style-references")
@RequiredArgsConstructor
public class StyleReferenceController {

    private static final Logger log = LoggerFactory.getLogger(StyleReferenceController.class);

    private final ReferenceImageLibrary library;

    @GetMapping
    public List<StyleReferenceSummary> list() {
        return library.tags().stream()
                .map(tag -> new StyleReferenceSummary(
                        tag,
                        humanize(tag),
                        IntStream.range(0, library.referenceCount(tag))
                                .mapToObj(i -> "/api/v1/posters/style-references/" + tag + "/" + i)
                                .toList()))
                .toList();
    }

    @GetMapping("/{tag}/{index}")
    public ResponseEntity<byte[]> image(@PathVariable String tag, @PathVariable int index) {
        try {
            byte[] bytes = library.loadBytes(tag, index);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            // The whole /api/v1/posters/** tree is permitAll, and GlobalExceptionHandler copies a
            // ResponseStatusException's reason into the body — so reflecting e.getMessage() here
            // handed anonymous callers whatever the library chose to say about the classpath,
            // including the per-tag reference count ("size=K"). Fixed string out, detail in the log.
            log.debug("Style reference not found (tag={}, index={}): {}", tag, index, e.getMessage());
            throw ApiException.notFound("Style reference");
        }
    }

    static String humanize(String tag) {
        if (tag == null || tag.isBlank()) return "";
        StringBuilder out = new StringBuilder(tag.length());
        boolean capNext = true;
        for (char c : tag.toCharArray()) {
            if (c == '_' || c == '-') {
                out.append(' ');
                capNext = true;
            } else if (capNext) {
                out.append(Character.toUpperCase(c));
                capNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
