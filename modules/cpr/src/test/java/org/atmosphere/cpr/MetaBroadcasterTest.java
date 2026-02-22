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

import org.atmosphere.util.ExecutorsFactory;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MetaBroadcasterTest {
    private AtmosphereConfig config;
    private BroadcasterFactory factory;
    private MetaBroadcaster metaBroadcaster;

    @BeforeEach
    public void setUp() throws Exception {
        AtmosphereFramework f = new AtmosphereFramework().addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        config = f.init().getAtmosphereConfig();
        factory = config.getBroadcasterFactory();
        factory.remove(Broadcaster.ROOT_MASTER);
        metaBroadcaster = config.metaBroadcaster();
    }

    @AfterEach
    public void unSet() throws Exception {
        config.destroy();
        ExecutorsFactory.reset(config);
        factory.destroy();
    }

    @Test
    public void wildcardBroadcastTest() throws ExecutionException, InterruptedException {
        factory.get("/a");
        factory.get("/b");
        factory.get("/c");

        assertEquals(3, metaBroadcaster.broadcastTo("/*", "yo").get().size());
        assertEquals(0, metaBroadcaster.broadcastTo("/a/b", "yo").get().size());
        assertEquals(1, metaBroadcaster.broadcastTo("/a", "yo").get().size());
        assertEquals(3, metaBroadcaster.broadcastTo("/", "yo").get().size());

        factory.get("/*");
        assertEquals(4, metaBroadcaster.broadcastTo("/", "yo").get().size());
    }

    @Test
    public void exactBroadcastTest() throws ExecutionException, InterruptedException {

        factory.get("/a");
        factory.get("/a/b");
        factory.get("/c");

        assertEquals("/a", metaBroadcaster.broadcastTo("/a", "yo").get().get(0).getID());
    }

    @Test
    public void traillingBroadcastTest() throws ExecutionException, InterruptedException {

        factory.get("/a/b");
        factory.get("/b");
        factory.get("/c");
        assertEquals(1, metaBroadcaster.broadcastTo("/a/b", "yo").get().size());

    }

    @Test
    public void complexBroadcastTest() throws ExecutionException, InterruptedException {
        factory.get("/a/b/c/d");
        factory.get("/b");
        factory.get("/c");

        assertEquals(3, metaBroadcaster.broadcastTo("/*", "yo").get().size());
        assertEquals(1, metaBroadcaster.broadcastTo("/a/b/c/d", "yo").get().size());
        assertEquals(0, metaBroadcaster.broadcastTo("/a", "yo").get().size());
        assertEquals(1, metaBroadcaster.broadcastTo("/b", "yo").get().size());

    }

    @Test
    public void chatTest() throws ExecutionException, InterruptedException {
        factory.get("/a/chat1");
        factory.get("/a/chat2");
        factory.get("/a/chat3");

        assertEquals(3, metaBroadcaster.broadcastTo("/a/*", "yo").get().size());

    }

    @Test
    public void underscoreMatching() throws ExecutionException, InterruptedException {
        factory.get("/a/_b");
        factory.get("/b");
        factory.get("/c");
        assertEquals(1, metaBroadcaster.broadcastTo("/a/_b", "yo").get().size());

    }

    @Test
    public void issue836Test() throws ExecutionException, InterruptedException {
        factory.get("/a/@b");
        factory.get("/b");
        factory.get("/c");
        assertEquals(1, metaBroadcaster.broadcastTo("/a/@b", "yo").get().size());

    }
}
