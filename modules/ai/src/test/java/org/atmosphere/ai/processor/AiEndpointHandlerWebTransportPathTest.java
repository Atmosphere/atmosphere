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
package org.atmosphere.ai.processor;

import java.lang.reflect.Method;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A templated {@code @AiEndpoint} must resolve its concrete path on EVERY transport.
 *
 * <p>The 2026-08-28 sweep found {@code @PathParam} injecting null and room isolation
 * silently lost for WebTransport clients — the Console's default transport. Cause:
 * {@code assignPerPathBroadcaster} and {@code extractPathParams} both read
 * {@code request.getRequestURI()}, which the WebSocket path populates but the
 * WebTransport path does not: {@code ReactorNettyTransportServer} sets
 * {@code request.pathInfo(...)} from the HTTP/3 CONNECT {@code :path} instead. Every
 * WebTransport client therefore landed on one broadcaster with the empty-string id,
 * regardless of which room it asked for.</p>
 *
 * <p>{@code org.atmosphere.util.Utils.pathInfo(request)} is the framework's
 * transport-agnostic resolver (servletPath + pathInfo) and is what
 * {@code PathParamIntrospector} already uses. These tests pin that the handler
 * resolves the same concrete path whichever field the transport happened to fill.</p>
 */
class AiEndpointHandlerWebTransportPathTest {

    private static final String TEMPLATE = "/atmosphere/classroom/{room}";
    private static final String CONCRETE = "/atmosphere/classroom/math";

    /** Invokes the handler's private resolver so the test pins behaviour, not plumbing. */
    private static String resolve(AtmosphereRequest request) throws Exception {
        Method m = AiEndpointHandler.class.getDeclaredMethod("resolvePath", AtmosphereRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(null, request);
    }

    @Test
    void resolvesConcretePathWhenTransportSetsRequestUri() throws Exception {
        // WebSocket / SSE / long-polling shape: requestURI carries the concrete path.
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .requestURI(CONCRETE)
                .pathInfo(CONCRETE)
                .build();

        assertEquals(CONCRETE, resolve(request));
    }

    @Test
    void resolvesConcretePathWhenOnlyPathInfoIsSet() throws Exception {
        // WebTransport shape: ReactorNettyTransportServer sets pathInfo from the
        // HTTP/3 CONNECT :path and never sets requestURI. Before the fix this
        // resolved to "" and both the broadcaster id and @PathParam were lost.
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .pathInfo(CONCRETE)
                .build();

        String resolved = resolve(request);
        assertNotNull(resolved, "WebTransport path must resolve, not return null");
        assertEquals(CONCRETE, resolved,
                "WebTransport sets pathInfo, not requestURI — resolving via requestURI alone "
                        + "yields the empty string, which is what put every room on one broadcaster");
    }

    @Test
    void resolvedPathIsNeverTheRawTemplate() throws Exception {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .pathInfo(CONCRETE)
                .build();

        assertEquals(CONCRETE, resolve(request));
        // assignPerPathBroadcaster bails when the resolved path equals the template;
        // if resolution regressed to the template, room isolation would silently vanish.
        org.junit.jupiter.api.Assertions.assertNotEquals(TEMPLATE, resolve(request));
    }
}
