package com.coursechatbot.service;

import com.coursechatbot.dto.ChatResponse;
import com.coursechatbot.model.CourseContent;
import com.coursechatbot.repository.CourseContentRepository;
import com.coursechatbot.repository.CourseIndexRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/**
 * Core PageIndex RAG service — fully reactive with WebFlux + R2DBC.
 *
 * Flow:
 *   1. Load the course index from PostgreSQL (reactive).
 *   2. Keyword-score each chapter/section to pick the best page range (CPU, non-blocking).
 *   3. Fetch up to 2 pages of context from PostgreSQL (reactive).
 *   4. Single LLM call offloaded to boundedElastic scheduler (blocking I/O).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PageIndexService {

    private static final int MAX_CONTEXT_CHARS = 800;
    private static final int MAX_PAGES         = 2;

    private final CourseContentRepository contentRepository;
    private final CourseIndexRepository   indexRepository;
    private final ChatLanguageModel       chatModel;
    private final ObjectMapper            objectMapper;

    public Mono<ChatResponse> answer(String courseId, String question) {

        return indexRepository.findById(courseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "No index found for course: " + courseId)))
                .flatMap(courseIndex -> {

                    // ── Step 2: Keyword-based page selection (CPU, instant) ───
                    Map<String, Object> indexMap = parseJson(courseIndex.getIndexJson());
                    PageSelection selection = selectPagesByKeyword(indexMap, question);
                    log.info("Selected pages {}-{} for question='{}' reason='{}'",
                            selection.startPage(), selection.endPage(), question, selection.reason());

                    // ── Step 3: Fetch pages reactively ───────────────────────
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
                                    return Mono.just(ChatResponse.builder()
                                            .courseId(courseId)
                                            .startPage(selection.startPage())
                                            .endPage(selection.endPage())
                                            .sectionReason(selection.reason())
                                            .answer("I could not find course content for the selected page range.")
                                            .build());
                                }

                                // ── Step 4: LLM call — offload to boundedElastic (blocking I/O) ──
                                String context = buildContext(pages, MAX_PAGES, MAX_CONTEXT_CHARS);
                                String prompt  = buildAnswerPrompt(context, question);
                                log.debug("Answer prompt: {} chars", prompt.length());

                                return Mono.fromCallable(() ->
                                        chatModel.generate(
                                                SystemMessage.from("You are a concise Java course assistant. Answer in 4-5 sentences maximum using ONLY the provided context."),
                                                UserMessage.from(prompt)
                                        ).content().text()
                                )
                                .subscribeOn(Schedulers.boundedElastic())   // Ollama call is blocking
                                .map(answer -> ChatResponse.builder()
                                        .courseId(courseId)
                                        .startPage(selection.startPage())
                                        .endPage(selection.endPage())
                                        .sectionReason(selection.reason())
                                        .answer(answer.strip())
                                        .build());
                            });
                });
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

        int bestScore              = -1;
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

    // ── Prompt builder ────────────────────────────────────────────────────────

    private String buildAnswerPrompt(String context, String question) {
        return """
                Context:
                %s

                Question: %s

                Answer concisely in 4-5 sentences using ONLY the context above.
                Answer:""".formatted(context, question);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
