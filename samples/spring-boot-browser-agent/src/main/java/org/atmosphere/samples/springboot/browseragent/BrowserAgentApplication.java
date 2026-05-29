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
package org.atmosphere.samples.springboot.browseragent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Code-as-action browser agent sample. The AI model is given a single
 * {@code code_exec} tool: it writes Playwright (JavaScript) that drives a
 * headless browser inside an isolated, ephemeral container, saves screenshots
 * to the sandbox workspace, and Atmosphere streams those screenshots back to
 * the Console live as the agent works.
 *
 * <p><strong>Code execution is enabled in this sample on purpose</strong> — it
 * is the feature being demonstrated. In production, code execution is
 * default-deny; an operator must explicitly set
 * {@code org.atmosphere.ai.code.enabled=true} and provide a container engine.
 * This sample sets that flag in {@link #main(String[])} (overridable via the
 * {@code ATMO_CODE_EXEC} environment variable) and logs a prominent warning so
 * the security posture is never silent.</p>
 */
@SpringBootApplication
public class BrowserAgentApplication {

    private static final Logger logger = LoggerFactory.getLogger(BrowserAgentApplication.class);

    public static void main(String[] args) {
        // Enable the code-as-action sandbox before Spring scans the @AiEndpoint
        // (the code_exec tool is registered at endpoint-registration time, which
        // reads this flag). Default on for the demo; set ATMO_CODE_EXEC=false to
        // run the endpoint without code execution.
        String enabled = System.getenv().getOrDefault("ATMO_CODE_EXEC", "true");
        System.setProperty("org.atmosphere.ai.code.enabled", enabled);
        // Pin the Cohere endpoint so an ambient LLM_BASE_URL (e.g. a dev machine
        // configured for Gemini's OpenAI-compatible endpoint) cannot redirect the
        // Cohere client to the wrong host. The cohere.base.url system property wins
        // outright over the framework-resolved base URL.
        System.setProperty("cohere.base.url", "https://api.cohere.com");
        // The Playwright image ships the browsers (/ms-playwright) but not the
        // 'playwright' npm package, so install it once per sandbox. Browsers are
        // already present, so skip their download. This runs at container start.
        if (System.getProperty("org.atmosphere.ai.code.setup") == null) {
            System.setProperty("org.atmosphere.ai.code.setup",
                    "cd /workspace && npm init -y >/dev/null 2>&1; "
                    + "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install playwright@1.60.0");
        }
        // The Cohere model is pinned on @AiEndpoint(model=...) in BrowserAgent —
        // effectiveModel() honors the endpoint model ahead of the global LLM
        // settings, so an ambient LLM_MODEL env var (which the Atmosphere starter
        // reads from the OS environment and lets win over config) cannot leak in.
        // A browser agent must reach the web, so this sample opts the sandbox into
        // outbound network (Docker's default 'bridge'). The product default is
        // 'none' (no network); we loosen it deliberately and visibly for the demo.
        if (System.getProperty("org.atmosphere.ai.code.network") == null) {
            System.setProperty("org.atmosphere.ai.code.network", "bridge");
        }
        if (Boolean.parseBoolean(enabled)) {
            logger.warn("Code execution ENABLED with network={}: the AI may run "
                    + "model-written code in an isolated container (image={}) that can "
                    + "reach the network. This is a demo default; production deployments "
                    + "must opt in explicitly and choose their own network policy.",
                    System.getProperty("org.atmosphere.ai.code.network"),
                    System.getProperty("org.atmosphere.ai.code.image",
                            "mcr.microsoft.com/playwright:v1.60.0-noble"));
        }
        SpringApplication.run(BrowserAgentApplication.class, args);
    }

    /** Redirect the web root to the Atmosphere Console so the sample opens ready to drive. */
    @Configuration
    static class ConsoleRedirect implements WebMvcConfigurer {
        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            registry.addRedirectViewController("/", "/atmosphere/console/");
        }
    }
}
