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
package org.atmosphere.mcp.processor;

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.annotation.Processor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.mcp.annotation.McpServer;
import org.atmosphere.mcp.registry.McpRegistry;
import org.atmosphere.mcp.runtime.McpHandler;
import org.atmosphere.mcp.runtime.McpProtocolHandler;
import org.atmosphere.mcp.runtime.McpWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * Annotation processor for {@link McpServer}. Discovered by Atmosphere's annotation
 * scanning infrastructure via {@link AtmosphereAnnotation}. Scans the annotated class
 * for {@code @McpTool}, {@code @McpResource}, and {@code @McpPrompt} methods,
 * builds a registry, and registers the MCP handler at the configured path.
 */
@AtmosphereAnnotation(McpServer.class)
public class McpServerProcessor implements Processor<Object> {

    private static final Logger logger = LoggerFactory.getLogger(McpServerProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        try {
            var annotation = annotatedClass.getAnnotation(McpServer.class);
            if (annotation == null) {
                return;
            }

            // Create instance and scan for MCP methods
            var instance = framework.newClassInstance(Object.class, annotatedClass);
            var registry = new McpRegistry();
            registry.scan(instance);

            var protocolHandler = new McpProtocolHandler(
                    annotation.name(), annotation.version(), registry);

            // Register both AtmosphereHandler (for SSE/long-polling) and WebSocketHandler
            var handler = new McpHandler(protocolHandler);
            var wsHandler = new McpWebSocketHandler(protocolHandler);

            framework.addAtmosphereHandler(annotation.path(), handler, new ArrayList<>());

            logger.info("MCP server '{}' v{} registered at {} — {} tools, {} resources, {} prompts",
                    annotation.name(), annotation.version(), annotation.path(),
                    registry.tools().size(), registry.resources().size(), registry.prompts().size());

        } catch (Exception e) {
            logger.error("Failed to register MCP server from {}", annotatedClass.getName(), e);
        }
    }
}
