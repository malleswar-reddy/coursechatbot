package com.coursechatbot.config;

import org.springframework.context.annotation.Configuration;

/**
 * WebFlux runs on Netty's event loop — no Tomcat thread pool configuration needed.
 *
 * Blocking work (e.g. Ollama LLM calls) is offloaded to Schedulers.boundedElastic()
 * inside PageIndexService, keeping the Netty event loop free.
 */
@Configuration
public class VirtualThreadConfig {
    // No-op: Netty handles concurrency via its non-blocking event loop.
}
