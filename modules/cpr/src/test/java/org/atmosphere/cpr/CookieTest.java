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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class CookieTest {

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
    public void unSet() throws Exception {
        framework.destroy();
    }

    @Test
    public void basicHandlerTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<Cookie> cValue = new AtomicReference<Cookie>();
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                Cookie[] c = event.getResource().getRequest().getCookies();
                cValue.set(c[0]);
            }

            @Override
            public void destroy() {
            }
        });
        Set<Cookie> c = new HashSet<Cookie>();
        c.add(new Cookie("yo", "man"));

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().cookies(c).pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(cValue.get());

        Cookie i = c.iterator().next();
        assertEquals(i.getName(), cValue.get().getName());
        assertEquals(i.getValue(), cValue.get().getValue());
    }

    @Test
    public void setCookieTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<Cookie> cValue = new AtomicReference<Cookie>();
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.getResponse().addCookie(resource.getRequest().getCookies()[0]);
                r.set(resource);
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                Cookie[] c = event.getResource().getRequest().getCookies();
                cValue.set(c[0]);
            }

            @Override
            public void destroy() {
            }
        });
        Set<Cookie> c = new HashSet<Cookie>();
        Cookie a = new Cookie("yo", "man");
        a.setComment("kdaskjdaskda");
        a.setDomain("dasdasdasd");
        a.setHttpOnly(true);
        a.setPath("/ya");
        c.add(a);

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().cookies(c).pathInfo("/a").build();
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance().delegateToNativeResponse(false);
        response.destroyable(false);
        framework.doCometSupport(request, response);

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(cValue.get());

        Cookie i = c.iterator().next();
        assertEquals(i.getName(), cValue.get().getName());
        assertEquals(i.getValue(), cValue.get().getValue());
        assertEquals("yo=man; Domain=dasdasdasd; Path=/ya; HttpOnly", response.headers().get("Set-Cookie"));
    }
}
