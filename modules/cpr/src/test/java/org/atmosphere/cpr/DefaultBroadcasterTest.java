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

import org.atmosphere.cache.AbstractBroadcasterCache;
import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.BroadcasterCacheTest.AR;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class DefaultBroadcasterTest {

    private Broadcaster broadcaster;
    private final AtomicReference<List<CacheMessage>> cachedMessage = new AtomicReference<List<CacheMessage>>();

    public static final class B extends DefaultBroadcaster {

        public B() {
        }

        protected void cacheAndSuspend(AtmosphereResource r) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            super.cacheAndSuspend(r);
        }
    }

    @BeforeMethod
    public void setUp() throws Exception {
        AtmosphereFramework framework = new AtmosphereFramework();
        framework.addInitParameter(ApplicationConfig.BROADCASTER_CACHE_STRATEGY, "beforeFilter");
        AtmosphereConfig config = framework.getAtmosphereConfig();

        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(B.class, "NEVER", config);
        broadcaster = factory.get("test");
        config.framework().setBroadcasterFactory(factory);
    }

    @Test
    public void testSimultaneousAddResourceAndPush() throws ExecutionException, InterruptedException, ServletException {
        final Map<String, BroadcastMessage> cache = new HashMap<String, BroadcastMessage>();

        broadcaster.getBroadcasterConfig().setBroadcasterCache(new AbstractBroadcasterCache() {
            @Override
            public CacheMessage addToCache(String id, String uuid, BroadcastMessage e) {
                CacheMessage c = put(e, System.nanoTime(), uuid);
                cache.put(id, e);
                return c;
            }

            @Override
            public List<Object> retrieveFromCache(String id, String uuid) {
                ArrayList<Object> cacheContents = new ArrayList<Object>();
                if (!cache.isEmpty()) {
                    cacheContents.add(cache.get(id).message());
                    cache.clear();
                }
                return cacheContents;
            }
        });

        final AtmosphereResourceImpl ar = new AtmosphereResourceImpl(new AtmosphereFramework().getAtmosphereConfig(),
                broadcaster,
                mock(AtmosphereRequestImpl.class),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new AR());

        final String message = "foo";
        Runnable broadcastRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    broadcaster.broadcast(message).get();
                } catch (InterruptedException ex) {
                    Logger.getLogger(DefaultBroadcasterTest.class.getName()).log(Level.SEVERE, null, ex);
                } catch (ExecutionException ex) {
                    Logger.getLogger(DefaultBroadcasterTest.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        };

        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                broadcaster.addAtmosphereResource(ar);
            }
        };
        int remainingTest = 10;
        ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(2);

        while (remainingTest > 0) {
            remainingTest--;
            Future<?> pollFuture = newFixedThreadPool.submit(pollRunnable);

            Future<?> broadcastFuture = newFixedThreadPool.submit(broadcastRunnable);
            broadcastFuture.get();
            pollFuture.get();

            Object eventMessage = ar.getAtmosphereResourceEvent().getMessage();
            Object retrievedMessage;
            if (eventMessage instanceof List) {
                retrievedMessage = ((List) eventMessage).get(0);
            } else {
                retrievedMessage = eventMessage;
            }

            //cleanup
            broadcaster.removeAtmosphereResource(ar);
            cache.clear();

            //System.out.println(iterations++ + ": message:" + retrievedMessage);
            assertEquals(retrievedMessage, message);
        }

    }
}