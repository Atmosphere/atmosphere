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
package org.atmosphere.tests.http;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterListener;
import org.atmosphere.cpr.BroadcasterListenerAdapter;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.Entry;
import org.atmosphere.cpr.MetaBroadcaster;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import static org.testng.Assert.assertEquals;

public class MetaBroadcasterLoadTest {
    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new FFT(config);
    }

    @AfterMethod
    public void destroy() {
        factory.destroy();
    }

    public static final class TestBroadcaster extends DefaultBroadcaster {

        public TestBroadcaster(String name, AtmosphereConfig config) {
            super(name, config);
        }

        public BlockingQueue<Entry> messages() {
            return messages;
        }
    }

    @Test
    public void loadTest() throws InterruptedException {
        TestBroadcaster a = (TestBroadcaster) factory.get("/a");
        final int run = 1000;

        Thread[] threads = new Thread[10];

        final CountDownLatch latch = new CountDownLatch(run);

        BroadcasterListener l = new BroadcasterListenerAdapter() {

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
        };

        MetaBroadcaster.getDefault().addBroadcasterListener(l);
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {

                @Override
                public void run() {
                    for (int i = 0; i < run; i++) {
                        MetaBroadcaster.getDefault().broadcastTo("/*", "a-" + Math.random());
                    }
                }
            });
            threads[i].start();
        }

        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }

        latch.await();

        while (a.messages().size() != 0) {
            Thread.sleep(1000);
        }

        assertEquals(a.messages().size(), 0);
    }

    public class FFT extends DefaultBroadcasterFactory {

        public FFT(AtmosphereConfig config) {
            super(TestBroadcaster.class, "NEVER", config);
        }
    }
}
