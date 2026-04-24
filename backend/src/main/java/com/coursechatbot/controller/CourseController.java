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
        return getCollections()
                .map(cols -> cols.stream()
                        .map(c -> (String) c.get("name"))
                        .collect(java.util.stream.Collectors.toList()))
                .onErrorReturn(List.of());
    }

    /**
     * Returns courses with full metadata (branch, subject, title) from ChromaDB collection metadata.
     */
    @GetMapping
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Mono<List<Map<String, Object>>> getAllCourses() {
        return getCollections()
                .map(cols -> cols.stream()
                        .map(col -> {
                            String name    = col.get("name") instanceof String s ? s : "";
                            Object metaObj = col.get("metadata");
                            String branch  = "General";
                            String subject = name;
                            String title   = name;
                            if (metaObj instanceof Map<?, ?> m) {
                                Object b = m.get("branch");
                                Object s = m.get("subject");
                                Object t = m.get("title");
                                if (b instanceof String v) branch  = v;
                                if (s instanceof String v) subject = v;
                                if (t instanceof String v) title   = v;
                            }
                            return Map.<String, Object>of(
                                    "courseId", name,
                                    "branch",   branch,
                                    "subject",  subject,
                                    "title",    title);
                        })
                        .collect(java.util.stream.Collectors.toList()))
                .onErrorReturn(List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<List<Map<String, Object>>> getCollections() {
        return webClientBuilder.clone().baseUrl(chromaBaseUrl).build()
                .get()
                .uri(CHROMA_V2 + "/collections")
                .retrieve()
                .bodyToMono(List.class)
                .map(cols -> (List<Map<String, Object>>) cols)
                .doOnError(e -> log.warn("ChromaDB /collections error: {}", e.getMessage()))
                .onErrorReturn(List.of());
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
