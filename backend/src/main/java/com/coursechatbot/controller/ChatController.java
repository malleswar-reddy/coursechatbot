package com.coursechatbot.controller;

import com.coursechatbot.dto.ChatRequest;
import com.coursechatbot.dto.ChatResponse;
import com.coursechatbot.service.PageIndexService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * REST API for the PageIndex-powered course chatbot.
 *
 * POST /api/chat  — Student asks a question; receives an AI-generated answer.
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final PageIndexService pageIndexService;

    /**
     * Answer a student question using the PageIndex RAG flow.
     *
     * Request body:
     * {
     *   "courseId": "course1",
     *   "question": "What is Panchakarma?"
     * }
     *
     * Response body:
     * {
     *   "answer": "Panchakarma is ...",
     *   "startPage": 15,
     *   "endPage": 20,
     *   "sectionReason": "Chapter 2 covers Panchakarma in detail",
     *   "courseId": "course1"
     * }
     */
    @PostMapping
    public Mono<ResponseEntity<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request: courseId={}, question={}", request.getCourseId(), request.getQuestion());
        return pageIndexService.answer(request.getCourseId(), request.getQuestion())
                .map(ResponseEntity::ok);
    }
}
