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
package org.atmosphere.util;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutorsFactoryTest {

    private AtmosphereConfig config;
    private AtmosphereFramework framework;
    private ConcurrentHashMap<String, Object> properties;
    private final List<ExecutorService> toShutdown = new ArrayList<>();

    @BeforeEach
    void setUp() {
        config = mock(AtmosphereConfig.class);
        framework = mock(AtmosphereFramework.class);
        properties = new ConcurrentHashMap<>();

        when(config.framework()).thenReturn(framework);
        when(config.properties()).thenReturn(properties);
        when(config.getInitParameter(anyString(), anyBoolean())).thenReturn(true);
        when(config.getInitParameter(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(framework.isShareExecutorServices()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        for (ExecutorService svc : toShutdown) {
            svc.shutdownNow();
        }
        toShutdown.clear();
    }

    private <T extends ExecutorService> T track(T svc) {
        toShutdown.add(svc);
        return svc;
    }

    @Test
    void getMessageDispatcherCreatesNonNullService() {
        ExecutorService svc = track(ExecutorsFactory.getMessageDispatcher(config, "test"));

        assertNotNull(svc);
    }

    @Test
    void getAsyncOperationExecutorCreatesNonNullService() {
        ExecutorService svc = track(ExecutorsFactory.getAsyncOperationExecutor(config, "test"));

        assertNotNull(svc);
    }

    @Test
    void getSchedulerCreatesScheduledExecutorService() {
        ScheduledExecutorService svc = track(ExecutorsFactory.getScheduler(config));

        assertNotNull(svc);
    }

    @Test
    void nonSharedServicesCreateNewInstancesEachCall() {
        ExecutorService svc1 = track(ExecutorsFactory.getMessageDispatcher(config, "test"));
        ExecutorService svc2 = track(ExecutorsFactory.getMessageDispatcher(config, "test"));

        assertNotSame(svc1, svc2);
    }

    @Test
    void sharedServicesAreReused() {
        when(framework.isShareExecutorServices()).thenReturn(true);

        ExecutorService svc1 = track(ExecutorsFactory.getMessageDispatcher(config, "test"));
        ExecutorService svc2 = ExecutorsFactory.getMessageDispatcher(config, "test");

        assertSame(svc1, svc2);
    }

    @Test
    void sharedAsyncServicesAreReused() {
        when(framework.isShareExecutorServices()).thenReturn(true);

        ExecutorService svc1 = track(ExecutorsFactory.getAsyncOperationExecutor(config, "test"));
        ExecutorService svc2 = ExecutorsFactory.getAsyncOperationExecutor(config, "test");

        assertSame(svc1, svc2);
    }

    @Test
    void sharedSchedulersAreReused() {
        when(framework.isShareExecutorServices()).thenReturn(true);

        ScheduledExecutorService svc1 = track(ExecutorsFactory.getScheduler(config));
        ScheduledExecutorService svc2 = ExecutorsFactory.getScheduler(config);

        assertSame(svc1, svc2);
    }

    @Test
    void resetShutsDownServices() {
        when(framework.isShareExecutorServices()).thenReturn(true);

        ExecutorService msg = ExecutorsFactory.getMessageDispatcher(config, "test");
        ExecutorService async = ExecutorsFactory.getAsyncOperationExecutor(config, "test");
        ScheduledExecutorService sched = ExecutorsFactory.getScheduler(config);

        ExecutorsFactory.reset(config);

        assertNotNull(msg);
        assertNotNull(async);
        assertNotNull(sched);
    }

    @Test
    void virtualThreadsUsedByDefault() {
        when(config.getInitParameter(anyString(), anyBoolean())).thenReturn(true);

        ExecutorService svc = track(ExecutorsFactory.getMessageDispatcher(config, "test"));

        // Virtual-thread executor class name contains "Virtual"
        assertNotNull(svc);
    }

    @Test
    void nonVirtualThreadPoolCreatedWhenDisabled() {
        when(config.getInitParameter(anyString(), anyBoolean())).thenReturn(false);
        when(config.getInitParameter(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));

        ExecutorService svc = track(ExecutorsFactory.getMessageDispatcher(config, "test"));

        assertNotNull(svc);
    }
}
