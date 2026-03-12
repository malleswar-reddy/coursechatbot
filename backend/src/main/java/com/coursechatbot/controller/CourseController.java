package com.coursechatbot.controller;

import com.coursechatbot.dto.IngestRequest;
import com.coursechatbot.service.CourseIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for ingesting course PageIndex data.
 *
 * POST /api/courses  — Ingest a PageIndex JSON (from build_index.py) into the database.
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
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody IngestRequest request) {
        log.info("Ingest request for courseId={}", request.getCourseId());
        int pagesIngested = ingestionService.ingest(request.getCourseId(), request.getIndexJson());
        return ResponseEntity.ok(Map.of(
                "courseId", request.getCourseId(),
                "pagesIngested", pagesIngested,
                "status", "ok"
        ));
    }
}
