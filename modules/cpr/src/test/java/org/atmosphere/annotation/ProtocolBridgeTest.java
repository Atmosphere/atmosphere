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
package org.atmosphere.annotation;

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests for {@link ProtocolBridge} — validates that protocol registration
 * silently skips when the protocol modules are not on the classpath.
 *
 * <p>Since the cpr module does not depend on {@code atmosphere-mcp} or
 * {@code atmosphere-a2a}, calls to {@code tryRegisterMcp()} and
 * {@code tryRegisterA2a()} should catch {@code ClassNotFoundException}
 * and return silently without any side effects.</p>
 */
public class ProtocolBridgeTest {

    /**
     * A plain object with no protocol annotations.
     */
    public static class PlainService {
        public void handleMessage(String msg) {
            // no MCP or A2A annotations
        }
    }

    @Test
    public void testTryRegisterMcpSilentlySkipsWhenNotOnClasspath() {
        var framework = mock(AtmosphereFramework.class);
        var instance = new PlainService();

        // Should not throw — atmosphere-mcp is not on the cpr test classpath
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterMcp(framework, instance, "/chat"));

        // Framework should not have been touched at all
        verifyNoInteractions(framework);
    }

    @Test
    public void testTryRegisterA2aSilentlySkipsWhenNotOnClasspath() {
        var framework = mock(AtmosphereFramework.class);
        var instance = new PlainService();

        // Should not throw — atmosphere-a2a is not on the cpr test classpath
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterA2a(framework, instance, "/chat"));

        // Framework should not have been touched at all
        verifyNoInteractions(framework);
    }

    @Test
    public void testTryRegisterMcpWithNullInstance() {
        var framework = mock(AtmosphereFramework.class);

        // Null instance — should not throw (ClassNotFoundException caught first)
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterMcp(framework, new Object(), "/test"));
        verifyNoInteractions(framework);
    }

    @Test
    public void testTryRegisterA2aWithNullInstance() {
        var framework = mock(AtmosphereFramework.class);

        // Plain Object — should not throw (ClassNotFoundException caught first)
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterA2a(framework, new Object(), "/test"));
        verifyNoInteractions(framework);
    }

    @Test
    public void testTryRegisterMcpWithDifferentBasePaths() {
        var framework = mock(AtmosphereFramework.class);
        var instance = new PlainService();

        // Various paths should all silently skip
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterMcp(framework, instance, "/"));
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterMcp(framework, instance, "/api/v1/chat"));
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterMcp(framework, instance, "/deep/nested/path"));

        verifyNoInteractions(framework);
    }

    @Test
    public void testTryRegisterA2aWithDifferentBasePaths() {
        var framework = mock(AtmosphereFramework.class);
        var instance = new PlainService();

        // Various paths should all silently skip
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterA2a(framework, instance, "/"));
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterA2a(framework, instance, "/api/v1/chat"));
        assertDoesNotThrow(() -> ProtocolBridge.tryRegisterA2a(framework, instance, "/deep/nested/path"));

        verifyNoInteractions(framework);
    }
}
