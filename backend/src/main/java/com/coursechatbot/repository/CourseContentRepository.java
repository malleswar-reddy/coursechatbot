package com.coursechatbot.repository;

import com.coursechatbot.model.CourseContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CourseContentRepository extends JpaRepository<CourseContent, Long> {

    /**
     * Retrieve page texts for a course within an inclusive page range.
     * Used by the PageIndex RAG flow after the LLM has selected a chapter.
     */
    @Query("""
           SELECT c FROM CourseContent c
           WHERE c.courseId = :courseId
             AND c.pageNumber BETWEEN :startPage AND :endPage
           ORDER BY c.pageNumber ASC
           """)
    List<CourseContent> findPageRange(
            @Param("courseId") String courseId,
            @Param("startPage") int startPage,
            @Param("endPage") int endPage
    );

    /** Delete all pages for a course (used when re-ingesting). */
    void deleteByCourseId(String courseId);
}
