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

import org.atmosphere.container.BlockingIOCometSupport;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class ConcurrentBroadcasterTest {

    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;

    @BeforeMethod
    public void setUp() throws Exception {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(AtmosphereRequest.class),
                AtmosphereResponse.create(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar);
    }

    @AfterMethod
    public void unSetUp() throws Exception {
        broadcaster.destroy();
        atmosphereHandler.value.set(new StringBuffer());
    }

    public final static class AR implements AtmosphereHandler {

        public AtomicReference<StringBuffer> value = new AtomicReference<StringBuffer>(new StringBuffer());

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
            value.get().append(e.getMessage());
        }

        @Override
        public void destroy() {
        }
    }

    @Test
    public void testOrderedConcurrentBroadcast() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1000);
        broadcaster.addBroadcasterListener(new BroadcasterListener() {
            @Override
            public void onPostCreate(Broadcaster b) {
            }

            @Override
            public void onComplete(Broadcaster b) {
                latch.countDown();
            }

            @Override
            public void onPreDestroy(Broadcaster b) {
            }
        });

        StringBuffer b = new StringBuffer();
        for (int i = 0; i < 1000; i++) {
            b.append("message-"+i);
            broadcaster.broadcast("message-" + i);
        }
        latch.await(10, TimeUnit.SECONDS);

        assertEquals(atmosphereHandler.value.get().toString(), b.toString());
    }
}
