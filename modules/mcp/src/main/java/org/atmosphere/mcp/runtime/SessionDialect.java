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
 * The session-model dialect ({@code 2024-11-05 … 2025-11-25}): the
 * {@code initialize} handshake, {@code Mcp-Session-Id} stickiness,
 * session-scoped subscriptions and {@code tasks/list}. This is the behavior
 * Atmosphere has always shipped; the dialect is a thin façade that delegates to
 * {@link McpProtocolHandler#dispatchSession} so the existing code path — and its
 * test coverage — is unchanged. It exists so the stateless model can sit beside
 * it as a peer rather than a branch.
 */
final class SessionDialect implements ProtocolDialect {

    private final McpProtocolHandler core;

    SessionDialect(McpProtocolHandler core) {
        this.core = core;
    }

    @Override
    public JsonRpc.Response dispatch(McpRequestContext ctx) {
        return core.dispatchSession(ctx.resource(), ctx.id(), ctx.method(), ctx.params());
    }
}
