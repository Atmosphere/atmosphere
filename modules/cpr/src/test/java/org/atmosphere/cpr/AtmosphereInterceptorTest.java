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

import org.atmosphere.interceptor.InvokationOrder;
import org.mockito.Mockito;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AtmosphereInterceptorTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private AsynchronousProcessor processor;
    private final AtmosphereHandler handler = mock(AtmosphereHandler.class);

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
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
        config = framework.getAtmosphereConfig();
        processor = new AsynchronousProcessor(config) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return action(req, res);
            }
        };
    }

    @Test
    public void actionContinueTest() throws ServletException, IOException {
        framework.addAtmosphereHandler("/*", handler);
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                // Default is CREATED
                AtmosphereResourceImpl.class.cast(r).action().type(Action.TYPE.CONTINUE);
                return Action.CONTINUE;
            }
            @Override
            public void destroy() {
            }
            @Override
            public void postInspect(AtmosphereResource r) {
            }
        });

        assertEquals(processor.service(mock(AtmosphereRequestImpl.class), AtmosphereResponseImpl.newInstance()), Action.CONTINUE);
    }

    @Test
    public void actionContinueCreatedTest() throws ServletException, IOException {
        framework.addAtmosphereHandler("/*", handler);
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }
            @Override
            public void destroy() {
            }
            @Override
            public Action inspect(AtmosphereResource r) {
                // Default is CREATED
                AtmosphereResourceImpl.class.cast(r).action().type(Action.TYPE.CONTINUE);
                return Action.CONTINUE;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }
        });

        assertEquals(processor.service(mock(AtmosphereRequestImpl.class), AtmosphereResponseImpl.newInstance()), Action.CONTINUE);
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }
            @Override
            public void destroy() {
            }
            @Override
            public Action inspect(AtmosphereResource r) {
                // Default is CREATED
                AtmosphereResourceImpl.class.cast(r).action().type(Action.TYPE.CREATED);
                return Action.CONTINUE;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }
        });
        assertEquals(processor.service(mock(AtmosphereRequestImpl.class), AtmosphereResponseImpl.newInstance()), Action.CREATED);
    }

    @Test
    public void actionCancelledTest() throws ServletException, IOException {
        framework.addAtmosphereHandler("/*", handler);
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }
            @Override
            public void destroy() {
            }
            @Override
            public Action inspect(AtmosphereResource r) {
                return Action.CANCELLED;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }
        });

        assertEquals(processor.service(mock(AtmosphereRequestImpl.class), AtmosphereResponseImpl.newInstance()), Action.CANCELLED);
    }

    @Test
    public void actionCreatedTest() throws ServletException, IOException {
        framework.addAtmosphereHandler("/*", handler);
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }
            @Override
            public void destroy() {
            }
            @Override
            public Action inspect(AtmosphereResource r) {
                return Action.CREATED;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }
        });

        assertEquals(processor.service(mock(AtmosphereRequestImpl.class), AtmosphereResponseImpl.newInstance()), Action.CREATED);
    }

    @Test
    public void priorityTest() throws ServletException, IOException {
        framework.addAtmosphereHandler("/*", handler);
        framework.interceptor(new AtmosphereInterceptorAdapter() {

            @Override
            public Action inspect(AtmosphereResource r) {
                return Action.CREATED;
            }

            @Override
            public PRIORITY priority() {
                return InvokationOrder.FIRST_BEFORE_DEFAULT;
            }

            @Override
            public String toString() {
                return "XXX";
            }
        });

        assertEquals(processor.service(mock(AtmosphereRequestImpl.class), AtmosphereResponseImpl.newInstance()), Action.CREATED);
        assertEquals("CORS Interceptor Support", framework.getAtmosphereHandlers().get("/" + AtmosphereFramework.MAPPING_REGEX).interceptors().removeFirst().toString());
        assertEquals("XXX", framework.getAtmosphereHandlers().get("/" + AtmosphereFramework.MAPPING_REGEX).interceptors().getFirst().toString());

    }

    @Test
    public void configureTest() throws ServletException, IOException {
        final AtomicInteger count = new AtomicInteger();
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
                count.incrementAndGet();
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                return Action.CREATED;
            }

            @Override
            public void destroy() {
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }
        });

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
        config = framework.getAtmosphereConfig();
        processor = new AsynchronousProcessor(config) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return action(req, res);
            }
        };
        framework.addAtmosphereHandler("/*", handler);

        assertEquals(processor.service(mock(AtmosphereRequestImpl.class), AtmosphereResponseImpl.newInstance()), Action.CREATED);
        assertEquals(count.get(), 1);

    }

    @Test
    public void priorityIllegalTest() throws ServletException, IOException {
        framework.addAtmosphereHandler("/*", handler);
        framework.interceptor(new AtmosphereInterceptorAdapter() {

            @Override
            public Action inspect(AtmosphereResource r) {
                return Action.CREATED;
            }

            @Override
            public PRIORITY priority() {
                return InvokationOrder.FIRST_BEFORE_DEFAULT;
            }

            @Override
            public String toString() {
                return "XXX";
            }
        });
        try {
            framework.interceptor(new AtmosphereInterceptorAdapter() {

                @Override
                public Action inspect(AtmosphereResource r) {
                    return Action.CREATED;
                }

                @Override
                public PRIORITY priority() {
                    return InvokationOrder.FIRST_BEFORE_DEFAULT;
                }

                @Override
                public String toString() {
                    return "XXX";
                }
            });
        } catch (Exception ignored) {
        }
        assertEquals(processor.service(mock(AtmosphereRequestImpl.class), AtmosphereResponseImpl.newInstance()), Action.CREATED);
        assertEquals("CORS Interceptor Support", framework.getAtmosphereHandlers().get("/" + AtmosphereFramework.MAPPING_REGEX).interceptors().removeFirst().toString());
        assertEquals("XXX", framework.getAtmosphereHandlers().get("/" + AtmosphereFramework.MAPPING_REGEX).interceptors().getFirst().toString());
    }

    @Test
    public void postInspectOnThrown() throws Exception{
        AtmosphereHandler handler = mock(AtmosphereHandler.class);
        Mockito.doThrow(new RuntimeException()).when(handler).onRequest(Mockito.any(AtmosphereResource.class));
        framework.addAtmosphereHandler("/*", handler);

        final AtomicBoolean postInspected = new AtomicBoolean(false);
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public void destroy() {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                return Action.CONTINUE;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
                postInspected.set(true);
            }
        });

        try{
            processor.service(mock(AtmosphereRequestImpl.class), AtmosphereResponseImpl.newInstance());
        } catch(Throwable t){}
        assertTrue(postInspected.get());
    }
}
