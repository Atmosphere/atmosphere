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

import org.atmosphere.config.service.BroadcasterListenerService;
import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.testng.Assert.assertTrue;

public class BroadcasterListenerTest {
    private AtmosphereFramework framework;
    private static final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
    private static final AtomicReference<String> message = new AtomicReference<String>();
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
        }).addAtmosphereHandler("/*", new AR()).addBroadcasterListener(new L()).init();
    }

    @AfterMethod
    public void after() {
        r.set(null);
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

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertTrue(completed.get());
        assertTrue(postCreated.get());
        assertTrue(preDssrtoyed.get());
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
