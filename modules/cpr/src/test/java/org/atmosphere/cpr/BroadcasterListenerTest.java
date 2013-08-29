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
package org.atmosphere.cpr;

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class BroadcasterListenerTest {
    private AtmosphereFramework framework;
    private static final AtomicBoolean completed = new AtomicBoolean();
    private static final AtomicBoolean postCreated = new AtomicBoolean();
    private static final AtomicBoolean preDssrtoyed = new AtomicBoolean();

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName());
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
        }).addBroadcasterListener(new L());
    }

    @AfterMethod
    public void after() {
        BAR.count.set(0);
        framework.destroy();
    }

    public final static class L extends BroadcasterListenerAdapter {

        @Override
        public void onPostCreate(Broadcaster b) {
            postCreated.set(true);
        }

        @Override
        public void onComplete(Broadcaster b) {
            completed.set(true);
        }

        @Override
        public void onPreDestroy(Broadcaster b) {
            preDssrtoyed.set(true);
        }
    }

    @Test
    public void testGet() throws IOException, ServletException {
        framework.addAtmosphereHandler("/*", new AR()).init();
        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertTrue(completed.get());
        assertTrue(postCreated.get());
        assertTrue(preDssrtoyed.get());
    }


    @Test
    public void testOnBroadcast() throws IOException, ServletException {
        framework.addAtmosphereHandler("/*", new BAR()).init();

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(BAR.count.get(), 1);
    }

    @Test
    public void testLongPollingOnBroadcast() throws IOException, ServletException {
        framework.addAtmosphereHandler("/*", new BAR()).init();

        Map<String,String> m = new HashMap<String,String>();
        m.put(HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.LONG_POLLING_TRANSPORT);
        AtmosphereRequest request = new AtmosphereRequest.Builder().headers(m).pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(BAR.count.get(),1);
    }

    @Test
    public void testCachedOnBroadcast() throws IOException, ServletException {
        framework.setBroadcasterCacheClassName(UUIDBroadcasterCache.class.getName()).addAtmosphereHandler("/*", new CachedAR()).init();

        Map<String,String> m = new HashMap<String,String>();
        m.put(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, UUID.randomUUID().toString());
        m.put(HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.LONG_POLLING_TRANSPORT);
        AtmosphereRequest request = new AtmosphereRequest.Builder().headers(m).pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(CachedAR.count.get(), 3);
    }

    public final static class CachedAR implements AtmosphereHandler {

        static AtomicInteger count = new AtomicInteger();

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
            try {
                e.suspend();
                e.getBroadcaster().broadcast("test1").get();
                e.resume();

                ((AtmosphereResourceImpl)e).reset();

                e.getBroadcaster().broadcast("test2").get();
                e.getBroadcaster().broadcast("test3").get();
                e.getBroadcaster().broadcast("test4").get();

                e.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                    @Override
                    public void onBroadcast(AtmosphereResourceEvent event) {
                        if (List.class.isAssignableFrom(event.getMessage().getClass())) {
                            count.set(List.class.cast(event.getMessage()).size());
                        }
                    }
                }).suspend();
                e.getBroadcaster().destroy();
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            } catch (ExecutionException e1) {
                e1.printStackTrace();
            }
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
        }


        @Override
        public void destroy() {
        }
    }

    public final static class BAR implements AtmosphereHandler {

        static AtomicInteger count = new AtomicInteger();


         @Override
         public void onRequest(AtmosphereResource e) throws IOException {
             try {
                 e.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                     @Override
                     public void onBroadcast(AtmosphereResourceEvent event) {
                        count.incrementAndGet();
                     }
                 }).suspend();
                 e.getBroadcaster().broadcast("test").get();
                 e.getBroadcaster().destroy();
             } catch (InterruptedException e1) {
                 e1.printStackTrace();
             } catch (ExecutionException e1) {
                 e1.printStackTrace();
             }
         }

         @Override
         public void onStateChange(AtmosphereResourceEvent e) throws IOException {
         }


         @Override
         public void destroy() {
         }
     }


    public final static class AR implements AtmosphereHandler {

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
            try {
                e.getBroadcaster().broadcast("test").get();
                e.getBroadcaster().destroy();
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            } catch (ExecutionException e1) {
                e1.printStackTrace();
            }
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
        }


        @Override
        public void destroy() {
        }
    }
}
