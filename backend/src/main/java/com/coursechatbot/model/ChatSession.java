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
 * Tracks one study session — tied to a course, mode and difficulty level.
 */
@Table("chat_session")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSession {

    @Id
    @Column("session_id")
    private UUID sessionId;

    @Column("course_id")
    private String courseId;

    /** LEARN or EXAM */
    @Column("mode")
    private String mode;

    /** BEGINNER, INTERMEDIATE, or ADVANCED */
    @Column("difficulty")
    private String difficulty;

    @Column("started_at")
    private OffsetDateTime startedAt;

    @Column("message_count")
    private int messageCount;

    @Column("hint_count")
    private int hintCount;
}

