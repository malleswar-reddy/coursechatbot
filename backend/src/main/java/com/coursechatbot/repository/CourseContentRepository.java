package com.coursechatbot.repository;

import com.coursechatbot.model.CourseContent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Plain interface — no R2DBC/Spring Data.
 * Course content is stored in ChromaDB; this interface is kept for
 * PageIndex test compatibility (Mockito mocks it directly).
 */
public interface CourseContentRepository {

    Flux<CourseContent> findPageRange(String courseId, int startPage, int endPage);

    Mono<Void> deleteByCourseId(String courseId);
}
