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
}
