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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core PageIndex RAG service — optimised for speed.
 *
 * Flow:
 *   1. Load the course index from PostgreSQL.
 *   2. Keyword-score each chapter/section to pick the best page range (no LLM call).
 *   3. Fetch up to 2 pages of context from PostgreSQL.
 *   4. Single LLM call to generate a concise answer (max 800 chars context, 250 tokens).
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

    public ChatResponse answer(String courseId, String question) {

        // ── Step 1: Load course index ─────────────────────────────────────────
        var courseIndex = indexRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No index found for course: " + courseId));

        Map<String, Object> indexMap = parseJson(courseIndex.getIndexJson());

        // ── Step 2: Keyword-based page selection (instant, no LLM) ───────────
        PageSelection selection = selectPagesByKeyword(indexMap, question);
        log.info("Selected pages {}-{} for question='{}' reason='{}'",
                selection.startPage(), selection.endPage(), question, selection.reason());

        // ── Step 3: Fetch up to MAX_PAGES pages of content ────────────────────
        List<CourseContent> pages = contentRepository.findPageRange(
                courseId, selection.startPage(), selection.endPage());

        // Try slightly wider range if nothing found
        if (pages.isEmpty()) {
            int fallbackStart = Math.max(1, selection.startPage() - 3);
            int fallbackEnd   = selection.endPage() + 3;
            pages = contentRepository.findPageRange(courseId, fallbackStart, fallbackEnd);
            log.warn("Empty result for pages {}-{}, retried with {}-{}",
                    selection.startPage(), selection.endPage(), fallbackStart, fallbackEnd);
        }

        if (pages.isEmpty()) {
            return ChatResponse.builder()
                    .courseId(courseId)
                    .startPage(selection.startPage())
                    .endPage(selection.endPage())
                    .sectionReason(selection.reason())
                    .answer("I could not find course content for the selected page range.")
                    .build();
        }

        // ── Step 4: Single LLM call with limited context ──────────────────────
        String context = buildContext(pages, MAX_PAGES, MAX_CONTEXT_CHARS);
        String prompt  = buildAnswerPrompt(context, question);
        log.debug("Answer prompt: {} chars", prompt.length());

        String answer = chatModel.generate(
                SystemMessage.from("You are a concise Java course assistant. Answer in 4-5 sentences maximum using ONLY the provided context."),
                UserMessage.from(prompt)
        ).content().text();

        return ChatResponse.builder()
                .courseId(courseId)
                .startPage(selection.startPage())
                .endPage(selection.endPage())
                .sectionReason(selection.reason())
                .answer(answer.strip())
                .build();
    }

    // ── Keyword-based page selector ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private PageSelection selectPagesByKeyword(Map<String, Object> indexMap, String question) {
        List<Map<String, Object>> chapters =
                (List<Map<String, Object>>) indexMap.getOrDefault("chapters", List.of());

        if (chapters.isEmpty()) {
            return new PageSelection(1, 10, "fallback — no chapters in index");
        }

        String q = question.toLowerCase();
        String[] words = q.split("\\s+");

        int bestScore    = -1;
        Map<String, Object> bestTarget = chapters.get(0);

        for (Map<String, Object> ch : chapters) {
            int score = scoreTarget(ch, words);

            // Check children (sub-sections) first — more precise
            List<Map<String, Object>> children =
                    (List<Map<String, Object>>) ch.getOrDefault("children", List.of());
            for (Map<String, Object> sub : children) {
                int subScore = scoreTarget(sub, words);
                if (subScore > bestScore) {
                    bestScore  = subScore;
                    bestTarget = sub;
                }
            }
            if (score > bestScore) {
                bestScore  = score;
                bestTarget = ch;
            }
        }

        int start  = ((Number) bestTarget.getOrDefault("start_page", 1)).intValue();
        int end    = ((Number) bestTarget.getOrDefault("end_page", start + 9)).intValue();
        String title = (String) bestTarget.getOrDefault("title", "");
        return new PageSelection(start, end, title);
    }

    private int scoreTarget(Map<String, Object> target, String[] words) {
        String title   = ((String) target.getOrDefault("title",   "")).toLowerCase();
        String summary = ((String) target.getOrDefault("summary", "")).toLowerCase();
        int score = 0;
        for (String w : words) {
            if (w.length() < 3) continue;           // skip short stop-words
            if (title.contains(w))   score += 2;    // title match = higher weight
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
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (CourseContent p : pages) {
            if (count >= maxPages) break;
            String text = p.getContent();
            if (text == null || text.isBlank()) continue;
            String chunk = "[Page " + p.getPageNumber() + "]\n"
                    + text.substring(0, Math.min(text.length(), maxChars / maxPages));
            sb.append(chunk).append("\n\n");
            count++;
        }
        // Hard cap total
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
