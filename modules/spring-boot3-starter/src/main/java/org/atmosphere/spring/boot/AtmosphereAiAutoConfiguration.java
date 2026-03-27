/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.spring.boot;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Atmosphere AI module.
 * Activates when {@code atmosphere-ai} is on the classpath and
 * {@code atmosphere.ai.enabled=true} (default).
 *
 * <p>Configures the LLM settings from Spring properties (with environment variable
 * fallback) and registers a default AI chat endpoint when no user-defined
 * {@code @AiEndpoint} is present.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(AiConfig.class)
@ConditionalOnBean(AtmosphereFramework.class)
@ConditionalOnProperty(name = "atmosphere.ai.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AtmosphereProperties.class)
public class AtmosphereAiAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAiAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(AiConfig.LlmSettings.class)
    public AiConfig.LlmSettings atmosphereAiSettings(AtmosphereProperties properties) {
        var aiProps = properties.getAi();
        var apiKey = resolveApiKey(aiProps);
        if (apiKey == null && !"local".equalsIgnoreCase(aiProps.getMode())) {
            logger.warn("No AI API key configured. Set atmosphere.ai.api-key, "
                    + "LLM_API_KEY, OPENAI_API_KEY, or GEMINI_API_KEY environment variable");
        }
        var settings = AiConfig.configure(
                aiProps.getMode(),
                aiProps.getModel(),
                apiKey,
                aiProps.getBaseUrl());
        logger.info("Atmosphere AI configured: mode={}, model={}", aiProps.getMode(), aiProps.getModel());
        return settings;
    }

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereAiEndpointRegistrar atmosphereAiEndpointRegistrar(
            AtmosphereFramework framework,
            AtmosphereProperties properties) {
        return new AtmosphereAiEndpointRegistrar(framework, properties);
    }

    @Bean
    FilterRegistrationBean<Filter> atmosphereConsoleFilter() {
        var registration = new FilterRegistrationBean<Filter>(new ConsoleResourceFilter());
        registration.addUrlPatterns("/atmosphere/console/*");
        registration.setOrder(0);
        return registration;
    }

    @Bean
    ApplicationListener<WebServerInitializedEvent> atmosphereAiConsoleLog() {
        return event -> {
            int port = event.getWebServer().getPort();
            logger.info("Atmosphere AI console available at http://localhost:{}/atmosphere/console/", port);
        };
    }

    private String resolveApiKey(AtmosphereProperties.AiProperties aiProps) {
        if (aiProps.getApiKey() != null && !aiProps.getApiKey().isBlank()) {
            return aiProps.getApiKey();
        }
        // Fall back to environment variables
        var envKey = env("LLM_API_KEY");
        if (envKey != null) {
            return envKey;
        }
        envKey = env("OPENAI_API_KEY");
        if (envKey != null) {
            return envKey;
        }
        envKey = env("GEMINI_API_KEY");
        if (envKey != null) {
            return envKey;
        }
        return null;
    }

    private static String env(String key) {
        var val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : null;
    }

    /**
     * Serves built-in console static assets from {@code META-INF/resources/atmosphere/console/}
     * before the Atmosphere servlet (mapped to {@code /atmosphere/*}) can intercept them.
     */
    static class ConsoleResourceFilter implements Filter {

        private static final String CONSOLE_PREFIX = "/atmosphere/console";
        private static final String RESOURCE_BASE = "META-INF/resources/atmosphere/console/";

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            var httpReq = (HttpServletRequest) request;
            var httpRes = (HttpServletResponse) response;
            var path = httpReq.getRequestURI();

            if (!path.startsWith(CONSOLE_PREFIX)) {
                chain.doFilter(request, response);
                return;
            }

            // Extract the relative path after /atmosphere/console/
            var relativePath = path.substring(CONSOLE_PREFIX.length());
            if (relativePath.isEmpty() || relativePath.equals("/")) {
                relativePath = "/index.html";
            }

            // Reject path traversal attempts
            if (relativePath.contains("..")) {
                httpRes.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            // Strip leading slash for classpath lookup
            var resourceName = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
            var resourcePath = RESOURCE_BASE + resourceName;

            InputStream resource = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resourcePath);
            if (resource != null) {
                try (resource) {
                    httpRes.setContentType(guessContentType(resourceName));
                    resource.transferTo(httpRes.getOutputStream());
                }
                return;
            }

            chain.doFilter(request, response);
        }

        private String guessContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (path.endsWith(".css")) return "text/css; charset=utf-8";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }
}
