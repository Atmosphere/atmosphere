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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class BroadcastFilterLifecycleTest {

    @Test
    void broadcastFilterLifecycleExtendsBroadcastFilter() {
        Class<?>[] interfaces = BroadcastFilterLifecycle.class.getInterfaces();
        assertEquals(1, interfaces.length);
        assertEquals(BroadcastFilter.class, interfaces[0]);
    }

    @Test
    void implementationReceivesInitAndDestroy() {
        AtomicBoolean initialized = new AtomicBoolean(false);
        AtomicBoolean destroyed = new AtomicBoolean(false);

        BroadcastFilterLifecycle filter = new BroadcastFilterLifecycle() {
            @Override
            public void init(AtmosphereConfig config) {
                initialized.set(true);
            }

            @Override
            public void destroy() {
                destroyed.set(true);
            }

            @Override
            public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
                return new BroadcastAction(message);
            }
        };

        AtmosphereConfig config = mock(AtmosphereConfig.class);
        filter.init(config);
        assertEquals(true, initialized.get());

        filter.destroy();
        assertEquals(true, destroyed.get());
    }

    @Test
    void broadcastActionContinueByDefault() {
        BroadcastFilter.BroadcastAction action = new BroadcastFilter.BroadcastAction("hello");
        assertEquals(BroadcastFilter.BroadcastAction.ACTION.CONTINUE, action.action());
        assertEquals("hello", action.message());
        assertNull(action.originalMessage());
    }

    @Test
    void broadcastActionAbort() {
        BroadcastFilter.BroadcastAction action =
                new BroadcastFilter.BroadcastAction(BroadcastFilter.BroadcastAction.ACTION.ABORT, "dropped");
        assertEquals(BroadcastFilter.BroadcastAction.ACTION.ABORT, action.action());
        assertEquals("dropped", action.message());
    }

    @Test
    void broadcastActionSkip() {
        BroadcastFilter.BroadcastAction action =
                new BroadcastFilter.BroadcastAction(BroadcastFilter.BroadcastAction.ACTION.SKIP, "last", "orig");
        assertEquals(BroadcastFilter.BroadcastAction.ACTION.SKIP, action.action());
        assertEquals("last", action.message());
        assertEquals("orig", action.originalMessage());
    }

    @Test
    void voidAtmosphereResourceUuidConstant() {
        assertEquals("-1", BroadcastFilter.VOID_ATMOSPHERE_RESOURCE_UUID);
    }

    @Test
    void filterChainContinuePassesMessage() {
        BroadcastFilterLifecycle filter1 = createPassThroughFilter("filter1-");
        BroadcastFilterLifecycle filter2 = createPassThroughFilter("filter2-");

        BroadcastFilter.BroadcastAction result1 = filter1.filter("b1", "orig", "msg");
        assertEquals(BroadcastFilter.BroadcastAction.ACTION.CONTINUE, result1.action());
        assertEquals("filter1-msg", result1.message());

        BroadcastFilter.BroadcastAction result2 = filter2.filter("b1", "orig", result1.message());
        assertEquals(BroadcastFilter.BroadcastAction.ACTION.CONTINUE, result2.action());
        assertEquals("filter2-filter1-msg", result2.message());
    }

    @Test
    void filterChainAbortStopsDelivery() {
        BroadcastFilterLifecycle abortFilter = new BroadcastFilterLifecycle() {
            @Override
            public void init(AtmosphereConfig config) { }

            @Override
            public void destroy() { }

            @Override
            public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
                return new BroadcastAction(BroadcastAction.ACTION.ABORT, message);
            }
        };

        BroadcastFilter.BroadcastAction result = abortFilter.filter("b1", "orig", "msg");
        assertEquals(BroadcastFilter.BroadcastAction.ACTION.ABORT, result.action());
    }

    @Test
    void perRequestBroadcastFilterExtendsBroadcastFilter() {
        Class<?>[] interfaces = PerRequestBroadcastFilter.class.getInterfaces();
        assertEquals(1, interfaces.length);
        assertEquals(BroadcastFilter.class, interfaces[0]);
    }

    @Test
    void perRequestBroadcastFilterImplReceivesResource() {
        AtmosphereResource resource = mock(AtmosphereResource.class);

        PerRequestBroadcastFilter perRequestFilter = new PerRequestBroadcastFilter() {
            @Override
            public BroadcastAction filter(String broadcasterId, AtmosphereResource r,
                                          Object originalMessage, Object message) {
                assertNotNull(r);
                return new BroadcastAction(message.toString() + "-filtered");
            }

            @Override
            public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
                return new BroadcastAction(message);
            }
        };

        BroadcastFilter.BroadcastAction result =
                perRequestFilter.filter("b1", resource, "orig", "msg");
        assertEquals("msg-filtered", result.message());
    }

    @Test
    void broadcastActionRecordEquality() {
        BroadcastFilter.BroadcastAction a1 =
                new BroadcastFilter.BroadcastAction(BroadcastFilter.BroadcastAction.ACTION.CONTINUE, "m", "o");
        BroadcastFilter.BroadcastAction a2 =
                new BroadcastFilter.BroadcastAction(BroadcastFilter.BroadcastAction.ACTION.CONTINUE, "m", "o");
        assertEquals(a1, a2);
    }

    private BroadcastFilterLifecycle createPassThroughFilter(String prefix) {
        return new BroadcastFilterLifecycle() {
            @Override
            public void init(AtmosphereConfig config) { }

            @Override
            public void destroy() { }

            @Override
            public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
                return new BroadcastAction(prefix + message);
            }
        };
    }
}
