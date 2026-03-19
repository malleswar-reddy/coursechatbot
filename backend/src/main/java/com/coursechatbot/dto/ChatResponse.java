package com.coursechatbot.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Response body for POST /api/chat. */
@Data
@Builder
public class ChatResponse {

    private String answer;
    private int startPage;
    private int endPage;
    private String sectionReason;
    private String courseId;

    private String       sessionId;
    private String       mode;
    private String       difficultyLevel;
    private boolean      isHintOnly;
    private boolean      pomodoroReminder;
    private List<String> followUpSuggestions;
}
