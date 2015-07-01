/*
 * Copyright 2015 Jean-Francois Arcand
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
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class QueryStringTest {

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
    public void basicQueryStringTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        final AtomicReference<String> q = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                q.set(resource.getRequest().getQueryString());
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });
        // use ordered map to keep ordering in map see https://github.com/Atmosphere/atmosphere/issues/1652
        Map<String, String[]> queryStrings = new LinkedHashMap<String, String[]>();
        queryStrings.put("a", new String[]{"b"});
        queryStrings.put("b", new String[]{"d"});
        queryStrings.put("c", new String[]{"f"});

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryStrings(queryStrings).pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(q.get());
        assertEquals(q.get(), "a=b&b=d&c=f");
    }

    @Test
    public void issue1321() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        final AtomicReference<String> q = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                q.set(resource.getRequest().getQueryString());
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });
        String s = "&X-Atmosphere-tracking-id=c8834462-c46e-4dad-a22f-b86aabe3f883&X-Atmosphere-Framework=2.0.3-javascript&X-Atmosphere-Transport=long-polling&X-Atmosphere-TrackMessageSize=true&X-atmo-protocol=true&_=1380799455333";

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryString(s).pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(q.get());
        assertEquals(q.get(), "");
    }

    @Test
    public void issue1421() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        final AtomicReference<String> q = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                q.set(resource.getRequest().getContentType());
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });
        String s = "&Content-Type=text/x-gwt-rpc;%20charset=UTF-8&X-Atmosphere-tracking-id=c8834462-c46e-4dad-a22f-b86aabe3f883&X-Atmosphere-Framework=2.0.3-javascript&X-Atmosphere-Transport=long-polling&X-Atmosphere-TrackMessageSize=true&X-atmo-protocol=true&_=1380799455333";

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryString(s).pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(q.get());
        assertEquals(q.get(), "text/x-gwt-rpc; charset=UTF-8");
    }
}
