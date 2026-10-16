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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shutdown hook registered while the framework's destroy runs its hooks (a
 * request thread that registers one at request time, during an undeploy) must
 * not break the hook loop: the hooks registered before it still run and the
 * destroy goes on to its later phases.
 */
class AtmosphereConfigShutdownHookTest {

    @Test
    void aHookRegisteredWhileTheHooksRunDoesNotStopTheDestroy() {
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();
        var laterHookRan = new AtomicBoolean();
        config.shutdownHook(() -> config.shutdownHook(() -> { }));
        config.shutdownHook(() -> laterHookRan.set(true));
        config.properties().put("marker", Boolean.TRUE);

        assertDoesNotThrow(() -> framework.destroy());

        assertTrue(laterHookRan.get(), "a hook registered before the destroy still runs");
        assertTrue(config.properties().isEmpty(),
                "the destroy reaches its last phase, which clears the properties: " + config.properties());
    }
}
