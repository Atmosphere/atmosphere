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

import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

public class BroadcasterLifecyclePolicyTest {
    private AtmosphereFramework framework;


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
        }).init();
    }

    @AfterMethod
    public void after() {
        framework.destroy();
    }

    @Test
    public void testNever() throws IOException, ServletException {
        Broadcaster b = framework.getBroadcasterFactory().lookup(B.class, "/test", true);
        b.setBroadcasterLifeCyclePolicy(BroadcasterLifeCyclePolicy.NEVER);
        AR ah = new AR();

        framework.addAtmosphereHandler("/*", ah, b).init();
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        b.removeAtmosphereResource(ah.resource);

        assertFalse(B.class.cast(b).releaseExternalResources.get());
        assertFalse(B.class.cast(b).destroy.get());
    }

    @Test
    public void testEmpty() throws IOException, ServletException {
        Broadcaster b = framework.getBroadcasterFactory().lookup(B.class, "/test", true);
        b.setBroadcasterLifeCyclePolicy(BroadcasterLifeCyclePolicy.EMPTY);
        AR ah = new AR();

        framework.addAtmosphereHandler("/*", ah, b).init();
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        b.removeAtmosphereResource(ah.resource);

        assertTrue(B.class.cast(b).releaseExternalResources.get());
        assertFalse(B.class.cast(b).destroy.get());
    }

    @Test
    public void testEmptyDestroy() throws IOException, ServletException {
        Broadcaster b = framework.getBroadcasterFactory().lookup(B.class, "/test", true);
        b.setBroadcasterLifeCyclePolicy(BroadcasterLifeCyclePolicy.EMPTY_DESTROY);
        AR ah = new AR();

        framework.addAtmosphereHandler("/*", ah, b).init();
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        b.removeAtmosphereResource(ah.resource);

        assertFalse(B.class.cast(b).releaseExternalResources.get());
        assertTrue(B.class.cast(b).destroy.get());
    }

    @Test
    public void testIdle() throws IOException, ServletException, InterruptedException {
        B b = framework.getBroadcasterFactory().lookup(B.class, "/test", true);
        b.setBroadcasterLifeCyclePolicy(
                new BroadcasterLifeCyclePolicy.Builder().policy(BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE).idleTimeInMS(500).build());
        b.latch = new CountDownLatch(1);

        AR ah = new AR();

        framework.addAtmosphereHandler("/*", ah, b).init();
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        b.removeAtmosphereResource(ah.resource);

        b.latch.await();

        assertTrue(B.class.cast(b).releaseExternalResources.get());
        assertFalse(B.class.cast(b).destroy.get());
    }

    @Test
    public void testIdleDestroy() throws IOException, ServletException, InterruptedException {
        B b = framework.getBroadcasterFactory().lookup(B.class, "/test", true);
        b.setBroadcasterLifeCyclePolicy(
                new BroadcasterLifeCyclePolicy.Builder().policy(BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY).idleTimeInMS(500).build());
        b.latch = new CountDownLatch(1);

        AR ah = new AR();

        framework.addAtmosphereHandler("/*", ah, b).init();
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        b.removeAtmosphereResource(ah.resource);

        b.latch.await();

        assertFalse(B.class.cast(b).releaseExternalResources.get());
        assertTrue(B.class.cast(b).destroy.get());
    }

    @Test
    public void testIdleResume() throws IOException, ServletException, InterruptedException {
        B b = framework.getBroadcasterFactory().lookup(B.class, "/test", true);
        b.setBroadcasterLifeCyclePolicy(
                new BroadcasterLifeCyclePolicy.Builder().policy(BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME).idleTimeInMS(500).build());
        b.latch = new CountDownLatch(1);

        AR ah = new AR();

        framework.addAtmosphereHandler("/*", ah, b).init();
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        b.latch.await();

        assertFalse(B.class.cast(b).releaseExternalResources.get());
        assertTrue(B.class.cast(b).destroy.get());
    }

    public final static class AR implements AtmosphereHandler {

        private AtmosphereResource resource;

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
            e.suspend();
            resource = e;
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
        }


        @Override
        public void destroy() {
        }
    }

    public final static class B extends SimpleBroadcaster {

        public final AtomicBoolean releaseExternalResources = new AtomicBoolean();
        public final AtomicBoolean destroy = new AtomicBoolean();
        public CountDownLatch latch = new CountDownLatch(0);

        @Override
        public void releaseExternalResources() {
            releaseExternalResources.set(true);
            latch.countDown();
        }

        @Override
        public void destroy() {
            destroy.set(true);
            latch.countDown();
        }

    };
}
