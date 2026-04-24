package com.coursechatbot.repository;

import com.coursechatbot.model.ExamPerformance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Plain interface — no R2DBC/Spring Data.
 * Exam performance tracking is in-memory via SessionService.
 */
public interface ExamPerformanceRepository {

    Flux<ExamPerformance> findBySessionId(UUID sessionId);

    Mono<Long> countHintsBySessionId(UUID sessionId);
}
