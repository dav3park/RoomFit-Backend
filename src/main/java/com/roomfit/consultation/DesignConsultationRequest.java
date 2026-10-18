package com.roomfit.consultation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Browser-supplied context only; this endpoint neither reads nor writes saved layouts. */
public record DesignConsultationRequest(
        @NotBlank @Size(max = 128) String revision,
        @NotNull @Size(max = 2000) String description,
        @NotNull @Size(max = 24) List<@NotNull @Valid Item> furniture, boolean recommendationReady) {
    public DesignConsultationRequest(String revision,String description,List<Item> furniture) { this(revision,description,furniture,false); }
    public record Item(@NotBlank @Size(max = 128) String id,
                       @NotBlank @Size(max = 200) String name,
                       @NotBlank @Size(max = 48) String type) {}
}
