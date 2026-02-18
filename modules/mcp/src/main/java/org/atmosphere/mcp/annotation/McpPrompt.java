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
 * Marks a method as an MCP prompt template. The method returns a list of
 * messages that form a prompt, accessible via {@code prompts/get}.
 *
 * <pre>{@code
 * @McpPrompt(name = "analyze_data", description = "Analyze a dataset")
 * public List<McpMessage> analyzeData(@McpParam(name = "dataset") String dataset) {
 *     return List.of(
 *         McpMessage.system("You are a data analyst..."),
 *         McpMessage.user("Analyze: " + dataset)
 *     );
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpPrompt {

    /** Prompt template name. */
    String name();

    /** Description of what this prompt does. */
    String description() default "";
}
