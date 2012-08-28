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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class AtmosphereRequestTest {
    private AtmosphereFramework framework;

    @BeforeMethod
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

        Map<String,String[]> qs = new HashMap<String,String[]>();
        qs.put("Content-Type", new String[]{"application/xml"});
        qs.put("X-Atmosphere-Transport", new String[]{"long-polling"});

        AtmosphereRequest request = new AtmosphereRequest.Builder().queryStrings(qs).pathInfo("/a").build();

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
        });
        framework.doCometSupport(request, AtmosphereResponse.create());

        assertEquals(e.get(), "application/xml");
        assertEquals(e2.get().toLowerCase(), "long_polling");
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

        AtmosphereRequest request = new AtmosphereRequest.Builder().queryString("a=b").pathInfo("/a").build();

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
        });
        framework.doCometSupport(request, AtmosphereResponse.create());

        assertEquals(e.get(), "a=b");
    }
}
