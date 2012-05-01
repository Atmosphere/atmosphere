/*
 * Copyright 2012 Jeanfrancois Arcand
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
import static org.testng.Assert.assertNotNull;

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
            public AtmosphereFramework.Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return new AtmosphereFramework.Action(AtmosphereFramework.Action.TYPE.CREATED);
            }
        };
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

        AtmosphereRequest r = new AtmosphereRequest.Builder().pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/ab/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/abc/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b/c").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/c").build();
        try {
            processor.map(r);
        } catch (AtmosphereMappingException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void mappingTestServletPath() throws ServletException {
        framework.addAtmosphereHandler("/foo/a/", handler);

        AtmosphereRequest r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a").build();
        assertNotNull(processor.map(r));
    }

    @Test
    public void mappingSubWildcardPath() throws ServletException {
        framework.addAtmosphereHandler("/foo/*", handler);

        AtmosphereRequest r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a/b/c/d/////").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b/c/d/////").build();
        try {
            assertNotNull(processor.map(r));
        } catch (AtmosphereMappingException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void mappingWildcardPath() throws ServletException {
        framework.addAtmosphereHandler("/*", handler);

        AtmosphereRequest r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a/b/c/d/////").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b/c/d/////").build();
        assertNotNull(processor.map(r));
    }
}
