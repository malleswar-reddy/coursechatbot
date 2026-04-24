package com.coursechatbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Records each individual interaction inside an exam session — plain POJO (no DB).
 * Response types: HINT | EXPLAINED | REFUSED
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamPerformance {

    private Long id;
    private UUID sessionId;
    private String question;
    /** HINT | EXPLAINED | REFUSED */
    private String responseType;
    private String conceptTag;
    private OffsetDateTime createdAt;
}
