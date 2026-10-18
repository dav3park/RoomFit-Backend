package com.roomfit.consultation;

import java.util.List;

public record DesignConsultationResult(String revision, String source, String summary,
                                       List<Requirement> requirements, List<String> questions) {
    public record Requirement(String kind, String value, String description, String source,
                              String evidence, Integer imageIndex, String priority, String support) {}
}
