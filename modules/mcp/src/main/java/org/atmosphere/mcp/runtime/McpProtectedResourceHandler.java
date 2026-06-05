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

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Serves the OAuth 2.0 Protected Resource Metadata (RFC 9728) for the MCP
 * server, so a client that receives a {@code 401} can discover the
 * authorization server. Registered at {@code /.well-known/oauth-protected-resource}
 * (the discovery endpoint is public and itself unauthenticated). The metadata
 * itself comes from {@link McpAuthorization}.
 */
public final class McpProtectedResourceHandler implements AtmosphereHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpAuthorization authorization;

    public McpProtectedResourceHandler(McpAuthorization authorization) {
        this.authorization = authorization;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var response = resource.getResponse();
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(authorization.protectedResourceMetadata()));
        response.getWriter().flush();
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        // Metadata is a single synchronous response; nothing to stream.
    }

    @Override
    public void destroy() {
        // No resources held.
    }
}
