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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * E2E test endpoint that reflects the live {@link AgentRuntime#capabilities()}
 * sets of every runtime discovered on the classpath via
 * {@link AgentRuntimeResolver#resolveAll()}. Used by
 * {@code ai-tool-call-delta.spec.ts} to prove the positive + negative
 * capability assertions for {@link org.atmosphere.ai.AiCapability#TOOL_CALL_DELTA}
 * against runtime truth rather than prose in a matrix.
 *
 * <p>GET (or any request) returns a JSON document with one entry per
 * ServiceLoader-discovered runtime:</p>
 * <pre>
 * {
 *   "runtimes": [
 *     {
 *       "name": "built-in",
 *       "priority": 0,
 *       "capabilities": ["TEXT_STREAMING", "TOOL_CALLING", ..., "TOOL_CALL_DELTA"]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>On the integration-tests classpath only {@code BuiltInAgentRuntime} is
 * discoverable — framework runtime modules (Spring AI, LangChain4j, ADK,
 * Embabel, Koog, Semantic Kernel) are not depended upon by
 * {@code atmosphere-integration-tests} and therefore are not wired here.
 * This is deliberate: the assertion exercises the real SPI discovery path
 * so any future drift (e.g. a framework runtime that wrongly starts
 * advertising {@code TOOL_CALL_DELTA}) would fail the spec. The pinned
 * per-runtime contract tests in each framework runtime's own module
 * (e.g. {@code SpringAiRuntimeContractTest.expectedCapabilities}) are the
 * authoritative drift detectors for the runtimes that cannot be loaded here
 * — they run on every full Maven build.</p>
 */
public class CapabilitiesTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var runtimes = AgentRuntimeResolver.resolveAll();
        var body = new StringBuilder(256);
        body.append("{\"runtimes\":[");
        var first = true;
        for (AgentRuntime runtime : runtimes) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append("{\"name\":\"").append(escape(runtime.name())).append('"')
                    .append(",\"priority\":").append(runtime.priority())
                    .append(",\"capabilities\":[")
                    .append(runtime.capabilities().stream()
                            .map(Enum::name)
                            .sorted()
                            .map(n -> '"' + n + '"')
                            .collect(Collectors.joining(",")))
                    .append("]}");
        }
        body.append("]}");

        var response = resource.getResponse();
        response.setContentType("application/json");
        response.getWriter().write(body.toString());
        response.getWriter().flush();
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        // Synchronous handler — no broadcast path.
    }

    @Override
    public void destroy() {
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
