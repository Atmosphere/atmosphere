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
package org.atmosphere.mcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an MCP (Model Context Protocol) server endpoint.
 * Methods within this class can be annotated with {@link McpTool}, {@link McpResource},
 * and {@link McpPrompt} to expose capabilities to MCP clients.
 *
 * <pre>{@code
 * @McpServer(name = "my-server", version = "1.0.0", path = "/mcp")
 * public class MyMcpServer {
 *     @McpTool(name = "greet", description = "Say hello")
 *     public String greet(@McpParam(name = "name") String name) {
 *         return "Hello, " + name + "!";
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpServer {

    /** MCP server name reported during initialization. */
    String name();

    /** MCP server version reported during initialization. */
    String version() default "1.0.0";

    /** Atmosphere endpoint path for this MCP server. */
    String path() default "/mcp";
}
