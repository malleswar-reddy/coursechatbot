package com.coursechatbot.repository;

import com.coursechatbot.model.CourseIndex;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Plain interface — no R2DBC/Spring Data.
 * Course index metadata is served from ChromaDB; this interface is kept for
 * PageIndex test compatibility (Mockito mocks it directly).
 */
public interface CourseIndexRepository {

    Mono<CourseIndex> findById(String courseId);

    Flux<CourseIndex> findAll();

    Mono<Void> deleteById(String courseId);
}
