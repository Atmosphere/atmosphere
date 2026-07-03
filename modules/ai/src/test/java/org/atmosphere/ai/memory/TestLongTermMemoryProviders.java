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
package org.atmosphere.ai.memory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ServiceLoader fixtures for {@link LongTermMemoriesTest}. Both providers are
 * registered in {@code META-INF/services} but report unavailable until a test
 * flips their flag — so the rest of the module's tests keep resolving the
 * in-memory fallback.
 */
public final class TestLongTermMemoryProviders {

    public static final AtomicBoolean LOW_AVAILABLE = new AtomicBoolean(false);
    public static final AtomicBoolean HIGH_AVAILABLE = new AtomicBoolean(false);

    public static final LongTermMemory LOW_STORE = new InMemoryLongTermMemory(10);
    public static final LongTermMemory HIGH_STORE = new InMemoryLongTermMemory(10);

    private TestLongTermMemoryProviders() {
    }

    public static void reset() {
        LOW_AVAILABLE.set(false);
        HIGH_AVAILABLE.set(false);
    }

    public static final class LowPriorityProvider implements LongTermMemoryProvider {
        @Override
        public boolean isAvailable() {
            return LOW_AVAILABLE.get();
        }

        @Override
        public LongTermMemory get() {
            return LOW_STORE;
        }

        @Override
        public int priority() {
            return 1;
        }
    }

    public static final class HighPriorityProvider implements LongTermMemoryProvider {
        @Override
        public boolean isAvailable() {
            return HIGH_AVAILABLE.get();
        }

        @Override
        public LongTermMemory get() {
            return HIGH_STORE;
        }

        @Override
        public int priority() {
            return 10;
        }
    }
}
