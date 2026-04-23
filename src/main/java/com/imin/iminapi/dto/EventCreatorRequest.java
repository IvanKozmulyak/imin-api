package com.imin.iminapi.dto;

import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.service.AiEventDescriptionService;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record EventCreatorRequest(
        @NotBlank String vibe,
        @NotBlank String tone,
        @NotBlank String genre,
        @NotBlank String city,
        @NotNull LocalDate date,
        @NotEmpty List<String> platforms,
        String djName,
        String location,
        String title,
        String accentColor,
        String address,
        String rsvpUrl,
        String subStyleTag,
        ImageProvider imageProvider
) {
    public ImageProvider effectiveImageProvider() {
        return imageProvider != null ? imageProvider : ImageProvider.REPLICATE;
    }

    @AssertTrue(message = "subStyleTag must be one of the known style tags")
    public boolean isSubStyleTagValid() {
        return subStyleTag == null
                || subStyleTag.isBlank()
                || AiEventDescriptionService.VALID_SUB_STYLE_TAGS.contains(subStyleTag);
    }
}
