package com.coursechatbot.service;

import com.coursechatbot.dto.ChatRequest;
import com.coursechatbot.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
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
 *   4a. answer()       — full blocking LLM call (ChatModel / LangChain4j).
 *   4b. answerStream() — streaming tokens direct from Ollama /api/generate.
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
    private final ObjectMapper            objectMapper;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:gemma3:4b}")
    private String ollamaModel;

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
        ollamaClient = webClientBuilder.clone()
                .baseUrl(ollamaBaseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        chromaClient = webClientBuilder.clone().baseUrl(chromaBaseUrl).build();
        log.info("VectorRagService initialized — Ollama: {} | Model: {} | ChromaDB: {} | EmbedModel: {} | TopK: {}",
                ollamaBaseUrl, ollamaModel, chromaBaseUrl, embeddingModel, topK);
    }

    // ── Public API: blocking (full response) ─────────────────────────────────

    public Mono<ChatResponse> answer(ChatRequest request) {
        long t0 = System.currentTimeMillis();
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

        log.info("⏱ [{}] Step 2: Embedding question ({} chars)...", courseId, question.length());
        return embedQuestion(question)
                .doOnNext(v -> log.info("⏱ [{}] Embed done: {}ms", courseId, System.currentTimeMillis() - t0))
                .flatMap(embedding -> {
                    long t1 = System.currentTimeMillis();
                    log.info("⏱ [{}] Step 3: Querying ChromaDB...", courseId);
                    return queryChroma(courseId, embedding)
                            .doOnNext(ctx -> log.info("⏱ [{}] ChromaDB done: {}ms | context: {} chars",
                                    courseId, System.currentTimeMillis() - t1, ctx.length()));
                })
                .flatMap(context -> {
                    long t2 = System.currentTimeMillis();
                    log.info("⏱ [{}] Step 4: LLM call (blocking)...", courseId);
                    return generateAnswer(request, courseId, question, mode, difficulty, sessionId, context)
                            .doOnNext(r -> log.info("⏱ [{}] LLM done: {}ms | total: {}ms",
                                    courseId, System.currentTimeMillis() - t2, System.currentTimeMillis() - t0));
                });
    }

    // ── Public API: streaming (token by token) ────────────────────────────────

    /**
     * Stream answer tokens directly from Ollama /api/generate.
     * Emits individual text tokens as they arrive, then "[DONE]" as final marker.
     */
    public Flux<String> answerStream(ChatRequest request) {
        long t0 = System.currentTimeMillis();
        String courseId   = request.getCourseId();
        String question   = request.getQuestion();
        String mode       = request.getMode() != null ? request.getMode().toUpperCase() : "LEARN";
        String difficulty = request.getDifficultyLevel() != null ? request.getDifficultyLevel().toUpperCase() : "INTERMEDIATE";

        // Integrity check
        String refusal = promptBuilderService.checkIntegrityViolation(question);
        if (refusal != null) {
            return Flux.just(refusal, "[DONE]");
        }

        log.info("⏱ [{}] STREAM Step 2: Embedding...", courseId);
        return embedQuestion(question)
                .doOnNext(v -> log.info("⏱ [{}] STREAM Embed done: {}ms", courseId, System.currentTimeMillis() - t0))
                .flatMapMany(embedding -> {
                    long t1 = System.currentTimeMillis();
                    log.info("⏱ [{}] STREAM Step 3: ChromaDB...", courseId);
                    return queryChroma(courseId, embedding)
                            .doOnNext(ctx -> log.info("⏱ [{}] STREAM ChromaDB done: {}ms | ctx: {} chars",
                                    courseId, System.currentTimeMillis() - t1, ctx.length()))
                            .flatMapMany(context -> {
                                if (context.isBlank() || context.length() < 50) {
                                    log.warn("⚠️ [{}] Context too short ({} chars) — PDF may not be ingested", courseId, context.length());
                                    String msg = "⚠️ No relevant content found for course '" + courseId +
                                                 "'. Please ensure the course PDF has been ingested into ChromaDB.";
                                    return Flux.just(msg, "[DONE]");
                                }
                                String systemPrompt = promptBuilderService.buildSystemPrompt(mode, null, difficulty);
                                String userPrompt   = buildPrompt(context, question);
                                log.info("⏱ [{}] STREAM Step 4: Ollama streaming...", courseId);
                                long t2 = System.currentTimeMillis();
                                return streamFromOllama(systemPrompt, userPrompt)
                                        .doOnComplete(() -> log.info("⏱ [{}] STREAM LLM done: {}ms | total: {}ms",
                                                courseId, System.currentTimeMillis() - t2, System.currentTimeMillis() - t0));
                            });
                });
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<String> resolveCollectionUuid(String courseId) {
        return chromaClient.get()
                .uri(CHROMA_V2 + "/collections/{name}", courseId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> (String) resp.get("id"))
                .doOnError(e -> log.error("UUID lookup failed for '{}': {}", courseId, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    // ── Step 3: Query ChromaDB ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Mono<String> queryChroma(String courseId, List<Double> embedding) {
        return resolveCollectionUuid(courseId)
                .flatMap(uuid -> chromaClient.post()
                        .uri(CHROMA_V2 + "/collections/{uuid}/query", uuid)
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
                        })
                )
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.warn("Collection '{}' not found in ChromaDB — no UUID resolved", courseId);
                    return "";
                }));
    }

    // ── Step 4a: Generate answer (blocking) ───────────────────────────────────

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

    // ── Step 4b: Stream tokens from Ollama /api/generate ─────────────────────

    /**
     * Calls Ollama /api/generate with stream=true and returns individual tokens.
     * Ollama returns NDJSON: one JSON object per line, each with a "response" field.
     * Ends with "[DONE]" sentinel so the frontend knows the stream is complete.
     */
    private Flux<String> streamFromOllama(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = Map.of(
                "model",   ollamaModel,
                "system",  systemPrompt,
                "prompt",  userPrompt,
                "stream",  true,
                "options", Map.of("temperature", 0.7, "num_predict", 512)
        );

        return ollamaClient.post()
                .uri("/api/generate")
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)        // StringDecoder splits on \n → one JSON per emission
                .filter(line -> !line.isBlank())
                .mapNotNull(line -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> json = objectMapper.readValue(line, Map.class);
                        Object token = json.get("response");
                        return (token != null) ? token.toString() : null;
                    } catch (Exception e) {
                        log.debug("Skipping non-JSON line: {}", line);
                        return null;
                    }
                })
                .filter(token -> !token.isEmpty())
                // Encode \n as {NL} so SSE newlines (event delimiters) aren't confused
                // with content newlines. Frontend decodes {NL} back to \n.
                .map(token -> token.replace("\n", "{NL}"))
                .concatWith(Flux.just("[DONE]"))   // sentinel for frontend
                .doOnError(e -> log.error("Ollama stream error: {}", e.getMessage()))
                .onErrorResume(e -> Flux.just("⚠️ LLM stream error: " + e.getMessage(), "[DONE]"));
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

