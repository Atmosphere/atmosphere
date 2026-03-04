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
 * Annotates a parameter of an {@link AiTool}-annotated method to provide
 * metadata for the AI model's tool schema.
 *
 * <p>Example:</p>
 * <pre>{@code
 * @AiTool(name = "get_weather", description = "Get weather for a city")
 * public WeatherResult getWeather(
 *         @Param("city") String city,
 *         @Param(value = "unit", description = "Temperature unit", required = false) String unit) {
 *     return weatherService.lookup(city, unit);
 * }
 * }</pre>
 *
 * @see AiTool
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Param {

    /**
     * Parameter name as exposed to the AI model.
     */
    String value();

    /**
     * Human-readable description of this parameter.
     */
    String description() default "";

    /**
     * Whether this parameter is required. Defaults to {@code true}.
     */
    boolean required() default true;
}
