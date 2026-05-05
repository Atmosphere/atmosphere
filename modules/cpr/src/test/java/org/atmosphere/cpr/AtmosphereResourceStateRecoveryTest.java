/*
 * Copyright 2008-2026 Async-IO.org
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

import org.atmosphere.interceptor.AtmosphereResourceStateRecovery;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AtmosphereResourceStateRecoveryTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private final AtmosphereResourceStateRecovery recovery = new AtmosphereResourceStateRecovery();
    private AtmosphereResource r;

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
        framework.init();
        config = framework.getAtmosphereConfig();
        r = config.resourcesFactory().create(config, "1234567");
        r.setBroadcaster(config.getBroadcasterFactory().lookup("/*", true));
    }

    @AfterEach
    public void destroy() {
        recovery.states().clear();
        framework.destroy();
    }

    @Test
    public void basicTrackingTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);
        r.suspend();
        assertEquals(1, recovery.states().size());
    }

    @Test
    public void removeAtmosphereResourceTest() throws ServletException, IOException {
        recovery.states().clear();
        recovery.configure(config);
        recovery.inspect(r);
        r.suspend();
        r.getBroadcaster().removeAtmosphereResource(r);
        assertEquals(0, recovery.states().get(r.uuid()).ids().size());
    }

    @Test
    public void cancelAtmosphereResourceTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);
        r.suspend();
        r.getBroadcaster().removeAtmosphereResource(r);
        r.close();
        assertEquals(1, recovery.states().size());
    }

    @Test
    public void timeoutTest() throws ServletException, IOException, InterruptedException {
        recovery.configure(config);
        recovery.inspect(r);
        final AtomicBoolean resumed = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);
        r.addEventListener(new AtmosphereResourceEventListenerAdapter(){
            @Override
            public void onResume(AtmosphereResourceEvent event) {
                resumed.set(true);
            }
        }).suspend();
        latch.await(2, TimeUnit.SECONDS);
        r.resume();
        assertTrue(resumed.get());
        assertEquals(1, recovery.states().size());
    }

    @Test
    public void restoreStateAfterCloseTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);

        config.getBroadcasterFactory().lookup("/1", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/2", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/3", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/4", true).addAtmosphereResource(r);

        r.suspend();
        r.close();

        // After close, tracked broadcaster state should be cleared
        // (isClosedByClient / cancel removes tracked IDs)
        assertEquals(1, recovery.states().size());
        assertEquals(0, recovery.states().get(r.uuid()).ids().size());
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

        assertEquals(1, recovery.states().size());
        assertEquals(4, recovery.states().get(r.uuid()).ids().size());

    }

}
