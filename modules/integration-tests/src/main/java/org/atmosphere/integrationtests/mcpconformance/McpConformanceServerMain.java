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
package org.atmosphere.integrationtests.mcpconformance;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;

/**
 * Boots {@link ConformanceMcpServer} for the MCP conformance workflow
 * ({@code .github/workflows/mcp-conformance.yml}). The port comes from
 * {@code -Dserver.port} (default 3001); the MCP endpoint is {@code /mcp}.
 * Launched as plain {@code java -cp target/classes:target/e2e-lib/*} so the
 * workflow never runs Maven at test time.
 */
public final class McpConformanceServerMain {

    private McpConformanceServerMain() {
    }

    public static void main(String[] args) throws Exception {
        var port = Integer.getInteger("server.port", 3001);
        var server = new EmbeddedAtmosphereServer()
                .withPort(port)
                .withAnnotationPackage("org.atmosphere.integrationtests.mcpconformance")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.agent.processor")
                .withInitOnStart();
        server.start();
        System.out.println("MCP conformance server started on http://localhost:" + server.getPort() + "/mcp");
        Thread.currentThread().join();
    }
}
