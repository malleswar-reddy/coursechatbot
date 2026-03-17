package com.coursechatbot.service;

import com.coursechatbot.model.CourseContent;
import com.coursechatbot.model.CourseIndex;
import com.coursechatbot.repository.CourseContentRepository;
import com.coursechatbot.repository.CourseIndexRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Handles ingestion of a PageIndex JSON (produced by build_index.py) into PostgreSQL.
 *
 * Stores:
 *   - The hierarchical index (without page texts) in the course_index table.
 *   - Each page's text in the course_content table.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CourseIngestionService {

    private final CourseContentRepository contentRepository;
    private final CourseIndexRepository indexRepository;
    private final ObjectMapper objectMapper;

    public List<String> getAllCourseIds() {
        return indexRepository.findAll().stream()
                .map(CourseIndex::getCourseId)
                .toList();
    }

    @Transactional
    public int ingest(String courseId, String indexJson) {
        Map<String, Object> indexMap = parseJson(indexJson);

        // Strip inline pages from the index and store them separately
        @SuppressWarnings("unchecked")
        Map<String, String> pages = (Map<String, String>) indexMap.remove("pages");

        // Persist the index (without pages)
        String indexWithoutPages = toJson(indexMap);
        CourseIndex courseIndex = CourseIndex.builder()
                .courseId(courseId)
                .indexJson(indexWithoutPages)
                .build();
        indexRepository.save(courseIndex);
        log.info("Saved course index for courseId={}", courseId);

        // Remove existing pages and re-insert
        contentRepository.deleteByCourseId(courseId);

        int count = 0;
        if (pages != null) {
            for (Map.Entry<String, String> entry : pages.entrySet()) {
                int pageNum = Integer.parseInt(entry.getKey());
                CourseContent cc = CourseContent.builder()
                        .courseId(courseId)
                        .pageNumber(pageNum)
                        .content(entry.getValue())
                        .build();
                contentRepository.save(cc);
                count++;
            }
        }
        log.info("Ingested {} pages for courseId={}", count, courseId);
        return count;
    }

    @SuppressWarnings("unchecked")
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
