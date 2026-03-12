package com.coursechatbot.service;

import com.coursechatbot.dto.ChatResponse;
import com.coursechatbot.model.CourseContent;
import com.coursechatbot.repository.CourseContentRepository;
import com.coursechatbot.repository.CourseIndexRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Core PageIndex RAG service.
 *
 * Flow for answering a student question:
 *   1. Load the course's hierarchical index from PostgreSQL.
 *   2. Ask the LLM to select the relevant chapter / page range.
 *   3. Fetch the selected page texts from PostgreSQL.
 *   4. Ask the LLM to generate the final answer using only that context.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PageIndexService {

    private final CourseContentRepository contentRepository;
    private final CourseIndexRepository indexRepository;
    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;

    /**
     * Answer a student question using the PageIndex RAG flow.
     */
    public ChatResponse answer(String courseId, String question) {
        // Step 1 — load the index
        var courseIndex = indexRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No index found for course: " + courseId));

        String indexJson = courseIndex.getIndexJson();

        // Step 2 — ask the LLM to select relevant page range
        String selectionPrompt = buildSelectionPrompt(indexJson, question);
        log.debug("Selection prompt:\n{}", selectionPrompt);

        String selectionResponse = chatModel.generate(
                SystemMessage.from("You are a document navigation assistant. You respond only with valid JSON."),
                UserMessage.from(selectionPrompt)
        ).content().text();

        log.debug("Selection response:\n{}", selectionResponse);

        PageSelection selection = parseSelection(selectionResponse, indexJson);

        // Step 3 — retrieve page texts
        List<CourseContent> pages = contentRepository.findPageRange(
                courseId, selection.startPage(), selection.endPage());

        if (pages.isEmpty()) {
            return ChatResponse.builder()
                    .courseId(courseId)
                    .startPage(selection.startPage())
                    .endPage(selection.endPage())
                    .sectionReason(selection.reason())
                    .answer("I could not find course content for the selected page range.")
                    .build();
        }

        // Step 4 — generate the answer
        String context = buildContext(pages);
        String answerPrompt = buildAnswerPrompt(context, question);
        log.debug("Answer prompt length: {} chars", answerPrompt.length());

        String answer = chatModel.generate(
                SystemMessage.from("You are a helpful course assistant. Answer only from the provided context."),
                UserMessage.from(answerPrompt)
        ).content().text();

        return ChatResponse.builder()
                .courseId(courseId)
                .startPage(selection.startPage())
                .endPage(selection.endPage())
                .sectionReason(selection.reason())
                .answer(answer.strip())
                .build();
    }

    // -------------------------------------------------------------------------
    // Prompt builders
    // -------------------------------------------------------------------------

    private String buildSelectionPrompt(String indexJson, String question) {
        return """
                You are given a course's hierarchical table-of-contents index (JSON) and a student's question.
                Identify the most relevant page range in the course that would answer the question.

                Course Index:
                %s

                Student Question:
                %s

                Return ONLY valid JSON in this exact format (no extra text):
                {"start_page": <integer>, "end_page": <integer>, "reason": "<brief explanation>"}
                """.formatted(indexJson, question);
    }

    private String buildAnswerPrompt(String context, String question) {
        return """
                Use ONLY the context below to answer the student's question.
                If the answer is not present in the context, say: "I could not find an answer in the course material."

                Context:
                %s

                Question:
                %s

                Answer:
                """.formatted(context, question);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildContext(List<CourseContent> pages) {
        return pages.stream()
                .map(p -> "[Page " + p.getPageNumber() + "]\n" + p.getContent())
                .collect(Collectors.joining("\n\n"));
    }

    private PageSelection parseSelection(String json, String indexJson) {
        try {
            // Extract JSON object from LLM response (may contain prose)
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start == -1 || end == -1) {
                throw new IllegalStateException("No JSON object in LLM response");
            }
            String trimmed = json.substring(start, end + 1);
            Map<String, Object> map = objectMapper.readValue(trimmed, new TypeReference<>() {});
            int startPage = ((Number) map.getOrDefault("start_page", 1)).intValue();
            int endPage = ((Number) map.getOrDefault("end_page", startPage + 9)).intValue();
            String reason = (String) map.getOrDefault("reason", "");
            return new PageSelection(startPage, endPage, reason);
        } catch (Exception e) {
            log.warn("Failed to parse page selection from LLM response: {}", e.getMessage());
            // Fallback: use pages 1–10
            return new PageSelection(1, 10, "fallback due to parse error");
        }
    }

    record PageSelection(int startPage, int endPage, String reason) {}
}
