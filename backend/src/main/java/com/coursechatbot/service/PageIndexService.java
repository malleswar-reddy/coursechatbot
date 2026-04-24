package com.coursechatbot.service;

import com.coursechatbot.dto.ChatRequest;
import com.coursechatbot.dto.ChatResponse;
import com.coursechatbot.model.CourseContent;
import com.coursechatbot.repository.CourseContentRepository;
import com.coursechatbot.repository.CourseIndexRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PageIndex RAG service (legacy — not used by ChatController).
 *
 * ChromaDB vector search is now the primary RAG path via VectorRagService.
 * This class is retained for unit-test coverage only; it is NOT a Spring bean
 * (@Service removed) so the Spring context does not try to autowire the
 * plain repository interfaces at startup.
 *
 * Flow:
 *   1. Academic integrity check (instant, no LLM call if violated).
 *   2. Load the course index (reactive — requires injected CourseIndexRepository mock).
 *   3. Keyword-score chapters/sections to pick the best page range.
 *   4. Fetch up to 5 pages of context (reactive — requires CourseContentRepository mock).
 *   5. Single LLM call offloaded to boundedElastic scheduler (blocking I/O).
 *   6. Record interaction in session (async, best-effort).
 */
@Slf4j
@RequiredArgsConstructor
public class PageIndexService {

    private static final int MAX_CONTEXT_CHARS = 3000;
    private static final int MAX_PAGES         = 5;

    private final CourseContentRepository contentRepository;
    private final CourseIndexRepository   indexRepository;
    private final ChatModel               chatModel;
    private final ObjectMapper            objectMapper;
    private final PromptBuilderService    promptBuilderService;
    private final SessionService          sessionService;

    public Mono<ChatResponse> answer(ChatRequest request) {
        String courseId       = request.getCourseId();
        String question       = request.getQuestion();
        String mode           = request.getMode() != null ? request.getMode().toUpperCase() : "LEARN";
        String difficulty     = request.getDifficultyLevel() != null ? request.getDifficultyLevel().toUpperCase() : "INTERMEDIATE";
        String sessionIdStr   = request.getSessionId();

        // ── Step 1: Academic integrity guard ─────────────────────────────────
        String refusal = promptBuilderService.checkIntegrityViolation(question);
        if (refusal != null) {
            return buildRefusalResponse(courseId, mode, difficulty, sessionIdStr, question, refusal);
        }

        return indexRepository.findById(courseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "No index found for course: " + courseId)))
                .flatMap(courseIndex -> {

                    // ── Step 2: Keyword-based page selection ──────────────────
                    Map<String, Object> indexMap = parseJson(courseIndex.getIndexJson());
                    PageSelection selection = selectPagesByKeyword(indexMap, question);
                    log.info("Selected pages {}-{} for question='{}' reason='{}'",
                            selection.startPage(), selection.endPage(), question, selection.reason());

                    // ── Step 3: Fetch pages reactively ────────────────────────
                    return contentRepository
                            .findPageRange(courseId, selection.startPage(), selection.endPage())
                            .collectList()
                            .flatMap(pages -> {
                                if (pages.isEmpty()) {
                                    int fallbackStart = Math.max(1, selection.startPage() - 3);
                                    int fallbackEnd   = selection.endPage() + 3;
                                    log.warn("Empty result for pages {}-{}, retrying {}-{}",
                                            selection.startPage(), selection.endPage(),
                                            fallbackStart, fallbackEnd);
                                    return contentRepository
                                            .findPageRange(courseId, fallbackStart, fallbackEnd)
                                            .collectList();
                                }
                                return Mono.just(pages);
                            })
                            .flatMap(pages -> {
                                if (pages.isEmpty()) {
                                    return Mono.just(buildResponse(courseId, mode, difficulty, sessionIdStr,
                                            selection, "I could not find course content for the selected page range.", false));
                                }

                                // ── Step 4: LLM call — offload to boundedElastic ──
                                String context    = buildContext(pages, MAX_PAGES, MAX_CONTEXT_CHARS);
                                String prompt     = buildAnswerPrompt(context, question);
                                String systemPmpt = promptBuilderService.buildSystemPrompt(
                                        mode, courseIndex.getBranch(), difficulty);
                                boolean isExam    = "EXAM".equals(mode);

                                log.debug("Answer prompt: {} chars | mode={} difficulty={}", prompt.length(), mode, difficulty);

                                return Mono.fromCallable(() ->
                                        chatModel.chat(
                                                SystemMessage.from(systemPmpt),
                                                UserMessage.from(prompt)
                                        ).aiMessage().text()
                                )
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(answer -> {
                                    ChatResponse resp = buildResponse(
                                            courseId, mode, difficulty, sessionIdStr,
                                            selection, answer.strip(), isExam);

                                    // ── Step 5: Record interaction (async) ────
                                    if (sessionIdStr != null) {
                                        String responseType = isExam ? "HINT" : "EXPLAINED";
                                        String conceptTag   = selection.reason();
                                        return sessionService.recordInteraction(
                                                UUID.fromString(sessionIdStr), question, responseType, conceptTag)
                                                .then(sessionService.isPomodoroThresholdReached(UUID.fromString(sessionIdStr)))
                                                .map(pomodoro -> {
                                                    resp.setPomodoroReminder(pomodoro);
                                                    return resp;
                                                })
                                                .onErrorResume(e -> {
                                                    log.warn("Session record failed (non-fatal): {}", e.getMessage());
                                                    return Mono.just(resp);
                                                });
                                    }
                                    return Mono.just(resp);
                                });
                            });
                });
    }

    // ── Backward-compatible overload (legacy callers) ─────────────────────────
    public Mono<ChatResponse> answer(String courseId, String question) {
        com.coursechatbot.dto.ChatRequest req = new com.coursechatbot.dto.ChatRequest();
        req.setCourseId(courseId);
        req.setQuestion(question);
        return answer(req);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChatResponse buildResponse(String courseId, String mode, String difficulty,
                                       String sessionId, PageSelection sel, String answer, boolean isHintOnly) {
        List<String> suggestions = promptBuilderService.buildFollowUpSuggestions(answer, mode);
        return ChatResponse.builder()
                .courseId(courseId)
                .mode(mode)
                .difficultyLevel(difficulty)
                .sessionId(sessionId)
                .startPage(sel.startPage())
                .endPage(sel.endPage())
                .sectionReason(sel.reason())
                .answer(answer)
                .isHintOnly(isHintOnly)
                .followUpSuggestions(suggestions)
                .build();
    }

    private Mono<ChatResponse> buildRefusalResponse(String courseId, String mode, String difficulty,
                                                     String sessionId, String question, String refusal) {
        if (sessionId != null) {
            return sessionService.recordInteraction(UUID.fromString(sessionId), question, "REFUSED", null)
                    .onErrorResume(e -> Mono.empty())
                    .thenReturn(ChatResponse.builder()
                            .courseId(courseId).mode(mode).difficultyLevel(difficulty)
                            .sessionId(sessionId).answer(refusal).isHintOnly(false)
                            .followUpSuggestions(List.of()).build());
        }
        return Mono.just(ChatResponse.builder()
                .courseId(courseId).mode(mode).difficultyLevel(difficulty)
                .sessionId(sessionId).answer(refusal).isHintOnly(false)
                .followUpSuggestions(List.of()).build());
    }

    // ── Keyword-based page selector ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private PageSelection selectPagesByKeyword(Map<String, Object> indexMap, String question) {
        List<Map<String, Object>> chapters =
                (List<Map<String, Object>>) indexMap.getOrDefault("chapters", List.of());

        if (chapters.isEmpty()) {
            return new PageSelection(1, 10, "fallback — no chapters in index");
        }

        String   q     = question.toLowerCase();
        String[] words = q.split("\\s+");

        int bestScore = -1;
        Map<String, Object> bestTarget = chapters.get(0);

        for (Map<String, Object> ch : chapters) {
            int score = scoreTarget(ch, words);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children =
                    (List<Map<String, Object>>) ch.getOrDefault("children", List.of());
            for (Map<String, Object> sub : children) {
                int subScore = scoreTarget(sub, words);
                if (subScore > bestScore) { bestScore = subScore; bestTarget = sub; }
            }
            if (score > bestScore) { bestScore = score; bestTarget = ch; }
        }

        int    start = ((Number) bestTarget.getOrDefault("start_page", 1)).intValue();
        int    end   = ((Number) bestTarget.getOrDefault("end_page", start + 9)).intValue();
        String title = (String) bestTarget.getOrDefault("title", "");
        return new PageSelection(start, end, title);
    }

    private int scoreTarget(Map<String, Object> target, String[] words) {
        String title   = ((String) target.getOrDefault("title",   "")).toLowerCase();
        String summary = ((String) target.getOrDefault("summary", "")).toLowerCase();
        int score = 0;
        for (String w : words) {
            if (w.length() < 3) continue;
            if (title.contains(w))   score += 2;
            if (summary.contains(w)) score += 1;
        }
        return score;
    }

    private String buildAnswerPrompt(String context, String question) {
        return """
                Context:
                %s

                Question: %s

                Answer using ONLY the context above.
                Answer:""".formatted(context, question);
    }


    private String buildContext(List<CourseContent> pages, int maxPages, int maxChars) {
        StringBuilder sb    = new StringBuilder();
        int           count = 0;
        for (CourseContent p : pages) {
            if (count >= maxPages) break;
            String text = p.getContent();
            if (text == null || text.isBlank()) continue;
            sb.append("[Page ").append(p.getPageNumber()).append("]\n")
              .append(text, 0, Math.min(text.length(), maxChars / maxPages))
              .append("\n\n");
            count++;
        }
        String result = sb.toString();
        return result.length() > maxChars ? result.substring(0, maxChars) : result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse index JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    record PageSelection(int startPage, int endPage, String reason) {}
}
