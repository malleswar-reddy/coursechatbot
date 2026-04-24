package com.coursechatbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Configures LangChain4j to use Ollama as the LLM backend.
 * The model and base URL are read from application.properties (or env vars).
 */
@Configuration
public class OllamaConfig {

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:qwen2.5:0.5b}")
    private String ollamaModel;

    @Value("${ollama.timeout-seconds:600}")
    private int timeoutSeconds;

    @Value("${ollama.num-predict:250}")
    private int numPredict;

    @Bean
    public ChatModel chatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(ollamaModel)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .temperature(0.1)
                .numPredict(numPredict)
                .build();
    }

    /** Explicit ObjectMapper bean — WebFlux auto-config does not expose one by default. */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Explicit WebClient.Builder bean — required by VectorRagService and CourseController.
     * Spring Boot 4 / Spring Framework 7 with lazy-initialization does not auto-expose this.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
