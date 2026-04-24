package com.coursechatbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tracks one study session — in-memory only (no database).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSession {

    private UUID sessionId;
    private String courseId;
    /** LEARN or EXAM */
    private String mode;
    /** BEGINNER, INTERMEDIATE, or ADVANCED */
    private String difficulty;
    private OffsetDateTime startedAt;
    private int messageCount;
    private int hintCount;
}
