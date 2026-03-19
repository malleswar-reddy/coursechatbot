package com.coursechatbot.repository;

import com.coursechatbot.model.ChatSession;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ChatSessionRepository extends ReactiveCrudRepository<ChatSession, UUID> {

    Flux<ChatSession> findByCourseId(String courseId);
}

