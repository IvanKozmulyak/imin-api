package com.imin.iminapi.controller;

import com.imin.iminapi.service.poster.ReferenceImageLibrary;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Anonymous style-reference images over the real classpath library. */
@IminIntegrationTest
class StyleReferenceControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ReferenceImageLibrary library;

    private String populatedTag() {
        return library.tags().stream().filter(t -> library.referenceCount(t) > 0).findFirst()
                .orElseThrow(() -> new AssertionError("no populated reference tag on the classpath"));
    }

    /** The Poster Studio picker: one entry per populated tag, a readable label and one URL per image. */
    @Test
    void the_catalog_lists_every_tag_with_a_label_and_its_image_urls() throws Exception {
        List<String> tags = library.tags();
        int i = tags.indexOf("brutalist_techno");
        assertThat(i).as("brutalist_techno is a curated vibe with flyers").isNotNegative();
        int count = library.referenceCount("brutalist_techno");

        mvc.perform(get("/api/v1/posters/style-references"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(tags.size()))
                .andExpect(jsonPath("$[" + i + "].tag").value("brutalist_techno"))
                .andExpect(jsonPath("$[" + i + "].label").value("Brutalist Techno"))
                .andExpect(jsonPath("$[" + i + "].imageUrls.length()").value(count))
                .andExpect(jsonPath("$[" + i + "].imageUrls[0]")
                        .value("/api/v1/posters/style-references/brutalist_techno/0"));
    }

    @Test
    void image_of_a_known_tag_is_served_as_png_bytes() throws Exception {
        String tag = populatedTag();
        byte[] expected = library.loadBytes(tag, 0);

        mvc.perform(get("/api/v1/posters/style-references/{tag}/0", tag))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(expected));
        assertThat(expected).isNotEmpty();
    }

    /**
     * The tree is permitAll and GlobalExceptionHandler copies a reason into the body, so the 404 must
     * be the fixed string, never the library's message (which carried the per-tag "size=K").
     */
    @ParameterizedTest
    @CsvSource({"known-tag-index-out-of-range", "unknown-tag"})
    void a_missing_image_is_a_404_that_does_not_echo_the_internal_message(String kind) throws Exception {
        String tag = kind.equals("unknown-tag") ? "nope_not_a_vibe" : populatedTag();
        int index = kind.equals("unknown-tag") ? 0 : library.referenceCount(tag) + 5;

        mvc.perform(get("/api/v1/posters/style-references/{tag}/{index}", tag, index))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("size="))))
                .andExpect(content().string(not(containsString("out of range"))))
                .andExpect(content().string(not(containsString("Unknown sub-style"))));
    }
}
