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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit test for the PageIndex RAG flow.
 * All dependencies are mocked — no database or Spring context needed.
 */
@ExtendWith(MockitoExtension.class)
class PageIndexServiceTest {

    @Mock private CourseContentRepository contentRepository;
    @Mock private CourseIndexRepository   indexRepository;
    @Mock private ChatLanguageModel       chatLanguageModel;

    private PageIndexService pageIndexService;

    private static final String COURSE_ID = "test-course";
    private static final String INDEX_JSON = """
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
                  "summary": "Object-oriented programming polymorphism concepts",
                  "start_page": 11,
                  "end_page": 20
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        // Construct service manually so we can supply a real ObjectMapper
        pageIndexService = new PageIndexService(
                contentRepository, indexRepository, chatLanguageModel, new ObjectMapper());
    }

    @Test
    void shouldReturnAnswerForValidQuestion() {
        when(indexRepository.findById(COURSE_ID))
                .thenReturn(Mono.just(CourseIndex.builder()
                        .courseId(COURSE_ID)
                        .indexJson(INDEX_JSON)
                        .build()));

        when(contentRepository.findPageRange(eq(COURSE_ID), anyInt(), anyInt()))
                .thenReturn(Flux.just(
                        CourseContent.builder().courseId(COURSE_ID).pageNumber(11)
                                .content("Polymorphism is the ability of an object to take many forms.")
                                .build(),
                        CourseContent.builder().courseId(COURSE_ID).pageNumber(12)
                                .content("Inheritance enables code reuse in OOP.")
                                .build()
                ));

        when(chatLanguageModel.generate(any(), any(dev.langchain4j.data.message.UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("Polymorphism allows objects to take many forms.")));

        ChatResponse response = pageIndexService.answer(COURSE_ID, "What is polymorphism?").block();

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).containsIgnoringCase("Polymorphism");
        assertThat(response.getCourseId()).isEqualTo(COURSE_ID);
        assertThat(response.getStartPage()).isPositive();
        assertThat(response.getEndPage()).isGreaterThanOrEqualTo(response.getStartPage());
    }

    @Test
    void shouldReturnNoContentMessageWhenPagesEmpty() {
        when(indexRepository.findById(COURSE_ID))
                .thenReturn(Mono.just(CourseIndex.builder()
                        .courseId(COURSE_ID)
                        .indexJson(INDEX_JSON)
                        .build()));

        // Both primary and fallback queries return empty
        when(contentRepository.findPageRange(eq(COURSE_ID), anyInt(), anyInt()))
                .thenReturn(Flux.empty());

        StepVerifier.create(pageIndexService.answer(COURSE_ID, "What is polymorphism?"))
                .assertNext(response ->
                        assertThat(response.getAnswer()).contains("could not find"))
                .verifyComplete();
    }

    @Test
    void shouldErrorWhenCourseNotFound() {
        when(indexRepository.findById("nonexistent-course")).thenReturn(Mono.empty());

        StepVerifier.create(pageIndexService.answer("nonexistent-course", "Any question"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("No index found for course"))
                .verify();
    }
}
