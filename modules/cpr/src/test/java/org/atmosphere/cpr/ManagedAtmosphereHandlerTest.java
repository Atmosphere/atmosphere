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

import org.atmosphere.config.service.Delete;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.ManagedAtmosphereHandlerService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Post;
import org.atmosphere.config.service.Put;
import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class ManagedAtmosphereHandlerTest {
    private AtmosphereFramework framework;
    private static final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
    private static final AtomicReference<String> message = new AtomicReference<String>();

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName()) ;
        String name = new File(".").getAbsolutePath();
        framework.setLibPath(name.substring(0, name.length() - 1) + "/modules/cpr/target/");
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {

            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return suspended(req, res);
            }

            public void action(AtmosphereResourceImpl r) {
                try {
                    resumed(r.getRequest(), r.getResponse());
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ServletException e) {
                    e.printStackTrace();
                }
            }
        }).init(new ServletConfig() {
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
    public void after() {
        r.set(null);
        framework.destroy();
    }

    @ManagedAtmosphereHandlerService(path = "/a")
    public final static class ManagedGet {
        @Get
        public void get(AtmosphereResource resource) {
            resource.suspend();
            r.set(resource);
        }
    }

    @Test
    public void testGet() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        r.get().resume();

        assertNotNull(r.get());
    }

    @ManagedAtmosphereHandlerService(path = "/b")
    public final static class ManagedPost {
        @Post
        public void post(AtmosphereResource resource) {
            resource.suspend();
            r.set(resource);
        }
    }

    @Test
    public void testPost() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/b").method("POST").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertNotNull(r.get());
        r.get().resume();

    }

    @ManagedAtmosphereHandlerService(path = "/c")
    public final static class ManagedDelete {
        @Delete
        public void delete(AtmosphereResource resource) {
            resource.suspend();
            r.set(resource);
        }
    }

    @Test
    public void testDelete() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/c").method("DELETE").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertNotNull(r.get());
        r.get().resume();
    }

    @ManagedAtmosphereHandlerService(path = "/d")
    public final static class ManagedPut {
        @Put
        public void put(AtmosphereResource resource) {
            resource.suspend();
            r.set(resource);
        }
    }

    @Test
    public void testPut() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/d").method("PUT").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertNotNull(r.get());
        r.get().resume();
    }

    @ManagedAtmosphereHandlerService(path = "/e")
    public final static class ManagedMessage {

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    event.getResource().getBroadcaster().broadcast("message");
                }
            }).suspend();

        }

        @Message
        public void message(AtmosphereResourceEvent event) {
            message.set(event.getMessage().toString());
        }
    }

    @Test
    public void testMessage() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/e").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(message.get());
        assertNotNull(message.get(), "message");

    }
}
