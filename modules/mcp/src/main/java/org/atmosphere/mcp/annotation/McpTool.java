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
 * Marks a method as an MCP tool. The method will be invocable by MCP clients
 * via {@code tools/call}. Parameters should be annotated with {@link McpParam}.
 *
 * <pre>{@code
 * @McpTool(name = "search", description = "Search the knowledge base")
 * public List<Result> search(@McpParam(name = "query", required = true) String query) {
 *     return knowledgeBase.search(query);
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpTool {

    /** Tool name as reported to MCP clients. Programmatic identifier. */
    String name();

    /**
     * Human-friendly display title (MCP 2025-06-18+). Use this for UI labels
     * so {@link #name()} stays a stable programmatic identifier. Defaults to
     * empty, in which case the response omits the {@code title} field.
     */
    String title() default "";

    /** Human-readable description of what this tool does. */
    String description() default "";

    /**
     * Optional icon URI advertised in tools/list (MCP 2025-11-25). Empty
     * string omits the {@code icons} field from the wire response.
     */
    String iconUrl() default "";

    /**
     * Marks this tool as long-running. On the stateless {@code 2026-07-28}
     * transport this is the server-side signal (SEP-2663) to materialize a
     * <em>task</em>: a {@code tools/call} answers with a {@code CreateTaskResult}
     * handle that the client polls via {@code tasks/get}, instead of blocking
     * for the final {@code CallToolResult}. Requires the client to have
     * negotiated the {@code io.modelcontextprotocol/tasks} extension; otherwise
     * the call is rejected with {@code -32003}. Has no effect on the legacy
     * session transport.
     */
    boolean longRunning() default false;

    /**
     * Associates this tool with an MCP App UI (SEP-1865). When set to a
     * {@code ui://} URI, the tool advertises {@code _meta.ui.resourceUri} in
     * {@code tools/list} and the server advertises the
     * {@code io.modelcontextprotocol/apps} extension. A host fetches that
     * {@code ui://} resource (served as {@code text/html;profile=mcp-app}, e.g.
     * via an {@link McpResource}) and renders it in a sandboxed iframe. Empty
     * string means the tool has no associated UI.
     */
    String uiResource() default "";

    /**
     * Tool annotation hint (MCP 2025-03-26+): the tool does not modify its
     * environment. Spec default {@code false}.
     */
    boolean readOnlyHint() default false;

    /**
     * Tool annotation hint: the tool may perform destructive updates. Only
     * meaningful when {@link #readOnlyHint()} is {@code false}. Spec default
     * {@code true}.
     */
    boolean destructiveHint() default true;

    /**
     * Tool annotation hint: calling the tool repeatedly with the same
     * arguments has no additional effect. Spec default {@code false}.
     */
    boolean idempotentHint() default false;

    /**
     * Tool annotation hint: the tool interacts with an open world of external
     * entities (e.g. the web) rather than a closed domain. Spec default
     * {@code true}.
     */
    boolean openWorldHint() default true;

    /**
     * Type of the structured result (MCP 2025-06-18+). When set, the tool
     * advertises an {@code outputSchema} generated from this type (a record's
     * components become required properties) and every successful call must
     * return a JSON object carrying those properties as
     * {@code structuredContent}; a result that does not is reported as a tool
     * error. {@code void.class} (the default) declares no output schema.
     */
    Class<?> outputType() default void.class;
}
