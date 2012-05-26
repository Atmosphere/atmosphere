/*
 * Copyright 2012 Jean-Francois Arcand
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

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class AtmosphereInterceptorTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private AsynchronousProcessor processor;
    private final AtmosphereHandler handler = mock(AtmosphereHandler.class);

    @BeforeMethod
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
        });

        assertEquals(Action.CONTINUE, processor.service(mock(AtmosphereRequest.class), AtmosphereResponse.create()));
    }

    @Test
    public void actionContinueCreatedTest() throws ServletException, IOException {
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
        });

        assertEquals(Action.CONTINUE, processor.service(mock(AtmosphereRequest.class), AtmosphereResponse.create()));
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                // Default is CREATED
                AtmosphereResourceImpl.class.cast(r).action().type(Action.TYPE.CREATED);
                return Action.CONTINUE;
            }
        });
        assertEquals(Action.CREATED, processor.service(mock(AtmosphereRequest.class), AtmosphereResponse.create()));
    }

    @Test
    public void actionCancelledTest() throws ServletException, IOException {
        framework.addAtmosphereHandler("/*", handler);
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                return Action.CANCELLED;
            }
        });

        assertEquals(Action.CANCELLED, processor.service(mock(AtmosphereRequest.class), AtmosphereResponse.create()));
    }

    @Test
    public void actionCreatedTest() throws ServletException, IOException {
        framework.addAtmosphereHandler("/*", handler);
        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                return Action.CREATED;
            }
        });

        assertEquals(Action.CREATED, processor.service(mock(AtmosphereRequest.class), AtmosphereResponse.create()));
    }

}
