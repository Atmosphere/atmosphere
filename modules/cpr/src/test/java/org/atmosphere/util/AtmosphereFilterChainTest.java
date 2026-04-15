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
package org.atmosphere.util;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AtmosphereFilterChainTest {

    @Test
    void addFilterRejectsNullFilter() {
        var chain = new AtmosphereFilterChain();
        var fc = new FilterConfigImpl(mock(ServletConfig.class));
        // FilterConfigImpl with null filter
        assertThrows(NullPointerException.class,
                () -> chain.addFilter(fc));
    }

    @Test
    void addFilterAcceptsValidFilter() {
        var chain = new AtmosphereFilterChain();
        var fc = filterConfig(passThroughFilter());
        assertDoesNotThrow(() -> chain.addFilter(fc));
        assertNotNull(chain.getFilter(0));
    }

    @Test
    void getFilterReturnsAddedFilter() {
        var chain = new AtmosphereFilterChain();
        var filter = passThroughFilter();
        var fc = filterConfig(filter);
        chain.addFilter(fc);
        assertSame(filter, chain.getFilter(0).getFilter());
    }

    @Test
    void setServletAndGetServlet() {
        var chain = new AtmosphereFilterChain();
        var servlet = mock(Servlet.class);
        var config = mock(ServletConfig.class);
        chain.setServlet(config, servlet);
        assertSame(servlet, chain.getServlet());
        assertSame(config, chain.getServletConfig());
    }

    @Test
    void invokeFilterChainCallsServletWhenNoFilters()
            throws IOException, ServletException {
        var chain = new AtmosphereFilterChain();
        var servlet = mock(Servlet.class);
        var config = mock(ServletConfig.class);
        chain.setServlet(config, servlet);

        var request = attributeStoringRequest();
        var response = mock(ServletResponse.class);
        chain.invokeFilterChain(request, response);
        verify(servlet).service(request, response);
    }

    @Test
    void invokeFilterChainExecutesFiltersInOrder()
            throws IOException, ServletException {
        var chain = new AtmosphereFilterChain();
        var order = new ArrayList<String>();

        chain.addFilter(filterConfig(recordingFilter(order, "A")));
        chain.addFilter(filterConfig(recordingFilter(order, "B")));

        var servlet = mock(Servlet.class);
        var config = mock(ServletConfig.class);
        chain.setServlet(config, servlet);

        var request = attributeStoringRequest();
        var response = mock(ServletResponse.class);
        chain.invokeFilterChain(request, response);

        assertEquals(List.of("A", "B"), order);
        verify(servlet).service(request, response);
    }

    @Test
    void filtersArrayGrowsBeyondInitialCapacity() {
        var chain = new AtmosphereFilterChain();
        // Initial capacity is 20; add 25 filters
        for (int i = 0; i < 25; i++) {
            chain.addFilter(filterConfig(passThroughFilter()));
        }
        assertNotNull(chain.getFilter(24));
    }

    @Test
    void destroyRecyclesFilters()
            throws IOException, ServletException {
        var chain = new AtmosphereFilterChain();
        var filter = mock(Filter.class);
        chain.addFilter(filterConfig(filter));
        chain.destroy();
        verify(filter).destroy();
    }

    @Test
    void destroyCallsServletDestroy() {
        var chain = new AtmosphereFilterChain();
        var servlet = mock(Servlet.class);
        chain.setServlet(mock(ServletConfig.class), servlet);
        chain.destroy();
        verify(servlet).destroy();
    }

    @Test
    void getServletReturnsNullWhenNotSet() {
        assertNull(new AtmosphereFilterChain().getServlet());
    }

    @Test
    void getServletConfigReturnsNullWhenNotSet() {
        assertNull(new AtmosphereFilterChain().getServletConfig());
    }

    @Test
    void initCallsFilterInit()
            throws ServletException {
        var chain = new AtmosphereFilterChain();
        var filter = mock(Filter.class);
        chain.addFilter(filterConfig(filter));
        chain.init();
        verify(filter).init(Mockito.any());
    }

    // --- helpers ---

    private ServletRequest attributeStoringRequest() {
        var request = mock(ServletRequest.class);
        Map<String, Object> attrs = new HashMap<>();
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(
                Mockito.anyString(), Mockito.any());
        when(request.getAttribute(Mockito.anyString()))
                .thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        return request;
    }

    private FilterConfigImpl filterConfig(Filter filter) {
        var fc = new FilterConfigImpl(mock(ServletConfig.class));
        fc.setFilter(filter);
        return fc;
    }

    private Filter passThroughFilter() {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res,
                                 FilterChain chain)
                    throws IOException, ServletException {
                chain.doFilter(req, res);
            }
        };
    }

    private Filter recordingFilter(List<String> order, String name) {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res,
                                 FilterChain chain)
                    throws IOException, ServletException {
                order.add(name);
                chain.doFilter(req, res);
            }
        };
    }
}
