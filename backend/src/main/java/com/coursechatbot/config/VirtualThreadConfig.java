package com.coursechatbot.config;

import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

/**
 * Configures Tomcat to use a large thread pool for handling concurrent student requests.
 *
 * On Java 21+, replace {@code Executors.newCachedThreadPool()} with
 * {@code Executors.newVirtualThreadPerTaskExecutor()} to enable virtual threads,
 * which allows thousands of concurrent students with minimal memory overhead.
 */
@Configuration
public class VirtualThreadConfig {

    @Bean
    public TomcatProtocolHandlerCustomizer<?> virtualThreadTomcatProtocolHandlerCustomizer() {
        return protocolHandler -> protocolHandler.setExecutor(
                Executors.newCachedThreadPool()
        );
    }
}

