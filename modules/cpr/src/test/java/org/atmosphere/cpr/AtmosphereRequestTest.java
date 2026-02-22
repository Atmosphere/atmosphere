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
package org.atmosphere.cpr;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AtmosphereRequestTest {
    private AtmosphereFramework framework;

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
    }

    @AfterEach
    public void stop() {
        framework.destroy();
    }

    @Test
    public void testQueryStringAsRequest() throws IOException, ServletException {
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        Map<String, String[]> qs = new HashMap<String, String[]>();
        qs.put("Content-Type", new String[]{"application/xml"});
        qs.put("X-Atmosphere-Transport", new String[]{"long-polling"});

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryStrings(qs).pathInfo("/a").build();

        final AtomicReference<String> e = new AtomicReference<String>();
        final AtomicReference<String> e2 = new AtomicReference<String>();

        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                e.set(r.getRequest().getContentType());
                e2.set(r.transport().name());
                return Action.CANCELLED;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }

            @Override
            public void destroy() {

            }
        });
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        assertEquals("application/xml", e.get());
        assertEquals("long_polling", e2.get().toLowerCase());
    }

    @Test
    public void testQueryStringBuilder() throws IOException, ServletException {
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryString("a=b").pathInfo("/a").build();

        final AtomicReference<String> e = new AtomicReference<String>();

        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                e.set(r.getRequest().getQueryString());
                return Action.CANCELLED;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }

            @Override
            public void destroy() {

            }
        });
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        assertEquals("a=b", e.get());
    }

    @Test
    public void testNormalQueryStringBuilder() throws IOException, ServletException {
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        request.queryString("a=b");

        final AtomicReference<String> e = new AtomicReference<String>();

        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                e.set(r.getRequest().getQueryString());
                return Action.CANCELLED;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }

            @Override
            public void destroy() {

            }
        });
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        assertEquals("a=b", e.get());
    }

    @Test
    public void testStrinpQueryStringBuilder() throws IOException, ServletException {
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        request.queryString("a=b&X-Atmosphere-Transport=websocket");

        final AtomicReference<String> e = new AtomicReference<String>();

        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                e.set(r.getRequest().getQueryString());
                return Action.CANCELLED;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }
            @Override
            public void destroy() {

            }
        });
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        assertEquals("a=b", e.get());
    }

    @Test
    public void testEncodingOnPreSuspend() throws IOException, ServletException {
        final AtomicReference<AtmosphereResponse> e = new AtomicReference<AtmosphereResponse>();
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                    @Override
                    public void onPreSuspend(AtmosphereResourceEvent event) {
                        AtmosphereResponse response = event.getResource().getResponse();
                        response.setCharacterEncoding("utf-8");
                        e.set(response);
                    }
                }).suspend(10);
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance().delegateToNativeResponse(false));

        assertEquals("utf-8", e.get().getCharacterEncoding());
    }

    @Test
    public void testRequestBodyString() throws IOException, ServletException {
        final AtomicReference<AtmosphereRequestImpl.Body> e = new AtomicReference<AtmosphereRequestImpl.Body>();
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                e.set(resource.getRequest().body());
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").body("test").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance().delegateToNativeResponse(false));

        assertNotNull(e.get());
        assertTrue(e.get().hasString());
        assertFalse(e.get().hasBytes());
        assertEquals("test", e.get().asString());

    }

    @Test
    public void testRequestBodyBytes() throws IOException, ServletException {
        final AtomicReference<AtmosphereRequestImpl.Body> e = new AtomicReference<AtmosphereRequestImpl.Body>();
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                e.set(resource.getRequest().body());
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").body("test".getBytes()).build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance().delegateToNativeResponse(false));

        assertNotNull(e.get());
        assertTrue(e.get().hasBytes());
        assertFalse(e.get().hasString());
        assertEquals("test", new String(e.get().asBytes()));

    }
    @Test
    public void testForceContentType() throws Exception {
        // a non-empty body
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").body("test".getBytes()).build();
        // default type for a non-empty body
        assertEquals("text/plain", request.getContentType());
        // no body
        request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        // default type for a non-empty type
        assertEquals("text/plain", request.getContentType());

        // no content-type explicitly set
        request = new AtmosphereRequestImpl.Builder().pathInfo("/a").contentType(null).build();
        // no content type
        assertNull(request.getContentType());
    }

    @Test
    public void testWrapMethodWithNullAttributeValue() throws IOException, ServletException {
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getAttributeNames()).thenReturn(Collections.enumeration(Arrays.asList("org.eclipse.jetty.multipartConfig")));
        when(mockRequest.getAttribute("org.eclipse.jetty.multipartConfig")).thenReturn(null);

        AtmosphereRequest wrappedRequest = AtmosphereRequestImpl.wrap(mockRequest);

        assertNotNull(wrappedRequest, "Wrapped request should not be null");
        assertNull(wrappedRequest.getAttribute("org.eclipse.jetty.multipartConfig"), "Attribute value should be null");
    }

    public class AtmosphereRequestImplTest {

        @Test
        public void testGetParameterRaceCondition() throws InterruptedException {
            final Map<String, String[]> queryStrings = Collections.synchronizedMap(new HashMap<>());
            queryStrings.put("testParam", new String[]{"testValue"});

            AtmosphereRequestImpl.Builder builder = new AtmosphereRequestImpl.Builder();
            builder.queryStrings(queryStrings);

            final AtmosphereRequest request = builder.build();

            Runnable addAndRemove = () -> {
                queryStrings.put("testParam", new String[]{"testValue"});
                queryStrings.remove("testParam");
            };

            Runnable getParameter = () -> {
                try {
                    request.getParameter("testParam");
                } catch (NullPointerException e) {
                    fail("NullPointerException occurred");
                }
            };

            ExecutorService executor = Executors.newFixedThreadPool(2);
            for (int i = 0; i < 10000; i++) {
                executor.execute(addAndRemove);
                executor.execute(getParameter);
            }

            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.MINUTES);
        }
    }

}
