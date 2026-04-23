package com.coursechatbot.service;

import com.coursechatbot.dto.PerformanceSummaryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * SessionService — stub (PostgreSQL/R2DBC removed; using ChromaDB).
 * All methods are no-ops that return empty Monos so VectorRagService
 * continues to work without any database.
 */
@Service
@Slf4j
public class SessionService {

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
