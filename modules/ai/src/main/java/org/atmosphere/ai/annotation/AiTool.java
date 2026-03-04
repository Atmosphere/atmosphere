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
package org.atmosphere.ai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an AI-callable tool/function. Tools annotated with this
 * are registered globally in the {@link org.atmosphere.ai.tool.ToolRegistry}
 * and can be selectively exposed per {@link AiEndpoint}.
 *
 * <p>This provides a framework-agnostic tool definition that bridges to
 * framework-specific tool APIs (LangChain4j {@code @Tool}, Spring AI function
 * callbacks, ADK {@code BaseTool}, Embabel {@code @Action}).</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * public class WeatherTools {
 *
 *     @AiTool(name = "get_weather", description = "Get current weather for a city")
 *     public WeatherResult getWeather(@Param("city") String city,
 *                                     @Param(value = "unit", required = false) String unit) {
 *         return weatherService.lookup(city, unit);
 *     }
 * }
 * }</pre>
 *
 * <p>Tools are registered globally and selected per-endpoint:</p>
 * <pre>{@code
 * @AiEndpoint(path = "/chat", tools = {WeatherTools.class, CalendarTools.class})
 * }</pre>
 *
 * @see Param
 * @see AiEndpoint
 * @see org.atmosphere.ai.tool.ToolRegistry
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AiTool {

    /**
     * The tool name as exposed to the AI model. Must be unique within
     * a {@link org.atmosphere.ai.tool.ToolRegistry}.
     *
     * <p>Convention: use snake_case (e.g., {@code "get_weather"}).</p>
     */
    String name();

    /**
     * Human-readable description of what the tool does. This is sent to
     * the AI model to help it decide when to call the tool.
     */
    String description();
}
