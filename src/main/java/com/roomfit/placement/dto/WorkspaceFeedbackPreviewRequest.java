package com.roomfit.placement.dto;

import com.roomfit.room.FurnitureStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** A transient snapshot of the browser draft, not a request to save a Room or Layout. */
public record WorkspaceFeedbackPreviewRequest(
        @NotNull @Positive Long roomId,
        @NotBlank @Size(max = 128) String revision,
        @NotBlank @Size(max = 2000) String feedback,
        @NotNull @Valid RoomDimensions roomDimensions,
        @NotNull @Size(max = 24) List<@NotNull @Valid FurnitureSnapshot> furniture,
        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 128) String> lockedFurnitureIds,
        @Size(max = 128) String selectedFurnitureId,
        @NotNull @Valid Preferences preferences,
        @Size(max = 2) List<@NotNull @Valid ClarificationSelection> clarificationSelections
) {
    public WorkspaceFeedbackPreviewRequest(Long roomId, String revision, String feedback, RoomDimensions roomDimensions,
            List<FurnitureSnapshot> furniture, List<String> lockedFurnitureIds, String selectedFurnitureId, Preferences preferences) {
        this(roomId, revision, feedback, roomDimensions, furniture, lockedFurnitureIds, selectedFurnitureId, preferences, null);
    }

    public record ClarificationSelection(
            @NotBlank @Size(max = 128) String revision,
            @NotBlank @Size(max = 2000) String feedback,
            @NotBlank @Pattern(regexp = "targetFurnitureId|referenceFurnitureId") String requiredField,
            @NotBlank @Size(max = 128) String furnitureId) {}

    public record RoomDimensions(@Positive @DecimalMax("50") double width,
                                 @Positive @DecimalMax("50") double depth,
                                 @Positive @DecimalMax("10") double height) {}

    public record PositionSnapshot(@DecimalMin("-100") @DecimalMax("100") double x,
                                   @DecimalMin("-100") @DecimalMax("100") double z) {}

    public record FurnitureSnapshot(
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 48) String type,
            @NotBlank @Size(max = 200) String label,
            @Positive @DecimalMax("50") double width,
            @Positive @DecimalMax("50") double depth,
            @Positive @DecimalMax("10") double height,
            @NotNull @Valid PositionSnapshot position,
            @DecimalMin("-3600") @DecimalMax("3600") double rotation,
            @NotNull FurnitureStatus status,
            @Size(max = 128) String productId,
            @Size(max = 128) String variantId,
            @Size(max = 12) List<@NotBlank @Size(max = 48) String> styleTags
    ) {}

    public record Preferences(
            @NotBlank @Pattern(regexp = "휴식|업무 / 공부|취미 / 여가|수납") String purpose,
            @NotBlank @Pattern(regexp = "minimal|natural|modern|classic|midcentury") String style,
            @NotBlank @Pattern(regexp = "ivory|beige|gray|brown|green|blue|pink|black|auto") String palette
    ) {}
}
