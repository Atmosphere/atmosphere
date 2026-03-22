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
package org.atmosphere.integrationtests.agent;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.annotation.Command;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test agent for integration testing. Uses simple in-process responses
 * instead of real LLM calls.
 */
@Agent(name = "test-agent", description = "Test agent for e2e tests")
public class TestAgent {

    private static final Logger logger = LoggerFactory.getLogger(TestAgent.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Test agent: client {} connected", resource.uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        // Simple echo-style response for testing
        var words = ("Agent received: " + message).split("(?<=\\s)");
        try {
            for (var word : words) {
                session.send(word);
                Thread.sleep(10);
            }
            session.complete("Agent received: " + message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    @Command(value = "/ping", description = "Ping the agent")
    public String ping() {
        return "pong";
    }

    @Command(value = "/echo", description = "Echo arguments")
    public String echo(String args) {
        return "echo: " + args;
    }

    @Command(value = "/danger", description = "Dangerous action",
            confirm = "This is dangerous. Continue?")
    public String danger() {
        return "Danger executed!";
    }

    @AiTool(name = "test_tool", description = "A test tool that returns a greeting")
    public String testTool(@Param(value = "name", description = "Name to greet") String name) {
        return "Hello, " + name + "!";
    }
}
