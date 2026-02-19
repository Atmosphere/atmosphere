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
package org.atmosphere.mcp.protocol;

import java.util.Map;

/**
 * Helper to construct MCP prompt messages with role and content.
 */
public record McpMessage(String role, Map<String, String> content) {

    public static McpMessage system(String text) {
        return new McpMessage("system", Map.of("type", "text", "text", text));
    }

    public static McpMessage user(String text) {
        return new McpMessage("user", Map.of("type", "text", "text", text));
    }
}
