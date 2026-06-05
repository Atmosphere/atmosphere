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
 * The client-supplied responses available to an {@code @McpTool} handler during
 * a multi-round-trip (SEP-2322) interaction on the stateless {@code 2026-07-28}
 * transport. Inject it as a tool-method parameter to read responses the client
 * returned for input the handler previously requested:
 *
 * <pre>{@code
 * @McpTool(name = "book")
 * public String book(@McpParam(name = "date") String date, McpInputContext input) {
 *     if (!input.has("confirm")) {
 *         throw new McpInputRequiredException(Map.of(
 *             "confirm", Map.of("method", "elicitation/create",
 *                 "params", Map.of("message", "Confirm booking for " + date + "?"))));
 *     }
 *     return "Booked " + date;   // input.get("confirm") holds the client's reply
 * }
 * }</pre>
 *
 * <p>The keys match the keys the handler used in its {@link McpInputRequiredException}.
 * The context accumulates across rounds, so a handler that requests input several
 * times sees every prior response. Empty on the first call.</p>
 */
public final class McpInputContext {

    private static final McpInputContext EMPTY = new McpInputContext(Map.of());

    private final Map<String, Object> responses;

    public McpInputContext(Map<String, Object> responses) {
        this.responses = responses == null ? Map.of() : Map.copyOf(responses);
    }

    /** An empty context — no responses yet (the first call of an interaction). */
    public static McpInputContext empty() {
        return EMPTY;
    }

    /** All accumulated input responses, keyed as the handler requested them. */
    public Map<String, Object> responses() {
        return responses;
    }

    /** Whether the client has returned a response for {@code key}. */
    public boolean has(String key) {
        return responses.containsKey(key);
    }

    /** The client's response for {@code key}, or {@code null} if not yet provided. */
    public Object get(String key) {
        return responses.get(key);
    }
}
