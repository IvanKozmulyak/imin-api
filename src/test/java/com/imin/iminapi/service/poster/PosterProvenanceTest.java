package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Act Art.50 provenance, decided from the URL rather than a client flag.
 *
 * <p>{@code events.poster_ai_generated} has existed since V71 with no writer for
 * TRUE, so every AI-rendered poster stored NULL. The one thing the server knows
 * for certain is where the image came from: {@link PosterImageStorage#writePng}
 * is the only writer of an {@code ai-posters/} object key or a local
 * {@code /images/} file, so a poster URL pointing at either was rendered here.
 */
class PosterProvenanceTest {

    @Test
    void recognises_an_r2_ai_poster_url() {
        assertThat(PosterImageStorage.isAiGeneratedPosterUrl(
                "https://cdn.imin.wtf/ai-posters/9f1c-4b2a.png")).isTrue();
    }

    @Test
    void recognises_the_local_disk_fallback_url() {
        assertThat(PosterImageStorage.isAiGeneratedPosterUrl(
                "https://api.imin.wtf/images/9f1c-4b2a.png")).isTrue();
    }

    @Test
    void a_media_upload_url_is_not_an_ai_poster() {
        assertThat(PosterImageStorage.isAiGeneratedPosterUrl(
                "https://cdn.imin.wtf/events/abc/poster-deadbeef.png")).isFalse();
    }

    @Test
    void a_pasted_third_party_url_is_not_an_ai_poster() {
        assertThat(PosterImageStorage.isAiGeneratedPosterUrl(
                "https://example.com/my-artwork.jpg")).isFalse();
    }

    @Test
    void null_and_blank_are_not_ai_posters() {
        assertThat(PosterImageStorage.isAiGeneratedPosterUrl(null)).isFalse();
        assertThat(PosterImageStorage.isAiGeneratedPosterUrl("  ")).isFalse();
    }

    /**
     * The identifier persisted on every variant must stay in step with the API
     * path that decides which model actually runs.
     */
    @Test
    void model_id_matches_the_generate_path() {
        assertThat(IdeogramV3Client.GENERATE_PATH).contains(IdeogramV3Client.MODEL_ID);
    }
}
