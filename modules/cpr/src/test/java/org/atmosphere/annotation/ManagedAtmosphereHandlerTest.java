/*
 * Copyright 2013 Jean-Francois Arcand
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
package org.atmosphere.annotation;

import org.atmosphere.config.service.Delete;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Post;
import org.atmosphere.config.service.Put;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.interceptor.InvokationOrder;
import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;

public class ManagedAtmosphereHandlerTest {
    private AtmosphereFramework framework;
    private static final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
    private static final AtomicReference<String> message = new AtomicReference<String>();

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName()) ;
        framework.addAnnotationPackage(ManagedGet.class);
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

    @ManagedService(path = "/a")
    public final static class ManagedGet {
        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.suspend();
        }
    }

    @Test
    public void testGet() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        r.get().resume();

        assertNotNull(r.get());
    }

    @ManagedService(path = "/b")
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

    @ManagedService(path = "/c")
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

    @ManagedService(path = "/d")
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

    @ManagedService(path = "/e")
    public final static class ManagedMessage {

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource);
            resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/e").method("POST").body("message").build();

                    try {
                        event.getResource().getAtmosphereConfig().framework().doCometSupport(request, AtmosphereResponse.newInstance());
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (ServletException e) {
                        e.printStackTrace();
                    }
                }
            }).suspend();

        }

        @Message
        public void message(String m) {
            message.set(m);
        }
    }

    @Test
    public void testMessage() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/e").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(message.get());
        assertEquals(message.get(), "message");

    }

    @ManagedService(path = "/k")
    public final static class ManagedMessageWithResource {

      @Get
      public void get(AtmosphereResource resource) {
          r.set(resource);
          resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
              @Override
              public void onSuspend(AtmosphereResourceEvent event) {
                  AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/k").method("POST").body("message").build();

                  try {
                      event.getResource().getAtmosphereConfig().framework().doCometSupport(request, AtmosphereResponse.newInstance());
                  } catch (IOException e) {
                      e.printStackTrace();
                  } catch (ServletException e) {
                      e.printStackTrace();
                  }
              }
          }).suspend();

        }

        @Message
        public void message(AtmosphereResource resource, String m) {
            message.set(m);
            assertSame(resource, r.get());
        }
    }

    @Test
    public void testMessageWithResource() throws IOException, ServletException {
        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/k").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertNotNull(r.get());
        r.get().resume();
        assertNotNull(message.get());
        assertEquals(message.get(), "message");
    }

    @ManagedService(path = "/j")
    public final static class ManagedSuspend {
        @Get
        public void get(AtmosphereResource resource) {
            // Normally we don't need that, this will be done using an Interceptor.
            resource.suspend();
        }

        @Ready
        public void suspend(AtmosphereResource resource) {
            r.set(resource);
        }
    }

    @Test
    public void testSuspend() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/j").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertNotNull(r.get());
    }

    public final static class I extends AtmosphereInterceptorAdapter {
       @Override
       public PRIORITY priority() {
           return InvokationOrder.FIRST_BEFORE_DEFAULT;
       }

        @Override
        public String toString(){
            return "XXX";
        }
    }

    @ManagedService(path = "/priority", interceptors = I.class)
    public final static class Priority {
        @Get
        public void get(AtmosphereResource resource) {
            // Normally we don't need that, this will be done using an Interceptor.
            resource.suspend();
        }

        @Ready
        public void suspend(AtmosphereResource resource) {
            r.set(resource);
        }
    }

    @Test
    public void testPriority() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/priority").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(framework.interceptors().getFirst().toString(), "XXX");

        assertNotNull(r.get());
    }
}
