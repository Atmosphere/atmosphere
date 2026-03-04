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
package org.atmosphere.ai.tool;

/**
 * Result of an AI tool execution.
 *
 * @param toolName the name of the tool that was called
 * @param result   the serialized result (JSON string)
 * @param success  whether the tool executed successfully
 * @param error    error message if {@code success} is false
 */
public record ToolResult(
        String toolName,
        String result,
        boolean success,
        String error
) {
    /** Create a successful result. */
    public static ToolResult success(String toolName, String result) {
        return new ToolResult(toolName, result, true, null);
    }

    /** Create a failed result. */
    public static ToolResult failure(String toolName, String error) {
        return new ToolResult(toolName, null, false, error);
    }
}
