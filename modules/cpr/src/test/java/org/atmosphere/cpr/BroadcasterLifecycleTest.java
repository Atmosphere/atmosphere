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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcasterLifecycleTest {

    private BroadcasterLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new BroadcasterLifecycle();
    }

    @Test
    void defaultPolicyIsNever() {
        assertEquals(BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.NEVER,
                lifecycle.policy().getLifeCyclePolicy());
    }

    @Test
    void addAndRemoveLifeCycleListener() {
        var listener = Mockito.mock(BroadcasterLifeCyclePolicyListener.class);
        lifecycle.addLifeCycleListener(listener);
        assertTrue(lifecycle.lifeCycleListeners().contains(listener));

        lifecycle.removeLifeCycleListener(listener);
        assertFalse(lifecycle.lifeCycleListeners().contains(listener));
    }

    @Test
    void clearListenersRemovesAll() {
        var listener = Mockito.mock(BroadcasterLifeCyclePolicyListener.class);
        lifecycle.addLifeCycleListener(listener);
        lifecycle.clearListeners();
        assertTrue(lifecycle.lifeCycleListeners().isEmpty());
    }

    @Test
    void recentActivityDefaultFalse() {
        assertFalse(lifecycle.recentActivity().get());
    }

    @Test
    void recentActivityCanBeSet() {
        lifecycle.recentActivity().set(true);
        assertTrue(lifecycle.recentActivity().get());
    }

    @Test
    void lifecycleHandlerInitiallyNull() {
        assertNull(lifecycle.lifecycleHandler());
    }

    @Test
    void currentLifecycleTaskInitiallyNull() {
        assertNull(lifecycle.currentLifecycleTask());
    }

    @Test
    void lifecycleListenersReturnsNonNull() {
        assertNotNull(lifecycle.lifeCycleListeners());
        assertTrue(lifecycle.lifeCycleListeners().isEmpty());
    }
}
