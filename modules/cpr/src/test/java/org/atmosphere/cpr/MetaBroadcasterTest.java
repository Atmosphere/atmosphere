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

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutionException;

import static org.testng.Assert.assertEquals;

public class MetaBroadcasterTest {
    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
    }

    @AfterMethod
    public void destroy() {
        factory.destroy();
    }

    @Test
    public void wildcardBroadcastTest() throws ExecutionException, InterruptedException {
        factory.get("/a");
        factory.get("/b");
        factory.get("/c");

        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/*", "yo").get().size(), 3);
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/a/b", "yo").get().size(), 0);
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/a", "yo").get().size(), 1);
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/", "yo").get().size(), 3);

        factory.get("/*");
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/", "yo").get().size(), 4);
    }

    @Test
    public void exactBroadcastTest() throws ExecutionException, InterruptedException {

        factory.get("/a");
        factory.get("/a/b");
        factory.get("/c");

        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/a", "yo").get().get(0).getID(), "/a");
    }

    @Test
    public void traillingBroadcastTest() throws ExecutionException, InterruptedException {

        factory.get("/a/b");
        factory.get("/b");
        factory.get("/c");
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/a/b", "yo").get().size(), 1);

    }

    @Test
    public void complexBroadcastTest() throws ExecutionException, InterruptedException {
        factory.get("/a/b/c/d");
        factory.get("/b");
        factory.get("/c");

        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/*", "yo").get().size(), 3);
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/a/b/c/d", "yo").get().size(), 1);
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/a", "yo").get().size(), 0);
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/b", "yo").get().size(), 1);

    }

    @Test
    public void chatTest() throws ExecutionException, InterruptedException {
        factory.get("/a/chat1");
        factory.get("/a/chat2");
        factory.get("/a/chat3");

        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/a", "yo").get().size(), 0);
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/a/", "yo").get().size(), 0);
        assertEquals(MetaBroadcaster.getDefault().broadcastTo("/a/*", "yo").get().size(), 3);

    }
}
