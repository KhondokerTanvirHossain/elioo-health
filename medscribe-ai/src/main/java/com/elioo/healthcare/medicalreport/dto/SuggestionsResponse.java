package com.elioo.healthcare.medicalreport.dto;

import com.elioo.healthcare.medicalreport.domain.Priority;
import com.elioo.healthcare.medicalreport.domain.Severity;
import com.elioo.healthcare.medicalreport.domain.SuggestionCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionsResponse {
    private String reportId;
    private String summary;
    private List<KeyFinding> keyFindings;
    private List<AiSuggestion> aiSuggestions;
    private String insight;
    private Severity riskLevel;
    private Boolean requiresImmediateAttention;
    private LocalDateTime generatedAt;
    private Double confidenceScore;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyFinding {
        private String finding;
        private Severity severity;
        private String interpretation;
        private String normalRange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiSuggestion {
        private SuggestionCategory category;
        private Priority priority;
        private String recommendation;
        private String rationale;
    }
}
