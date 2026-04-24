package com.coursechatbot.repository;

import com.coursechatbot.model.ChatSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Plain interface — no R2DBC/Spring Data.
 * Sessions are managed in-memory via SessionService.
 */
public interface ChatSessionRepository {

    Flux<ChatSession> findByCourseId(String courseId);

    Mono<ChatSession> findById(UUID sessionId);
}
