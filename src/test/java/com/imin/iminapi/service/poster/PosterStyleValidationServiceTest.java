package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.Rgb;
import com.imin.iminapi.dto.StyleCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PosterStyleValidationServiceTest {

    private static final byte[] IMAGE = new byte[] {1, 2, 3};

    private static StyleCard styleCard() {
        return new StyleCard(
                "neon-rave",
                "photo",
                List.of(new Rgb(10, 20, 30)),
                List.of("a dancer mid-spin under stage lights"),
                List.of("a cracked vinyl record on concrete"),
                List.of("hero centered, type along the bottom"),
                List.of("heavy film grain"),
                List.of("electric magenta on black"),
                List.of("condensed sans, all caps"),
                List.of("example prompt one"));
    }

    @Test
    void disabledShortCircuitsWithoutCallingClient() {
        PosterStyleValidationClient client = mock(PosterStyleValidationClient.class);
        PosterStyleValidationService service = new PosterStyleValidationService(client, false);

        PosterStyleValidationService.ValidationDecision decision =
                service.validateOrExplain(IMAGE, styleCard(), HeroType.PEOPLE);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void nullCardIsAcceptedWithoutCallingClient() {
        PosterStyleValidationClient client = mock(PosterStyleValidationClient.class);
        PosterStyleValidationService service = new PosterStyleValidationService(client, true);

        PosterStyleValidationService.ValidationDecision decision =
                service.validateOrExplain(IMAGE, null, HeroType.PEOPLE);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isNull();
        verify(client, never()).validate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptedResultProducesAcceptedDecision() {
        PosterStyleValidationClient client = mock(PosterStyleValidationClient.class);
        when(client.validate(IMAGE, styleCard(), HeroType.PEOPLE))
                .thenReturn(new PosterStyleValidationClient.StyleValidationResult(
                        true, true, true, true, List.of()));
        PosterStyleValidationService service = new PosterStyleValidationService(client, true);

        PosterStyleValidationService.ValidationDecision decision =
                service.validateOrExplain(IMAGE, styleCard(), HeroType.PEOPLE);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void rejectedResultWhenHeroSubjectAbsentProducesNonEmptyReason() {
        PosterStyleValidationClient client = mock(PosterStyleValidationClient.class);
        when(client.validate(IMAGE, styleCard(), HeroType.PEOPLE))
                .thenReturn(new PosterStyleValidationClient.StyleValidationResult(
                        false, false, true, true, List.of("no person visible in the poster")));
        PosterStyleValidationService service = new PosterStyleValidationService(client, true);

        PosterStyleValidationService.ValidationDecision decision =
                service.validateOrExplain(IMAGE, styleCard(), HeroType.PEOPLE);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.reason()).isNotBlank();
        assertThat(decision.reason()).contains("hero subject not present");
        assertThat(decision.reason()).contains("no person visible in the poster");
    }

    @Test
    void rejectedResultWithNoSignalsStillHasReason() {
        PosterStyleValidationClient client = mock(PosterStyleValidationClient.class);
        when(client.validate(IMAGE, styleCard(), HeroType.OBJECT))
                .thenReturn(new PosterStyleValidationClient.StyleValidationResult(
                        false, true, true, true, null));
        PosterStyleValidationService service = new PosterStyleValidationService(client, true);

        PosterStyleValidationService.ValidationDecision decision =
                service.validateOrExplain(IMAGE, styleCard(), HeroType.OBJECT);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.reason()).isNotBlank();
    }
}
