package com.coursechatbot;

import com.coursechatbot.dto.ChatResponse;
import com.coursechatbot.model.CourseContent;
import com.coursechatbot.model.CourseIndex;
import com.coursechatbot.repository.CourseContentRepository;
import com.coursechatbot.repository.CourseIndexRepository;
import com.coursechatbot.service.PageIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration test for the PageIndex RAG flow.
 * Uses an H2 in-memory database and a mocked ChatLanguageModel.
 */
@SpringBootTest
class PageIndexServiceTest {

    @Autowired
    private PageIndexService pageIndexService;

    @Autowired
    private CourseIndexRepository indexRepository;

    @Autowired
    private CourseContentRepository contentRepository;

    @MockBean
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String COURSE_ID = "test-course";

    @BeforeEach
    void setUp() {
        contentRepository.deleteAll();
        indexRepository.deleteAll();

        // Save a simple course index
        String indexJson = """
                {
                  "title": "Introduction to Java",
                  "total_pages": 20,
                  "chapters": [
                    {
                      "title": "Chapter 1: Basics",
                      "summary": "Introduction to Java basics",
                      "start_page": 1,
                      "end_page": 10
                    },
                    {
                      "title": "Chapter 2: OOP",
                      "summary": "Object-oriented programming concepts",
                      "start_page": 11,
                      "end_page": 20
                    }
                  ]
                }
                """;
        indexRepository.save(CourseIndex.builder()
                .courseId(COURSE_ID)
                .indexJson(indexJson)
                .build());

        // Save some page content
        for (int i = 11; i <= 15; i++) {
            contentRepository.save(CourseContent.builder()
                    .courseId(COURSE_ID)
                    .pageNumber(i)
                    .content("Page " + i + ": Polymorphism is the ability of an object to take many forms.")
                    .build());
        }
    }

    @Test
    void shouldReturnAnswerForValidQuestion() {
        // Mock LLM — first call returns page selection, second call returns the answer
        when(chatLanguageModel.generate(any(), any(dev.langchain4j.data.message.UserMessage.class)))
                .thenReturn(
                        // Selection response
                        Response.from(AiMessage.from("{\"start_page\": 11, \"end_page\": 15, \"reason\": \"Chapter 2 covers OOP\"}")),
                        // Answer response
                        Response.from(AiMessage.from("Polymorphism allows objects to take many forms."))
                );

        ChatResponse response = pageIndexService.answer(COURSE_ID, "What is polymorphism?");

        assertThat(response.getAnswer()).contains("Polymorphism");
        assertThat(response.getStartPage()).isEqualTo(11);
        assertThat(response.getEndPage()).isEqualTo(15);
        assertThat(response.getSectionReason()).isEqualTo("Chapter 2 covers OOP");
        assertThat(response.getCourseId()).isEqualTo(COURSE_ID);
    }

    @Test
    void shouldFallbackWhenLlmReturnsInvalidJson() {
        when(chatLanguageModel.generate(any(), any(dev.langchain4j.data.message.UserMessage.class)))
                .thenReturn(
                        // Bad selection response (no JSON)
                        Response.from(AiMessage.from("I don't know which pages to select.")),
                        // Answer response for fallback range (pages 1-10 — no content saved for those)
                        Response.from(AiMessage.from("I could not find an answer in the course material."))
                );

        ChatResponse response = pageIndexService.answer(COURSE_ID, "What is polymorphism?");

        // Fallback range is pages 1–10
        assertThat(response.getStartPage()).isEqualTo(1);
        assertThat(response.getEndPage()).isEqualTo(10);
        // No content was stored for pages 1–10, so answer should indicate no content found
        assertThat(response.getAnswer()).contains("could not find");
    }

    @Test
    void shouldThrowWhenCourseNotFound() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> pageIndexService.answer("nonexistent-course", "Any question")
        );
    }
}
