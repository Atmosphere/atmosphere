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
import org.atmosphere.handler.AtmosphereHandlerAdapter;
import org.atmosphere.interceptor.AtmosphereResourceStateRecovery;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class AtmosphereResourceStateRecoveryTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private final AtmosphereResourceStateRecovery recovery = new AtmosphereResourceStateRecovery();
    private AtmosphereResource r;

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
        framework.init();
        config = framework.getAtmosphereConfig();
        r = AtmosphereResourceFactory.getDefault().create(config, "1234567");
        r.setBroadcaster(config.getBroadcasterFactory().lookup("/*", true));
    }

    @AfterMethod
    public void destroy() {
        recovery.states().clear();
        framework.destroy();
    }

    @Test
    public void basicTrackingTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);
        r.suspend();
        assertEquals(recovery.states().size(), 1);
    }

    @Test
    public void removeAtmosphereResourceTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);
        r.suspend();
        r.getBroadcaster().removeAtmosphereResource(r);
        assertEquals(recovery.states().get(r.uuid()).ids().size(), 0);
    }

    @Test
    public void cancelAtmosphereResourceTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);
        r.suspend();
        r.close();
        r.getBroadcaster().removeAtmosphereResource(r);
        assertEquals(recovery.states().size(), 1);
    }

    // This test is no longer working since isClosedByClient changes the behavior.
    @Test (enabled = false)
    public void restoreStateTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);

        config.getBroadcasterFactory().lookup("/1", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/2", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/3", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/4", true).addAtmosphereResource(r);

        r.suspend();
        r.close();

        r.getBroadcaster().removeAtmosphereResource(r);

        r.suspend();

        assertEquals(recovery.states().size(), 1);
        assertEquals(recovery.states().get(r.uuid()).ids().size(), 5);

    }

    @Test
    public void restorePartialStateTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);

        config.getBroadcasterFactory().lookup("/1", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/2", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/3", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/4", true).addAtmosphereResource(r);

        r.suspend();
        r.getBroadcaster().removeAtmosphereResource(r);

        r.suspend();

        assertEquals(recovery.states().size(), 1);
        assertEquals(recovery.states().get(r.uuid()).ids().size(), 4);

    }

    @Test (enabled = false)
    public void longPollingAggregatedTest() throws ServletException, IOException, ExecutionException, InterruptedException {
        final AtomicReference<Object> ref = new AtomicReference<Object>();
        AtmosphereResourceImpl r = (AtmosphereResourceImpl) AtmosphereResourceFactory.getDefault().create(config, "1234567");
        r.setBroadcaster(config.getBroadcasterFactory().lookup("/1", true));

        recovery.configure(config);
        recovery.inspect(r);

        config.getBroadcasterFactory().lookup("/1", true).getBroadcasterConfig().setBroadcasterCache(new UUIDBroadcasterCache());
        config.getBroadcasterFactory().lookup("/2", true).getBroadcasterConfig().setBroadcasterCache(new UUIDBroadcasterCache());
        config.getBroadcasterFactory().lookup("/3", true).getBroadcasterConfig().setBroadcasterCache(new UUIDBroadcasterCache());
        config.getBroadcasterFactory().lookup("/4", true).getBroadcasterConfig().setBroadcasterCache(new UUIDBroadcasterCache());

        config.getBroadcasterFactory().lookup("/1", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/2", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/3", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/4", true).addAtmosphereResource(r);


        r.suspend();
        MetaBroadcaster.getDefault().broadcastTo("/1", "Initialize Cache").get();
        r.close();

        AtmosphereResourceImpl r2 = (AtmosphereResourceImpl) AtmosphereResourceFactory.getDefault().create(config, "1234567");
        // Set a different one to hit caching.
        r2.setBroadcaster(config.getBroadcasterFactory().lookup("/*", true));

        config.getBroadcasterFactory().lookup("/1", true).broadcast(("1")).get();
        config.getBroadcasterFactory().lookup("/2", true).broadcast(("2")).get();
        config.getBroadcasterFactory().lookup("/3", true).broadcast(("3")).get();
        config.getBroadcasterFactory().lookup("/4", true).broadcast(("4")).get();

        r2.transport(AtmosphereResource.TRANSPORT.LONG_POLLING).atmosphereHandler(new AtmosphereHandlerAdapter() {
            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                ref.set(event.getMessage());
            }
        }).suspend();

        recovery.inspect(r2);

        assertTrue(List.class.isAssignableFrom(ref.get().getClass()));
        assertEquals(List.class.cast(ref.get()).size(), 4);

        StringBuilder b = new StringBuilder();
        for (Object o : List.class.cast(ref.get())) {
            b.append(o.toString());
        }
        assertEquals(b.toString(), "1234");

    }

}
