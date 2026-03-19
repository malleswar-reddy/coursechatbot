package com.coursechatbot.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Request body for POST /api/chat. */
@Data
public class ChatRequest {

    @NotBlank(message = "courseId must not be blank")
    private String courseId;

    @NotBlank(message = "question must not be blank")
    private String question;

    /** "LEARN" or "EXAM" — defaults to LEARN if null */
    private String mode;

    /** "BEGINNER", "INTERMEDIATE", or "ADVANCED" — defaults to INTERMEDIATE if null */
    private String difficultyLevel;

    /** nullable — UUID string of the current session */
    private String sessionId;
}
