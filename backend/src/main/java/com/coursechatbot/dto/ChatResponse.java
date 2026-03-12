package com.coursechatbot.dto;

import lombok.Builder;
import lombok.Data;

/** Response body for POST /api/chat. */
@Data
@Builder
public class ChatResponse {

    private String answer;
    private int startPage;
    private int endPage;
    private String sectionReason;
    private String courseId;
}
