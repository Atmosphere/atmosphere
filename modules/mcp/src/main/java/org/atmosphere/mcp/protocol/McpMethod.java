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

/**
 * MCP protocol method constants.
 */
public final class McpMethod {

    private McpMethod() {}

    // Lifecycle
    public static final String INITIALIZE = "initialize";
    public static final String INITIALIZED = "notifications/initialized";
    public static final String PING = "ping";

    // Tools
    public static final String TOOLS_LIST = "tools/list";
    public static final String TOOLS_CALL = "tools/call";

    // Resources
    public static final String RESOURCES_LIST = "resources/list";
    public static final String RESOURCES_READ = "resources/read";
    public static final String RESOURCES_SUBSCRIBE = "resources/subscribe";
    public static final String RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";

    // Prompts
    public static final String PROMPTS_LIST = "prompts/list";
    public static final String PROMPTS_GET = "prompts/get";

    // Notifications
    public static final String PROGRESS = "notifications/progress";
    public static final String CANCELLED = "notifications/cancelled";
    public static final String RESOURCES_UPDATED = "notifications/resources/updated";

    // Elicitation (MCP 2025-06-18+) — server requests info from user via the
    // client. Issued as a request from server to client; the client returns an
    // ElicitResult envelope with the user's response.
    public static final String ELICITATION_CREATE = "elicitation/create";

    // Tasks (MCP 2025-11-25, experimental) — durable request tracking.
    public static final String TASKS_GET = "tasks/get";
    public static final String TASKS_RESULT = "tasks/result";
    public static final String TASKS_LIST = "tasks/list";
    public static final String TASKS_CANCEL = "tasks/cancel";
    public static final String TASKS_STATUS = "notifications/tasks/status";
}
