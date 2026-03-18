package com.coursechatbot.repository;

import com.coursechatbot.model.CourseContent;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CourseContentRepository extends ReactiveCrudRepository<CourseContent, Long> {

    /**
     * Retrieve page texts for a course within an inclusive page range.
     * Used by the PageIndex RAG flow after the keyword selector picks a chapter.
     */
    @Query("SELECT * FROM course_content WHERE course_id = :courseId " +
           "AND page_number BETWEEN :startPage AND :endPage ORDER BY page_number ASC")
    Flux<CourseContent> findPageRange(String courseId, int startPage, int endPage);

    /** Delete all pages for a course (used when re-ingesting). */
    Mono<Void> deleteByCourseId(String courseId);
}
