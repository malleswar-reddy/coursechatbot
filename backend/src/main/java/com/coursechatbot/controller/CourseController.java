package com.coursechatbot.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Course API — lists courses from ChromaDB collections.
 * POST /api/courses is removed (ingestion is via ingest_to_chroma.py script).
 * GET  /api/courses/courseIds — returns collection names from ChromaDB.
 * GET  /api/courses           — returns course metadata.
 */
@RestController
@RequestMapping("/api/courses")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class CourseController {

    private final WebClient.Builder webClientBuilder;

    @Value("${chroma.base-url:http://localhost:8001}")
    private String chromaBaseUrl;

    private static final String CHROMA_V2 =
            "/api/v2/tenants/default_tenant/databases/default_database";

    /**
     * Returns all course IDs (ChromaDB collection names).
     */
    @GetMapping("/courseIds")
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Mono<List<String>> getAllCourseIds() {
        return webClientBuilder.clone().baseUrl(chromaBaseUrl).build()
                .get()
                .uri(CHROMA_V2 + "/collections")
                .retrieve()
                .bodyToMono(List.class)
                .map(cols -> (List<String>) ((java.util.List<?>) cols).stream()
                        .map(c -> (String) ((Map<?, ?>) c).get("name"))
                        .collect(java.util.stream.Collectors.toList()))
                .onErrorReturn(List.of());
    }

    /** Returns courses with metadata for UI. */
    @GetMapping
    public Mono<List<Map<String, Object>>> getAllCourses() {
        return getAllCourseIds()
                .map(ids -> ids.stream()
                        .map(id -> Map.<String, Object>of(
                                "courseId", id,
                                "branch", "General",
                                "subject", id))
                        .toList());
    }

    /**
     * Ingest via script — this endpoint returns guidance.
     */
    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> ingest(@RequestBody Map<String, Object> body) {
        return Mono.just(ResponseEntity.ok(Map.of(
                "status", "info",
                "message", "Use pageindex/ingest_to_chroma.py to ingest course PDFs into ChromaDB"
        )));
    }
}
