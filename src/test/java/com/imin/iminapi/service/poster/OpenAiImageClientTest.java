package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiImageClientTest {

    @Test
    void mapAspectRatio_maps1to1ToSquare() {
        assertThat(OpenAiImageClient.mapAspectRatio("1:1")).isEqualTo("1024x1024");
    }

    @Test
    void mapAspectRatio_mapsPortraitRatiosToTall() {
        assertThat(OpenAiImageClient.mapAspectRatio("3:4")).isEqualTo("1024x1536");
        assertThat(OpenAiImageClient.mapAspectRatio("4:5")).isEqualTo("1024x1536");
    }

    @Test
    void mapAspectRatio_mapsLandscapeRatiosToWide() {
        assertThat(OpenAiImageClient.mapAspectRatio("3:2")).isEqualTo("1536x1024");
        assertThat(OpenAiImageClient.mapAspectRatio("16:9")).isEqualTo("1536x1024");
    }

    @Test
    void mapAspectRatio_nullFallsBackToPortrait() {
        assertThat(OpenAiImageClient.mapAspectRatio(null)).isEqualTo("1024x1536");
    }
}
