package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.model.VibeStyle;
import com.imin.iminapi.repository.VibeStyleRepository;
import com.imin.iminapi.security.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VibeStyleTrainingServiceTest {

    @Mock VibeLibrary vibeLibrary;
    @Mock ReferenceImageLibrary referenceLibrary;
    @Mock RecraftClient recraftClient;
    @Mock VibeStyleRepository vibeStyleRepository;

    private VibeStyleTrainingService service() {
        return new VibeStyleTrainingService(vibeLibrary, referenceLibrary, recraftClient, vibeStyleRepository);
    }

    private Vibe vibe(String id, String styleId) {
        return new Vibe(id, "Name", List.of(), "vs", List.of(), "typo", "comp",
                List.of(), List.of(), "recraft", List.of("reference-images/" + id), styleId, "layout", false,
                "subject", com.imin.iminapi.dto.StyleMode.TRAINED_STYLE_ID, null);
    }

    @Test
    void trainRecraftStyle_persistsReturnedStyleId() {
        when(vibeLibrary.byId("brutalist_techno")).thenReturn(Optional.of(vibe("brutalist_techno", null)));
        when(referenceLibrary.loadAllBytes("brutalist_techno"))
                .thenReturn(List.of(new byte[]{1}, new byte[]{2}));
        when(recraftClient.createStyle(any())).thenReturn("style-trained-001");
        when(vibeStyleRepository.findByVibeIdAndProvider("brutalist_techno", ImageProvider.RECRAFT))
                .thenReturn(Optional.empty());
        when(vibeStyleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VibeStyleTrainingService.TrainResult result = service().trainRecraftStyle("brutalist_techno");

        assertThat(result.styleId()).isEqualTo("style-trained-001");
        assertThat(result.provider()).isEqualTo(ImageProvider.RECRAFT);

        ArgumentCaptor<VibeStyle> saved = ArgumentCaptor.forClass(VibeStyle.class);
        verify(vibeStyleRepository).save(saved.capture());
        assertThat(saved.getValue().getVibeId()).isEqualTo("brutalist_techno");
        assertThat(saved.getValue().getProvider()).isEqualTo(ImageProvider.RECRAFT);
        assertThat(saved.getValue().getStyleId()).isEqualTo("style-trained-001");
    }

    @Test
    void trainRecraftStyle_unknownVibe_throwsNotFound() {
        when(vibeLibrary.byId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().trainRecraftStyle("nope"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void trainRecraftStyle_noReferences_throwsAndDoesNotCallRecraft() {
        when(vibeLibrary.byId("liquid_melodic")).thenReturn(Optional.of(vibe("liquid_melodic", null)));
        when(referenceLibrary.loadAllBytes("liquid_melodic")).thenReturn(List.of());

        assertThatThrownBy(() -> service().trainRecraftStyle("liquid_melodic"))
                .isInstanceOf(ApiException.class);
        verify(recraftClient, org.mockito.Mockito.never()).createStyle(any());
    }

    @Test
    void resolveStyleId_prefersTrainedRowOverYaml() {
        VibeStyle row = new VibeStyle();
        row.setVibeId("brutalist_techno");
        row.setProvider(ImageProvider.RECRAFT);
        row.setStyleId("trained-id");
        row.setTrainedAt(LocalDateTime.now());
        when(vibeStyleRepository.findByVibeIdAndProvider("brutalist_techno", ImageProvider.RECRAFT))
                .thenReturn(Optional.of(row));

        assertThat(service().resolveStyleId("brutalist_techno", ImageProvider.RECRAFT))
                .isEqualTo("trained-id");
    }

    @Test
    void resolveStyleId_fallsBackToYamlStyleId() {
        when(vibeStyleRepository.findByVibeIdAndProvider("brutalist_techno", ImageProvider.RECRAFT))
                .thenReturn(Optional.empty());
        when(vibeLibrary.byId("brutalist_techno"))
                .thenReturn(Optional.of(vibe("brutalist_techno", "yaml-style-id")));

        assertThat(service().resolveStyleId("brutalist_techno", ImageProvider.RECRAFT))
                .isEqualTo("yaml-style-id");
    }

    @Test
    void resolveStyleId_noTrainedRowNoYaml_returnsNull() {
        when(vibeStyleRepository.findByVibeIdAndProvider(eq("x"), any()))
                .thenReturn(Optional.empty());
        when(vibeLibrary.byId("x")).thenReturn(Optional.empty());

        assertThat(service().resolveStyleId("x", ImageProvider.RECRAFT)).isNull();
    }
}
