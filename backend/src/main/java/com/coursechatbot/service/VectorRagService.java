package com.coursechatbot.service;

import com.coursechatbot.dto.ChatRequest;
import com.coursechatbot.dto.ChatResponse;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vector RAG service using ChromaDB + nomic-embed-text embeddings.
 *
 * Flow:
 *   1. Academic integrity check (instant).
 *   2. Embed question via Ollama nomic-embed-text.
 *   3. Query ChromaDB collection (per courseId) for top-K chunks.
 *   4. Build context → single LLM call (gemma3:4b).
 *   5. Record session interaction (async, best-effort).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VectorRagService {

    private final ChatModel               chatModel;
    private final PromptBuilderService    promptBuilderService;
    private final SessionService          sessionService;
    private final WebClient.Builder       webClientBuilder;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.embedding-model:nomic-embed-text}")
    private String embeddingModel;

    @Value("${chroma.base-url:http://localhost:8001}")
    private String chromaBaseUrl;

    @Value("${chroma.top-k:3}")
    private int topK;

    private WebClient ollamaClient;
    private WebClient chromaClient;

    @PostConstruct
    void init() {
        ollamaClient = webClientBuilder.clone().baseUrl(ollamaBaseUrl).build();
        chromaClient = webClientBuilder.clone().baseUrl(chromaBaseUrl).build();
        log.info("VectorRagService initialized — Ollama: {} | ChromaDB: {} | EmbedModel: {} | TopK: {}",
                ollamaBaseUrl, chromaBaseUrl, embeddingModel, topK);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Mono<ChatResponse> answer(ChatRequest request) {
        String courseId   = request.getCourseId();
        String question   = request.getQuestion();
        String mode       = request.getMode() != null ? request.getMode().toUpperCase() : "LEARN";
        String difficulty = request.getDifficultyLevel() != null ? request.getDifficultyLevel().toUpperCase() : "INTERMEDIATE";
        String sessionId  = request.getSessionId();

        // Step 1: Academic integrity guard
        String refusal = promptBuilderService.checkIntegrityViolation(question);
        if (refusal != null) {
            return buildRefusalResponse(courseId, mode, difficulty, sessionId, question, refusal);
        }

        // Step 2 → 3 → 4: Embed → Retrieve → Generate
        return embedQuestion(question)
                .flatMap(embedding -> queryChroma(courseId, embedding))
                .flatMap(context -> generateAnswer(request, courseId, question, mode, difficulty, sessionId, context));
    }

    // ── Step 2: Embed question via Ollama ─────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Mono<List<Double>> embedQuestion(String text) {
        return ollamaClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of("model", embeddingModel, "prompt", text))
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> (List<Double>) resp.get("embedding"))
                .doOnError(e -> log.error("Embedding failed: {}", e.getMessage()));
    }

    // ── ChromaDB v2 base path ─────────────────────────────────────────────────
    private static final String CHROMA_V2 =
            "/api/v2/tenants/default_tenant/databases/default_database";

    // ── Step 3: Query ChromaDB ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Mono<String> queryChroma(String courseId, List<Double> embedding) {
        // ChromaDB v2: query directly by collection name — no UUID lookup needed
        return chromaClient.post()
                .uri(CHROMA_V2 + "/collections/{name}/query", courseId)
                .bodyValue(Map.of(
                        "query_embeddings", List.of(embedding),
                        "n_results", topK,
                        "include", List.of("documents", "metadatas")
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    List<List<String>> docs = (List<List<String>>) resp.get("documents");
                    if (docs == null || docs.isEmpty() || docs.get(0).isEmpty()) return "";
                    return String.join("\n\n---\n\n", docs.get(0));
                })
                .onErrorResume(e -> {
                    log.error("ChromaDB query failed for course '{}': {}", courseId, e.getMessage());
                    return Mono.just("");
                });
    }

    // ── Step 4: Generate answer with LLM ─────────────────────────────────────

    private Mono<ChatResponse> generateAnswer(ChatRequest request, String courseId,
                                               String question, String mode, String difficulty,
                                               String sessionId, String context) {
        if (context.isBlank()) {
            String msg = "⚠️ No relevant content found for course '" + courseId +
                         "'. Please ensure the course PDF has been ingested into ChromaDB.";
            return Mono.just(buildSimpleResponse(courseId, mode, difficulty, sessionId, msg, false));
        }

        boolean isExam      = "EXAM".equals(mode);
        String systemPrompt = promptBuilderService.buildSystemPrompt(mode, null, difficulty);
        String userPrompt   = buildPrompt(context, question);

        log.debug("LLM call: mode={} difficulty={} contextLen={} chars", mode, difficulty, context.length());

        return Mono.fromCallable(() ->
                chatModel.chat(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userPrompt)
                ).aiMessage().text()
        )
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(answer -> {
            List<String> suggestions = promptBuilderService.buildFollowUpSuggestions(question, mode);
            ChatResponse resp = buildSimpleResponse(courseId, mode, difficulty, sessionId,
                    answer.strip(), isExam);
            resp.setFollowUpSuggestions(suggestions);

            if (sessionId != null) {
                String responseType = isExam ? "HINT" : "EXPLAINED";
                return sessionService.recordInteraction(
                                UUID.fromString(sessionId), question, responseType, "vector-rag")
                        .then(sessionService.isPomodoroThresholdReached(UUID.fromString(sessionId)))
                        .map(pomodoro -> { resp.setPomodoroReminder(pomodoro); return resp; })
                        .onErrorResume(e -> {
                            log.warn("Session record failed (non-fatal): {}", e.getMessage());
                            return Mono.just(resp);
                        });
            }
            return Mono.just(resp);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildPrompt(String context, String question) {
        return """
                Context:
                %s

                Question: %s

                Answer using ONLY the context above. Be concise and clear.
                Answer:""".formatted(context, question);
    }

    private ChatResponse buildSimpleResponse(String courseId, String mode, String difficulty,
                                              String sessionId, String answer, boolean isHintOnly) {
        return ChatResponse.builder()
                .courseId(courseId)
                .mode(mode)
                .difficultyLevel(difficulty)
                .sessionId(sessionId)
                .answer(answer)
                .isHintOnly(isHintOnly)
                .followUpSuggestions(List.of())
                .startPage(0)
                .endPage(0)
                .sectionReason("vector-search")
                .build();
    }

    private Mono<ChatResponse> buildRefusalResponse(String courseId, String mode, String difficulty,
                                                     String sessionId, String question, String refusal) {
        if (sessionId != null) {
            return sessionService.recordInteraction(UUID.fromString(sessionId), question, "REFUSED", null)
                    .onErrorResume(e -> Mono.empty())
                    .thenReturn(buildSimpleResponse(courseId, mode, difficulty, sessionId, refusal, false));
        }
        return Mono.just(buildSimpleResponse(courseId, mode, difficulty, sessionId, refusal, false));
    }
}

