package com.coursechatbot.controller;

import com.coursechatbot.dto.IngestRequest;
import com.coursechatbot.service.CourseIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * REST API for course ingestion and listing.
 *
 * POST /api/courses             — Ingest a PageIndex JSON
 * GET  /api/courses/courseIds   — Return all course IDs (legacy)
 * GET  /api/courses             — Return full course metadata (branch/subject) for grouped UI
 */
@RestController
@RequestMapping("/api/courses")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class CourseController {

    private final CourseIngestionService ingestionService;

    /**
     * Ingest a course's PageIndex JSON.
     *
     * Request body:
     * {
     *   "courseId": "course1",
     *   "indexJson": "{ ...output of build_index.py... }"
     * }
     */
    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> ingest(@Valid @RequestBody IngestRequest request) {
        log.info("Ingest request for courseId={}", request.getCourseId());
        return ingestionService.ingest(request.getCourseId(), request.getIndexJson())
                .map(pagesIngested -> ResponseEntity.ok(Map.<String, Object>of(
                        "courseId",      request.getCourseId(),
                        "pagesIngested", pagesIngested,
                        "status",        "ok"
                )));
    }

    /** Legacy — returns just a flat list of course IDs. */
    @GetMapping("/courseIds")
    public Mono<List<String>> getAllCourseIds() {
        return ingestionService.getAllCourseIds();
    }

    /** Returns all courses with branch/subject metadata for the grouped UI dropdown. */
    @GetMapping
    public Mono<List<Map<String, Object>>> getAllCourses() {
        return ingestionService.getAllCourses();
    }
}
