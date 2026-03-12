package com.coursechatbot.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configures LangChain4j to use Ollama as the LLM backend.
 * The model and base URL are read from application.properties (or env vars).
 */
@Configuration
public class OllamaConfig {

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:qwen2.5:7b}")
    private String ollamaModel;

    @Value("${ollama.timeout-seconds:120}")
    private int timeoutSeconds;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(ollamaModel)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .temperature(0.2)
                .build();
    }
}
