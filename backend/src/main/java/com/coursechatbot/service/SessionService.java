package com.coursechatbot.service;

import com.coursechatbot.dto.PerformanceSummaryResponse;
import com.coursechatbot.model.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * SessionService — in-memory stub (PostgreSQL/R2DBC removed; using ChromaDB).
 * All methods return lightweight stubs so the rest of the application compiles
 * and runs without any database.
 */
@Service
@Slf4j
public class SessionService {

    /**
     * Create a new in-memory study session (no DB persistence).
     */
    public Mono<ChatSession> createSession(String courseId, String mode, String difficulty) {
        ChatSession session = ChatSession.builder()
                .sessionId(UUID.randomUUID())
                .courseId(courseId)
                .mode(mode != null ? mode.toUpperCase() : "LEARN")
                .difficulty(difficulty != null ? difficulty.toUpperCase() : "INTERMEDIATE")
                .startedAt(OffsetDateTime.now())
                .messageCount(0)
                .hintCount(0)
                .build();
        log.info("Session created (in-memory): sessionId={} courseId={} mode={}", session.getSessionId(), courseId, mode);
        return Mono.just(session);
    }

    public Mono<Void> recordInteraction(UUID sessionId, String question,
                                        String responseType, String conceptTag) {
        return Mono.empty(); // no-op
    }

    public Mono<Boolean> isPomodoroThresholdReached(UUID sessionId) {
        return Mono.just(false); // no-op
    }

    public Mono<PerformanceSummaryResponse> getPerformanceSummary(UUID sessionId) {
        return Mono.just(PerformanceSummaryResponse.builder()
                .sessionId(sessionId != null ? sessionId.toString() : "")
                .questionsAttempted(0)
                .hintCount(0)
                .conceptGaps(List.of())
                .studyDurationMinutes(0)
                .build());
    }
}
