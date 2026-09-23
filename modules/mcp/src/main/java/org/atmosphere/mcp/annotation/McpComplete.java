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
 * Supplies argument completions ({@code completion/complete}, MCP 2025-06-18+)
 * for one argument of a prompt or of a resource template. Set exactly one of
 * {@link #prompt()} or {@link #resource()}.
 *
 * <p>The method receives the partial value typed so far and returns candidate
 * values; it may also declare a second {@code Map<String, String>} parameter
 * to receive the other arguments already resolved by the client
 * ({@code context.arguments}). At most 100 values are sent back.</p>
 *
 * <pre>{@code
 * @McpComplete(prompt = "analyze", argument = "topic")
 * public List<String> topics(String prefix) {
 *     return catalog.topicsStartingWith(prefix);
 * }
 * }</pre>
 *
 * <p>Enum-typed prompt or template arguments complete automatically from the
 * enum constants; an explicit {@code @McpComplete} for the same argument wins.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpComplete {

    /** Name of the {@link McpPrompt} whose argument this completes. */
    String prompt() default "";

    /** URI template of the {@link McpResource} whose variable this completes. */
    String resource() default "";

    /** The argument (prompt argument or URI-template variable) to complete. */
    String argument();
}
