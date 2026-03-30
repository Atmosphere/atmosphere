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

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.atmosphere.a2a.runtime.A2aHandler;
import org.atmosphere.a2a.runtime.A2aProtocolHandler;
import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.types.AgentCard;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereHandlerWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WellKnownAgentFilterTest {

    private AtmosphereFramework framework;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        framework = mock(AtmosphereFramework.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);

        when(request.getMethod()).thenReturn("GET");

        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    @Test
    void delegatesToChainWhenNoHandlers() throws Exception {
        when(framework.getAtmosphereHandlers()).thenReturn(new ConcurrentHashMap<>());

        var filter = new AtmosphereAutoConfiguration.WellKnownAgentFilter(framework);
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void delegatesToChainWhenNoA2aHandlers() throws Exception {
        var config = mock(AtmosphereConfig.class);
        var plainHandler = mock(AtmosphereHandler.class);
        var wrapper = new AtmosphereHandlerWrapper(null, plainHandler, "/chat", config);

        var map = new ConcurrentHashMap<String, AtmosphereHandlerWrapper>();
        map.put("/chat", wrapper);
        when(framework.getAtmosphereHandlers()).thenReturn(map);

        var filter = new AtmosphereAutoConfiguration.WellKnownAgentFilter(framework);
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void returnsSingleAgentCard() throws Exception {
        var map = buildA2aHandlerMap("agent-one");
        when(framework.getAtmosphereHandlers()).thenReturn(map);

        var filter = new AtmosphereAutoConfiguration.WellKnownAgentFilter(framework);
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(200);

        var json = responseBody.toString();
        assertThat(json).contains("agent-one");
        // Single card is NOT wrapped in array
        assertThat(json).doesNotStartWith("[");
    }

    @Test
    void returnsMultipleCardsAsArray() throws Exception {
        var map = buildA2aHandlerMap("agent-a", "agent-b", "agent-c");
        when(framework.getAtmosphereHandlers()).thenReturn(map);

        var filter = new AtmosphereAutoConfiguration.WellKnownAgentFilter(framework);
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(200);

        var json = responseBody.toString();
        assertThat(json).startsWith("[");
        assertThat(json).endsWith("]");
        assertThat(json).contains("agent-a");
        assertThat(json).contains("agent-b");
        assertThat(json).contains("agent-c");
    }

    @Test
    void setsCorrectContentType() throws Exception {
        var map = buildA2aHandlerMap("test-agent");
        when(framework.getAtmosphereHandlers()).thenReturn(map);

        var filter = new AtmosphereAutoConfiguration.WellKnownAgentFilter(framework);
        filter.doFilter(request, response, chain);

        verify(response).setContentType("application/json; charset=utf-8");
    }

    @Test
    void delegatesNonGetToChain() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(framework.getAtmosphereHandlers()).thenReturn(new ConcurrentHashMap<>());

        var filter = new AtmosphereAutoConfiguration.WellKnownAgentFilter(framework);
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void mixedHandlersReturnOnlyA2aCards() throws Exception {
        // A map with one plain handler and one A2A handler.
        // The plain handler should be skipped; only the A2A card is returned.
        var config = mock(AtmosphereConfig.class);
        var plainHandler = mock(AtmosphereHandler.class);
        var plainWrapper = new AtmosphereHandlerWrapper(null, plainHandler, "/chat", config);

        var a2aMap = buildA2aHandlerMap("my-agent");
        a2aMap.put("/chat", plainWrapper);
        when(framework.getAtmosphereHandlers()).thenReturn(a2aMap);

        var filter = new AtmosphereAutoConfiguration.WellKnownAgentFilter(framework);
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        var json = responseBody.toString();
        assertThat(json).contains("my-agent");
        // Only one card, not wrapped in array
        assertThat(json).doesNotStartWith("[");
    }

    /**
     * Creates a handler map with A2A handlers that return agent cards.
     */
    private ConcurrentHashMap<String, AtmosphereHandlerWrapper> buildA2aHandlerMap(
            String... agentNames) {
        var config = mock(AtmosphereConfig.class);
        var map = new ConcurrentHashMap<String, AtmosphereHandlerWrapper>();

        for (var name : agentNames) {
            var card = new AgentCard(name, "Test " + name,
                    "http://localhost/" + name, "1.0",
                    null, null, null, List.of(), null, null, null, null);
            var protocolHandler = new A2aProtocolHandler(
                    mock(A2aRegistry.class), mock(TaskManager.class), card);
            var handler = new A2aHandler(protocolHandler);
            var wrapper = new AtmosphereHandlerWrapper(null, handler,
                    "/atmosphere/" + name, config);
            map.put("/atmosphere/" + name, wrapper);
        }
        return map;
    }
}
