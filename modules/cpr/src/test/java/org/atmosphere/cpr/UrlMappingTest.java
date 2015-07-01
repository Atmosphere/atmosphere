/*
 * Copyright 2015 Async-IO.org
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

import org.atmosphere.util.DefaultEndpointMapper;
import org.atmosphere.util.EndpointMapper;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertNotNull;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.fail;

public class UrlMappingTest {

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
                return new Action(Action.TYPE.CREATED);
            }
        };
    }

    @AfterMethod
    public void unSet() throws Exception {
        framework.destroy();
    }

    @Test
    public void mathTest() throws ServletException {
        EndpointMapper<String> mapper = new DefaultEndpointMapper<String>();

        Map<String,String> mappingPoints = new HashMap<String, String>();

        mappingPoints.put("/c", "/c");
        mappingPoints.put("/{a}/{b}", "/{a}/{b}");

        Assert.assertEquals("/c", mapper.map("/c", mappingPoints));
    }

    @Test
    public void mappingTest1() throws ServletException {
        framework.addAtmosphereHandler("/a/", handler);
        framework.addAtmosphereHandler("/a", handler);
        framework.addAtmosphereHandler("/ab/", handler);
        framework.addAtmosphereHandler("/abc/", handler);
        framework.addAtmosphereHandler("/a/b", handler);
        framework.addAtmosphereHandler("/a/b/", handler);
        framework.addAtmosphereHandler("/a/b/c", handler);
        framework.addAtmosphereHandler("/", handler);

        AtmosphereRequest r = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/a/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/ab/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/abc/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/a/b").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/a/b/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/a/b/c").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/c").build();
        try {
            processor.map(r);
        } catch (AtmosphereMappingException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void mappingTestServletPath() throws ServletException {
        framework.addAtmosphereHandler("/com.zyxabc.abc.Abc/gwtCometEvent*", handler);

        AtmosphereRequest r = new AtmosphereRequestImpl.Builder().servletPath("/com.zyxabc.abc.Abc/gwtCometEvent").build();
        assertNotNull(processor.map(r));
    }

    @Test
    public void mappingExactUrl() throws ServletException {
        framework.addAtmosphereHandler("/foo/a/", handler);

        AtmosphereRequest r = new AtmosphereRequestImpl.Builder().servletPath("/foo").pathInfo("/a").build();
        assertNotNull(processor.map(r));
    }

    @Test
    public void mappingSubWildcardPath() throws ServletException {
        framework.addAtmosphereHandler("/foo/*", handler);

        AtmosphereRequest r = new AtmosphereRequestImpl.Builder().servletPath("/foo").pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().servletPath("/foo").pathInfo("/a/b/c/d/////").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/a/b/c/d/////").build();
        try {
            assertNotNull(processor.map(r));
        } catch (AtmosphereMappingException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void mappingDotWildcard() throws ServletException {
        framework.addAtmosphereHandler("/*", handler);

        AtmosphereRequest r = new AtmosphereRequestImpl.Builder().pathInfo("/a.b/b").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/a/1.2").build();
        assertNotNull(processor.map(r));
    }

    @Test
    public void mappingWildcardPath() throws ServletException {
        framework.addAtmosphereHandler("/*", handler);

        AtmosphereRequest r = new AtmosphereRequestImpl.Builder().servletPath("/foo").pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().servletPath("/foo").pathInfo("/a/b/c/d/////").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/a/b/c/d/////").build();
        assertNotNull(processor.map(r));
    }

    @Test
    public void mappingTestTraillingHandler() throws ServletException {
        // servlet-mapping : /a/*
        framework.addAtmosphereHandler("/a", handler);

        AtmosphereRequest r = new AtmosphereRequestImpl.Builder().pathInfo("/a/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequestImpl.Builder().pathInfo("/a/1").build();
        assertNotNull(processor.map(r));
    }

    @Test
    public void batchMappingTest() throws ServletException {
        framework.addAtmosphereHandler("/", new AH("/"));
        framework.addAtmosphereHandler("/red", new AH("red"));
        framework.addAtmosphereHandler("/red/*", new AH("red"));
        framework.addAtmosphereHandler("/red/red/*", new AH("redred"));
        framework.addAtmosphereHandler("/red/blue/*", new AH("redblue"));
        framework.addAtmosphereHandler("/blue/*", new AH("blue"));
        framework.addAtmosphereHandler("/blue/blue/*", new AH("blueblue"));

        AtmosphereRequest r = new AtmosphereRequestImpl.Builder().pathInfo("/").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "/");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/red").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "red");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/red/1").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "red");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/red/red").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "red");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/red/red/1").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "redred");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/red/blue/").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "redblue");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/red/blue/1").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "redblue");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/blue").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "/");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/blue/blue").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "blue");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/blue/blue/white").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "blueblue");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/green").build();
        assertEquals(processor.map(r).atmosphereHandler.toString(), "/");

        framework.removeAtmosphereHandler("/");

        r = new AtmosphereRequestImpl.Builder().pathInfo("/green").build();
        try {
            processor.map(r);
            fail();
        } catch (AtmosphereMappingException e) {
            assertNotNull(e);
        }
    }

    public final static class AH implements AtmosphereHandler {

        private final String name;

        public AH(String name) {
            this.name = name;
        }

        public String toString() {
            return name;
        }

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        }

        @Override
        public void destroy() {
        }
    }
}
