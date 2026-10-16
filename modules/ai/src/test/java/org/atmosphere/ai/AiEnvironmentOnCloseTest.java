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
package org.atmosphere.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AiEnvironment#onClose(Runnable)}: how a per-application cache drops an
 * application's entry when it stops.
 */
class AiEnvironmentOnCloseTest {

    @AfterEach
    void tearDown() {
        AiConfig.resetHostConfig();
    }

    @Test
    void anActionRunsOnceWhenTheEnvironmentCloses() {
        var environment = AiEnvironment.create(key -> null);
        var runs = new AtomicInteger();
        environment.onClose(runs::incrementAndGet);
        assertEquals(0, runs.get(), "nothing runs while the application is open");

        environment.close();
        environment.close();

        assertEquals(1, runs.get(), "the action runs once, on the first close");
    }

    @Test
    void anActionRegisteredOnAClosedEnvironmentRunsAtOnce() {
        var environment = AiEnvironment.create(key -> null);
        environment.close();
        var runs = new AtomicInteger();

        environment.onClose(runs::incrementAndGet);

        assertEquals(1, runs.get(), "a closed environment never keeps an action");
    }

    @Test
    void aFailingActionDoesNotStopTheOthers() {
        var environment = AiEnvironment.create(key -> null);
        var runs = new AtomicInteger();
        environment.onClose(() -> {
            throw new IllegalStateException("boom");
        });
        environment.onClose(runs::incrementAndGet);

        environment.close();

        assertEquals(1, runs.get());
    }

    @Test
    void theProcessDefaultKeepsNoAction() {
        var runs = new AtomicInteger();
        AiEnvironment.processDefault().onClose(runs::incrementAndGet);
        AiEnvironment.processDefault().close();

        assertEquals(0, runs.get(), "the process default never closes, so it keeps nothing to run");
    }
}
