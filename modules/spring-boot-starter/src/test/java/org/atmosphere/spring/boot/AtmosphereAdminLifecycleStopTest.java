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
package org.atmosphere.spring.boot;

import org.atmosphere.admin.AdminEventHandler;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.BroadcasterListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * The admin {@code SmartLifecycle.stop()} must run every cleanup step even
 * when an earlier one fails: an exception from the event-producer uninstall
 * used to escape {@code stop()}, so the admin handler was never removed from
 * the framework.
 */
class AtmosphereAdminLifecycleStopTest {

    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() throws Exception {
        var real = new AtmosphereFramework()
                .addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        real.init();
        framework = spy(real);
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    @Test
    void stopRemovesTheAdminHandlerEvenWhenListenerRemovalFails() {
        var lifecycle = new AtmosphereAdminAutoConfiguration().atmosphereAdminLifecycle(framework);
        lifecycle.start();
        assertTrue(lifecycle.isRunning(), "precondition: admin lifecycle must start on an initialized framework");

        doThrow(new IllegalStateException("simulated listener removal failure"))
                .when(framework).removeBroadcasterListener(any(BroadcasterListener.class));

        assertDoesNotThrow(() -> lifecycle.stop(),
                "a failing cleanup step must be logged, not escape stop()");
        verify(framework).removeAtmosphereHandler(AdminEventHandler.ADMIN_BROADCASTER_ID);
        assertFalse(lifecycle.isRunning());
    }
}
