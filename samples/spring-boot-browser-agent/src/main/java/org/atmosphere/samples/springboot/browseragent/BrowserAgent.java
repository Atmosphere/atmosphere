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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Code-as-action browser agent. The system prompt hands the model exactly one
 * capability — the framework-provided {@code code_exec} tool — and asks it to
 * accomplish web tasks by writing Playwright code rather than by predicting
 * individual clicks. Each {@code code_exec} round runs in the session's
 * isolated container and streams its screenshots back to the Console live.
 *
 * <p>The {@code code_exec} tool is <em>not</em> declared in {@code tools()}: it
 * is registered automatically by the framework when code execution is enabled
 * (see {@code BrowserAgentApplication}). Declaring it here would double-register
 * it.</p>
 */
@AiEndpoint(
        path = "/atmosphere/ai-chat",
        // Pin the Cohere model at the endpoint. effectiveModel() honors the
        // endpoint/context model before the global LLM settings, so this beats an
        // ambient LLM_MODEL env var (which the Atmosphere starter otherwise lets
        // win) without depending on the dev machine's environment.
        model = "command-a-03-2025",
        conversationMemory = true,
        maxHistoryMessages = 40,
        systemPrompt = """
                You are a browser automation agent. You accomplish web tasks by writing code,
                not by predicting clicks. You have one tool:

                  code_exec(language, code)

                Use language="javascript" and write Playwright that drives a headless browser.
                The sandbox already has the 'playwright' package and Chromium installed in
                /workspace. The code runs under node via stdin, so wrap your logic in an async
                IIFE and use require (CommonJS). A typical step:

                  const { chromium } = require('playwright');
                  (async () => {
                    const browser = await chromium.launch();
                    const page = await browser.newPage();
                    await page.goto('https://example.com', { waitUntil: 'domcontentloaded' });
                    const title = await page.title();
                    await page.screenshot({ path: '/workspace/artifacts/step.png', fullPage: true });
                    console.log('title:', title);
                    await browser.close();
                  })();

                Rules:
                - Wrap browser code in (async () => { ... })(); — do NOT use top-level await.
                - ALWAYS save a screenshot to /workspace/artifacts/ so the user can see progress.
                - The /workspace directory persists across code_exec calls within this session,
                  so you can build up state over several steps.
                - Print the facts you gathered with console.log so you can reason over them.
                - Work in small steps: run code, read the logs and screenshot, then decide the
                  next step. Finish with a short plain-language summary of what you found.
                """)
@AgentScope(unrestricted = true,
        justification = "Browser-automation demo — accepts arbitrary web tasks and writes "
                + "arbitrary Playwright code by design. The security boundary is the isolated, "
                + "ephemeral sandbox container (no host fs, capped resources, torn down per "
                + "session), not endpoint-level scope restriction.")
public class BrowserAgent {

    private static final Logger logger = LoggerFactory.getLogger(BrowserAgent.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Browser-agent client {} connected", resource.uuid());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Browser-agent client {} disconnected", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        logger.info("Task from {}: {}", resource.uuid(), message);

        // Require an explicit Cohere key. This sample pins the Cohere runtime, so
        // we must check COHERE_API_KEY directly rather than the resolved
        // AiConfig.apiKey(): the generic resolver falls back to LLM_API_KEY /
        // OPENAI_API_KEY / GEMINI_API_KEY, and handing one of those to Cohere
        // produces a confusing 401 instead of a clear "set COHERE_API_KEY" hint.
        var cohereKey = System.getenv("COHERE_API_KEY");
        if (cohereKey == null || cohereKey.isBlank()) {
            session.send("This sample needs a Cohere key for tool calling. Set COHERE_API_KEY "
                    + "and ensure Docker is running, then ask me to browse a site — "
                    + "e.g. \"What's the top story on news.ycombinator.com?\"");
            session.complete();
            return;
        }

        // Delegate the full agent loop to the resolved runtime. The framework
        // offers the code_exec tool, lifts the tool-loop ceiling, and streams
        // each round's AgentStep + screenshots back to the Console.
        session.stream(message);
    }
}
