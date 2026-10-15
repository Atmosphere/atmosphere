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
package org.atmosphere.inject;

import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two callbacks of one shared service run at once, and each injection pass
 * writes its proxy into the same field. A pass can create its proxy before the
 * other callback starts and write it into the field only after that callback's
 * own injection: the field then holds the first client's proxy while the second
 * callback runs. That proxy must resolve the second callback's own client, as
 * any proxy the field holds during a callback does, not act on the first
 * client as if it were a reference stored by an earlier callback.
 */
class CallbackScopePublicationTest {

    /** The shared service's {@code @Inject AtmosphereResource} field. */
    static final class Service {
        AtmosphereResource r;
    }

    private final AtmosphereResourceIntrospector introspector = new AtmosphereResourceIntrospector();
    private ExecutorService alice;
    private ExecutorService bob;

    @BeforeEach
    void setUp() {
        // One thread per callback: each keeps its own binding between the steps.
        alice = Executors.newSingleThreadExecutor();
        bob = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        alice.shutdownNow();
        bob.shutdownNow();
    }

    @Test
    void aProxyWrittenIntoTheFieldAfterACallbackStartedResolvesThatCallbacksClient() throws Exception {
        var service = new Service();
        var field = Service.class.getDeclaredField("r");
        field.setAccessible(true);
        var aliceResource = resource("A");
        var bobResource = resource("B");

        // Alice's injection pass starts and creates its proxy, then is preempted
        // before writing it into the field.
        var aliceProxy = on(alice, () -> {
            CallbackScope.enter(aliceResource);
            return introspector.injectable(aliceResource);
        });
        // Bob's callback starts after that and writes its own proxy into the field.
        on(bob, () -> {
            CallbackScope.enter(bobResource);
            publish(service, field, introspector.injectable(bobResource));
            return null;
        });
        // Alice's pass resumes: its write replaces Bob's.
        on(alice, () -> {
            publish(service, field, aliceProxy);
            return null;
        });

        assertEquals("B", on(bob, () -> service.r.uuid()),
                "Bob's callback reads its own client through the field a concurrent injection wrote");
        assertEquals("A", on(alice, () -> service.r.uuid()),
                "Alice's callback reads her own client through the same field");
    }

    @Test
    void aProxyWrittenBeforeACallbackStartedStaysOnItsOwnClient() throws Exception {
        var service = new Service();
        var field = Service.class.getDeclaredField("r");
        field.setAccessible(true);
        var aliceResource = resource("A");
        var bobResource = resource("B");

        // Alice's callback stores the proxy her injection wrote, as a map of users does.
        var stored = on(alice, () -> {
            CallbackScope.enter(aliceResource);
            publish(service, field, introspector.injectable(aliceResource));
            return service.r;
        });
        on(bob, () -> {
            CallbackScope.enter(bobResource);
            publish(service, field, introspector.injectable(bobResource));
            return null;
        });

        assertEquals("A", on(bob, stored::uuid),
                "a reference written before Bob's callback started acts on the client it was injected for");
        assertEquals("B", on(bob, () -> service.r.uuid()), "Bob's own field reads Bob");
    }

    /** What InjectableObjectFactory does with each injected value of a callback-scoped field. */
    private static void publish(Service service, Field field, AtmosphereResource proxy) throws Exception {
        CallbackScope.publish(service, field, proxy);
    }

    private static <T> T on(ExecutorService thread, Callable<T> step) throws Exception {
        return thread.submit(step).get(10, TimeUnit.SECONDS);
    }

    private static AtmosphereResource resource(String uuid) {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn(uuid);
        return resource;
    }
}
