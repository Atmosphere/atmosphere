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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class MeteorTest {


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

    @AfterMethod
    public void stop() {
        framework.destroy();
    }

    @Test
    public void testMeteor() throws IOException, ServletException {
        final AtomicReference<Meteor> meteor = new AtomicReference<Meteor>();
        final Servlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
                meteor.set(Meteor.lookup(req));
            }
        };
        framework.addAtmosphereHandler("/a", new ReflectorServletProcessor(s));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        framework.interceptor(new AtmosphereInterceptorAdapter() {
            @Override
            public Action inspect(AtmosphereResource r) {
                Meteor m = Meteor.build(r.getRequest());
                return Action.CONTINUE;
            }

        });
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        assertNotNull(meteor.get());
    }

    @Test
    public void testMeteorNull() throws IOException, ServletException {
        final AtomicReference<Meteor> meteor = new AtomicReference<Meteor>();
        final Servlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
                meteor.set(Meteor.lookup(req));
            }
        };
        framework.addAtmosphereHandler("/a", new ReflectorServletProcessor(s));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        framework.interceptor(new AtmosphereInterceptorAdapter() {
            @Override
            public Action inspect(AtmosphereResource r) {
                return Action.CONTINUE;
            }

        });
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        assertNull(meteor.get());
    }

}
