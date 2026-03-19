package com.coursechatbot.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Response for GET /api/chat/session/{id}/summary */
@Data
@Builder
public class PerformanceSummaryResponse {

    private String       sessionId;
    private int          questionsAttempted;
    private int          hintCount;
    private List<String> conceptGaps;
    private long         studyDurationMinutes;
}

