package com.coursechatbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Records each individual interaction inside an exam session.
 * Response types: HINT | EXPLAINED | REFUSED
 */
@Table("exam_performance")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamPerformance {

    @Id
    @Column("id")
    private Long id;

    @Column("session_id")
    private UUID sessionId;

    @Column("question")
    private String question;

    /** HINT | EXPLAINED | REFUSED */
    @Column("response_type")
    private String responseType;

    @Column("concept_tag")
    private String conceptTag;

    @Column("created_at")
    private OffsetDateTime createdAt;
}

