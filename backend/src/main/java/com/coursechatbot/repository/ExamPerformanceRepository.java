package com.coursechatbot.repository;

import com.coursechatbot.model.ExamPerformance;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ExamPerformanceRepository extends ReactiveCrudRepository<ExamPerformance, Long> {

    Flux<ExamPerformance> findBySessionId(UUID sessionId);

    @Query("SELECT COUNT(*) FROM exam_performance WHERE session_id = :sessionId AND response_type = 'HINT'")
    reactor.core.publisher.Mono<Long> countHintsBySessionId(UUID sessionId);
}

