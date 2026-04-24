package com.coursechatbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * CourseIngestionService — stub only.
 *
 * Course content is now ingested into ChromaDB via the Python script
 * {@code pageindex/ingest_to_chroma.py}. This service no longer writes to
 * PostgreSQL and does not require R2DBC.
 *
 * Course listings are served directly from ChromaDB by CourseController.
 */
@Service
@Slf4j
public class CourseIngestionService {

    /**
     * @deprecated Use pageindex/ingest_to_chroma.py to ingest PDFs into ChromaDB.
     */
    @Deprecated
    public Mono<Integer> ingest(String courseId, String indexJson) {
        log.warn("CourseIngestionService.ingest() is a no-op — use ingest_to_chroma.py instead.");
        return Mono.just(0);
    }

    /**
     * @deprecated Course IDs are now read from ChromaDB via CourseController.
     */
    @Deprecated
    public Mono<List<String>> getAllCourseIds() {
        return Mono.just(List.of());
    }

    /**
     * @deprecated Course metadata is now read from ChromaDB via CourseController.
     */
    @Deprecated
    public Mono<List<Map<String, Object>>> getAllCourses() {
        return Mono.just(List.of());
    }
}
