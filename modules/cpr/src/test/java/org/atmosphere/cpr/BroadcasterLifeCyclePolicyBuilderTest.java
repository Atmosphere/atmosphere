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

import org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BroadcasterLifeCyclePolicyBuilderTest {

    @Test
    void builderWithIdleTimeInMilliseconds() {
        var policy = new BroadcasterLifeCyclePolicy.Builder()
                .policy(ATMOSPHERE_RESOURCE_POLICY.IDLE)
                .idleTimeInMS(5000)
                .build();

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE, policy.getLifeCyclePolicy());
        assertEquals(5000, policy.getTimeout());
        assertEquals(TimeUnit.MILLISECONDS, policy.getTimeUnit());
    }

    @Test
    void builderWithIdleTimeAndCustomUnit() {
        var policy = new BroadcasterLifeCyclePolicy.Builder()
                .policy(ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY)
                .idleTime(30, TimeUnit.SECONDS)
                .build();

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY, policy.getLifeCyclePolicy());
        assertEquals(30, policy.getTimeout());
        assertEquals(TimeUnit.SECONDS, policy.getTimeUnit());
    }

    @Test
    void builderWithIdleResumePolicy() {
        var policy = new BroadcasterLifeCyclePolicy.Builder()
                .policy(ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME)
                .idleTimeInMS(10000)
                .build();

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME, policy.getLifeCyclePolicy());
        assertEquals(10000, policy.getTimeout());
    }

    @Test
    void builderWithIdleEmptyDestroyPolicy() {
        var policy = new BroadcasterLifeCyclePolicy.Builder()
                .policy(ATMOSPHERE_RESOURCE_POLICY.IDLE_EMPTY_DESTROY)
                .idleTime(1, TimeUnit.MINUTES)
                .build();

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE_EMPTY_DESTROY, policy.getLifeCyclePolicy());
        assertEquals(1, policy.getTimeout());
        assertEquals(TimeUnit.MINUTES, policy.getTimeUnit());
    }

    @Test
    void predefinedIdleConstant() {
        var policy = BroadcasterLifeCyclePolicy.IDLE;

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE, policy.getLifeCyclePolicy());
        assertEquals(-1, policy.getTimeout());
        assertNull(policy.getTimeUnit());
    }

    @Test
    void predefinedIdleDestroyConstant() {
        var policy = BroadcasterLifeCyclePolicy.IDLE_DESTROY;

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY, policy.getLifeCyclePolicy());
        assertEquals(-1, policy.getTimeout());
        assertNull(policy.getTimeUnit());
    }

    @Test
    void predefinedIdleResumeConstant() {
        var policy = BroadcasterLifeCyclePolicy.IDLE_RESUME;

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME, policy.getLifeCyclePolicy());
        assertEquals(-1, policy.getTimeout());
    }

    @Test
    void predefinedEmptyConstant() {
        var policy = BroadcasterLifeCyclePolicy.EMPTY;

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.EMPTY, policy.getLifeCyclePolicy());
        assertEquals(-1, policy.getTimeout());
    }

    @Test
    void predefinedEmptyDestroyConstant() {
        var policy = BroadcasterLifeCyclePolicy.EMPTY_DESTROY;

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.EMPTY_DESTROY, policy.getLifeCyclePolicy());
        assertEquals(-1, policy.getTimeout());
    }

    @Test
    void predefinedIdleEmptyDestroyConstant() {
        var policy = BroadcasterLifeCyclePolicy.IDLE_EMPTY_DESTROY;

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE_EMPTY_DESTROY, policy.getLifeCyclePolicy());
        assertEquals(-1, policy.getTimeout());
    }

    @Test
    void predefinedNeverConstant() {
        var policy = BroadcasterLifeCyclePolicy.NEVER;

        assertEquals(ATMOSPHERE_RESOURCE_POLICY.NEVER, policy.getLifeCyclePolicy());
        assertEquals(-1, policy.getTimeout());
    }

    @Test
    void predefinedConstantsAreSameInstances() {
        assertSame(BroadcasterLifeCyclePolicy.NEVER, BroadcasterLifeCyclePolicy.NEVER);
        assertSame(BroadcasterLifeCyclePolicy.IDLE, BroadcasterLifeCyclePolicy.IDLE);
        assertSame(BroadcasterLifeCyclePolicy.EMPTY, BroadcasterLifeCyclePolicy.EMPTY);
    }

    @Test
    void allPolicyEnumValuesExist() {
        var values = ATMOSPHERE_RESOURCE_POLICY.values();
        assertEquals(7, values.length);
        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE, ATMOSPHERE_RESOURCE_POLICY.valueOf("IDLE"));
        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY, ATMOSPHERE_RESOURCE_POLICY.valueOf("IDLE_DESTROY"));
        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME, ATMOSPHERE_RESOURCE_POLICY.valueOf("IDLE_RESUME"));
        assertEquals(ATMOSPHERE_RESOURCE_POLICY.EMPTY, ATMOSPHERE_RESOURCE_POLICY.valueOf("EMPTY"));
        assertEquals(ATMOSPHERE_RESOURCE_POLICY.EMPTY_DESTROY, ATMOSPHERE_RESOURCE_POLICY.valueOf("EMPTY_DESTROY"));
        assertEquals(ATMOSPHERE_RESOURCE_POLICY.IDLE_EMPTY_DESTROY, ATMOSPHERE_RESOURCE_POLICY.valueOf("IDLE_EMPTY_DESTROY"));
        assertEquals(ATMOSPHERE_RESOURCE_POLICY.NEVER, ATMOSPHERE_RESOURCE_POLICY.valueOf("NEVER"));
    }

    @Test
    void builderIdleTimeOverridesIdleTimeInMS() {
        var policy = new BroadcasterLifeCyclePolicy.Builder()
                .policy(ATMOSPHERE_RESOURCE_POLICY.IDLE)
                .idleTimeInMS(5000)
                .idleTime(10, TimeUnit.SECONDS)
                .build();

        assertEquals(10, policy.getTimeout());
        assertEquals(TimeUnit.SECONDS, policy.getTimeUnit());
    }
}
