package com.roomfit.placement.dto;

import com.roomfit.placement.FeedbackOperationExecution;
import com.roomfit.placement.FeedbackStatus;
import com.roomfit.placement.ValidationResult;
import com.roomfit.room.Furniture;
import java.util.List;

public record WorkspaceFeedbackPreviewResponse(
        String revision,
        FeedbackStatus status,
        String source,
        String reasonCode,
        String summary,
        List<Furniture> furniture,
        List<FeedbackOperationExecution> operationResults,
        FeedbackClarificationResponse clarification,
        ValidationResult validationResult
) {}
