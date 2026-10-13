package com.roomfit.placement;

import com.roomfit.placement.dto.FeedbackClarificationResponse;
import java.util.Map;
import java.util.Set;

public record WorkspaceFeedbackPlan(FeedbackPlan plan, Map<String, Set<String>> preserve,
                                    FeedbackClarificationResponse clarification, String unsupportedReason) {}
