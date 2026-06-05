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
package org.atmosphere.mcp.runtime;

import org.atmosphere.mcp.protocol.JsonRpc;

/**
 * Strategy for one generation of the MCP protocol. The two generations differ
 * only in lifecycle and envelope — handshake vs stateless, session vs
 * {@code _meta} — never in how a tool/resource/prompt actually runs, which both
 * delegate back to the shared execution in {@link McpProtocolHandler}.
 *
 * <ul>
 *   <li>{@link SessionDialect} — the session model (handshake + {@code Mcp-Session-Id}),
 *       serving {@code 2024-11-05 … 2025-11-25}.</li>
 *   <li>{@link StatelessDialect} — the stateless model (SEP-2567/SEP-2575),
 *       serving {@code 2026-07-28}.</li>
 * </ul>
 *
 * <p>{@link McpProtocolHandler} selects the dialect per request from the
 * presence of a {@code _meta} protocol version, so a legacy client and a
 * stateless client can hit the same endpoint and each get correct behavior.</p>
 */
interface ProtocolDialect {

    /**
     * Dispatch a parsed JSON-RPC request and return the response to serialize,
     * or {@code null} if there is nothing to send (e.g. a notification already
     * handled upstream).
     */
    JsonRpc.Response dispatch(McpRequestContext ctx);
}
