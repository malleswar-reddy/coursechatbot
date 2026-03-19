package com.coursechatbot.service;

import com.coursechatbot.model.CourseContent;
import com.coursechatbot.model.CourseIndex;
import com.coursechatbot.repository.CourseContentRepository;
import com.coursechatbot.repository.CourseIndexRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Handles ingestion of a PageIndex JSON into PostgreSQL — fully reactive.
 *
 * R2dbcEntityTemplate.insert() is used for CourseIndex so that a String @Id
 * always triggers INSERT (not an UPDATE attempt) even when the ID is non-null.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CourseIngestionService {

    private final CourseContentRepository contentRepository;
    private final CourseIndexRepository   indexRepository;
    private final R2dbcEntityTemplate     r2dbcTemplate;
    private final ObjectMapper            objectMapper;

    public Mono<List<String>> getAllCourseIds() {
        return indexRepository.findAll()
                .map(CourseIndex::getCourseId)
                .collectList();
    }

    public Mono<List<Map<String, Object>>> getAllCourses() {
        return indexRepository.findAll()
                .map(c -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("courseId",    c.getCourseId());
                    m.put("branch",      c.getBranch());
                    m.put("subject",     c.getSubject());
                    m.put("title",       c.getTitle());
                    return m;
                })
                .collectList();
    }

    @Transactional
    public Mono<Integer> ingest(String courseId, String indexJson) {
        Map<String, Object> indexMap = parseJson(indexJson);

        @SuppressWarnings("unchecked")
        Map<String, String> pages = (Map<String, String>) indexMap.remove("pages");

        String       indexWithoutPages = toJson(indexMap);
        CourseIndex  courseIndex       = CourseIndex.builder()
                .courseId(courseId)
                .indexJson(indexWithoutPages)
                .build();

        return contentRepository.deleteByCourseId(courseId)           // 1. delete old pages
                .then(indexRepository.deleteById(courseId))            // 2. delete old index
                .then(r2dbcTemplate.insert(courseIndex))               // 3. INSERT new index (always)
                .doOnSuccess(i -> log.info("Saved course index for courseId={}", courseId))
                .then(pages != null
                        ? Flux.fromIterable(pages.entrySet())
                              .map(e -> CourseContent.builder()
                                      .courseId(courseId)
                                      .pageNumber(Integer.parseInt(e.getKey()))
                                      .content(e.getValue())
                                      .build())
                              .as(contentRepository::saveAll)          // 4. INSERT all pages
                              .count()
                              .map(Long::intValue)
                        : Mono.just(0))
                .doOnSuccess(count -> log.info("Ingested {} pages for courseId={}", count, courseId));
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }
}
