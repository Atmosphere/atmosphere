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
 * Marks a method as an MCP resource. The method provides read-only data
 * accessible to MCP clients via {@code resources/read}.
 *
 * <pre>{@code
 * @McpResource(uri = "atmosphere://rooms/{roomId}/history",
 *              name = "Room History",
 *              description = "Chat message history for a room")
 * public String roomHistory(@McpParam(name = "roomId") String roomId) {
 *     return roomService.getHistory(roomId);
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpResource {

    /** URI or URI template for this resource. */
    String uri();

    /** Human-readable name of this resource. */
    String name() default "";

    /** Description of this resource. */
    String description() default "";

    /** MIME type of the resource content. */
    String mimeType() default "text/plain";
}
