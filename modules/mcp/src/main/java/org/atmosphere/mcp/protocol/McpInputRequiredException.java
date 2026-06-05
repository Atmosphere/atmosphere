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
 * Thrown by an {@code @McpTool} handler to request additional input from the
 * client mid-execution (multi-round-trip, SEP-2322). On the stateless
 * {@code 2026-07-28} transport the server answers the original request with an
 * {@code InputRequiredResult} ({@code resultType: "input_required"}) carrying
 * these {@code inputRequests} plus an opaque {@code requestState}; the client
 * fulfills the requests and re-sends the original call with {@code inputResponses}
 * and the echoed {@code requestState}, at which point the handler re-runs with
 * the responses visible via {@link McpInputContext}.
 *
 * <p>Each entry of {@code inputRequests} is a server-to-client request object
 * (e.g. an {@code elicitation/create} or {@code sampling/createMessage}); the
 * map key is the handler's identifier for that request and is echoed back as the
 * key in the client's {@code inputResponses}.</p>
 */
public final class McpInputRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, Object> inputRequests;

    public McpInputRequiredException(Map<String, Object> inputRequests) {
        super("MCP tool requires additional client input");
        this.inputRequests = inputRequests == null ? Map.of() : Map.copyOf(inputRequests);
    }

    /** The server-to-client requests the client must fulfill before retrying. */
    public Map<String, Object> inputRequests() {
        return inputRequests;
    }
}
