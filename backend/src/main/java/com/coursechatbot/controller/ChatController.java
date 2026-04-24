package com.coursechatbot.controller;

import com.coursechatbot.dto.ChatRequest;
import com.coursechatbot.dto.ChatResponse;
import com.coursechatbot.dto.PerformanceSummaryResponse;
import com.coursechatbot.service.VectorRagService;
import com.coursechatbot.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * REST API for the Vector RAG (ChromaDB) Course Chatbot.
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

    private final VectorRagService vectorRagService;
    private final SessionService   sessionService;

    /** Answer a student question using ChromaDB Vector RAG flow. */
    @PostMapping
    public Mono<ResponseEntity<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request: courseId={}, mode={}, difficulty={}, question={}",
                request.getCourseId(), request.getMode(), request.getDifficultyLevel(), request.getQuestion());
        return vectorRagService.answer(request).map(ResponseEntity::ok);
    }

    /**
     * Streaming endpoint — returns tokens via Server-Sent Events as the LLM generates them.
     * Frontend reads with fetch() + ReadableStream.
     * Each event: "data: <token>\n\n"
     * Final event: "data: [DONE]\n\n"
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody ChatRequest request) {
        log.info("Stream request: courseId={}, mode={}, difficulty={}",
                request.getCourseId(), request.getMode(), request.getDifficultyLevel());
        return vectorRagService.answerStream(request)
                .map(token -> ServerSentEvent.<String>builder()
                        .data(token)
                        .build());
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
