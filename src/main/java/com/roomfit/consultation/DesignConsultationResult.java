package com.roomfit.consultation;

import java.util.List;

public record DesignConsultationResult(String revision, String source, String summary,
                                       List<Requirement> requirements, List<String> questions, ConsultationPreparation.Result preparation) {
    public DesignConsultationResult(String revision,String source,String summary,List<Requirement> requirements,List<String> questions) {
        this(revision,source,summary,requirements,questions,null);
    }
    public record Requirement(String kind, String value, String description, String source,
                              String evidence, Integer imageIndex, String priority, String support) {}
}
