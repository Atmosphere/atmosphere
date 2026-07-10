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
package org.atmosphere.spring.boot;

import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.mcp.registry.McpRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot 3 parity for the admin MCP write-tool authorizer wiring. The
 * write surface — which now includes the pre-redaction {@code atmosphere_read_tape}
 * tool — must default to {@code REQUIRE_PRINCIPAL}, never {@code ALLOW_ALL}
 * (Correctness Invariant #6, default deny). This pins the fix for the drift
 * where spring-boot3-starter registered write tools on the ALLOW_ALL read
 * bridge, letting an anonymous MCP caller read cross-principal tape content.
 */
class AdminMcpWriteAuthzTest {

    @Test
    void writeToolsRequirePrincipalSoAnonymousReadTapeIsDenied() throws Exception {
        var registry = new McpRegistry();
        // Deep stubs let the denial path's admin.auditLog().record(...) run
        // without standing up a real AtmosphereFramework.
        var admin = Mockito.mock(AtmosphereAdmin.class, Mockito.RETURNS_DEEP_STUBS);
        var properties = new AtmosphereProperties();
        properties.setAdminMcpWriteTools("true");

        // Build the bridge exactly as the auto-config does (no custom
        // ControlAuthorizer bean): write tools must be mounted on a
        // REQUIRE_PRINCIPAL bridge, not the ALLOW_ALL read bridge.
        new AtmosphereAdminAutoConfiguration.AdminMcpBridgeConfiguration()
                .atmosphereAdminMcpBridge(admin, registry, properties, null);

        // Assert on a write tool that is always registered (no optional-module
        // gate): atmosphere_read_tape rides this very same write bridge and
        // authorizer, so denying an anonymous broadcast proves read_tape is
        // equally gated — without depending on the tape module being on the
        // test runtime classpath.
        var writeTool = registry.tools().get("atmosphere_broadcast");
        assertThat(writeTool)
                .as("write tools must be registered when mcp-write-tools=true")
                .isNotNull();

        // The single-arg ToolHandler.execute delegates to the identity-aware
        // form with principal=null (anonymous) — the case that must be denied.
        var result = writeTool.handler().execute(Map.<String, Object>of());

        // REQUIRE_PRINCIPAL denies the null (anonymous) principal. The pre-fix
        // ALLOW_ALL wiring would have authorized and attempted the broadcast.
        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("error"))
                .as("anonymous callers must be denied, not authorized")
                .isEqualTo("unauthorized");
    }
}
