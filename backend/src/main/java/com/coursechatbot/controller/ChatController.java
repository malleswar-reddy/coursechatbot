package com.coursechatbot.controller;

import com.coursechatbot.dto.ChatRequest;
import com.coursechatbot.dto.ChatResponse;
import com.coursechatbot.dto.PerformanceSummaryResponse;
import com.coursechatbot.service.PageIndexService;
import com.coursechatbot.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * REST API for the PageIndex-powered ExamPrep AI chatbot.
 *
 * POST /api/chat                         — Ask a question (LEARN or EXAM mode)
 * POST /api/chat/session                 — Create a new session
 * GET  /api/chat/session/{id}/summary    — Get performance summary
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final PageIndexService pageIndexService;
    private final SessionService   sessionService;

    /** Answer a student question using the PageIndex RAG flow. */
    @PostMapping
    public Mono<ResponseEntity<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request: courseId={}, mode={}, difficulty={}, question={}",
                request.getCourseId(), request.getMode(), request.getDifficultyLevel(), request.getQuestion());
        return pageIndexService.answer(request).map(ResponseEntity::ok);
    }

    /**
     * Create a new study session.
     * Body: { "courseId": "cse-pqb-1", "mode": "EXAM", "difficultyLevel": "INTERMEDIATE" }
     */
    @PostMapping("/session")
    public Mono<ResponseEntity<Map<String, Object>>> createSession(@RequestBody Map<String, String> body) {
        String courseId   = body.get("courseId");
        String mode       = body.getOrDefault("mode", "LEARN");
        String difficulty = body.getOrDefault("difficultyLevel", "INTERMEDIATE");
        return sessionService.createSession(courseId, mode, difficulty)
                .map(session -> ResponseEntity.ok(Map.<String, Object>of(
                        "sessionId",       session.getSessionId().toString(),
                        "courseId",        session.getCourseId(),
                        "mode",            session.getMode(),
                        "difficultyLevel", session.getDifficulty()
                )));
    }

    /** Return performance summary for a session. */
    @GetMapping("/session/{id}/summary")
    public Mono<ResponseEntity<PerformanceSummaryResponse>> getSessionSummary(@PathVariable String id) {
        return sessionService.getPerformanceSummary(UUID.fromString(id))
                .map(ResponseEntity::ok);
    }
}
